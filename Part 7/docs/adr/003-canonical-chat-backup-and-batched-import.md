# ADR 003 — canonical chat migration, one-release backup and durable receipts

Status: **APPROVED WITH CONDITIONS; implementation staged at commits 1–2 only.** Date: 2026-11-11. The user approved ADR003 with five conditions below and explicitly required a STOP/report after frozen fixtures plus additive 8→9 migration/export/seeded tests. Importer/backup/delete/UI work (commits 3–4) still requires the next review. Approval does not authorize merging main or an APK now.

## Context / source findings

Room is already the active transcript writer. `ConversationRepository.appendTurn()` writes via ChatDao; JarvisConversationService has the production appendTurn call sites. `ChatRepository` is only a read/clear bridge, and `MemoryManager` already writes facts through MemoryRepository/Room while maintaining short-term process context. Do not re-add a second transcript writer to MemoryManager to satisfy the prompt's stale premise.

Source pointers: `data/repository/ChatRepository.kt:5`, `data/chat/ConversationRepository.kt:111,162`, `data/preferences/AppPreferences.kt:652`, `data/chat/ChatDao.kt:94-108`, relative to app/src/main/java/com/jarvis/assistant at the A3 source head e7fd158 (verify pointers when implementing).

Current importer parses/validates the whole legacy JSON, builds stable old:<turn ID>:<role> UUIDs, inserts IGNORE and verifies expected rows in one Room transaction, then calls clearMigratedJson. It preserves corrupt input and supports idempotent retries, but **clears source after verification**. A4 now explicitly requires a read-only backup for one release, a durable done flag, bounded batches/10k-message tests and process-death handling. Current Room is v8; do not overwrite existing 5→6 sync migration. Previously cleared legacy data cannot be reconstructed if no backup exists; do not invent recovered messages.

## Options

1. Keep existing importer unchanged: least code, but does not meet A4 retention/batching requirements.
2. Put only a done flag/cursor in encrypted preferences and keep timestamp-only Room ordering: small initially, but cross-store crashes during clear-history can resurrect a retained source; equal/backward timestamps do not provide a reliable sequence for 10k imports. A done flag alone is not enough.
3. **Recommended:** move the verified legacy JSON into a dedicated encrypted read-only backup store; keep canonical data and durable migration/deletion receipts in the same existing SQLCipher Room database; add a forward-only message order column. Reuse the existing repository mutex and DAO transactions, not a new chat database, provider or conversation engine.

## Data changes approved for stage 2

- Forward migration **8→9**, additive only. Add `position INTEGER NOT NULL DEFAULT 0` to chat_messages and an index on (sessionId, position). Preserve every existing ID/content/role/timestamp/status/metadata column.
- Populate old positions with a streaming cursor ordered by sessionId, timestamp, rowid; assign a monotonic position per session. Avoid a quadratic correlated COUNT for large databases. Equal timestamps retain insertion order; original timestamps are not fabricated or changed.
- Add one Room receipt table (key/source SHA-256/payload version/status/committed cursor/unique message count/import-session ID/backup release marker/updated time). The completion key is **chat_migration_v1_done**; DONE is visible only after the final verifying transaction commits. Status distinguishes IMPORTING, DONE and explicit user-deletion tombstone/backup-cleanup pending. No transcript copy in this table.
- **Freeze historical test entities** before modifying live ChatMessageEntity: HistoricalV5Database currently imports live chat/session/mission/notification types. Copy their provable historical declarations into immutable androidTest fixtures so a new live field cannot silently rewrite v5 fixture claims. Original v1/v5 fixture exports and v6–v8 production exports must remain unchanged. Commit v9 export separately with its explicit migration.
- Keep future B3 checkpoint work on the then-current forward version (e.g. 9→10), never replace an existing migration/version from the stale prompt.

## Import / write / clear protocol

1. Run migration on the existing app-owned background path and repository boundary. No main-thread preference reads, JSON processing, batch verification or file writes.
2. Read raw legacy JSON without a legacy writer. Streaming preflight verifies the entire source and IDs/shape before importing; SHA-256 identifies the immutable snapshot. Use bounded batches (proposed max 200 messages and byte cap), not a full Gson DOM. A malformed/truncated/unsupported oversized source retains its original encrypted data and reports a typed outcome; never mark it complete or silently discard rows. Test 10k ordinary messages including Hindi/Hinglish and large text.
3. Keep the existing stable ID scheme for known turn IDs, deterministic fallback IDs for missing ones. Same ID with conflicting content is an error; distinct IDs with repeated text remain distinct messages. Source role/order is retained in position, with original timestamps unmodified. No REPLACE of user-edited canonical rows; old stable IDs already belonging to another legitimate session keep their original session/order. Never move or duplicate them just to make an imported view look continuous.
4. Record the dedicated import-session ID and committed source cursor/count in a Room receipt; do not reserve position ranges in live sessions. Per-batch @Transaction inserts missing IDs, verifies rows/parents and advances committed progress. On process death, resume/reconcile from the committed receipt and immutable source hash. A changed source digest is a user-visible reconciliation case, not permission to reset/delete canonical data.
5. Allocate positions per session through DAO transactions. Imports have a separate, persisted import-session ID; normal chat writes use their normal sessions. No reserved ranges: no verified same-session contention case justifies them. Keep partial import sessions separate/read-only until verified; existing completed Room chats remain usable. Reuse the existing repository as the only write authority.
6. Commit DONE in a verifying transaction only after all data batches have committed and every expected unique message verifies. After a verified, synchronously committed copy into dedicated EncryptedSharedPreferences `jarvis_legacy_chat_backup` (existing Keystore-backed AES256-SIV keys / AES256-GCM values), remove `key_chat_history` from AppPreferences main and secure stores only after read-back/hash verification. Keep that isolated source as a **read-only backup for at least one delivered release**, subject to explicit user deletion. Failed copy/removal is resumable and reported, never silently treated as complete. Import reads skip it after the durable receipt is complete. Cleanup is a separate reviewed/user-controlled action, not startup garbage collection. The concrete trigger is specified below.
7. Explicit user-confirmed Clear History atomically deletes canonical messages/sessions and writes a Room deletion tombstone in the same transaction before attempting backup removal. If preference cleanup fails or the process dies, the tombstone prevents source reimport; show that backup removal is pending instead of reporting complete erasure. Startup recovery finishes only this already-authorized local cleanup. Preserve current confirmation UX and clearly state that retained migration backup is included.
8. Keep raw backup/export available only through explicit local user action. No cloud sync, logs of text, new permissions, new exported component or plaintext backup file.

## Proposed small commit sequence

1. Freeze historical chat/session/mission/notification fixture declarations; characterization tests pin current transcripts/IDs/times and clear-history consent.
2. Add position/receipt entities and forward 8→9 migration/export with seeded migration tests; no importer switch yet.
3. Implement streaming preflight and bounded verified batch/cursor protocol, with 10k/process-recreation/conflict tests.
4. Switch completion to durable receipt plus retained backup; add transactionally guarded user deletion/cleanup recovery, with failure-window tests.
5. Full unit/lint/schema checks and Android-test compilation; device execution honestly separate. Only commits 1–2 are authorized now; STOP for review before commits 3–4.

## Verification required before calling A4 complete

- Unit tests using deterministic fakes: run migration twice; 10k-message streaming/batch bounds; empty/corrupt/truncated input; interrupted batch and final-marker crash windows; duplicate identities versus repeated distinct text; exact original timestamps and sequence; conflicting existing edits; changed source digest; no flag before commit; retained backup after DONE; deletion tombstone prevents resurrection even if backup cleanup fails.
- Real Room/SQLCipher instrumentation: additive 8→9 seeded upgrade, canonical pair/order transactions, per-batch rollback, process recreation/retry, retained source and clear/delete recovery. Compile tests locally; execute only when a device/emulator is available and report separately. JVM fakes cannot prove native transaction crash safety.
- Verify MemoryManager's existing fact persistence against actual Room in instrumented tests; preserve short-term context behavior. Do not create duplicate chat writes for a test.
- Full JVM/lint/new-issue gate and schema drift checks; no original/historical exports modified; no Native key/encryption changes. 10k tests measure correctness/bounds, not a fabricated device latency/RAM benchmark.

## Precise boundaries

The exact JSON string is moved only after verified copy into the dedicated encrypted backup store, never into an unencrypted file or a remote service. A valid absent source does not create invented backup content. Unsupported/corrupt input cannot set DONE. A source copy already cleared by a previously installed version cannot be restored by this plan. Backup cleanup remains separate and consent-controlled, but an explicit user-confirmed history deletion overrides retention and must also remove its backup or clearly show pending cleanup.

Existing old-ID messages can be distributed across legitimate prior sessions; preserving their organization takes priority over automatically moving them. The retained source can support an explicitly selected read-only original-history view/export. No claim is made that timestamps alone were sufficient to reconstruct missing messages or lost source order.

## Risks / rollback

This is a real forward schema change and more storage-retention behavior than the already-canonical baseline. Only the approved first two commits may be implemented before the next review. Migration may be slow on large histories; quantify with device tracing later. Backup remains sensitive and encrypted on the phone; user-requested deletion must remove it or honestly report a failed cleanup. A lost Keystore key is not permission to make a new empty database.

Rollback after a v9 database is opened must **retain v9-compatible schema support**; do not install a v8-only Room binary or enable destructive downgrade. A rollback build may show the retained source through an explicitly selected read-only backup view/export while continuing to write only Room; it must not re-enable dual writes. If migration fails, preserve DB/source/receipt for retry/reconciliation. Data already erased by older releases is not recoverable through this feature.

## Approved conditions and remaining implementation boundary

1. **Dedicated backup store:** `jarvis_legacy_chat_backup.xml` must be encrypted and isolated from `jarvis_prefs.xml` / `jarvis_secure_api_prefs.xml`. Copy + synchronous commit + read-back/digest verification precedes removal of the original key. During a failure window both may exist; never clear the only verified source. No plaintext fallback.
2. **Concrete cleanup trigger (commit 4):** Settings → Privacy → Legacy chat backup is visible whenever backup/cleanup state exists, with retention date, size and Delete action. Persist `doneAtMs`, backup version and `cleanupDueAtMs = doneAtMs + 30 days`. On the first foreground entry at/after that date, offer deletion once and durably record the offer. Explicit Delete removes it even in the same release. Keep for 30 days records a new visible due date; the persistent Settings row never vanishes while bytes remain. Routine offered cleanup also requires at least the next delivered version; if not yet installed, the overdue row explains the retention requirement. No silent indefinite retention, forced background dialog or implicit deletion. The exact UI/retry behavior is still part of the next staged review.
3. **No reserved positions:** per-session sequence plus a dedicated import-session ID. Already imported stable IDs keep their existing sessions/positions; never duplicate or move them merely to force a contiguous imported session. Current runtime readers/writers remain unchanged in commit 2; the new column is populated on upgrade but is not the canonical order until commit 3 switches allocation/queries atomically.
4. **Readiness:** a waiter timeout must leave actual initialization Loading (not Failed); native migration continues on its existing IO owner. Add a fake-clock >30s case and a real SQLCipher 10k-seeded v8→v9 test with an injected migration barrier after the real migration. Timeout test delays are deliberate controls, not measured migration performance. Actual native failure remains Failed. Document cold migration and end-to-end native open timing separately; no invented device numbers.
5. **Backup exclusion:** current manifest has `allowBackup=false`, but **no** `dataExtractionRules` / `fullBackupContent` resources; explicit exclusion is currently missing. Before creating the backup store in commit 4, add and test exclusions for `jarvis_legacy_chat_backup.xml` in pre-31 full-backup plus Android 12+ cloud-backup and device-transfer paths, preserving no-backup policy. Do not claim `allowBackup=false` alone proves OEM transfer exclusion. No backup store or backup manifest change is introduced in commits 1–2.

### Which installations benefit

- Existing v8 databases (including already-imported chats): additive sequence backfill and empty receipt table; every original row is preserved. No synthetic DONE receipt or backup is inferred from Room rows alone.
- Never-imported installs with a surviving legacy source: future commits 3–4 can import/resume and retain the isolated backup. These first two commits do **not** enable that protocol; the pre-existing verified importer still operates unchanged.
- Already-imported installs whose older app cleared JSON: canonical Room history remains, but the erased source cannot be recreated as an original backup. No recovery claim. An absent source on a fresh install also creates no invented receipt/backup.

### Stage boundary

Commit 1 freezes historical fixtures and records this approval. Commit 2 adds v9 schema/migration/export and seeded tests only. Then STOP and report for review. Native SQLCipher instrumentation remains NOT RUN unless actually executed on Android. A0–A3 are integrated in dependency order on `phaseA/integration` (main unchanged); the merge record is in the staged verification report. Main integration and APK generation remain future work.
