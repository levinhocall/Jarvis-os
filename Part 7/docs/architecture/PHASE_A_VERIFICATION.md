# Phase A verification — 2026-11-11

Baseline: main `56b37aa627abed2178680993fecbe1b5af458a72`. Work branch: `phaseA/storage-ci-audit`. Parts 1–6, package ID, database version, encryption/key format and current v6–v8 schema exports are unchanged. No orb/voice engine rewrite, class deletion, main merge or APK build was performed.

## Delivered changes

- [CALLSITE_MAP.md](CALLSITE_MAP.md): source-pinned file/line evidence. AudioPipelineManager, VoiceSessionManager and SelfHealingAgentLoop are WIRED; UniversalActionDispatcher TEST-ONLY; AutonomousGoalAgent DEAD by the defined static-search criteria. These five implementation files are unchanged.
- [CI_FAILURE_ANALYSIS.md](CI_FAILURE_ANALYSIS.md): exact public startup-failure annotation and jobs evidence. Read-only push/PR test+lint job separated from manual APK/release workflow; existing releases retained. Main integrity and Android-runtime workflows inspected; runtime remains manual-only.
- [DATABASE_STARTUP.md](DATABASE_STARTUP.md): shared IO readiness gate, gated repository/Flow consumers and direct workers/services, failure/cancellation behavior and cold-start measurement procedure. The baseline had already removed `runBlocking` and deferred native opening; this change does not pretend to discover that old implementation in current code.
- [CHAT_CANONICAL_STORAGE.md](CHAT_CANONICAL_STORAGE.md): Room is canonical; obsolete legacy writer APIs removed after finding no production callers. Deterministic idempotent import verifies rows in a Room transaction, then clears only its unchanged preference snapshot. Corrupt input and import failures preserve the source. MemoryManager already writes facts through Room and maintains in-process short-term context; it was not given a duplicate transcript writer.
- [../../app/schemas/README.md](../../app/schemas/README.md): audit export enabled and generated; actual historical-source v1/v5 fixture provenance, SQLCipher/MigrationTestHelper tests and remaining evidence gaps. No fabricated original shipped snapshots.

## Checks actually executed

Local environment: JDK 17, Android SDK 36, existing Gradle wrapper. Logs are local ignored files under `work/`; results below are from completed processes, not planned commands.

| Check | Executed result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest --tests '*DatabaseReadinessTest' --no-daemon` | Corrected rerun passed 3/3. Initial run failed one exception-identity assertion because coroutine stack-trace recovery copied the exception; test now verifies propagated type/message and one initialization. |
| `./gradlew :app:testDebugUnitTest --no-daemon --offline` | Passed: 514 tests, 0 failures/errors, 1 skip. Includes 3 readiness and 8 new legacy-import tests. |
| `./gradlew :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --offline` | Passed after updating the pre-existing preferences security test to use the import/read-clear API instead of the removed JSON writer. Initial compilation correctly caught those two stale API references; fixed rerun succeeded in 3m03s. |
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --offline` | Final combined check passed in 1m37s (52 tasks: 6 executed, 46 up-to-date). JVM XML totals 514 tests, 0 failures/errors, 1 skip, latest suite timestamp 2026-11-11T08:01:13 UTC. |
| Android lint XML | 0 errors, 1,398 warnings, 2 hints. Not warning-free. |
| `python3 scripts/verify-directive-migration.py` and `python3 scripts/verify-directive-migration.py 7 8` (from Part 7) | Passed: existing rows retained across 6→7 and 7→8; schema matches committed exports. These Python checks do not replace native encrypted migration execution. |
| YAML parsing + structural assertions | All workflows parsed; verification has push/PR/dispatch, read-only token, expected unit/lint tasks and no assembly/release commands; APK workflow dispatch-only. No disallowed third-party action was added. |
| Schema JSON/provenance checks | JSON parsed; historical endpoint assets byte-match their compiler-generated fixtures; v6–v8 exports unchanged. Historical v5 chat/mission/notification table definitions checked against `81c5ec1`. |
| Room generated DAO inspection | `ChatDao_Impl.importLegacyVerified()` calls `DBUtil.performInTransactionSuspending`. This proves compiler wiring, not a device transaction test. |
| Source scope + whitespace | `git diff --check` passed; no diff in Parts 1–6 or the five audited class implementations. |

Final local log: `work/phaseA-final-checks.log`. Earlier logs: `phaseA-readiness.log`, `phaseA-readiness-recheck.log`, `phaseA-chat-tests.log`, `phaseA-android-compile-lint.log`, `phaseA-android-compile-lint-recheck.log`. No signing secrets or record contents are committed.

## Not executed / not claimed

- No `assembleDebug`, release build, APK publication, signature/install/upgrade test or emulator runtime workflow in this task. APK delivery remains deferred.
- `adb devices` returned an empty device list; `/dev/kvm` was absent. No `connectedDebugAndroidTest` execution or physical Android/HyperOS test. Instrumentation compilation is not runtime validation.
- New Android tests cover native encrypted v1→v5→v8 migration, real Room v5/v8 opening, wrong/empty cipher keys, corrupt-file retention/explicit known-good backup restore, readiness awaited on Main with worker native open, and transactional legacy-import retry/rollback. They are **compiled, not executed here**.
- No measured cold-start latency/ANR improvement, CPU/disk trace or Perfetto capture. Synchronous AppPreferences secure initialization remains outside this focused DB change.
- V1/v5 exports were not committed historically; test fixtures reconstruct exact historical source declarations. Compatibility with every previously installed APK remains unverified. Production fixed-path plaintext encryption swap, OEM Keystore invalidation, process-death during native migration and recovery require Android execution on disposable/backup data.
- No exact user-device crash stack trace was available. This task cannot establish the original crash's precise caller or claim the phone crash is reproduced/fixed.
- Hosted validation of this branch is separate from the successful local checks. The final delivery links the actual PR/Actions result; the older successful main run 42979318685 is baseline evidence only.

The branch is for review. It must not be represented as a device-verified release candidate or merged automatically.
