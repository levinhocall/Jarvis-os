# MAYA technology integration: implementation and remaining work

Baseline: main `3cd65a939aa21b4b192f9091fb1652f5adb8f93e`, clean tree,
2026-11-16. Working branch: `phaseA/maya-tech-parity`. Only Part 7 changes.
The supplied reverse-engineering report is secondary evidence, not source code,
a license grant, an Android SDK, or a performance benchmark.

## Six-pillar status

| Pillar / requested component | Actual implementation in this change | Limits / outstanding work |
| --- | --- | --- |
| Brain: multiple providers | Existing mission planner now selects Gemini, OpenAI, OpenRouter, DeepSeek or an explicitly configured HTTPS OpenAI-compatible API root. Typed schemas, one tool per step, cancellation, timeout, existing approval and verification retained. Keys use the existing encrypted key pool. | REST planner responses, not SSE chat/audio. No silent Live-to-another-provider failover. Provider credentials and service availability have not been tested against live accounts. Model IDs are user-configured for external providers. |
| Gemini adjacent-turn repair | Audited DirectiveAiClient, LlmProvider and GeminiLiveWebSocket: these paths currently serialize a single user turn, not an alternating historical chat array. | No verified adjacent-role HTTP400 failure was reproduced. Adding a detached repair helper would not fix an actual call path. Future multi-turn request assembly needs normalization at its serialization boundary. |
| Tool tiers | Existing real ToolRegistry remains the authority; OpenAI tool schemas now expose its validated tools. | No fabricated 55-tool catalog or main-thread execution/50ms guarantee. A unified tiered disclosure policy across Live and mission catalogs remains outstanding. |
| Deterministic JSON workflows | MacroCompiler compiles bounded JSON into existing MissionEngine steps. Mission Desk reviews and starts them through existing authorization, checkpoints and observable verification. New wait_for_screen_text requires stable actual observations, respects cancellation and cannot infer disappearance from an unreadable/wrong screen. | Current tools: open_app, inspect_screen, click_ui_element, type_text, scroll_screen, wait_for_screen_text. No independent mission engine. MAYA aliases, optional-step semantics, read-aloud step and shipped third-party app recipes are not implemented. |
| WebRTC AEC3 | Actual public BSD source pinned by commit and digest, Android JNI/CMake builds for arm64-v8a, armeabi-v7a, x86 and x86_64. Existing AudioPlayer supplies accepted PCM writes and queued-frame delay; existing AudioRecorder processes capture. Session/epoch reset rejects old reference data. Optional setting OFF by default. | Not libmaya_aec3. Host synthetic echo and near-end tests do not establish loudspeaker/Bluetooth performance. Non-PCM TTS uses guarded listening; it is not falsely labeled full duplex. |
| Silero speech detection | Official recurrent Silero v4 ONNX model, ONNX Runtime Android, 480-sample frames and persistent h/c state. Separate speech decision from RMS. Used by optional neural wake and optional active-voice/AEC3 path. | This is ONNX, not the report's unavailable converted TFLite file. Fixed 16kHz/480-sample contract validated against shipped tensors. Real speech/false-positive accuracy unmeasured. |
| OpenWakeWord | Actual public mel, speech embedding and Hey Jarvis TFLite models via LiteRT. Shared AudioRecorder/microphone lease; bounded history, hashes, warmup, repeated probability and Silero speech gate. Optional toggle OFF. | Trained Hey Jarvis classifier only; custom names, existing enrolled-owner mode and plain x86 retain Vosk. No custom-name neural-training claim. CC BY-NC-SA pretrained weights: personal/noncommercial distribution only unless separate permission is obtained. |
| DTLN AEC | Public MIT breizhn/DTLN-aec source and both 128-unit TFLite models inspected with real host interpreters. | NOT integrated. Its 512-sample/128-hop reference timing and overlap-add must be correctly aligned with audible playback. Stacking an unaligned processor behind AEC3 would not establish correct echo suppression. Research files are not shipped. |
| Neural turn completion | Optional Smart Turn v3.2 CPU ONNX consumes existing PCM; original Kotlin Whisper frontend matches official Pipecat reference. Bounded last-8-seconds buffer, one analysis per pause, stale-result rejection, existing VAD fallback; opted-in Live audioStreamEnd hints and confirmed-speech stream reopening. Silero gates accepted speech on this optional path. | Audio-based public alternative, not MAYA token/JNI code. Device accuracy/latency unmeasured; no sub-300ms claim. |
| Pocket TTS | Installed system-TTS engine selection wired into both existing local TTS owners. Supports the public PocketTTS Android engine package through the standard Android API; existing Gemini/ElevenLabs paths preserved. | User must install engine/models; no Pocket weights/JNI copied. Actual Pocket speech, chosen engine and latency NOT device-tested. Android may fall back if an engine cannot initialize; UI says requested, not confirmed. |
| Voice Guardian | Existing Vosk/Kaldi neural x-vector owner enrollment, matching and encrypted profiles retained. Official MIT ReDimNet source exists and was inspected. | Exact MAYA converted model/frontend/calibration unavailable for direct audit. No ReDimNet/ECAPA compatibility claim; switching incompatible vectors would break profiles. Speaker identity never replaces action authorization or Android lock security. |
| Accessibility matching | Bounded normalized >=0.90 Levenshtein fallback preserves Indic text, exact numeric identifiers and existing semantic priority; ambiguous approximate targets are refused. | Existing service remains sole accessibility owner. No claim every app exposes readable nodes. |
| Tap feedback | Existing opt-in indicator upgraded to one pooled animated ripple, reduced-motion handling and explicit service teardown. | Animation/device appearance NOT device-tested. |
| Financial privacy | Exact known financial package guard blocks node/screenshot actions; protected events stop MediaProjection and invalidate grounding. Projection checks a privacy latch before accepting/publishing frames/snapshots. | Not a universal bank/app/web-domain classifier. Requires actual accessibility package events; no claim to protect unknown apps or frames already sent before a transition was observed. Protected windows are never bypassed. |
| Termux | New production ShellPolicy defaults to a small diagnostic allowlist and requires exact trusted review for other commands. Existing mission/Dev Studio approvals wired through it. Real official nested result Bundle/err=-1 parsing replaces the missing-exit-as-success bug. Unique one-shot PendingIntent URI, bounded queue and cancellation cleanup. | This is an app dispatch policy, not an OS sandbox. Explicitly reviewed developer shell commands remain supported. No speculative startForegroundService call to an unverified Termux FGS contract. Termux phone IPC NOT device-tested. |
| Default Android assistant | Real VoiceInteractionService, session service and bound offline RecognitionService; role chooser uses Android system UI. Recognition reuses Vosk/AudioRecorder and the process microphone lease. | User must select the role. Recognition adapter currently supports the installed English Vosk contract, not arbitrary language packs. Role/keyguard behavior needs device verification. Locked session asks the user to unlock; no lock-screen bypass. |
| Accessibility self-healing | Existing connection-token diagnostics, bounded recovery and enabled-vs-connected distinction preserved. | No automatic secure-settings toggle is added. WRITE_SECURE_SETTINGS requires a genuine platform grant and cannot guarantee OEM service restart; the user's earlier shell error did not grant it. Manual Android recovery remains truthful. |
| Continuous service | Existing microphone/mediaPlayback/mediaProjection types remain tied to actual operation. | No unused camera/dataSync types or unsupported immortality guarantee. Android 14-16 background/permission restrictions still apply. |
| Floating reactive orb | Existing optional overlay and plasma renderer preserved; they observe existing presentation facts. | No duplicate recorder or fabricated playback amplitude added. Existing features have their own device-verification limits. |
| Interactive wallpaper | Additional optional WallpaperService reuses PremiumOrbView and the existing voice snapshot. Touch response, visibility cancellation, reduced-motion and power-save frame rate. Existing video wallpaper preserved. | Activated only through Android's wallpaper picker. No microphone/session starts. Surface/lifecycle/appearance NOT device-tested. |
| In-app browser | XML/ViewBinding HTTPS browser, no privileged JS interface, file/content/mixed-content/geolocation/mic/camera access blocked, explicit page reading through an installed offline voice. Feature Hub entry. | User browsing creates ordinary WebView/network data; this is not incognito. Page content never authorizes tools. No automated login or unsupported screenshot scraping. |
| Secrets and face lock | Existing encrypted key storage/Keystore and local memory remain authoritative. | No duplicate preference store. Camera face detection alone is not identity verification. Watchman/MobileFaceNet enrollment and device-lock integration remain unimplemented pending a verified model, user-consent design and supported APIs. |

## Architecture boundaries

- `JarvisConversationService` owns active conversation and captures; existing `AudioPipelineManager`,
  `AudioRecorder`, `AudioPlayer`, `MicrophoneOwnership` and presentation store remain in use.
- `DuplexEchoSession` owns DSP resources only. It cannot open microphone/player hardware.
  Native creation/model loading occur off the UI thread. Capture/render callbacks serialize
  session state; playback epochs invalidate stale data on flush/interruption.
- `OpenWakeWordPipeline` is a PCM consumer. It does not own Android audio or perform network I/O.
  `JarvisRecognitionService` is a system-bound adapter using the same microphone lease; concurrent
  voice capture is refused instead of opening another competing AudioRecord.
- `ConfiguredPlannerProvider` implements existing `LlmFunctionCallProvider`. It does not start a
  parallel Live session. Tool result success still requires the existing MissionEngine evidence.
- New UI entry points do not change Room schemas, encryption keys, navigation destinations,
  accessibility permission state, existing orb modes, Vosk enrollment or stored memories.

See [primary-source voice audit](VOICE_STACK_SOURCE_AUDIT.md) for actual artifact availability, upstream versions, license evidence and selected compatible alternatives.

## Reproducible host checks

From Part 7, with JDK17 and Android SDK36/NDK28.2.13676358/CMake3.22.1:

```sh
./gradlew :app:compileDebugKotlin :app:externalNativeBuildDebug :app:testDebugUnitTest
./gradlew :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug
node scripts/test-plasma-motion.cjs
node scripts/test-plasma-webgl.cjs
python scripts/verify-neural-models.py
cmake -S app/src/main/cpp -B /tmp/jarvis-aec-test -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/jarvis-aec-test
ctest --test-dir /tmp/jarvis-aec-test --output-on-failure
```

The Python check uses the pinned **host-only** requirements file. No Python runtime ships.
The host native test checks a deterministic delayed echo, reset, resampling and nonzero near-end
preservation. It is not a phone echo/battery benchmark. Android tests in `NeuralAudioAssetsTest`
load actual packaged models/JNI without recording; they are NOT RUN until executed on Android.

## Provenance

- OpenWakeWord official v0.5.1: https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1
- Silero: https://github.com/snakers4/silero-vad (MIT), actual packaged-model digest verified.
- WebRTC extraction: https://github.com/Enaium/webrtc-aec3 at
  `2cec2f52e26646f93bd2d5498bbabf59cba18da9` (BSD); pinned original source archive,
  digest and original JARVIS CMake/JNI integration documented in `app/src/main/cpp/third_party/PROVENANCE.md`.
- DTLN research: https://github.com/breizhn/DTLN-aec at
  `9d24e128b4f409db18227b8babb343016625921f` (verify full hash in final evidence; not shipped).
- Model license notices and runtime license texts are under `app/src/main/assets/licenses`.
- No MAYA binaries, SDKs, persona/voice assets or secrets are copied.

## Required device acceptance (NOT RUN)

Android API26 and Android16/HyperOS: denied/revoked mic; Vosk/OWW switching; screen-off wake;
custom-name/owner fallback; repeated 100 start/stop cycles; concurrent recognition/voice refusal;
noisy room/loudspeaker/Bluetooth AEC3 double talk; route/focus changes; interruption flush;
known financial app/split-screen/keyboard privacy; accessibility reconnect, gestures and queues;
assistant role/keyguard entry; browser navigation/reading cancellation; wallpaper picker,
visibility/lock/rotation/power saver; long-session CPU/RAM/thermal/battery profiling.
No measured device latency, FPS, accuracy or battery figures are asserted.
