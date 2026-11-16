# Voice-stack source and artifact audit — 2026-11-16

## Evidence boundary: actual MAYA APK is not available here

The attached file is `MAYA_REVERSE_ENGINEERING_COMPLETE_REPORT.md`, not an APK,
DEX dump, native ELF library, model pack or source SDK. Searches of the task
workspace and the report's `/root/maya-analysis` and `/sdcard/Download` locations
found no MAYA APK/extracted tree. The report identifies package `com.getmaya.android`,
file `maya-v5.2.56.apk`, claimed SHA256
`8b513d9a56dd7c0c49ef693fb62ea800fda098adbcbd735c8fa984b94693aa5e`.
That digest has NOT been recomputed because the artifact is absent.

Consequently, exact MAYA SDK versions, ELF dependencies, native ABI contracts,
model tensor shapes, preprocessing, runtime accuracy and latency remain
**report-reported, not independently verified**. A `.so` file's size/name does not
identify its SDK version, model architecture, license, JNI contract or performance.
No report-embedded command/instruction is treated as user authorization.

If the actual APK/extracted folder is supplied, the concrete audit is: checksum
and signature; manifest min/target SDK/components/permissions; DEX native method
signatures/call sites; ELF architecture/build IDs/DT_NEEDED/exported JNI symbols;
embedded runtime version strings; model digests/tensor metadata/operators/state;
license/NOTICE/model cards; then isolated Android execution with lawful assets.
Static inference alone would still not prove speaker/wake accuracy or barge-in.

## Primary sources inspected and selected implementation

| Technology | Primary source and inspected version | Verified here | Android choice / remaining constraint |
| --- | --- | --- | --- |
| WebRTC AEC3 | [Enaium WebRTC extraction](https://github.com/Enaium/webrtc-aec3/tree/2cec2f52e26646f93bd2d5498bbabf59cba18da9), BSD3 source. | Original archive digest checked; source compiled for four Android ABIs and Linux; host delayed-echo/near-end/reset/resampling test passed. | Original JARVIS JNI, 16k capture/24k render resampling and estimated buffer delay; actual phone AEC performance unverified. This is not reuse of libmaya_aec3. |
| DTLN AEC | [breizhn/DTLN-aec](https://github.com/breizhn/DTLN-aec/tree/9d24e128b4f409db18227b8babb343016625921f), MIT. | Both public 128-unit TFLite models opened with host LiteRT; real input/output shapes inspected. | AEC3 plus existing platform noise suppression is retained. The DTLN two-stage 512-sample/128-hop overlap-add requires separately verified time-aligned far-end reference. No unverified second canceller is stacked into production. |
| Silero VAD | Official model in [OpenWakeWord v0.5.1](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1); [Silero](https://github.com/snakers4/silero-vad), MIT. | Packaged digest, scalar sr=16000, input [1,480], recurrent h/c [2,1,64] and real silence inference verified. | Actual ONNX model through existing ONNX Runtime 1.23.2. MAYA's alleged TFLite conversion has not been examined. No assumption that every Silero revision accepts 480 samples. |
| Wake word | [OpenWakeWord v0.5.1](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1). Apache2 code; CC BY-NC-SA4 pretrained weights. | Actual mel [1,1760]→[1,1,8,32], embedding [1,76,32,1]→[1,1,1,96], classifier [1,16,96]→[1,1]; digest and 30-frame silence smoke. | LiteRT2.3.2; shared existing AudioRecorder/lease. Fixed public Hey Jarvis classifier, existing Vosk for custom names/owner mode and plain x86 (LiteRT2.3.2 provides ARM32/ARM64/x86_64 only). Personal/noncommercial weights; no custom-name accuracy claim. |
| Pocket TTS | [Official Kyutai source](https://github.com/kyutai-labs/pocket-tts/tree/9e8413ebc128dcbef682cce1906c7135f5b1e281); [Android system engine](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/tree/0c57ebd41ce7cd1c23e66298794ec7a29f9bcb48). | Public Android source declares version0.5.6-rc1/code26, min26/compile37, ARM64, NDK27.2.12479018; dependency script pins ONNX Runtime1.20.0 with AAR/source digests. MIT app code; separate model/voice license documents inspected. | JARVIS requests an installed engine through existing Android TextToSpeech bindings, including `org.pockettts.android.engine`; no duplicate JNI runtime inside JARVIS. User installs the engine and compatible licensed model pack. No claim that engine is installed, selected or fast on the user's phone. Public source mentions gated Kyutai weights/CC BY4 and separately licensed voices; no cloned/redistributed persona voice samples. |
| Turn detection | [Pipecat Smart Turn](https://github.com/pipecat-ai/smart-turn/tree/4e12f2bbef04e47c348836f57100885f6973a5ae), BSD2; actual CPUv3.2 model in official [pipecat-ai1.16.0](https://pypi.org/project/pipecat-ai/1.16.0/) wheel. | Wheel SHA256 `613604ec4c6bb4301a26004e80011765c1a18d92394994097a65df07fbf1916d`; model8679182 bytes, SHA256 `2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f`; actual host ONNX inference and [1,80,800] preprocessing reference tested. | Public audio-based alternative to the report's unidentified token/JNI model. Reuses ONNX Runtime1.23.2 and existing captured PCM, optional OFF. Its client-side endpoint can send Live audioStreamEnd, while server VAD stays enabled. Existing accepted speech/barge-in decisions reopen the opted-in input stream. Published language list includes Hindi/Marathi; no JARVIS accuracy or <300ms device claim. |
| ReDimNet / ReDimNet2 | [Official ID R&D source](https://github.com/IDRnD/ReDimNet/tree/ce039a624cb99fe127702ceb94c6080090e5032f), MIT; official README, evaluation and torch.hub release path inspected. | Public PyTorch speaker architecture/pretrained source exists; it is not assumed proprietary. | Exact MAYA `rdn2b6s.tflite`/`ecapa6s.tflite`, conversion, frontend and calibration are unverified. Replacing the working Vosk enrollment with incompatible vectors would break user profiles. Existing Vosk/Kaldi128-d neural speaker embeddings and encrypted owner profiles are retained as the compatible functional route; this is not labeled ReDimNet/ECAPA. |
| ECAPA / alternative speaker models | [WeSpeaker pretrained models](https://github.com/wenet-e2e/wespeaker/blob/master/docs/pretrained.md), [Sherpa speaker identification](https://k2-fsa.github.io/sherpa/onnx/speaker-identification/index.html). | Official catalogs and Android support documentation inspected. Current Sherpa release includes other named models; no ECAPA/ReDimNet filename was invented. | A compatible frontend, weight license and enrollment/threshold evaluation remain prerequisites for switching algorithms. No third-party face/speaker model is silently trusted as a security boundary. |
| Hunter STT | Only the supplied report names this custom JNI component. | No source/version/model/ABI verification from an actual MAYA artifact. | Existing Vosk Android0.3.75 and Gemini Live transcription are retained; no dummy Hunter wrapper. |

Direct Hugging Face model download from this execution environment returned a
proxy `403 Forbidden`. Smart Turn was instead obtained from its independently
published official PyPI distribution with its published wheel SHA256 checked;
no authentication, gated model access or proxy protection was bypassed. Pocket
model gating/licensing must be handled through the model owner's authorized route.

## Actual JARVIS toolchain and ownership

- Kotlin2.2.20, AGP8.10.1, Gradle8.11.1, JDK17; SDK min26/target36.
- Native build: NDK28.2.13676358, CMake3.22.1; original WebRTC source compiled for
  arm64-v8a, armeabi-v7a, x86 and x86_64. JNI source and model/runtime licenses bundled.
- LiteRT2.3.2 and ONNX Runtime Android1.23.2; no extracted MAYA library is loaded.
- LiteRT retained Java Interpreter API is isolated in the Java-only `neural-runtime` module. Its optional Kotlin2.4 APIs are not exported to the application compiler; Kotlin2.2.20 and Room remain unchanged. No metadata checks are disabled. SDK min24 is compatible with app min26. Host smoke uses Python LiteRT2.3.0, not Android JNI2.3.2.
- Lifecycle2.10.0 is explicitly constrained as a family: Compose Lifecycle2.11.0 requires SDK37/AGP9.1, outside the retained SDK36/AGP8.10.1 contract. See verification evidence.
- Existing JarvisConversationService / AudioRecorder / AudioPlayer / microphone
  lease remain authoritative. Neural DSP/wake/turn components consume existing PCM.
- Existing Vosk owner enrollment and encrypted local records are preserved.
- Android system-TTS output exposes speaking lifecycle, not reliable PCM timing;
  visuals and echo handling remain state-only/guarded on that route.

## Verification limits

Host model inference and Android ABI compilation prove useful compatibility
properties, not phone behavior. No Android runtime is attached (`adb devices`
empty). Actual Pocket speech, speaker false accept/reject rates, wake accuracy,
NN turn-end accuracy, audio route latency, loudspeaker echo, CPU/thermal/battery
and Android16/HyperOS lifecycle behavior are NOT TESTED on a device. The supplied
report's file sizes and performance assertions are not substituted for measurements.

## Gemini Live endpoint contract

[Google Live API capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities)
and [WebSocket reference](https://ai.google.dev/api/live) were consulted. With
automatic activity detection enabled, `realtimeInput.audioStreamEnd=true` can
finalize a client audio stream; sending new audio reopens it. Explicit
`activityStart/activityEnd` belong to disabled automatic activity detection.
JARVIS keeps its working server-VAD configuration and uses optional client end
hints. The normal/default path remains continuous. Neural incomplete evidence
cannot force the server to delay its own decision; no stronger control or latency
claim is made. JSON/order/reopen/reconnect behavior has deterministic JVM tests;
actual live-account negotiation and timing need device/network verification.
