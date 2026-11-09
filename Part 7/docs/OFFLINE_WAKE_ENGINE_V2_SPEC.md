# JARVIS Part 7 — Offline Wake Engine V2 Engineering Contract

## Current state
Existing `JarvisConversationService`, `AudioRecorder`, `VoskModelManager`, and `WakeWordActivity` already own the foreground microphone path. Do not add a second capture service. The bundled model is already approximately 68 MiB and requires no first-run download.

New, independent components on this branch:
- `audio/WakePhraseMatcher.kt`: text-level configurable matching, 0–100 sensitivity, monotonic elapsed-time cooldown.
- `audio/WakePhraseMatcherTest.kt`: focused unit tests (not yet executed).
- `audio/VoskModelDownloader.kt`: opt-in HTTPS ZIP installer with SHA-256, ZIP traversal defense, size bounds and progress.

## Service requirements
1. User explicitly starts microphone foreground service from a visible activity; Android 14+ requires microphone FGS permissions and valid while-in-use microphone eligibility. Do not start microphone capture at boot.
2. One AudioRecord owner at a time. Pause/stop standby recognition before Gemini Live captures audio, then resume only when authorized.
3. Start foreground notification promptly, show a persistent microphone notification and offer a Stop action.
4. Capture PCM 16-bit mono at 16 kHz, blocking reads on a dedicated IO thread; reuse buffers and avoid FFT on the wake-only path.
5. Feed Vosk incremental PCM, use partial/final hypothesis JSON, and apply a bounded phrase matcher. Avoid partial-result duplicate activations.
6. Suppress false triggers using phrase threshold, optional RMS/noise gating with calibrated floor, a 2.5 s cooldown, and an optional second-stage verification before activating cloud-backed conversation.
7. On permission revocation, privacy toggle, AudioRecord ERROR_DEAD_OBJECT, model failure, or service teardown: release recorder, recognizer and notification state; surface actionable status and bounded retry.
8. Respect battery optimizations and OEM background limits; never claim guaranteed pocket recognition.

## Model lifecycle
- Prefer the bundled offline model already used by VoskModelManager.
- Downloading is optional, explicit, and network-bound; use WorkManager with network constraints and foreground progress notification for long jobs.
- Trust a pinned HTTPS host and a published SHA-256 checksum, not an arbitrary untrusted user URL.
- Extract to staging; validate model structure and instantiate `Model` before activation.
- Keep a previous working model until replacement has passed validation; do not replace while the recognizer is reading it.
- Check free space, zip bombs, archive size, checksum, cancellation, retry/backoff and cleanup.
- Expose progress as a StateFlow: Downloading 0–70%, Extracting 70–99%, Validating, Ready 100%, Failed.

## UI contract
- Custom call sign input, validated at 1–40 characters.
- Sensitivity slider 0–100 with explicit explanation that it affects text fuzzy matching, not physical microphone gain.
- Local mic test with on-device waveform/RMS meter, recognized hypothesis preview and false-trigger counter. No recording retained unless the user opts in.
- Listening enabled/off, model health, last local detection and privacy controls.
- Voice test cannot run simultaneously with an active Gemini Live microphone capture.

## Privacy
No wake audio, transcripts or contacts leave the device. Do not log raw PCM or speech text in production. If Gemini Live is enabled, disclose that post-wake conversation may use the configured provider.

## Release gates
- Android 8 through target SDK test matrix; Android 14/15/16 microphone FGS restrictions.
- Unit tests: normalization, custom names, strict/lenient thresholds, cooldown, Unicode, unrelated speech, bounded inputs.
- Instrumented tests: permission revocation, foreground/background transitions, Bluetooth headset, call interruption, screen-off, device locked, audio focus, service kill/restart.
- Real device measurements: 8 h standby battery drain, wake latency p50/p95, false accepts/hour and false rejects in quiet, street noise, and pocket.
- Never claim zero-latency, zero-power or absolute reliability.
- No APK-producing Gradle tasks until explicitly authorized under repository engineering policy.

## Integration blocker
These new classes are not yet wired to the existing standby recognition lifecycle. The downloader is not yet connected to WorkManager/UI and does not validate a model by loading it before replacement. The current branch must not be merged as a production-ready wake engine.
