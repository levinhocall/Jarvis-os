# ADR 005 — bounded accessibility lifecycle and voice resources

Date: 2026-11-12. Scope: Part 7 only, source 3795d9a. Authorized by the production-stability/accessibility mission; no new service, permissions, dependencies, database schema, UI framework or privileged restart.

## Findings and options

The existing AccessibilityHealthModel treats a singleton/non-null state as operational; refresh can promote ERROR without a probe, onRebind publishes CONNECTED, failures/events share unsynchronized counters, event callbacks run armed tree searches synchronously, and four gesture entry points can cancel each other. Root selection can choose an unfocused application. Snapshot BFS limits visits but not child enqueue width/ownership. AudioPlayer has an unbounded queue and a flushed/dequeued frame can acquire the new epoch; released tracks can be recreated by an old worker. AudioRecorder retries failed replacements indefinitely. See stability report for source evidence and remaining risks.

Options: replace the automation stack; add a second service/queue/recorder; or extend existing owners. Choose incremental extension. Existing SerializedActionExecutor remains the only coroutine mutation gate; add bounded admission/health accounting and Main dispatch, retain generation/cancellation and consequential-action policy. Existing AccessibilityHealthModel gets a deterministic synchronized reducer with connection tokens and bounded metadata-only diagnostics. Events are coalesced as primitive metadata, not queued Android event objects. Legacy actions and all gestures must reject stale lifetimes and overlapping gestures; completion/timeout is distinct from verified application success.

## Ownership and limits

Only onServiceConnected establishes a new instance/token. Destroy/unbind clears it by identity; old callbacks cannot heal a new session. onRebind alone is not readiness proof. Ready requires a real readable snapshot; idle event silence is informational, never a dead-service test. Recovery cancels existing task work, clears owned callbacks/context and probes the currently connected service; disconnected recovery directs the user to Android Settings, never toggles secure settings.

Node work uses fresh local snapshots, bounded width/depth/time and explicit recycling on API 26–32; API 33+ recycling is a no-op. No screenshot/FLAG_SECURE bypass. Existing consent remains. Synchronous Binder calls cannot be forcibly preempted by coroutine timeouts; device ANR traces remain necessary. Repeated important mutations are not replayed after unknown outcomes.

Bound playback bytes/frames and carry the epoch with each frame through dequeue/write; stop/release is terminal and old workers cannot recreate AudioTrack. Bound microphone recovery and back off on zero reads. No fabricated waveform or second microphone. Retain the microphone lease until capture cleanup completes; existing service transitions await availability cooperatively with a bounded deadline before attempting a fresh lease. Unavailable hardware is visible rather than bypassed.

The read-only compatibility StateFlow projections inherit the existing coroutines 1.9.0 interface without creating jobs/scopes. This is explicitly opted into InternalCoroutinesApi/ExperimentalForInheritanceCoroutinesApi; the pinned coroutines upgrade requires a compile/collector contract review. The deterministic source reducer remains the single state owner.

## Verification / rollback

Deterministic JVM tests cover lifecycle tokens, idle/null/empty/stale states, bounded diagnostics, overload/cancellation/deadlines, single-flight gestures, window selection and audio bounds/epochs. Baseline tests/lint are run before changes; then tests/lint/debug APK and Android-test compilation. Native Android/device/soak/performance verification is separately NOT RUN if unavailable. No FPS, latency, ANR elimination or permanent background claim without measurement. New source remains on a feature branch until available checks pass. Roll back these source changes without downgrading Room v9 or changing keys/data. Existing v9 data/migrations remain untouched.
