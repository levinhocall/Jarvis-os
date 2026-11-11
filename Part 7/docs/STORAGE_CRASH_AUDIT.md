# Storage/startup audit and targeted fixes

Local working source inspected 2026-11-11. Pending orb changes are preserved. This is not a blanket certification of all application code.

## Findings verified against current source

| Claim | Current evidence / disposition |
|---|---|
| CI never executes checks | Latest main source 2514ee2 has successful APK [run 42875020918](https://github.com/levinhocall/Jarvis-os/actions/runs/42875020918) and Android 16 [run 42875020990](https://github.com/levinhocall/Jarvis-os/actions/runs/42875020990). Runtime job XML reports 27 tests, zero failures/errors/skips. These older runs do not verify the new local changes. |
| runBlocking opens DB in Application | No runBlocking found in active production Kotlin. getInstance builds a deferred Room facade; SQLCipher/key/file work happens later. Calling the builder is not evidence that SQLite opened on Main. |
| Any Main DAO access necessarily trips the helper guard | Suspend queries and Flow dispatch through Room executors. Inspection of the actual cached Room 2.8.4 bytecode shows inTransaction first checks whether the support DB is open. Direct cold synchronous helper access is deliberately forbidden; removing that guard would move encryption/migrations onto Main. |
| Mission checkpoints exist only in RAM | Production explicitly supplies PersistentMissionCheckpointStorage, while MissionEngine also uses Room MissionDao. In-memory implementations exist for tests/default convenience. Silent defaults are now removed so callers must explicitly choose storage. |
| AudioPipelineManager disconnected | JarvisConversationService constructs AudioPipelineManager(serviceScope, recorder), around line 1957. It is a production path. |
| AutonomousGoalAgent / UniversalActionDispatcher connected | No production references beyond their declarations were found. They remain non-routed modules, not features claimed working. They were not blindly enabled or substituted for the active dispatcher. |
| Semantic embeddings are neural | They are local heuristic/subword-hash vectors, not a pretrained neural model. Source already documents this; the Memory screen now discloses that a neural model is not configured. No cloud upload or fake neural replacement is added. |
| ElevenLabs missing | VoiceSynthesisRouter contains the ElevenLabs request/playback path and is called by the conversation service. Real account/provider testing remains unverified. Auth/subscription is not added to a personal APK just to satisfy a checklist. |
| Gemini 3.8 IDs fictional | The official [model catalogue](https://ai.google.dev/gemini-api/docs/models) currently lists gemini-3.8-flash and gemini-3.8-live. Existing execution also uses account-scoped ListModels/method filtering; documentation does not establish access for a particular API key. |

## Fixes made

- Extracted DeferredSqlCipherOpenHelperFactory from MemoryDatabase. On Main it can reuse only an already-open handle; it never creates native/key storage or reopens a cold/closed helper there. Background opens stay synchronized, retain WAL behavior and the existing preserving corruption/migration configuration. No schema bump, data deletion or allowMainThreadQueries.
- Removed encrypted legacy-chat reading from ChatRepository construction during Application.onCreate. MainViewModel explicitly loads it inside its existing IO migration path. New production turns remain Room-backed. The compatibility addTurn API is deprecated; no production callers were found. Legacy data is still cleared only after successful import.
- Made encrypted checkpoint preferences and coordinator restore lazy. The coordinator constructor now performs no storage reads; Application explicitly restores on Dispatchers.IO. Persist-before-publish and pause-on-process-restart behavior are retained.
- Removed implicit in-memory coordinator defaults from LongHorizonMissionCoordinator and JarvisUnifiedRuntime. Tests explicitly inject in-memory storage; the app explicitly injects persistent storage.
- Caught storage Flow failures on Home chat and Controls memory streams. Storage errors are surfaced, cancellation still propagates, and data is not deleted. An unavailable database should not become an unhandled UI coroutine exception on these paths.
- Added a deterministic constructor/restore-once test and an Android cold-Flow/Main-probe regression test. The latter uses a disposable framework SQLite fixture to isolate Room dispatch, not to pretend SQLCipher was tested.

## Executed verification

Final checks: testDebugUnitTest, lintDebug, assembleDebug and compileDebugAndroidTestKotlin succeeded in 6m 26s (work/storage-crash-final-checks.log). The new cold-Flow instrumentation test compiled but did not execute: no attached Android device/local emulator run is available. JVM count is recorded from final XML results. Existing unrelated warnings remain.

Final JVM XML: **503 tests, zero failures/errors, one existing environment skip**. Development APK signature verification passed and the extracted helper class is present in the packaged DEX. Local artifact: `/workspace/Jarvis-os/work/jarvis-storage-fix-debug.apk`, SHA-256 `f8c98704d9a67860ed1895e8811e0a2f1e9836d8a2c8d2379c55a6666c556681`. It is a local development-signed build; existing-install upgrade compatibility is not established and stored memories must not be erased to work around a signing mismatch.

The user has no fatal stack trace. No exact HyperOS crash cause or on-device fix is asserted from a screenshot/pm-grant failure. New source needs phone startup, memory/history opening, encrypted upgrades and process-death testing.

## Remaining work, explicitly not completed

- AppPreferences still constructs encrypted preferences and runs secure migrations synchronously. That is a remaining startup latency risk requiring a proper readiness/bootstrap change across callers and measurement; these targeted fixes do not make all of Application.onCreate nonblocking.
- The 4k-line conversation service and 2k-line Accessibility service still need staged, behavior-preserving decomposition. No risky wholesale rewrite is presented as a crash fix.
- Real multilingual neural embeddings require a selected/tested on-device model and runtime. Hash retrieval is not relabelled as neural.
- Non-routed automation modules still need explicit integration/design or removal decisions, with authorization and recovery regression coverage. They are not claimed fixed by having files.
- No new auth/subscription backend, live provider test, physical-device result, CI run for these local changes, or push/merge is claimed.

## Files changed in this audit

JarvisApp.kt; data/memory/MemoryDatabase.kt; data/memory/DeferredSqlCipherOpenHelperFactory.kt;
data/repository/ChatRepository.kt; ui/home/MainViewModel.kt; ui/controls/ControlsFragment.kt;
automation/mission/PersistentMissionCheckpointStorage.kt; automation/mission/LongHorizonMissionCoordinator.kt;
core/runtime/JarvisUnifiedRuntime.kt; res/layout/activity_memory.xml;
test/core/runtime/JarvisUnifiedRuntimeTest.kt; test/automation/mission/MissionStartupLoadingTest.kt;
androidTest/data/memory/ColdRoomDispatchInstrumentedTest.kt; this report.

All application changes remain under Part 7. Existing memory contents, keys, package and branding are preserved.
