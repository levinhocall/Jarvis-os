# Runtime verification still required

Environment checked 2026-11-12: `adb devices -l` returned no devices. No emulator executable or `/dev/kvm` was available. All scenarios below are **NOT RUN**. JVM reducers/fakes and Android-test compilation do not establish Android/OEM lifecycle correctness.

## Preconditions

Use a disposable test profile/device and non-sensitive test contacts. Export personal data before any installation/signature experiment; do not uninstall the user's app or clear its data to make a test pass. Preserve SQLCipher keys, schema version and data. Record the tested commit, APK SHA-256/signing certificate, device/build/API, enabled permissions, battery settings, network and clock. Confirm microphone/privacy controls and the user-visible foreground notification. Never enable Accessibility or grant privileged settings without the user's platform consent.

## Matrix and acceptance evidence

| Scenario | Procedure / evidence to record | Acceptance |
| --- | --- | --- |
| 100 cold starts / process recreation | Start with an existing encrypted DB, run 100 force-stop/explicit-launch cycles on the disposable profile, collect AndroidRuntime, ActivityManager and ANR/Perfetto traces. Preserve data across all cycles. Include v8->v9 upgrade separately. | No crash/ANR; startup/DB readiness correctly reported; memory intact. Report failures per cycle, not just final launch. |
| 100 voice cycles | Explicitly open/close Voice Mode 100 times, including rapid stop/start and voice-to-wake handoff. Record ownership state, mic privacy indicator, active sessions and native audio errors. | At most one JARVIS capture owner, no orphan mic indicator/foreground session, no old playback after stop. |
| Two-hour live voice soak | Use configured Gemini credentials and consent; converse in Hindi/Hinglish/English, interrupt speech, pause, change audio routes. Collect redacted connection/recovery states, audio timestamps and system resource traces. | No unbounded queues/reconnect storms; cancellation releases audio; bounded failures visible. No claim of measured audio latency without timestamps. |
| 24-hour background / idle | Enable supported hands-free mode explicitly, alternate lock/unlock/idle/recents removal/OEM power restrictions. | User-visible supported state, bounded recovery and an actionable error if Android refuses capture; no promise of permanent capture. |
| Accessibility disable/re-enable | Observe ENABLED_NOT_CONNECTED/CONNECTING/CONNECTED then a real readable snapshot. Repeat disable/re-enable and process recreation; check stale callbacks cannot affect a replacement connection. | Settings ON never proves READY; disconnected state never claims actionable service. |
| Idle screen / event storms | Leave a static screen for extended idle, then rapidly scroll/switch apps/open keyboard/notification shade/dialog/split-screen. | Silence does not mark the service dead; one coalesced event job, bounded traversal/admission and no growing backlog. |
| Null, stale, empty, custom or protected UI | Lock screen, transient loading, custom Canvas/WebView content, sensitive fields and restricted windows. | Accurate NO_ROOT/EMPTY_TREE/STALE/PROTECTED diagnostics; no fabricated readable content, secure-window bypass or successful verification from missing observations. |
| Gestures and unknown results | Repeated authorized low-impact actions, concurrent requests, gesture cancellation/timeouts, disconnect during action. Inspect actual target UI after each. | No overlap/replay after unknown outcome; completed gesture is separate from verified app result. Explicit recovery cancels old work before accepting new work. |
| WhatsApp send/call | Use explicit authorization and a test contact; prefill a different draft, leave WhatsApp during the delay, test call confirmation and unrecognized dialogs. | Wrong app/draft rejected, no repeated primary call tap, queued/cleared composer never reported as delivered/call-connected. |
| Permissions / network / audio routing | Revoke RECORD_AUDIO/contacts during operations, use mic privacy switch, airplane mode, Wi-Fi/mobile switch and Bluetooth changes. | Visible bounded recovery or terminal stop, no session restart storm or retained active mic after terminal error. |
| CPU/RAM/battery/native handles | Compare same scenario/device/brightness/build type with Perfetto or Android profiler; log peak/RSS/native allocations, CPU, wake locks, battery and ANRs for equal durations. | Report measured distributions and collection conditions. Host test timing or queue caps are not Android performance measurements. |
| Native Room / SQLCipher | Run existing migration/key/corruption instrumentation suites with seeded fixture DBs, including 10k messages. Use isolated test data. | Integrity/encryption and preserved records verified on actual SQLite/SQLCipher; JVM fixtures alone are insufficient. |

## Startup timing method

Collect equal baseline/current runs on the same device, version/data shape and cold-start definition. Use `adb shell am start -W -n com.jarvis.assistant/<actual launcher activity>` only after resolving the real launcher from the installed manifest. Pair launch timing with Perfetto Main-thread disk/Keystore/SQLite slices and the existing database-readiness events. Report median/p95, sample counts and observed ANRs separately for fresh, existing v9 and v8-upgrade databases. Do not infer startup improvement from a faster Gradle build or from removing a source-level blocking call that was already absent at the audited base.

## Recovery contract

The existing Permissions/Hands-free diagnostics display the real connection token state, snapshot/action/verification times, pending count, operation duration, typed failures and bounded recovery history. “Cancel task & probe” cancels owned work and reads the currently connected service. It cannot undo a side effect already accepted by another app, interrupt every synchronous Binder call, resurrect an unbound service, override Android/OEM restrictions or enable Accessibility. If disconnected, use the provided Android Settings action. Do not repeatedly toggle permissions as a hidden restart strategy.
