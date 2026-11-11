# ADR 002 — extend the existing DB gate with typed readiness and bounded waits

Status: accepted for the narrow task A3 extension under its explicit proceed-unless-blocked clause; committed before implementation. Date: 2026-11-11. Task-specific execution clause in the supplied JARVIS_PHASE_PROMPTS_V2.md: **“DESIGN-FIRST (ADR, then proceed unless blocked)”**. That A3 clause permits this narrow implementation after committing its design; it does not waive A4/B/C/D/E design-review gates.

## Context / verified source

At baseline 2084fe3, MemoryDatabase.kt:33 is already v8; getInstance constructs only a deferred Room facade. Production has zero runBlocking/GlobalScope textual hits (A0). JarvisApp.kt:102 obtains the facade, :105-106 injects the **same** memoryDatabase::awaitReady into MemoryRepository/ConversationRepository; :143 launches a background await before legacy-chat/mission recovery. DatabaseReadiness.kt already provides lazy single-flight open with PENDING/OPENING/READY/FAILED enum states. Native initialization, Keystore/plaintext conversion and actual Room validation run on the existing database-owned Dispatchers.IO scope with JARVIS.SQLCipher.open trace. DeferredSqlCipherOpenHelperFactory still prevents cold Main opens and allows safe warm probes.

Missing versus A3: typed Ready(db)/Failed(cause) observation, caller timeout semantics and deterministic timeout tests; debug disk-I/O StrictMode instrumentation. The old synchronous runBlocking premise is stale. Creating a second DatabaseProvider/open routine would duplicate state ownership and weaken the existing guard.

## Decision / options

Retain MemoryDatabase as the application's single holder. Generalize its existing DatabaseReadiness gate to T and expose one authoritative StateFlow<DatabaseState<MemoryDatabase>>: Loading, Ready(database), Failed(cause). No second MutableStateFlow mirroring a second initializer. Fake openers use simple values in JVM tests, not a fake SQLCipher success.

Keep native open on the same existing owned IO scope; the application singleton owns its lifetime and starts it from its appScope background boot coroutine. A waiter never owns/cancels the initialization. No blocking wait, GlobalScope, busy polling, alternate database or DI rewrite. The existing facade and suspend DAO signatures stay intact.

Offer awaitReady(timeoutMs), returning the ready database. Preserve zero-argument awaitReady(): Unit for current suspend callback/function references, with a conservative 30,000 ms wait budget. This budget is a failure-bound, **not a measured startup target**; actual timings remain unmeasured. Application background recovery uses a longer explicit 120,000 ms budget. getReadyInstance and repository hooks reuse the same gate.

A timeout ends only the caller's wait: native init continues and state stays Loading until actual completion/failure. Surface a typed DatabaseReadinessTimeoutException (not CancellationException), so existing UI/service error handlers report the wait failure rather than silently treating it as a user cancellation. Parent/caller cancellation still propagates normally and must not cancel the shared initializer. Initializer failure stays cached/Failed until process recreation; no destructive retry/reset or key generation. Later callers may succeed after an earlier wait timeout if initialization completes.

Use debug-only StrictMode detectDiskReads/detectDiskWrites with penaltyLog; no penaltyDeath or release change. This will also expose the **known AppPreferences startup I/O** outside this task, so do not claim the entire app is StrictMode-clean. Preserve existing trace marker rather than wrap a suspending function in unmatched thread-local trace sections.

## ALL singleton callers / threading and failure handling

Paths relative to app/src/main/java/com/jarvis/assistant at baseline 2084fe3:

| Caller | Evidence / context | Behavior before ready / on failure |
| --- | --- | --- |
| JarvisApp | :102 onCreate obtains facade only; :143 appScope IO boot; :105-106 repository hooks | Boot suspends with longer bounded wait, never Main open. Existing StorageHealth coroutine handler surfaces non-cancellation failures; no mission recovery before DB readiness. |
| IdeaReviewWorker | data/directives/IdeaReviewWorker.kt:18, CoroutineWorker background | getReadyInstance suspends; existing try/catch preserves cancellation and bounded retry/failure. |
| AutonomyWorker | ui/aura/AutonomyWorker.kt:18, CoroutineWorker background | Currently acquisition is before its retry catch. Put acquisition behind an explicit bounded error/retry guard; do not turn missing DB into a successful autonomous check. |
| NotificationHistoryActivity | ui/notification/NotificationHistoryActivity.kt:78,96,106,148,168,189 | Lifecycle IO work / withContext(IO); existing storageErrors handler and UI notice, no clearing on open failure. |
| JarvisNotificationListenerService | automation/notification/JarvisNotificationListenerService.kt:102,176 | Existing owned IO persistenceScope and error handler; no main-thread native open, no archived-success claim on failure. |
| MemoryRepository | JarvisApp.kt:105, repository readyDao/readyFlow | Same injected suspend hook before DAO access/Flow collection, timeout/error propagates to caller. Room still owns query/transaction dispatch. |
| ConversationRepository | JarvisApp.kt:106, repository readyDao/readyFlow | Same gate; import source remains retained if waiting/open/transaction verification fails. |

MissionEngine receives the generated suspend MissionDao at JarvisApp.kt:130; Room DAO queries already dispatch off Main, and boot recovery waits on the same gate. Do not create a parallel mission storage engine or rewrite all DAO contracts. No other production MemoryDatabase.getInstance caller was found; grep all getReadyInstance matches above. AuditDatabase is separate existing audit storage, not replaced here.

## Tests before/after / measurement

Existing three DatabaseReadinessTest cases already characterize lazy init-once, concurrent waits, caller cancellation isolation and cached failure (fresh A0 suite passed). Extend characterization with parent/owner cancellation where needed; tests precede implementation of the new API in the working tree. New fake-clock tests cover Loading→Ready(value), Loading→Failed(cause), bounded timeout while initializer continues, later successful await, concurrent deadlines, invalid budgets and caller cancellation. Existing caller/transaction/regression suites remain green.

Test-only dependency: org.jetbrains.kotlinx:kotlinx-coroutines-test **1.9.0**, matching the **resolved** runtime core/Android version 1.9.0 from A0's dependency graph (declared app Android version is older but resolves upward). Official Maven publisher artifact test-jvm is **135,030 bytes**, HTTP 200; publisher POM identifies Apache License 2.0. JetBrains-maintained; precise current maintenance/latest-release policy is UNVERIFIED because release-note domains are blocked. This is a deterministic fake-time testing dependency, **zero APK impact**. Alternative wall-clock sleeps make timeout tests slower/flaky; no runtime coroutine upgrade is intended. Compare runtime dependency insight before/after.

Keep/add Android instrumentation proving Main can await and actual native open runs on worker (existing RoomMigrationInstrumentedTest already covers this); compile it, mark execution NOT RUN without a device. No native migration, startup benchmark or StrictMode-clean claim from JVM tests.

Measurement procedure remains in docs/architecture/DATABASE_STARTUP.md: identical-data force-stopped launches, median/p95 TotalTime/WaitTime, Perfetto main-thread blocking slices and JARVIS.SQLCipher.open thread, fresh/pre-encryption/encrypted datasets. Never clear the real user's data. Add no invented performance numbers.

## Risks / rollback / blocked conditions

Timeout changes previously indefinite waiting; slow first encryption can outlast a UI budget. It must not interrupt migration, delete/recreate data or claim permanent corruption. User can retry/reopen once Ready; boot exceeding its longer budget leaves mission recovery for later manual/app-start recovery. This limitation must be reported. Debug StrictMode can produce known non-DB startup warnings; no fatal penalty is introduced.

DB version, file name, passphrase/Keystore format, encryption preparation, corruption-preservation callback, migration objects and schema exports stay byte-for-byte unchanged. Revert A3 source/test/config commits to restore former enum/await behavior; no data rollback/migration is needed. Do not add auto-fresh-DB recovery for lost keys. If native tests fail or schemas drift, stop and inspect rather than bypass them.
