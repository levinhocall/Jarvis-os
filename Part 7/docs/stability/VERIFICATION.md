# Executed verification — 2026-11-12

Branch: `phaseA/stability-accessibility`. Base: `3795d9a08583ecd1d684155dc133d73875f8460f`. Verified source commit: `216a60415c03eaca2cdc16470e5661353c2173d8`. Part 7 only; no main merge, remote push or GitHub Actions run for these fixes. Available host checks passed; this is **not a device-verified production release**.

## Final command / result

From `Part 7/`, with JDK 17.0.21+9, SDK 36/build-tools 35.0.0, Gradle 8.11.1 and the existing cached dependencies:

```sh
export JAVA_HOME=/workspace/.jarvis-tools/jdk-17.0.21+9
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/workspace/.jarvis-tools/android-sdk
export GRADLE_USER_HOME=/workspace/.jarvis-tools/gradle
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --offline
```

Observed: **BUILD SUCCESSFUL in 7m 21s**; 71 actionable tasks, 31 executed / 40 up-to-date. Final log is `work/stability-verified-checks.log` in the execution workspace (not committed).

| Check actually executed | Result |
| --- | --- |
| `:app:testDebugUnitTest` | 103 suites, **561 cases: 560 passed, 1 skipped, 0 failures, 0 errors**. Per-suite XML counts and hashes: evidence/JVM_RESULTS.json. |
| `:app:lintDebug` | Passed; no unfiltered errors/warnings. Existing baseline filtered 1,396 findings and 2 hints; 25 entries were unmatched. Baseline byte-identical to base; existing debt was not silently fixed/suppressed. |
| `:app:compileDebugAndroidTestKotlin` | Passed. This is compilation, **not execution** of Android tests. |
| `:app:assembleDebug` | Passed; APK generated. |
| `apksigner verify --verbose --print-certs` | Passed; one debug signer, APK signature v2 verified. |
| `aapt dump badging` | Package `com.jarvis.assistant`, versionCode 149 / versionName 1.11.0, minSdk 26, targetSdk 36. This does not establish API 26–36 runtime behavior. |
| `git diff --check` | Passed after source whitespace cleanup. |
| Protected-file comparison | 671 protected files byte-compared to base, 0 changed: Parts 1–6, committed schemas, Manifest, selected build files and MemoryDatabase. No modified/untracked deliverable outside Part 7; lint baseline unchanged. |
| `adb devices -l` | Executed, no devices listed. No emulator binary or `/dev/kvm`; runtime work blocked. |

Skipped test: `DevEnvironmentAndTermuxBridgeTest.detectEnvironment correctly identifies host dev environment` (existing conditional environment case). It is not reported as passed. Existing Kotlin deprecation/processor warnings remain; clean compilation does not mean warning-free source.

## Regression scope

Executed deterministic cases include: enabled-but-unbound and real connection tokens; all idle/null/empty/stale/error paths; stale lifecycle/callback ownership; readable snapshot versus accepted/verified result; queue overload and deadline including wait; cancellation/generation replacement; one gesture admission, timeout/cancellation/late callback/reset; active/focused window filtering; bounded width/depth/time with cancellation cleanup and native-style pooled-identity reuse; sensitive-label-free typed diagnostics; known app/draft/call confirmation policy; PCM memory/frame bounds and same-epoch flush invalidation; recovery budgets; microphone lease exclusivity, bounded handoff timeout and cancellation. These prove the exercised pure policies, not Android Binder/AudioRecord/gesture implementation behavior.

New `AccessibilitySnapshotInstrumentedTest` uses native detached SDK node fixtures for missing/unlabelled/password data. It and all existing instrumentation suites are **NOT RUN**. Detached fixtures, when eventually executed, still will not prove a system-bound service, protected-window behavior or real gesture execution.

## Earlier checks, including failures

- Initial baseline check: `:app:testDebugUnitTest :app:lintDebug --no-daemon --offline --rerun-tasks`, successful 6m 20s, 521 cases / 0 failures / 1 skipped. Tracked production source was still the audited base; unreferenced helper preparation occurred while this run finished. This is not an Android performance baseline.
- First integration unit command failed compilation on a callback receiver comparison; fixed outer-service qualification. No unit pass claimed for that attempt.
- Second integration unit command failed one planner fixture because it depended on another test fabricating global accessibility readiness. The fixture now tests replan history with intent-only tools; production validation remains strict. A subsequent complete unit run passed 555 cases / 0 failures / 1 skipped.
- First combined final command executed 560 cases / 0 failures / 1 skipped, then **failed lint** on 4 SuspiciousIndentation and 1 SetTextI18n error. APK/Android-test compilation targets were not reached in that attempt. Fixed source indentation and a string resource, retained baseline. One wrapper attempt was cancelled after a wrong working-directory edit command, before verification; it is not a successful check.
- Final command above reran after all production/test fixes and passed. No failing checks were bypassed.

## Artifact

Local debug APK: `Part 7/app/build/outputs/apk/debug/app-debug.apk`.

- Size: 177,153,465 bytes.
- SHA-256: `ccd454d266bda65338bf1f2e05a5b2efab2aa242228b2a261cfd904e1ab67cc8`.
- Debug signing certificate SHA-256: `3c14012da6e74f0ecf536c4c4bbd8047b803732b6c598cfc4a9bfd905053d3c6`.

This local debug certificate differs from the previously inspected CI APK certificate (`e65ca5d196f9650d74be6c806726ffe97b1a04d1523684728d25a1cee765ed38`). Installation over an app signed with that certificate will fail. This task has not installed, upgraded, published or re-signed the APK with the CI/release key. Preserve local memories: do not uninstall/clear app data to bypass an update signature conflict. The existing release signing configuration was not changed; signed release/R8 verification was not run.

## NOT RUN / remaining blockers

Physical Android/HyperOS 3 validation; API 26–36 runtime matrix; instrumented service/gesture/permission tests; native Room/SQLCipher/key/migration/corruption suites; fresh install and existing-version data-preserving upgrade; 100 cold starts; 100 voice cycles; two-hour Gemini Live/Vosk soak; 24-hour background soak; network switching; CPU/RAM/battery/FPS/ANR profiling. No connected device/emulator was available. Configured provider credentials/actual voice hardware are required for live tests. See DEVICE_VERIFICATION.md for procedures and acceptance evidence.

No Android before/after performance numbers were measured. Source caps and host regressions are not evidence of a particular FPS, voice latency, battery drain, OEM uptime or elimination of the user's crash. Synchronous native initialization and Binder calls, startup preferences/Keystore I/O, remaining legacy borrowed node ownership and existing lint/service-size debt remain risks. The user's exact crash/OEM disconnection root cause remains unconfirmed without runtime traces.

## Exact changed/new files

All paths below are relative to the repository. Reports distinguish baseline source evidence from current code. Generated build outputs/logs/SDK caches are ignored, not source changes.

- `Part 7/app/src/androidTest/java/com/jarvis/assistant/automation/accessibility/AccessibilitySnapshotInstrumentedTest.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/audio/AudioRecoveryBudget.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/audio/MicrophoneOwnership.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/audio/PcmPlaybackQueue.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityCallbackFailure.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityGestureGate.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityHealthModel.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityLegacyTargetPolicy.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilitySessionHealth.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityTraversalBudget.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityWindowPolicy.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/BoundedTreeTraversal.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/agent/ActionVerifier.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/agent/ScreenSignatureCalculator.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/agent/SerializedActionExecutor.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/grounding/ScreenGroundingEngine.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/grounding/ScreenGroundingModels.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/integration/UniversalActionRegistry.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/mission/ToolRegistry.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/permission/PermissionActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/HandsFreeActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/util/WhatsAppMessenger.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/vision/UiGroundingEngine.kt`
- `Part 7/app/src/main/res/values/strings_accessibility_health.xml`
- `Part 7/app/src/test/java/com/jarvis/assistant/audio/AudioRecoveryBudgetTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/audio/MicrophoneOwnershipTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/audio/PcmPlaybackQueueTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/accessibility/AccessibilityCallbackFailureTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/accessibility/AccessibilityGestureGateTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/accessibility/AccessibilityHealthModelTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/accessibility/AccessibilityLegacyTargetPolicyTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/accessibility/AccessibilitySessionHealthTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/accessibility/AccessibilityTraversalBudgetTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/accessibility/AccessibilityWindowPolicyTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/accessibility/BoundedTreeTraversalTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/agent/ActionObservationStabilityTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/agent/LlmPlannerTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/agent/SerializedActionExecutorTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/grounding/ScreenIntelligenceTest.kt`
- `Part 7/docs/adr/005-stability-accessibility-recovery.md`
- `Part 7/docs/stability/DEVICE_VERIFICATION.md`
- `Part 7/docs/stability/SOURCE_INVENTORY.json`
- `Part 7/docs/stability/STABILITY_AUDIT.md`
- `Part 7/docs/stability/VERIFICATION.md`
- `Part 7/docs/stability/evidence/JVM_RESULTS.json`
- `Part 7/docs/stability/evidence/BUILD_RESULT.json`
