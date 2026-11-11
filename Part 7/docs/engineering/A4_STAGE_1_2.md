# A4 staged review: frozen fixtures and additive v9 schema

Date: 2026-11-11. Scope: the user approved ADR003 **only through commits 1–2**, with conditions. Import batching, isolated backup, cleanup UI, deletion receipts and runtime ordering changes remain behind the next review. No main merge or APK is authorized by this staging report.

## Integration record

Created `phaseA/integration` from unchanged main `56b37aa627abed2178680993fecbe1b5af458a72`. Merged A0→A1→A2→A3 in dependency order, then the ADR003 design branch. Explicit merge commits retain the review boundaries; all were clean, **zero conflicts**. A0 already descends from the prior `phaseA/storage-ci-audit` work, so that dependency is included, not cherry-picked twice.

| Source branch | Source head | Integration merge |
| --- | --- | --- |
| phaseA/v2-baseline | 70ec77bcded796b0c6c195a5095735d207b4801c | 7fd29c87be09f2eb23d61f195bf8a8f3713f0a6c |
| phaseA/v2-wiring-map | aed3b6d084f11f554ebfbf0ea0c48cf91a7ab6eb | e73d68efb9380b79e7f16225544f90d16a2a88df |
| phaseA/v2-ci-gate | b6b996135faf8c2713316faa8cdcb3b1b0a40c1c | 6ce6e4006ab303f8bfff0d7235b1e85fa1f67bc8 |
| phaseA/v2-db-readiness | e7fd1585978499836bd7cb3a17197cbbae3010ae | 7265833066e8e3d8754b28eff839343e46c8d40d |
| phaseA/v2-chat-design | 7a24468f7116698b33138254476ca836769ea8c2 | c47ff0477af5d65aafbaa603d635c7cca4ca58c2 |

Implementation branch: `phaseA/v2-chat-schema`, based on that integration tree. Commit 1: `00eccf3` (historical fixtures + approved ADR conditions). Commit 2 is the additive schema/test/export change containing this report. Keep these two new commits on the implementation branch for review against `phaseA/integration`; the requested A0–A3 integration is complete, and main remains unchanged. Hosted job links/results belong in the review description to avoid claiming a future run in this document.

## Changes / exact stage boundary

- Historical v5 now uses independent frozen chat/session/mission/notification entities. All four persisted shapes were compared with exact source at `81c5ec1`; no main entity imports remain. Historical v5 identity stays `5a1ef08ec69aa450a6910b77c3e04905`. Expanded native characterization checks its seven original chat columns and seeded row fields before upgrading.
- MemoryDatabase advances **8→9** with registered, additive MIGRATION_8_9. Adds `chat_messages.position INTEGER NOT NULL DEFAULT 0`, non-unique `(sessionId,position)` index, and `chat_migration_receipts`. No original row/column/key/encryption change or destructive fallback.
- Positions start at zero for each session, sorted by original timestamp then original SQLite rowid; original IDs/timestamps/text/status/metadata and session membership remain untouched. The cursor writes only integer rowid/position pairs into a connection-local TEMP table, leaving its source table immutable during the scan. An indexed integer lookup then backfills positions; the TEMP table is dropped. Kotlin does not materialize the transcript or run a correlated COUNT per message. SQLite still needs sort/temp storage; this is **not** a measured constant-memory/latency claim.
- **No reserved ranges.** Future imports use a distinct receipt/import-session ID. Existing stable-ID messages keep their actual sessions. There is no demonstrated same-session import/live-write contention justifying reserved positions.
- Receipt table is **empty after upgrade**. It has nullable source digest/import-session/retention metadata (also allowing future explicit deletion tombstones when no source exists), explicit status/version/cursor/count fields, and no transcript copy. No DAO or startup receipt writer is enabled yet. `chat_migration_v1_done` is not fabricated just because rows already exist in Room.
- Runtime DAO ordering/writers/importer/preferences/MemoryManager remain unchanged. New runtime rows still receive position's default 0 until the separately reviewed allocation/query switch. The new column/index is staging, **not** a claim of complete canonical sequence behavior today.

## Install impact and data safety

| Install state | Benefit in these two commits | Not provided yet |
| --- | --- | --- |
| Existing v8 Room, never imported legacy JSON | Schema upgrade preserves Room rows and adds per-session backfill | New resumable importer and backup protocol are not active; existing startup importer still verifies then clears its source |
| Already imported, original JSON still survives | Existing canonical history receives the same additive backfill | No new backup copy/receipt is synthesized yet |
| Already imported and older version cleared JSON | Canonical Room records remain and receive backfill | Cannot recover the erased original JSON; no invented backup |
| Fresh install with no legacy source | Room creates v9 schema with empty receipt table | No invented history, DONE marker or backup |

**Do not distribute this staging as a backup-retention release.** Existing verified importer/clear behavior is deliberately unchanged until commits 3–4. No personal source is read/moved/cleared by the schema migration itself. A successful future copy must be committed and read-back/hash verified before removing the source from both AppPreferences stores.

## Backup conditions: designed, not implemented

ADR003 now specifies dedicated encrypted `jarvis_legacy_chat_backup.xml`, a visible Settings → Privacy → Legacy chat backup row, a one-time foreground deletion offer due **30 days after DONE**, persisted offer/due/version metadata and explicit delete/extension behavior. No indefinitely hidden retention or background deletion. These settings/store/copy/cleanup operations are **not implemented** in this stage.

Inspection: manifest currently has `android:allowBackup="false"`; there are no `android:dataExtractionRules` or `android:fullBackupContent` resources. **Explicit backup-store exclusion is missing, not verified.** Before introducing the store, commit 4 must exclude its XML in legacy full backup plus Android 12+ cloud and device-transfer rules and test the actual merged manifest/resources. No manifest/exported component/permission changes are included now. `allowBackup=false` alone is not evidence about every OEM transfer implementation.

## Readiness and seeded native tests

A3's authoritative DatabaseState remains Loading when a caller wait times out; only the initializer's actual exception sets Failed. No A4 production gate/state change was needed. The new JVM fake-clock test drives both the default 30s waiter and explicit boot 120s waiter past their deadlines while one opener remains Loading, then reaches Ready without reopening. Its virtual 125s is a deliberate test control, **not a migration timing number**. Existing A3 actual-failure/cancellation tests remain.

`ChatSchemaV9InstrumentedTest` uses MigrationTestHelper with the real SQLCipher factory and committed v8/v9 schema assets. No tests manually create a pretend v8 table layout. It covers:

1. **10,000 seeded messages**, multiple Unicode sessions, reversed/equal/extreme timestamps, ties whose ID order differs from insertion order, distinct rows with repeated text, all original fields and seeded rows in every pre-existing table. Validate v9 with Room, compare original-column SHA-256 digests, positions/order and empty receipts, then reopen through real Room.
2. Empty v8 upgrade creates neither chat nor DONE receipt; old insert statements still work with position default 0.
3. A second 10k-seeded upgrade executes the real production migration inside Room's upgrade transaction, then deliberately holds a bounded test-only barrier. A 25ms caller deadline must return the typed timeout while DatabaseState stays Loading; release lets **the same** opener reach Ready and data is verified. The barrier duration/deadline is a test control, not native performance evidence.
4. Inject a real exception after migration DDL/backfill, before Room commits. State must be Failed; a recreated opener retries the real upgrade, validates schema and preserves rows. This distinguishes real upgrade failure from a waiter deadline.

Those native tests require an Android runtime. Compilation is reported separately below; **instrumented execution is NOT RUN** unless explicitly updated with a real run.

## Device timing procedure (NOT EXECUTED)

Use a disposable test device/profile with no personal DB. Record phone/SoC, Android/HyperOS, power mode, available storage, ABI, SQLCipher/Room versions and build SHA. Build/install test artifacts only when separately authorized. Then run:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jarvis.assistant.data.memory.ChatSchemaV9InstrumentedTest
adb logcat -d -s JarvisDbMigrationTest:I
```

Each case seeds a uniquely named encrypted **v8** DB through the committed schema asset, outside its timed region. The normal 10k test logs `open+upgrade+validation` milliseconds around MigrationTestHelper, excluding seed and assertions. The barrier test logs only the actual production `migrate()` body before the injected wait; never include that artificial wait in a migration benchmark. JNI is loaded by @Before, so these are **not** cold-process library-load measurements. Repeat with new disposable DBs, record every valid sample and cache/power conditions; compare median/range only after collecting actual data. No thresholds are asserted from fabricated timings.

For full cold-app startup, use a separate disposable profile seeded at v8 and Perfetto/Android Studio System Trace around the existing `JARVIS.SQLCipher.open` trace and Main thread frames. That span includes native open/key/migration work; record Main blocking separately. A caller timeout exception does not constitute DB Failed or justify deleting/rekeying the file. The current boot coroutine does not automatically resume its post-open actions after its own 120s wait expires; later callers can await the ongoing initialization. That existing recovery limitation is not silently changed here.

## Verification evidence

Commit 1:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --offline
```

BUILD SUCCESSFUL in 1m13s; 52 tasks (6 executed, 46 up-to-date). AndroidTest KAPT/Kotlin compiled; unit/lint tasks were cached/up-to-date, **not a fresh test execution**. Exact historical persisted-shape comparison passed for four frozen types. All pre-existing schema JSON were byte-compared unchanged after generation.

Commit 2, same exact Gradle command: **BUILD SUCCESSFUL in 6m56s**, 52 tasks (20 executed, 32 up-to-date). Fresh JVM results: **521 test cases, zero failures/errors, one existing skip**; readiness suite **10/10** (JUnit timestamp 2026-11-11T15:04:44 UTC). Lint analysis completed, zero reported errors/warnings and two existing baseline hints; baseline unchanged. AndroidTest KAPT/Kotlin compiled, **native tests NOT RUN**. Existing project deprecation warnings remain; no new warnings at the changed/new Kotlin source paths appeared in this run.

Additional executed checks:

- `python3 scripts/verify-directive-migration.py` and `python3 scripts/verify-directive-migration.py 7 8`: PASS; existing host SQLite 6→7 and 7→8 schema/seed preservation. These are not v8→v9/SQLCipher execution evidence.
- All existing committed JSON schemas byte-compared to `7a24468`: unchanged after KAPT. Other than the additive chat_messages entity, all ten v8 table definitions are retained (the nine unchanged entities are JSON-equal). New v9 identity: `0f8936bdc2c6ec6ca03f989beea7bff5`.
- `git diff --check`: clean. Native key/encryption/factory files, runtime repositories/preferences/queries, manifest, Parts 1–6 unchanged in this two-commit diff.
- `adb devices -l`: no connected device; `/dev/kvm` absent. No connectedAndroidTest/device visual/startup/performance/upgrade/signature/APK run performed.

Full local logs are ignored workspace files `work/v2-a4-freeze-checks.log` and `work/v2-a4-schema-checks.log`, not uploaded user content.

## Risks / rollback

Native 8→9 compatibility/performance remains unverified until those Android tests execute. New positions are not consumed by the old writer/query contract in this staging. The receipt table is storage only, not a completed migration feature. SQLCipher backup/transfer exclusions and isolated retention still require the next reviewed implementation.

Before a database has opened v9, the source commit can be reverted. **After v9 opens, rollback must keep v9 schema support**: do not install a v8-only Room binary or enable destructive downgrade. A rollback can keep the additive entities/migration and existing timestamp readers while disabling later importer changes. Preserve the original encrypted DB/key; no data deletion, rekey or downgrade is an acceptable rollback.

STOP after these two staged commits. Next review must explicitly authorize commits 3–4 before enabling batching, backup copy/removal, retention/deletion UI, receipt writes or query/allocation switching.
