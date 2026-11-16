# Verification and delivery evidence

Baseline: `3cd65a939aa21b4b192f9091fb1652f5adb8f93e`. All changes are confined to Part 7; Parts1–6 are untouched.

The final local source tree passed the following command with JDK17 / SDK36:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :neural-runtime:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon
```

- Final local run: BUILD SUCCESSFUL,4m30s,120 actionable tasks (42 executed,78 up-to-date).
- JVM:615 tests,0 failures,0 errors,1 skipped. The skipped existing host-environment test requires a different host-dev fixture.
- Lint: no new issues; existing baseline was not expanded.1380 historical issues and2 hints remain filtered.
- Android instrumentation sources compiled; instrumentation/device tests NOT RUN.
- Native Android AEC3 four-ABI build passed in the preceding frozen source gate; native C++ sources did not change afterward.
- Native host CTest1/1 passed. This does not measure phone echo suppression.
- JavaScript:14 plasma motion checks plus WebGL shader/render/state/lifecycle/failure suite passed. Existing renderer sources remain unchanged.
- CI helper:8 tests passed. Source workflows remain unchanged.
- Local migration verification scripts passed6→7 (6 existing tables) and7→8 (9 existing tables). These are seeded schema checks, not Android SQLCipher/device upgrade tests.
- Host neural checks opened actual shipped models, verified digests/tensors and ran inference. Silence/noise smoke is not recognition/linguistic/biometric accuracy.
- Whisper frontend:all64000 coefficients matched the official Pipecat fixture within0.001; stale prediction and rolling8-second buffer tests passed.
- Protocol tests verify actual JSON, bytes, end/reopen ordering, rejected writes and reconnect reset. No live provider credentials were used.
- APK signature verification, package ID,min26/target36 and16KB ZIP alignment passed.
- All packaged ARM64 native libraries have ELF LOAD alignment≥16KB. Existing x86_64 JNA5.13.0 remains4KB-aligned, also present in the baseline; universal16KB emulator compatibility is not claimed.

Detailed machine-readable evidence: [LOCAL_RESULTS.json](verification/LOCAL_RESULTS.json).

The first full lint run failed with25 findings; static context references became weak references and actual resource/layout/KTX findings were fixed, without adding baseline suppressions. A subsequent TTS-picker KTX finding was fixed. One new protocol test initially used Android’s stub JSONObject; it was corrected to use the real Gson parser and the full suite rerun. An initial native build lacked NDK cpufeatures include/source; the CMake integration was corrected before passing all four ABI builds.

## LiteRT CI compatibility follow-up

The first main CI run [43856428097](https://github.com/levinhocall/Jarvis-os/actions/runs/43856428097) passed unit/migration/schema/test-source checks; lint failed the online dependency advisory for LiteRT1.4.0. Android LiteRT is now2.3.2, via the small Java-only `neural-runtime` module. The retained Interpreter API compiled successfully without exporting optional SDK Kotlin2.4 metadata to the application's Kotlin2.2.20/Room toolchain. No metadata checks were disabled and the lint baseline was not expanded. Four lifecycle declarations were aligned to2.10.0 already selected transitively by this SDK.

Both app and module lint tasks completed successfully. The library lint analyzer nevertheless prints incompatible optional Kotlin2.4 metadata diagnostics (its embedded analyzer expects2.1); these are an unresolved tooling limitation, not a claim of warning-free analysis. App compilation and all615 JVM tests completed. Android runtime smoke test sources compiled but were NOT EXECUTED; host Python LiteRT2.3.0 successfully opened the pinned model contracts and is not a substitute for Android2.3.2 JNI testing.

SDK2.3.2 omits plain x86; that ABI explicitly retains existing Vosk wake recognition. ARM32/ARM64/x86_64 neural wake selection remains opt-in. Unused SDK AiPack services/receiver and its dataSync permission were removed through manifest merging; their absence was checked in the compiled APK. No existing foreground service was removed. The final local APK signature, identity and16KB ZIP alignment were reverified; all ARM64 ELF libraries remain≥16KB aligned.

Local follow-up log: `work/maya-litert-boundary-verification.log`; host model check: `work/litert-upgrade/host-model-smoke.log`. These generated workspace logs are not committed; the machine-readable evidence records the checked source digest, counts and APK hash. A newly published CI APK will have separate CI signing/hash evidence.

## Device and service limitations

No device/emulator is connected. Cold-start/upgrade/data preservation on a phone, Android16/HyperOS accessibility behavior, role/keyguard launch, actual Pocket TTS installation/model speech, wake accuracy, double-talk, microphone permission revocation, Bluetooth/focus changes, browser/wallpaper lifecycle, repeated100-cycle tests,2-hour voice soak,24-hour background tests and performance/battery profiling are NOT TESTED. No60FPS,sub150ms TTS,sub300ms turn completion or latency-p95 result is asserted.

This is a development-signed debug APK, not a verified production release. CI signing may differ from an installed APK. A signature mismatch prevents an in-place update; do not erase the app/data to bypass it. Keep an export/backup of local memories and obtain the original signing key for a data-preserving signed upgrade.

The actual MAYA APK/extracted tree is missing. See [primary-source audit](VOICE_STACK_SOURCE_AUDIT.md) and [six-pillar status](MAYA_TECHNOLOGY_INTEGRATION.md) for components implemented, compatible retained implementations and outstanding work. Full MAYA parity is not claimed.

## Files created or modified

- [app/build.gradle.kts](../../app/build.gradle.kts)
- [app/proguard-rules.pro](../../app/proguard-rules.pro)
- [app/src/androidTest/java/com/jarvis/assistant/audio/NeuralAudioAssetsTest.kt](../../app/src/androidTest/java/com/jarvis/assistant/audio/NeuralAudioAssetsTest.kt)
- [app/src/main/AndroidManifest.xml](../../app/src/main/AndroidManifest.xml)
- [app/src/main/assets/licenses/onnxruntime-MIT.txt](../../app/src/main/assets/licenses/onnxruntime-MIT.txt)
- [app/src/main/assets/licenses/openwakeword-APACHE-2.0.txt](../../app/src/main/assets/licenses/openwakeword-APACHE-2.0.txt)
- [app/src/main/assets/licenses/openwakeword-NOTICE.txt](../../app/src/main/assets/licenses/openwakeword-NOTICE.txt)
- [app/src/main/assets/licenses/silero-MIT.txt](../../app/src/main/assets/licenses/silero-MIT.txt)
- [app/src/main/assets/licenses/smart-turn-BSD-2.txt](../../app/src/main/assets/licenses/smart-turn-BSD-2.txt)
- [app/src/main/assets/licenses/webrtc-aec3-BSD.txt](../../app/src/main/assets/licenses/webrtc-aec3-BSD.txt)
- [app/src/main/assets/licenses/whisper-turn-NOTICE.txt](../../app/src/main/assets/licenses/whisper-turn-NOTICE.txt)
- [app/src/main/assets/openwakeword/embedding_model.tflite](../../app/src/main/assets/openwakeword/embedding_model.tflite)
- [app/src/main/assets/openwakeword/hey_jarvis_v0.1.tflite](../../app/src/main/assets/openwakeword/hey_jarvis_v0.1.tflite)
- [app/src/main/assets/openwakeword/melspectrogram.tflite](../../app/src/main/assets/openwakeword/melspectrogram.tflite)
- [app/src/main/assets/openwakeword/silero_vad.onnx](../../app/src/main/assets/openwakeword/silero_vad.onnx)
- [app/src/main/assets/turn/smart-turn-v3.2-cpu.onnx](../../app/src/main/assets/turn/smart-turn-v3.2-cpu.onnx)
- [app/src/main/cpp/CMakeLists.txt](../../app/src/main/cpp/CMakeLists.txt)
- [app/src/main/cpp/aec_core.cpp](../../app/src/main/cpp/aec_core.cpp)
- [app/src/main/cpp/aec_core.h](../../app/src/main/cpp/aec_core.h)
- [app/src/main/cpp/aec_jni.cpp](../../app/src/main/cpp/aec_jni.cpp)
- [app/src/main/cpp/aec_test.cpp](../../app/src/main/cpp/aec_test.cpp)
- [app/src/main/cpp/third_party/PROVENANCE.md](../../app/src/main/cpp/third_party/PROVENANCE.md)
- [app/src/main/cpp/third_party/webrtc-aec3-2cec2f52.tar.gz](../../app/src/main/cpp/third_party/webrtc-aec3-2cec2f52.tar.gz)
- [app/src/main/java/com/jarvis/assistant/JarvisApp.kt](../../app/src/main/java/com/jarvis/assistant/JarvisApp.kt)
- [app/src/main/java/com/jarvis/assistant/assistant/JarvisRecognitionService.kt](../../app/src/main/java/com/jarvis/assistant/assistant/JarvisRecognitionService.kt)
- [app/src/main/java/com/jarvis/assistant/assistant/JarvisVoiceInteractionService.kt](../../app/src/main/java/com/jarvis/assistant/assistant/JarvisVoiceInteractionService.kt)
- [app/src/main/java/com/jarvis/assistant/assistant/JarvisVoiceInteractionSessionService.kt](../../app/src/main/java/com/jarvis/assistant/assistant/JarvisVoiceInteractionSessionService.kt)
- [app/src/main/java/com/jarvis/assistant/audio/AdaptiveVadEngine.kt](../../app/src/main/java/com/jarvis/assistant/audio/AdaptiveVadEngine.kt)
- [app/src/main/java/com/jarvis/assistant/audio/Aec3Native.kt](../../app/src/main/java/com/jarvis/assistant/audio/Aec3Native.kt)
- [app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt](../../app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt)
- [app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt](../../app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt)
- [app/src/main/java/com/jarvis/assistant/audio/AudioRecoveryBudget.kt](../../app/src/main/java/com/jarvis/assistant/audio/AudioRecoveryBudget.kt)
- [app/src/main/java/com/jarvis/assistant/audio/DuplexEchoSession.kt](../../app/src/main/java/com/jarvis/assistant/audio/DuplexEchoSession.kt)
- [app/src/main/java/com/jarvis/assistant/audio/FramedDuplexProcessor.kt](../../app/src/main/java/com/jarvis/assistant/audio/FramedDuplexProcessor.kt)
- [app/src/main/java/com/jarvis/assistant/audio/SystemTtsEngineSelection.kt](../../app/src/main/java/com/jarvis/assistant/audio/SystemTtsEngineSelection.kt)
- [app/src/main/java/com/jarvis/assistant/audio/VoiceSynthesisRouter.kt](../../app/src/main/java/com/jarvis/assistant/audio/VoiceSynthesisRouter.kt)
- [app/src/main/java/com/jarvis/assistant/audio/turn/NeuralTurnSession.kt](../../app/src/main/java/com/jarvis/assistant/audio/turn/NeuralTurnSession.kt)
- [app/src/main/java/com/jarvis/assistant/audio/turn/SmartTurnDetector.kt](../../app/src/main/java/com/jarvis/assistant/audio/turn/SmartTurnDetector.kt)
- [app/src/main/java/com/jarvis/assistant/audio/turn/WhisperTurnFeatures.kt](../../app/src/main/java/com/jarvis/assistant/audio/turn/WhisperTurnFeatures.kt)
- [app/src/main/java/com/jarvis/assistant/audio/wake/NeuralWakeGate.kt](../../app/src/main/java/com/jarvis/assistant/audio/wake/NeuralWakeGate.kt)
- [app/src/main/java/com/jarvis/assistant/audio/wake/OpenWakeWordPipeline.kt](../../app/src/main/java/com/jarvis/assistant/audio/wake/OpenWakeWordPipeline.kt)
- [app/src/main/java/com/jarvis/assistant/audio/wake/SileroSpeechGate.kt](../../app/src/main/java/com/jarvis/assistant/audio/wake/SileroSpeechGate.kt)
- [app/src/main/java/com/jarvis/assistant/audio/wake/WakeSettings.kt](../../app/src/main/java/com/jarvis/assistant/audio/wake/WakeSettings.kt)
- [app/src/main/java/com/jarvis/assistant/automation/accessibility/FinancialScreenPolicy.kt](../../app/src/main/java/com/jarvis/assistant/automation/accessibility/FinancialScreenPolicy.kt)
- [app/src/main/java/com/jarvis/assistant/automation/accessibility/FuzzyLabelMatcher.kt](../../app/src/main/java/com/jarvis/assistant/automation/accessibility/FuzzyLabelMatcher.kt)
- [app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt](../../app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt)
- [app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt](../../app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt)
- [app/src/main/java/com/jarvis/assistant/automation/dev/ShellPolicy.kt](../../app/src/main/java/com/jarvis/assistant/automation/dev/ShellPolicy.kt)
- [app/src/main/java/com/jarvis/assistant/automation/dev/TermuxBridge.kt](../../app/src/main/java/com/jarvis/assistant/automation/dev/TermuxBridge.kt)
- [app/src/main/java/com/jarvis/assistant/automation/macro/MacroCompiler.kt](../../app/src/main/java/com/jarvis/assistant/automation/macro/MacroCompiler.kt)
- [app/src/main/java/com/jarvis/assistant/automation/macro/ScreenTextWaiter.kt](../../app/src/main/java/com/jarvis/assistant/automation/macro/ScreenTextWaiter.kt)
- [app/src/main/java/com/jarvis/assistant/automation/mission/MissionEngine.kt](../../app/src/main/java/com/jarvis/assistant/automation/mission/MissionEngine.kt)
- [app/src/main/java/com/jarvis/assistant/automation/mission/ToolRegistry.kt](../../app/src/main/java/com/jarvis/assistant/automation/mission/ToolRegistry.kt)
- [app/src/main/java/com/jarvis/assistant/automation/routing/ConfiguredPlannerProvider.kt](../../app/src/main/java/com/jarvis/assistant/automation/routing/ConfiguredPlannerProvider.kt)
- [app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt](../../app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt)
- [app/src/main/java/com/jarvis/assistant/automation/routing/OpenAiToolCodec.kt](../../app/src/main/java/com/jarvis/assistant/automation/routing/OpenAiToolCodec.kt)
- [app/src/main/java/com/jarvis/assistant/automation/routing/PlannerEndpoint.kt](../../app/src/main/java/com/jarvis/assistant/automation/routing/PlannerEndpoint.kt)
- [app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt](../../app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt)
- [app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt](../../app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt)
- [app/src/main/java/com/jarvis/assistant/network/ClientAudioStream.kt](../../app/src/main/java/com/jarvis/assistant/network/ClientAudioStream.kt)
- [app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt](../../app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt)
- [app/src/main/java/com/jarvis/assistant/ui/aura/TapIndicator.kt](../../app/src/main/java/com/jarvis/assistant/ui/aura/TapIndicator.kt)
- [app/src/main/java/com/jarvis/assistant/ui/browser/JarvisBrowserActivity.kt](../../app/src/main/java/com/jarvis/assistant/ui/browser/JarvisBrowserActivity.kt)
- [app/src/main/java/com/jarvis/assistant/ui/dev/CodingDevFragment.kt](../../app/src/main/java/com/jarvis/assistant/ui/dev/CodingDevFragment.kt)
- [app/src/main/java/com/jarvis/assistant/ui/directives/MissionDeskActivity.kt](../../app/src/main/java/com/jarvis/assistant/ui/directives/MissionDeskActivity.kt)
- [app/src/main/java/com/jarvis/assistant/ui/directives/WakeWordActivity.kt](../../app/src/main/java/com/jarvis/assistant/ui/directives/WakeWordActivity.kt)
- [app/src/main/java/com/jarvis/assistant/ui/home/FeatureHubActivity.kt](../../app/src/main/java/com/jarvis/assistant/ui/home/FeatureHubActivity.kt)
- [app/src/main/java/com/jarvis/assistant/ui/permission/PermissionActivity.kt](../../app/src/main/java/com/jarvis/assistant/ui/permission/PermissionActivity.kt)
- [app/src/main/java/com/jarvis/assistant/ui/settings/ApiConnectionActivity.kt](../../app/src/main/java/com/jarvis/assistant/ui/settings/ApiConnectionActivity.kt)
- [app/src/main/java/com/jarvis/assistant/vision/ScreenCaptureManager.kt](../../app/src/main/java/com/jarvis/assistant/vision/ScreenCaptureManager.kt)
- [app/src/main/java/com/jarvis/assistant/wallpaper/JarvisWallpaperService.kt](../../app/src/main/java/com/jarvis/assistant/wallpaper/JarvisWallpaperService.kt)
- [app/src/main/res/layout/activity_jarvis_browser.xml](../../app/src/main/res/layout/activity_jarvis_browser.xml)
- [app/src/main/res/values/ambient_integrations.xml](../../app/src/main/res/values/ambient_integrations.xml)
- [app/src/main/res/values/assistant_integration.xml](../../app/src/main/res/values/assistant_integration.xml)
- [app/src/main/res/xml/jarvis_voice_interaction.xml](../../app/src/main/res/xml/jarvis_voice_interaction.xml)
- [app/src/main/res/xml/plasma_wallpaper.xml](../../app/src/main/res/xml/plasma_wallpaper.xml)
- [app/src/test/java/com/jarvis/assistant/audio/FramedDuplexProcessorTest.kt](../../app/src/test/java/com/jarvis/assistant/audio/FramedDuplexProcessorTest.kt)
- [app/src/test/java/com/jarvis/assistant/audio/NeuralWakeGateTest.kt](../../app/src/test/java/com/jarvis/assistant/audio/NeuralWakeGateTest.kt)
- [app/src/test/java/com/jarvis/assistant/audio/turn/NeuralTurnSessionTest.kt](../../app/src/test/java/com/jarvis/assistant/audio/turn/NeuralTurnSessionTest.kt)
- [app/src/test/java/com/jarvis/assistant/audio/turn/WhisperTurnFeaturesTest.kt](../../app/src/test/java/com/jarvis/assistant/audio/turn/WhisperTurnFeaturesTest.kt)
- [app/src/test/java/com/jarvis/assistant/automation/accessibility/FinancialScreenPolicyTest.kt](../../app/src/test/java/com/jarvis/assistant/automation/accessibility/FinancialScreenPolicyTest.kt)
- [app/src/test/java/com/jarvis/assistant/automation/accessibility/FuzzyLabelMatcherTest.kt](../../app/src/test/java/com/jarvis/assistant/automation/accessibility/FuzzyLabelMatcherTest.kt)
- [app/src/test/java/com/jarvis/assistant/automation/dev/ShellPolicyTest.kt](../../app/src/test/java/com/jarvis/assistant/automation/dev/ShellPolicyTest.kt)
- [app/src/test/java/com/jarvis/assistant/automation/macro/MacroCompilerTest.kt](../../app/src/test/java/com/jarvis/assistant/automation/macro/MacroCompilerTest.kt)
- [app/src/test/java/com/jarvis/assistant/automation/routing/OpenAiToolCodecTest.kt](../../app/src/test/java/com/jarvis/assistant/automation/routing/OpenAiToolCodecTest.kt)
- [app/src/test/java/com/jarvis/assistant/automation/routing/PlannerEndpointTest.kt](../../app/src/test/java/com/jarvis/assistant/automation/routing/PlannerEndpointTest.kt)
- [app/src/test/java/com/jarvis/assistant/network/ClientAudioStreamTest.kt](../../app/src/test/java/com/jarvis/assistant/network/ClientAudioStreamTest.kt)
- [app/src/test/resources/audio/smart-turn-pipecat-reference.f32.gz](../../app/src/test/resources/audio/smart-turn-pipecat-reference.f32.gz)
- [docs/parity/MAYA_TECHNOLOGY_INTEGRATION.md](../../docs/parity/MAYA_TECHNOLOGY_INTEGRATION.md)
- [docs/parity/VERIFICATION.md](../../docs/parity/VERIFICATION.md)
- [docs/parity/VOICE_STACK_SOURCE_AUDIT.md](../../docs/parity/VOICE_STACK_SOURCE_AUDIT.md)
- [docs/parity/verification/LOCAL_RESULTS.json](../../docs/parity/verification/LOCAL_RESULTS.json)
- [scripts/neural-model-requirements.txt](../../scripts/neural-model-requirements.txt)
- [scripts/verify-neural-models.py](../../scripts/verify-neural-models.py)

- [settings.gradle.kts](../../settings.gradle.kts)
- [neural-runtime/build.gradle.kts](../../neural-runtime/build.gradle.kts)
- [neural-runtime/src/main/AndroidManifest.xml](../../neural-runtime/src/main/AndroidManifest.xml)
- [neural-runtime/src/main/java/com/jarvis/assistant/neural/WakeInterpreter.java](../../neural-runtime/src/main/java/com/jarvis/assistant/neural/WakeInterpreter.java)
