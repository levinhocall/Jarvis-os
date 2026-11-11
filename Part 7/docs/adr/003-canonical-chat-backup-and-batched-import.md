# ADR 003 — canonical chat migration, one-release backup and durable receipts

Status: **PROPOSED — REVIEW REQUIRED; no A4 implementation yet.** Date: 2026-11-11. This is the next DESIGN-FIRST task after A3. The supplied prompt pack's master rule is explicit: **“Tasks tagged DESIGN-FIRST: commit the ADR and STOP; wait for approval.”** A4 has no proceed-unless-blocked exception. This document is the concrete review point; it does not authorize a data migration by its mere existence.

## Context / source findings

Room is already the active transcript writer. `ConversationRepository.appendTurn()` writes via ChatDao; JarvisConversationService has the production appendTurn call sites. `ChatRepository` is only a read/clear bridge, and `MemoryManager` already writes facts through MemoryRepository/Room while maintaining short-term process context. Do not re-add a second transcript writer to MemoryManager to satisfy the prompt's stale premise.

Source pointers: `data/repository/ChatRepository.kt:5`, `data/chat/ConversationRepository.kt:111,162`, `data/preferences/AppPreferences.kt:652`, `data/chat/ChatDao.kt:94-108`, relative to app/src/main/java/com/jarvis/assistant at the A3 source head e7fd158 (verify pointers when implementing).

Current importer parses/validates the whole legacy JSON, builds stable old:<turn ID>:<role> UUIDs, inserts IGNORE and verifies expected rows in one Room transaction, then calls clearMigratedJson. It preserves corrupt input and supports idempotent retries, but **clears source after verification**. A4 now explicitly requires a read-only backup for one release, a durable done flag, bounded batches/10k-message tests and process-death handling. Current Room is v8; do not overwrite existing 5→6 sync migration. Previously cleared legacy data cannot be reconstructed if no backup exists; do not invent recovered messages.

## Options

1. Keep existing importer unchanged: least code, but does not meet A4 retention/batching requirements.
2. Put only a done flag/cursor in encrypted preferences and keep timestamp-only Room ordering: small initially, but cross-store crashes during clear-history can resurrect a retained source; equal/backward timestamps do not provide a reliable sequence for 10k imports. A done flag alone is not enough.
3. **Recommended:** retain the existing encrypted legacy JSON as read-only backup; keep canonical data and durable migration/deletion receipts in the same existing SQLCipher Room database; add a forward-only message order column. Reuse the existing repository mutex and DAO transactions, not a new chat database, provider or conversation engine.

## Proposed data changes (require review)

- Forward migration **8→9**, additive only. Add `position INTEGER NOT NULL DEFAULT 0` to chat_messages and an index on (sessionId, position). Preserve every existing ID/content/role/timestamp/status/metadata column.
- Populate old positions with a streaming cursor ordered by sessionId, timestamp, rowid; assign a monotonic position per session. Avoid a quadratic correlated COUNT for large databases. Equal timestamps retain insertion order; original timestamps are not fabricated or changed.
- Add one Room receipt table (key/source SHA-256/payload version/status/committed cursor/unique message count/import-session ID/backup release marker/updated time). The completion key is **chat_migration_v1_done**; DONE is visible only after the final verifying transaction commits. Status distinguishes IMPORTING, DONE and explicit user-deletion tombstone/backup-cleanup pending. No transcript copy in this table.
- **Freeze historical test entities** before modifying live ChatMessageEntity: HistoricalV5Database currently imports live chat/session/mission/notification types. Copy their provable historical declarations into immutable androidTest fixtures so a new live field cannot silently rewrite v5 fixture claims. Original v1/v5 fixture exports and v6–v8 production exports must remain unchanged. Commit v9 export separately with its explicit migration.
- Keep future B3 checkpoint work on the then-current forward version (e.g. 9→10), never replace an existing migration/version from the stale prompt.

## Import / write / clear protocol

1. Run migration on the existing app-owned background path and repository boundary. No main-thread preference reads, JSON processing, batch verification or file writes.
2. Read raw legacy JSON without a legacy writer. Streaming preflight verifies the entire source and IDs/shape before importing; SHA-256 identifies the immutable snapshot. Use bounded batches (proposed max 200 messages and byte cap), not a full Gson DOM. A malformed/truncated/unsupported oversized source retains its original encrypted data and reports a typed outcome; never mark it complete or silently discard rows. Test 10k ordinary messages including Hindi/Hinglish and large text.
3. Keep the existing stable ID scheme for known turn IDs, deterministic fallback IDs for missing ones. Same ID with conflicting content is an error; distinct IDs with repeated text remain distinct messages. Source role/order is retained in position, with original timestamps unmodified. No REPLACE of user-edited canonical rows; old stable IDs already belonging to another legitimate session keep their original session/order. Never move or duplicate them just to make an imported view look continuous.
4. Reserve source positions/cursor/count in a Room receipt. Per-batch @Transaction inserts missing IDs, verifies rows/parents and advances committed progress. On process death, resume/reconcile from the committed receipt and immutable source hash. A changed source digest is a user-visible reconciliation case, not permission to reset/delete canonical data.
5. Normal new message order allocation and pair append use DAO transactions, respecting reserved legacy ranges so concurrent new turns cannot steal positions from an unfinished import. Keep partial import sessions separate/read-only until verified; existing completed Room chats remain usable. Reuse the existing repository as the only write authority.
6. Commit DONE in a verifying transaction only after all data batches have committed and every expected unique message verifies. Keep the original encrypted JSON as a **read-only backup for at least one delivered release**; default cleanup flag OFF. Import reads skip it after the durable receipt is complete. Cleanup is a separate reviewed/user-controlled action, not startup garbage collection.
7. Explicit user-confirmed Clear History atomically deletes canonical messages/sessions and writes a Room deletion tombstone in the same transaction before attempting backup removal. If preference cleanup fails or the process dies, the tombstone prevents source reimport; show that backup removal is pending instead of reporting complete erasure. Startup recovery finishes only this already-authorized local cleanup. Preserve current confirmation UX and clearly state that retained migration backup is included.
8. Keep raw backup/export available only through explicit local user action. No cloud sync, logs of text, new permissions, new exported component or plaintext backup file.

## Proposed small commit sequence

1. Freeze historical chat/session/mission/notification fixture declarations; characterization tests pin current transcripts/IDs/times and clear-history consent.
2. Add position/receipt entities and forward 8→9 migration/export with seeded migration tests; no importer switch yet.
3. Implement streaming preflight and bounded verified batch/cursor protocol, with 10k/process-recreation/conflict tests.
4. Switch completion to durable receipt plus retained backup; add transactionally guarded user deletion/cleanup recovery, with failure-window tests.
5. Full unit/lint/schema checks and Android-test compilation; device execution honestly separate. Source/data changes are not authorized merely by this proposed sequence; await review first.

## Verification required before calling A4 complete

- Unit tests using deterministic fakes: run migration twice; 10k-message streaming/batch bounds; empty/corrupt/truncated input; interrupted batch and final-marker crash windows; duplicate identities versus repeated distinct text; exact original timestamps and sequence; conflicting existing edits; changed source digest; no flag before commit; retained backup after DONE; deletion tombstone prevents resurrection even if backup cleanup fails.
- Real Room/SQLCipher instrumentation: additive 8→9 seeded upgrade, canonical pair/order transactions, per-batch rollback, process recreation/retry, retained source and clear/delete recovery. Compile tests locally; execute only when a device/emulator is available and report separately. JVM fakes cannot prove native transaction crash safety.
- Verify MemoryManager's existing fact persistence against actual Room in instrumented tests; preserve short-term context behavior. Do not create duplicate chat writes for a test.
- Full JVM/lint/new-issue gate and schema drift checks; no original/historical exports modified; no Native key/encryption changes. 10k tests measure correctness/bounds, not a fabricated device latency/RAM benchmark.

## Precise boundaries

The original encrypted JSON string is retained, not copied to an unencrypted file or sent to a service. A valid absent source does not create invented backup content. Unsupported/corrupt input cannot set DONE. A source copy already cleared by a previously installed version cannot be restored by this plan. Backup cleanup is separate/default-OFF, but an explicit user-confirmed history deletion overrides retention and must also remove its backup or clearly show pending cleanup.

Existing old-ID messages can be distributed across legitimate prior sessions; preserving their organization takes priority over automatically moving them. The retained source can support an explicitly selected read-only original-history view/export. No claim is made that timestamps alone were sufficient to reconstruct missing messages or lost source order.

## Risks / rollback

This is a real forward schema change and more storage-retention behavior than the already-canonical baseline. It needs explicit review before implementation. Migration may be slow on large histories; quantify with device tracing later. Backup remains sensitive and encrypted on the phone; user-requested deletion must remove it or honestly report a failed cleanup. A lost Keystore key is not permission to make a new empty database.

Rollback after a v9 database is opened must **retain v9-compatible schema support**; do not install a v8-only Room binary or enable destructive downgrade. A rollback build may show the retained source through an explicitly selected read-only backup view/export while continuing to write only Room; it must not re-enable dual writes. If migration fails, preserve DB/source/receipt for retry/reconciliation. Data already erased by older releases is not recoverable through this feature.

## Review requested

Approve/revise option 3: additive v9 order/receipt migration, retained encrypted backup, transactionally guarded completion/deletion and bounded import tests. Only after approval should implementation begin. Existing A0/A1/A2/A3 commits stay isolated/unmerged; final main integration, CI and APK happen after the remaining pack gates, as the user requested.
