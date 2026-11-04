# Offline wake engine: implementation and operational specification

## Scope and guarantees

Wake recognition and the microphone test run locally, without provider keys or cloud speech compilation. Optional model acquisition requires internet during setup only. A successful normal wake hands control to the existing assistant; live assistant replies may use the configured cloud provider. The explicit microphone-test action never starts that conversation.

There is no defensible zero-latency, zero-battery, or guaranteed pocket-recognition promise. Vosk waits for an utterance endpoint and this implementation deliberately rejects unstable partial results. CPU inference, AudioRecord and a screen-off CPU wake lock consume battery. Pocket fabric attenuates speech. Android/OEM task termination, force-stop, microphone privacy controls, calls and permission revocation can prevent capture. Physical handset measurements are release criteria, not implied by compilation.

## Kotlin modules and existing dependencies

- `audio/wake/WakeMatcher.kt`: pure bounded acoustic-decision policy.
- `audio/wake/WakeSettings.kt`: private local call sign, alias, sensitivity and pocket preference; ephemeral diagnostics StateFlow.
- `audio/wake/ModelArchive.kt`: bounded ZIP extraction and model-layout validation.
- `audio/wake/ModelDownloadWorker.kt`: explicit WorkManager download, progress, cancellation and transactional activation.
- `audio/VoskModelManager.kt`: lazy shared native model, installation mutex and bundled recovery.
- `background/JarvisConversationService.kt`: existing foreground microphone ownership, offline standby, test mode, bounded recovery and handoff.
- `ui/directives/WakeWordActivity.kt`: setup, progress listener, custom call sign, phonetic alias, sensitivity slider, pocket toggle and local mic test.

Reuse existing `com.alphacephei:vosk-android:0.3.75@aar`, OkHttp and WorkManager. The Maven group already used by the repository is **com.alphacephei**, not org.alphacephei; Kotlin Vosk classes live under `org.vosk`. No new dependency was added.

## Foreground service and audio contract

The manifest already declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `RECORD_AUDIO`, notification permission, wake-lock permission and a non-exported JarvisConversationService with `microphone|mediaPlayback|mediaProjection` types. This shared service owns both wake and live voice; do not instantiate a competing second microphone service. Runtime promotion must include microphone before standby is allowed. A visible activity explicitly starts listening following permission handling; boot recovery does not start a microphone.

Use one Vosk SpeechService and Recognizer, 16 kHz PCM input, with final word confidences enabled. Full-vocabulary decoding avoids artificially forcing ambient speech into a small keyword grammar and allows text configuration without recompiling models. CPU/battery cost may exceed a restricted grammar or hardware DSP hotword engine and must be measured.

Normal enabled listening requests START_STICKY (best effort only). Local test requests START_NOT_STICKY and stops when its task is removed. Stop closes the SpeechService/Recognizer, cancels model waits/recovery, releases CPU/Wi-Fi locks and stops the foreground service. Only active online conversation acquires the Wi-Fi lock. Screen-off CPU locking uses a non-reference-counted 40-minute lease renewed every 30 minutes while the service lives; shutdown cancels renewal. No microphone or automatic wake feature is enabled by default.

On a capture error, close stale handles before retrying with 1/2/4-second delays. After three retries or missing permission, stop and expose a restart instruction. Android can kill the process directly on permission revocation; no application exception handler can override that. Calls suppress wake acceptance. Pocket suppression remains on by default, with an explicit allow-pocket option.

## Model setup, progress and storage contract

The APK retains its existing ~68 MiB unpacked English speech model, so normal wake setup needs no download. Optional recovery downloads `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip` (~40 MB compressed, ~70 MB unpacked) into private internal staging storage.

WorkManager unique work prevents duplicate jobs and waits for connectivity/non-low battery. Users can cancel or retry. Known Content-Length produces measured 0–100% download progress, throttled to 250 ms; unknown length remains indeterminate. 100% indicates downloaded bytes, not successful extraction or initialization: phase and final WorkInfo state are shown separately. Progress observation survives activity recreation. A slow download is bounded by a three-minute call timeout and 15-second read timeout; cancellation checkpoints abort copying/extraction, with a blocked socket bounded by the read timeout. Network or storage failure returns a visible failure, not success or infinite retry.

Require 350 MiB free storage. Maximum download: 96 MiB. Maximum expanded data: 256 MiB and 3,000 entries. Reject absolute paths, backslashes, duplicate files, ZIP traversal, truncated downloads and incomplete model layouts. HTTPS certificate validation is enabled; redirects are not followed. ZIP CRC checks and a native Vosk Model load must succeed before activation. This is not a pinned publisher-hash verification scheme; it trusts the official HTTPS origin.

Installation uses same-filesystem staging/rename with backup/rollback and a shared mutex. An in-use downloaded model is never overwritten; a previously loaded bundled model stays valid until process restart. Only disposable model directories may be repaired. User memories, conversations and encryption keys are never deleted. If a cached model fails native initialization, fall back to a fresh copy of the bundled model. Out-of-memory/native-library failures surface a status rather than silently reporting ready.

## Custom-name and sensitivity policy

Accept one or two English words, 2–20 letters each, plus one optional phonetic alias. The bundled model is English; arbitrary scripts or invented pronunciations cannot be made acoustically recognizable by a string matcher. Test a name locally and add a recognized phonetic alias when necessary. This is configuration without model retraining, not universal open-vocabulary recognition.

Recognize the name alone or with `hey`, `hi`, `hello`, `ok`, `okay`, `wake up`, `sun` or `suno`. Require a complete short phrase rather than incidental substring matching. Say the wake phrase separately from a command. Reject unknown/missing confidence results, non-finite confidence, implausible durations and long unrelated utterances. Keep the existing 800 ms re-entry cooldown and enrolled-owner checks.

Sensitivity 0 means strictest, not disabled: minimum confidence is `0.95 - 0.30 * sensitivity / 100`. At 75–100 only, a prefixed single-word name of four or more letters can differ by one insertion/deletion/substitution. Unprefixed names always require exact matching. Sensitivity is a recognition acceptance threshold, not gain control or perfect separation of music/other voices. Changes apply on the next result without rebuilding a recognizer.

## Live microphone test

The test uses the same microphone service, native recognizer and matching policy. Show partial recognition as diagnostics only; final results show acceptance/confidence or rejection. A match flashes the existing wake border but never initiates cloud conversation. Text is bounded and held only in StateFlow; stopping clears it. No audio or test transcript is persisted or transmitted. Persistent notification distinguishes local testing from ordinary wake listening and exposes Stop.

## Verification and remaining release gates

Automated checks include custom names/aliases, bounded fuzzy acceptance, substring false positives, invalid confidence/duration, strict sensitivity, ZIP traversal, expanded-size limits, incomplete archive layouts and model loading with silent input. Android instrumentation additionally starts/stops the actual microphone foreground service in local-test mode and asserts no assistant session starts.

Results and delivery references are recorded after execution. Physical device gates: false accepts/hour and false rejects across names, measured end-of-speech-to-wake latency, eight-hour battery/thermal measurements, pocket/bag attenuation, screen-off/Doze/HyperOS operation, microphone privacy switch and permission revocation, calls and competing recorders, download interruption/disk-full recovery and official-server availability. The full model download against the official server has not yet been verified in this execution environment. Do not label these measurements passed without device evidence.

## Reproducing checks

Run `./gradlew :app:testDebugUnitTest :app:lintDebug` from `Part 7`. Run `:app:connectedDebugAndroidTest` only on a disposable Android 16 emulator: the existing repository test suite includes storage-clearing fixtures and is not a safe test procedure for a personal installation containing memories.

On a phone, use Aura → Offline Wake Word. Save a call sign/alias, start Live microphone test, speak the phrase separately, and verify the displayed acceptance/rejection while adjusting sensitivity. The test must not start a live assistant session. Stop from the notification and confirm Android's microphone indicator clears. For background use, choose Enable background wake listening; explicitly enable pocket wake if desired. Test permission loss, calls, screen lock and battery restrictions manually without clearing app data. Record latency and false-trigger measurements rather than assuming the emulator establishes acoustic accuracy.

## Executed verification

Local Gradle checks: 426 unit tests, 0 failures/errors, 1 environment-dependent skip; lint 0 errors / 1,254 warnings; Android instrumentation Kotlin compilation passed. Android 16 emulator run [41836468630](https://github.com/levinhocall/Jarvis-os/actions/runs/41836468630) passed all 21 tests, including the actual foreground microphone start/stop test with the assistant session off and Vosk model loading/silence rejection. Runtime source: `b6dbbc0`. Subsequent source `e4448d4` only corrects current-download selection, separates status labels, and refreshes the standby notification.

See [OFFLINE-WAKE-EVIDENCE.json](OFFLINE-WAKE-EVIDENCE.json) for artifact and CI details. Acoustic recognition accuracy, long-duration battery behavior, OEM pocket operation and the official model download end to end remain unverified.

CI [41836994092](https://github.com/levinhocall/Jarvis-os/actions/runs/41836994092) passed tests, migrations, lint, assembly and publication for source `e4448d4`. [Download 1.9.1 (145)](https://github.com/levinhocall/Jarvis-os/releases/download/debug-ci-301/app-debug.apk). The published artifact was downloaded and passed SHA-256 and APK signature verification; package `com.jarvis.assistant`, target SDK 36. SHA-256: `df0f38fb0f141bc2158f3c6315c4edc6200a8efddb0fbef7fca33e263d55b42d`. Its debug certificate differs from earlier builds; do not uninstall an existing installation without a verified memory backup. Stable private CI signing remains unconfigured.
