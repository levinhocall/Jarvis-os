# V2 A3 — typed DB readiness and cancellation-safe deadlines

Design/recon/callers: [ADR 002](../adr/002-db-readiness-state-and-timeout.md). Task-specific supplied-pack clause permits ADR-then-proceed-unless-blocked for A3; A4 still has a mandatory separate review gate.

## Implemented

- Generalized the existing DatabaseReadiness gate, not a parallel provider: one StateFlow<DatabaseState<T>> (Loading / Ready(actual value) / Failed(actual cause)). MemoryDatabase exposes the actual MemoryDatabase typed state.
- Positive bounded await budgets; warm Ready/Failed fast path; non-cancellation timeout exception. Timeout/caller cancellation cannot cancel the owned shared native opener or invent readiness. Cached initializer failures do not trigger destructive reset/retry.
- Existing Unit-returning repository callback retained; getReadyInstance accepts an optional deadline; app boot uses a longer explicit budget. Repositories and services continue using the same original DB object/gate.
- AutonomyWorker acquisition failures use bounded retry/failure and the existing honest storage notice path. No cloud/autonomous action claims on failed acquisition.
- Debug-only Main disk-I/O StrictMode logging; existing IO-native trace retained. AppPreferences I/O is a known separate limitation.
- Test-only coroutines-test 1.9.0 (135,030-byte publisher JVM artifact; Apache 2.0); no Android runtime dependency upgrade intended. Version/size/license rationale in ADR.

## Executed evidence

| Check | Observed result |
| --- | --- |
| Before refactor: `./gradlew :app:testDebugUnitTest --tests '*DatabaseReadinessTest' --no-daemon --offline` | **Passed 4 characterization tests**, 58s. Owner cancellation added before production refactor; original 3 behaviors retained. Commit 421af58. |
| First new API readiness run | Compiled, 8/9 passed; expected initializer-failure case surfaced as a runTest background-scope uncaught child error. Harness updated to an explicitly owned SupervisorJob + fake dispatcher, matching production supervision; no production failure was swallowed to make it green. |
| Final `./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --offline` | **BUILD SUCCESSFUL in 6m40s**, 52 tasks (19 executed, 33 up-to-date). **520 JVM cases, 0 failures/errors, 1 existing skip**. Readiness 9/9, latest XML timestamp 2026-11-11T13:05:18 UTC. Log `work/v2-a3-final-checks.log`. |
| Nine fake-clock/readiness tests | Lazy Loading, shared initializer, waiter cancellation, owner cancellation, cached failure/cause, timeout with later success, independent deadlines, invalid budgets and warm path. Actual SQLCipher is not substituted with fake success in production. |
| Android instrumentation | Updated existing Main-await/native-worker test to assert Ready contains the actual database. **Compiled only**, no device execution. |
| Schema/manifest/source guard | Existing migration blocks, encrypted key/encryption/deferred-factory files, schema JSON, manifest and Parts 1–6 unchanged. No native database data was accessed/modified by this source task. |

Local lint is a baseline/new-issue gate; existing 1,423 documented entries are not claimed fixed. No baseline/category suppression was added by A3. Runtime core version comparison uses `:app:dependencyInsight --configuration debugRuntimeClasspath --dependency kotlinx-coroutines-core`; final report records observed selection. No model/Live/TTS/phone action request or new permission.

## NOT RUN / risks / rollback

No physical/emulated Android test, StrictMode/Perfetto capture, measured startup improvement, actual native encryption/corruption recovery, APK assembly/install/signing/upgrade or main merge. Typed/fake-clock tests prove coordination, not a phone crash fix. No exact FATAL EXCEPTION stack was available.

A slow first encryption may exceed a caller budget while still progressing. A retry can later succeed; boot beyond 120s does not auto-replay missions. Existing memory key/corruption failures remain fail-closed, not fresh-database fallbacks. Revert A3 source/test/build changes to restore prior gate/API semantics; DB format/version stays v8 so no data rollback is needed. Release StrictMode behavior is unchanged.


Observed runtime dependency insight: org.jetbrains.kotlinx:kotlinx-coroutines-core **1.9.0**, unchanged from A0's selected runtime graph after adding the test-only 1.9.0 module. Command completed successfully; local log work/v2-a3-runtime-coroutines-insight.log. Final lint XML: 0 errors/warnings, 2 informational baseline hints. Existing migration-definition blocks were byte-compared against b6b9961 and matched; encryption/key/factory/schema/manifest working-tree diffs are empty.
