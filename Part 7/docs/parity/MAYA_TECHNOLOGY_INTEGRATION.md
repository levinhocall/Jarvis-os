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

## Hunter AI Android capability comparison, 2026-11-20

The [official Maya Android product page](https://www.getmaya.online/android) and [Maya Sites](https://getmaya.online/sites) are evidence of what Hunter AI advertises. The supplied v5.2.56 report describes a different artifact/version snapshot. The publisher's indexed page and report do not establish which version is installed on a phone. The workspace still contains the report and JARVIS artifacts, with no MAYA APK or extracted binaries. Direct retrieval from the publisher domain failed with HTTP 403 at the environment proxy. Maya runtime behavior, exclusive engine contracts and performance comparisons are unverified.

| Area | Maya published or report-described capability | Current JARVIS source path and delta |
| --- | --- | --- |
| Live dialogue, bilingual voice and interruption | Hindi/English, wake/sleep, voice customization and interruption | `JarvisConversationService`, `GeminiLiveWebSocket`, `AudioRecorder`, and `AudioPlayer`. AEC3, Silero, Smart Turn and OpenWakeWord are optional. Vosk retains custom wake names and owner mode. Exact Hunter STT/TTS is unavailable. |
| Voice selection and speaker checks | Hunter TTS, personas and Voice Guardian | `SystemTtsEngineSelection`, `VoiceSynthesisRouter`, existing cloud voices/personality and encrypted Vosk owner profiles. Pocket TTS usable as an installed engine where available. Exact Guardian models, Watchman face lock, and offline neural TTS latency remain unverified or unimplemented. |
| Calls, SMS and WhatsApp | Voice calls, calls answered aloud, texts and WhatsApp sharing/reading | `ContactCaller`, `PhoneControlManager`, mission `ToolRegistry` and notification listener. Contact resolution, call and WhatsApp draft/send/call routes are present. Full default-dialer answering, all chat reading, automatic replies and business/dual-app behavior need separate role or runtime verification. |
| App actions and media | Launch, tap, type, scroll, Spotify and YouTube | `JarvisAccessibilityService`, `VisualTaskOrchestrator`, `YouTubeController`, and `MissionEngine`. Existing grounding, timeout, ambiguous-target refusal and observable completion checks apply. |
| Screen and camera | Screen reading/share, camera and continuous game commentary | Accessibility, `ScreenCaptureManager`, `NeuralLensActivity`, and Gemini Live vision. Financial/private screen guards apply. A dedicated continuous game commentator is not verified. |
| Alarms, reminders and phone controls | Alarms, timers/calendar, torch, brightness, volume and system actions | `MobileUtilityActions`, `CommandParser`, `DeviceActionDispatcher`, and `TriggerReminderNotifier`. Android intent/permission support varies by action. Closing an intent or opening a composer is not evidence of execution. |
| Notifications | Spoken notifications and WhatsApp auto-reply | `JarvisNotificationListenerService`, `NotificationHistoryActivity`, and `NotificationPrivacyFilter`. Opt-in history and unread tool, redaction and supported notification workflows exist. Full auto-reply parity is not established. |
| Files, docs and email | Office/PDF generation, file search/sharing and email | `FileIntelligenceEngine` and existing share/composer and developer workflows. Broad native Office authoring and end-to-end document delivery are not established. |
| Code and websites | Voice builds, GitHub push and Vercel/hosted sites | `CodingDevFragment`, `DevToolRegistry`, `TermuxBridge` and explicit shell/action review. Native hosted publishing equivalent to Maya Sites is not implemented in this change. |
| Web, weather, markets and maps | Research, live data, market watchlists, nearby and navigation | `JarvisBrowserActivity`, `LocationWeatherHelper`, `AmoledMapActivity` and mission web search. Browser read-aloud exists. Verified market feeds, watchlists, and multi-source research parity were not established. |
| Smart home and PC link | Home Box with device control and paired Windows agent | No compatible Hub/PC protocol confirmed. A product integration needs the actual authorized endpoint/device contract. |
| Local memory | Long-term facts and personalization | Room/SQLCipher memory, canonical chat and mission records via `MemoryDatabase`, `RecallCoreManager`, and `MemoryRepository`. Android sync is disabled by the phone-only policy. The standalone backend has a protocol and deployment files. The Android upload worker performs no network work and no hosted deployment is verified. |
| Study, visuals and wallpapers | Whiteboard, animated characters and wallpaper | `WhiteboardActivity`, `EnglishTeacherActivity`, Neural Wave/legacy modes and `JarvisWallpaperService`. JARVIS uses original graphics and its existing character catalog. |
| Background missions and reusable macros | Task execution and named repeatable routines | Durable `MissionEngine`, reviewed JSON macros, and new **Saved workflows** in Mission Mode. Save, edit, preview, rerun and delete from existing encrypted local preferences. Each run recompiles the definition and goes through existing approval and execution verification. Recorded gestures, direct voice invocation by saved name, and unattended replay are outside this implementation. |
| Security and system role | Voice Guardian, bank privacy, default Android assistant and privileged recovery | JARVIS has encrypted preferences/Room, financial protection and native Assistant role services. Retains Android permission mediation, presence checks, bounded recovery and reviewed Termux dispatch. Phone-level tests of the OEM service lifecycle are still needed. |

The saved workflow library supports at most 20 entries and a 256 Ki-character encoded limit. Existing 24-step and 32 Ki-character input bounds still apply. It validates new or edited entries before persistence and rejects duplicate names and stale edits or deletes. Invalid/corrupt storage is preserved. No new schema, cloud storage or account setup is required.

Earlier repository phases are tracked in [Phase A verification](../architecture/PHASE_A_VERIFICATION.md), [accessibility stability ADR](../adr/005-stability-accessibility-recovery.md), [Neural Wave phase checklist](../orb/NEURAL_WAVE.md), and this report's six-pillar table. Code coverage and available builds do not verify real Android or subscription-backed features.

### Earlier phase status from the current source

| Workstream | Verified source state and unresolved work |
| --- | --- |
| Database and canonical chat | Shared readiness gate, encrypted Room, canonical chat and staged v9 ordering/receipt schema are present. ADR003 importer and separate legacy backup/cleanup protocol remain staged. Kotlin Android migration tests compile. Actual encrypted migration, process death and 10k-message timing have not been measured on a device. |
| Memory sync and embeddings | `MemorySyncWorker` is a legacy no-op under the active local-only policy. The backend contains cursor/tombstone support and neural embeddings, plus local deployment configuration. No host deployment or Android bidirectional sync is verified. Android retrieval uses local hashing and lexical signals rather than neural inference. |
| Voice / models / profiles | Gemini Live, conditional ElevenLabs, Android TTS, opt-in neural DSP, local profile and voice enrollment are integrated. No commercial license, credit or subscription backend is part of the personal APK. No live API account, wake accuracy or loudspeaker/Bluetooth soak results are asserted. |
| Mission and provider orchestration | `MultiAgentCouncil`, `BoundedAgentRunner`, `ConfiguredPlannerProvider`, verified tool results, approvals and durable mission checkpoints are present. New saved JSON workflows call the existing reviewed prepared mission route. Hosted Maya Agentic and provider-to-provider Live failover remain separate capabilities. |
| Accessibility and Android integration | Existing connection state, recovery diagnostics and financial privacy guards, plus latest main reconnect/gesture/screenshot fixes and default-assistant adapter. HyperOS connection longevity, role/keyguard behavior and physical gesture verification require device tests. |
| Avatar and UI | Neural Wave compact/full, earlier orb modes, density/palettes, reduced motion, overlay and wallpaper. Desktop shader tests ran. Installed-device fidelity, audio latency, TalkBack, rotation and FPS remain unmeasured. |
| Build/security/release | JVM, lint and Android test source compilation via CI. Signed debug releases published with digest. R8 production build, signed upgrade compatibility and Android instrumentation remain distinct checks. |

Saved-workflow follow-up passed local Kotlin compilation, 618 JVM tests (0 failures, 1 skip), lint and debug assembly (`BUILD SUCCESSFUL` in 6m36s). The three new JVM tests cover reload/edit/delete, duplicate and stale edits, and preserving corrupt storage. The APK signature/package and bundled renderer and saved-workflow code were checked. The standalone backend also passed all 9 storage/protocol tests; these do not exercise neural inference or hosting. Phone instrumentation and actual UI testing were unavailable.
