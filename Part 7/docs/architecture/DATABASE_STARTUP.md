# Database startup and readiness

## Current source versus the reported crash candidate

At baseline `56b37aa`, `JarvisApp.onCreate()` calls `MemoryDatabase.getInstance()` but that function builds a deferred Room facade. It contains no `runBlocking` and does not call `writableDatabase` on Main. `DeferredSqlCipherOpenHelperFactory` rejects a cold Main-thread open and allows a warm handle probe. Those earlier fixes remain in place; removing that guard would hide incorrect callers.

The explicit `MemoryDatabase.awaitReady()` gate now runs native loading, key access, plaintext encryption preparation, migrations and Room validation in a database-owned IO coroutine. Application startup launches it on IO without waiting on Main. All waiters share one initialization. Cancelling a UI waiter does not cancel initialization, failures propagate consistently and never trigger destructive recreation. A failed gate remains failed until process recreation; key repair/recovery requires user action. No polling or extra database is introduced.

`MemoryRepository` and `ConversationRepository` receive the same gate from the application. Suspending DAO access and Flow collection await it; services use these repositories. Remaining direct DAO consumers (notification listener/history, idea/autonomy workers) call suspend `getReadyInstance()`. The mission recovery startup coroutine awaits readiness before restoring saved missions. Raw generated Room DAO methods remain suspend/Flow and dispatch their own queries; constructing a DAO/facade itself does not open SQLite. No `allowMainThreadQueries` was added to production.

Encryption passphrases, database name/version, all eight-version migration registrations and corruption-preservation behavior are unchanged. `AppPreferences` still performs its existing synchronous encrypted-preference initialization; this change does not claim to remove every startup I/O source.

## Measuring cold-start blocking (not performed here)

Use a disposable Android 16 emulator or a backed-up test installation. Compare baseline and this branch with identical data, thermal state and build type; do not clear the real user's data or change signing keys. Warm filesystem caches and genuine device-cold starts are different measurements.

1. Capture at least 10 force-stopped starts per revision with `adb shell am start -S -W -n com.jarvis.assistant/.MainActivity` (verify the actual launcher component from the merged manifest first). Record median/p95 `TotalTime` and `WaitTime`.
2. Record a system trace in Android Studio/Perfetto with app tracing, scheduling, CPU frequency, disk and frame timeline. Inspect the **JARVIS.SQLCipher.open** trace section. It must run on a worker, never the main thread; compare main-thread runnable/blocking slices and time to first frame.
3. Separately test an existing pre-encryption database copy, existing encrypted data and a fresh install. Log only durations/counts, never keys or records. Verify memory/chat counts before/after migration.
4. Run `ColdRoomDispatchInstrumentedTest` and the real encrypted migration tests. In debug profiling temporarily enable StrictMode disk reads/writes on Main to find other startup I/O; do not infer no disk I/O merely from a successful unit test.

`DatabaseReadinessTest` checks single-flight lazy start, nonblocking suspension, cancelled waiters and cached failures. These are coordination tests, not an Android startup benchmark. No startup latency reduction or device ANR fix is claimed without device measurements.
