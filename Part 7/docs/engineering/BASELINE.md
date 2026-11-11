# Part 7 engineering baseline — A0

Snapshot: `2084fe301cf60bcd2bd745048b2c9ef5a75b38e2`, 2026-11-11 UTC. This task changes only this documentation file. Counts below are from actual current source and completed commands, not the older engineering/audit reports. No APK or device execution.

## Current facts versus prompt assumptions

| Fact | Observed evidence |
| --- | --- |
| Kotlin production source | 288 `.kt` files; 55,933 lines under `app/src/main/java` (Python Path.rglob + splitlines). |
| App identity / SDK | `app/build.gradle.kts:27` namespace com.jarvis.assistant; min SDK 26, compile/target 36; version 149 / 1.11.0. See defaultConfig. |
| Gradle/toolchain | `build.gradle.kts:2` AGP **8.10.1**, Kotlin **2.2.20**, KAPT; wrapper 8.11.1 (`gradle/wrapper/gradle-wrapper.properties`). JVM 17 (`app/build.gradle.kts:75`). The pack's AGP 8.9.1 is stale. |
| UI | Views/XML/ViewBinding remain the primary screens. An opt-in Compose orb already exists (`app/build.gradle.kts:79`, `ui/orb/VoiceOrb.kt:1`); the pack's blanket “NO Compose present” assumption is stale. This audit does not migrate or remove it. |
| Room / SQLCipher | `data/memory/MemoryDatabase.kt:33` version **8**, exportSchema=true; migrations 1→8. `data/memory/DatabaseReadiness.kt:27` exposes nonblocking readiness. No production runBlocking found. |
| Chat | `data/chat/ConversationRepository.kt:111` imports verified legacy JSON; `data/repository/ChatRepository.kt:5` is read/clear-only. `data/memory/MemoryManager.kt:29` stores in-process context; facts go through MemoryRepository. No legacy writer API remains. |
| Checkpoints | `JarvisApp.kt:126` constructs LongHorizonMissionCoordinator with PersistentMissionCheckpointStorage, not the in-memory default assumed by B3. Persistence is encrypted prefs, not Room (`PersistentMissionCheckpointStorage.kt:12`). |
| Audit schema | `security/sandbox/AuditEntities.kt:38` exportSchema=true; v1 schema committed. Existing memory exports v6–v8 plus historical-source fixture endpoints v1/v5; v2–v4 original snapshots absent (`app/schemas/README.md`). |

## Executed baseline checks

| Exact command (from Part 7 unless noted) | Observed result |
| --- | --- |
| ./gradlew :app:testDebugUnitTest :app:lintDebug --no-daemon --offline --rerun-tasks | BUILD SUCCESSFUL in **6m11s**, 40/40 tasks executed; local log `work/v2-a0-baseline.log` (ignored). JDK/SDK/Gradle cache environment under `/workspace/.jarvis-tools`. |
| JUnit XML aggregation: app/build/test-results/testDebugUnitTest/TEST-*.xml | **514 tests; 0 failures; 0 errors; 1 skipped**. No failing-test first line exists. |
| Skipped test | `DevEnvironmentAndTermuxBridgeTest.detectEnvironment correctly identifies host dev environment` (JUnit `<skipped>`). It was not counted as passed. |
| Lint XML aggregation: app/build/reports/lint-results-debug.xml | **0 errors; 1,398 warnings; 2 hints**. Category counts below; reports generated, not suppressed. |
| ./gradlew :app:dependencies --configuration debugRuntimeClasspath --no-daemon --offline | BUILD SUCCESSFUL in **10s**. Complete resolved runtime graph appended below; declared test/build dependencies listed separately. |
| /tmp/jarvis-v2-tools/actionlint -version; actionlint -color (repository root) | **v1.7.12**, exit 0, no diagnostics across current workflows. Official release archive SHA-256 verified: `8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8`. |
| gh auth status | CLI token rejected as invalid; no token printed. Connected GitHub read tools were used previously for CI evidence; no workflow dispatch claimed here. |
| Device / runtime | No new device tests. Current environment has no attached adb device or /dev/kvm (previous Phase A verification documents exact checks). Native migration tests are compiled in previous source verification, not executed by A0. |

### Lint categories

| Issue ID | Count |
| --- | --- |
| HardcodedText | 424 |
| SetTextI18n | 386 |
| UseKtx | 289 |
| UnusedResources | 95 |
| ContentDescription | 29 |
| ObsoleteSdkInt | 20 |
| SmallSp | 20 |
| Overdraw | 17 |
| ApplySharedPref | 14 |
| DiscouragedApi | 11 |
| RtlHardcoded | 11 |
| LockedOrientationActivity | 9 |
| ClickableViewAccessibility | 7 |
| DisableBaselineAlignment | 7 |
| UseCompoundDrawables | 6 |
| GradleDependency | 5 |
| IconLauncherShape | 5 |
| SdCardPath | 5 |
| UselessParent | 5 |
| VectorPath | 4 |
| Aligned16KB | 3 |
| Autofill | 3 |
| InlinedApi | 3 |
| SetJavaScriptEnabled | 3 |
| DrawAllocation | 2 |
| ImplicitSamInstance | 2 |
| MonochromeLauncherIcon | 2 |
| TooManyViews | 2 |
| UnnecessaryArrayInit | 2 |
| BatteryLife | 1 |
| DataExtractionRules | 1 |
| IconLocation | 1 |
| KaptUsageInsteadOfKsp | 1 |
| NotifyDataSetChanged | 1 |
| StaticFieldLeak | 1 |
| TextFields | 1 |
| UnusedAttribute | 1 |
| ViewConstructor | 1 |

## Static metrics
Method: UTF-8 `.kt` text under `app/src/main/java` only, Python `re.finditer`. These are **textual occurrences**, not a Kotlin AST or runtime leak proof. Patterns are word-boundary TODO/FIXME/HACK, runBlocking, GlobalScope; `Thread\s*\.\s*sleep\s*\(`; `!!`; `Handler\s*\(\s*Looper.getMainLooper\s*\(\s*\)\s*\)`; literal `catch (...) { whitespace only }`. Comment-only catch bodies are not included in the literal-empty count.

| Pattern | Occurrences |
| --- | --- |
| TODO/FIXME/HACK | 3 |
| runBlocking | 0 |
| GlobalScope | 0 |
| Thread.sleep | 2 |
| !! | 36 |
| Main Handler | 10 |
| literal empty catch | 66 |

`runBlocking` and `GlobalScope`: no file:line hits in this scope. The three TODO-like hits are two application task-marker strings and one actual integration comment; they do not mean three unfinished implementations.

### TODO/FIXME/HACK locations

| File:line | Text |
| --- | --- |
| app/src/main/java/com/jarvis/assistant/automation/intelligence/UniversalInformationEngine.kt:16 | `private val TASK_REGEX = Regex("""(?m)^(?:\s*[-*]\s*\[ \]\s*&#124;\s*(?:TODO&#124;TASK&#124;ACTION):\s*)(.+)$""", RegexOption.IGNORE_CASE)` |
| app/src/main/java/com/jarvis/assistant/ui/extras/BrainDumpActivity.kt:121 | `dao.addIdea(IdeaEntity(content = "[TODO] $title"))` |
| app/src/main/java/com/jarvis/assistant/ui/orb/AudioLevelTracker.kt:79 | `// TODO: For another TTS engine, feed its measured PCM RMS/bands here, never a fabricated waveform.` |

### Thread.sleep locations

| File:line | Text |
| --- | --- |
| app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt:95 | `Thread.sleep(150)` |
| app/src/main/java/com/jarvis/assistant/audio/JarvisSoundEffects.kt:62 | `Thread.sleep(durationMs + 60)` |

### Main Handler locations

| File:line | Text |
| --- | --- |
| app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt:241 | `private val handler = Handler(Looper.getMainLooper())` |
| app/src/main/java/com/jarvis/assistant/automation/phone/PhoneControlManager.kt:195 | `}, Handler(Looper.getMainLooper()))` |
| app/src/main/java/com/jarvis/assistant/ui/aura/TapIndicator.kt:12 | `Handler(Looper.getMainLooper()).post {` |
| app/src/main/java/com/jarvis/assistant/ui/aura/TapIndicator.kt:23 | `runCatching { manager.addView(view,params); Handler(Looper.getMainLooper()).postDelayed({ runCatching { manager.removeView(view) } },450) }` |
| app/src/main/java/com/jarvis/assistant/ui/directives/WakeGlow.kt:13 | `fun show(context: Context) = Handler(Looper.getMainLooper()).post {` |
| app/src/main/java/com/jarvis/assistant/ui/directives/WakeGlow.kt:27 | `val handler = Handler(Looper.getMainLooper())` |
| app/src/main/java/com/jarvis/assistant/ui/extras/AirGesturesActivity.kt:28 | `private val handler = Handler(Looper.getMainLooper())` |
| app/src/main/java/com/jarvis/assistant/ui/orb/AudioLevelTracker.kt:33 | `private val mainHandler=Handler(Looper.getMainLooper())` |
| app/src/main/java/com/jarvis/assistant/ui/orb/VoiceOrb.kt:47 | `val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {` |
| app/src/main/java/com/jarvis/assistant/util/ActivityLauncherHelper.kt:112 | `Handler(Looper.getMainLooper()).postDelayed({` |

Thread.sleep context: AudioRecorder backoff is guarded off Main (`audio/AudioRecorder.kt:71`); JarvisSoundEffects runs its static playback in a dedicated Thread (`audio/JarvisSoundEffects.kt:29`). Neither is evidence of a current Main-thread sleep.

### Per-file hotspots

| File | Empty catches | !! | Main Handler | Thread.sleep |
| --- | --- | --- | --- | --- |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt | 10 | 3 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/automation/dev/AutonomousDevLoop.kt | 0 | 10 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/automation/mission/MissionEngine.kt | 0 | 9 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt | 9 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt | 7 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt | 7 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt | 4 | 0 | 1 | 0 |
| app/src/main/java/com/jarvis/assistant/automation/phone/PhoneControlManager.kt | 4 | 0 | 1 | 0 |
| app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt | 3 | 0 | 0 | 1 |
| app/src/main/java/com/jarvis/assistant/audio/HeadsetAudioRouter.kt | 4 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/wallpaper/VideoWallpaperService.kt | 4 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/audio/JarvisSoundEffects.kt | 1 | 0 | 0 | 1 |
| app/src/main/java/com/jarvis/assistant/audio/VoiceSynthesisRouter.kt | 2 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/data/files/FileIntelligenceEngine.kt | 2 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/aura/TapIndicator.kt | 0 | 0 | 2 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/chat/ChatFragment.kt | 0 | 2 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/directives/WakeGlow.kt | 0 | 0 | 2 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt | 1 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/settings/ThemeSelectionBottomSheet.kt | 1 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/util/ActivityLauncherHelper.kt | 1 | 0 | 1 | 0 |
| app/src/main/java/com/jarvis/assistant/util/ThemeManager.kt | 2 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/audio/OwnerVoiceManager.kt | 1 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/automation/dev/SoftwareEngineeringAgent.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/automation/proactive/TriggerRepository.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/core/runtime/JarvisUnifiedRuntime.kt | 1 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/data/memory/PersonalMemoryGraph.kt | 1 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/network/GeminiModelCatalog.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/chat/ChatHistoryBottomSheet.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/dev/CodingDevFragment.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/extras/AirGesturesActivity.kt | 0 | 0 | 1 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/home/MainActivity.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/home/UpdatesFragment.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/orb/AudioLevelTracker.kt | 0 | 0 | 1 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/orb/VoiceOrb.kt | 0 | 0 | 1 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/settings/SettingsFragment.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/ui/whiteboard/WhiteboardActivity.kt | 1 | 0 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/vision/ScreenCaptureManager.kt | 0 | 1 | 0 | 0 |
| app/src/main/java/com/jarvis/assistant/vision/VisualTaskOrchestrator.kt | 0 | 1 | 0 | 0 |

### Literal empty catch locations

| File:line | Typed clause |
| --- | --- |
| app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt:92 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt:187 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt:188 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt:189 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt:231 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt:241 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt:258 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt:153 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt:213 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt:274 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/HeadsetAudioRouter.kt:169 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/HeadsetAudioRouter.kt:188 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/HeadsetAudioRouter.kt:193 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/HeadsetAudioRouter.kt:207 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/JarvisSoundEffects.kt:66 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/OwnerVoiceManager.kt:149 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/VoiceSynthesisRouter.kt:284 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/audio/VoiceSynthesisRouter.kt:292 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt:167 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt:169 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt:201 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt:221 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/automation/phone/PhoneControlManager.kt:219 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/automation/phone/PhoneControlManager.kt:271 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/automation/phone/PhoneControlManager.kt:309 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/automation/phone/PhoneControlManager.kt:816 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:537 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:538 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:598 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:2090 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:3657 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:3927 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:3984 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:4055 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:4059 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:4115 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/core/runtime/JarvisUnifiedRuntime.kt:287 | `catch (_: Throwable)` |
| app/src/main/java/com/jarvis/assistant/data/files/FileIntelligenceEngine.kt:114 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/data/files/FileIntelligenceEngine.kt:242 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/data/memory/PersonalMemoryGraph.kt:187 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:266 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:277 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:314 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:323 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:349 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:423 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:434 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:449 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt:508 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt:882 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/settings/ThemeSelectionBottomSheet.kt:39 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/wallpaper/VideoWallpaperService.kt:86 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/wallpaper/VideoWallpaperService.kt:87 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/wallpaper/VideoWallpaperService.kt:113 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/wallpaper/VideoWallpaperService.kt:114 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/ui/whiteboard/WhiteboardActivity.kt:118 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/ActivityLauncherHelper.kt:113 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt:139 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt:166 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt:182 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt:198 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt:216 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt:377 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt:389 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/ThemeManager.kt:88 | `catch (_: Exception)` |
| app/src/main/java/com/jarvis/assistant/util/ThemeManager.kt:92 | `catch (_: Exception)` |

### 25 largest Kotlin source files

| File | Lines |
| --- | --- |
| app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt | 4181 |
| app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt | 2381 |
| app/src/main/java/com/jarvis/assistant/automation/mission/MissionEngine.kt | 2163 |
| app/src/main/java/com/jarvis/assistant/vision/VisualTaskOrchestrator.kt | 1548 |
| app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt | 1173 |
| app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt | 895 |
| app/src/main/java/com/jarvis/assistant/automation/phone/PhoneControlManager.kt | 847 |
| app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt | 831 |
| app/src/main/java/com/jarvis/assistant/automation/agent/LlmPlanner.kt | 728 |
| app/src/main/java/com/jarvis/assistant/ui/dev/CodingDevFragment.kt | 721 |
| app/src/main/java/com/jarvis/assistant/automation/command/CommandParser.kt | 681 |
| app/src/main/java/com/jarvis/assistant/vision/UiGroundingEngine.kt | 637 |
| app/src/main/java/com/jarvis/assistant/automation/agent/AgentTaskEngine.kt | 590 |
| app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt | 568 |
| app/src/main/java/com/jarvis/assistant/ui/home/MainActivity.kt | 567 |
| app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt | 557 |
| app/src/main/java/com/jarvis/assistant/util/PromptGenerator.kt | 520 |
| app/src/main/java/com/jarvis/assistant/automation/council/MultiAgentCouncil.kt | 504 |
| app/src/main/java/com/jarvis/assistant/automation/grounding/ScreenGroundingEngine.kt | 486 |
| app/src/main/java/com/jarvis/assistant/ui/avatar/RealisticAvatarView.kt | 450 |
| app/src/main/java/com/jarvis/assistant/util/ContactCaller.kt | 446 |
| app/src/main/java/com/jarvis/assistant/ui/map/AmoledMapActivity.kt | 444 |
| app/src/main/java/com/jarvis/assistant/security/sandbox/CapabilitySandbox.kt | 441 |
| app/src/main/java/com/jarvis/assistant/ui/vision/NeuralLensActivity.kt | 434 |
| app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityHealthModel.kt | 429 |

## Declared dependency inventory
Versions are the **declared** pins, including BOM-controlled modules. The resolved debug runtime graph at the end records conflict resolution/transitive upgrades. No dependencies were upgraded by A0.

| Scope | Module / declared version | Evidence |
| --- | --- | --- |
| implementation | androidx.compose:compose-bom:2025.04.01 | app/build.gradle.kts:121 |
| implementation | androidx.compose.ui:ui | app/build.gradle.kts:122 |
| implementation | androidx.compose.material3:material3 | app/build.gradle.kts:123 |
| implementation | androidx.compose.ui:ui-tooling-preview | app/build.gradle.kts:124 |
| debugImplementation | androidx.compose.ui:ui-tooling | app/build.gradle.kts:125 |
| implementation | androidx.lifecycle:lifecycle-runtime-compose:2.8.7 | app/build.gradle.kts:126 |
| implementation | androidx.core:core-ktx:1.13.1 | app/build.gradle.kts:127 |
| implementation | androidx.appcompat:appcompat:1.7.0 | app/build.gradle.kts:128 |
| implementation | com.google.android.material:material:1.12.0 | app/build.gradle.kts:129 |
| implementation | androidx.constraintlayout:constraintlayout:2.1.4 | app/build.gradle.kts:130 |
| implementation | androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6 | app/build.gradle.kts:131 |
| implementation | androidx.lifecycle:lifecycle-livedata-ktx:2.8.6 | app/build.gradle.kts:132 |
| implementation | androidx.lifecycle:lifecycle-runtime-ktx:2.8.6 | app/build.gradle.kts:133 |
| implementation | androidx.activity:activity-ktx:1.9.3 | app/build.gradle.kts:134 |
| implementation | androidx.recyclerview:recyclerview:1.3.2 | app/build.gradle.kts:135 |
| implementation | org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1 | app/build.gradle.kts:136 |
| implementation | com.squareup.okhttp3:okhttp:4.12.0 | app/build.gradle.kts:137 |
| implementation | com.google.code.gson:gson:2.10.1 | app/build.gradle.kts:138 |
| implementation | androidx.webkit:webkit:1.12.0 | app/build.gradle.kts:139 |
| implementation | com.google.mlkit:object-detection:17.0.2 | app/build.gradle.kts:141 |
| implementation | androidx.camera:camera-core:1.6.2 | app/build.gradle.kts:143 |
| implementation | androidx.camera:camera-camera2:1.6.2 | app/build.gradle.kts:144 |
| implementation | androidx.camera:camera-lifecycle:1.6.2 | app/build.gradle.kts:145 |
| implementation | androidx.camera:camera-view:1.6.2 | app/build.gradle.kts:146 |
| implementation | org.osmdroid:osmdroid-android:6.1.20 | app/build.gradle.kts:149 |
| implementation | net.java.dev.jna:jna:5.13.0@aar | app/build.gradle.kts:150 |
| implementation | com.alphacephei:vosk-android:0.3.75@aar | app/build.gradle.kts:151 |
| implementation | androidx.room:room-runtime:2.8.4 | app/build.gradle.kts:154 |
| implementation | androidx.room:room-ktx:2.8.4 | app/build.gradle.kts:155 |
| implementation | androidx.sqlite:sqlite:2.7.0 | app/build.gradle.kts:156 |
| implementation | net.zetetic:sqlcipher-android:4.20.0@aar | app/build.gradle.kts:157 |
| kapt | androidx.room:room-compiler:2.8.4 | app/build.gradle.kts:158 |
| implementation | androidx.work:work-runtime-ktx:2.9.1 | app/build.gradle.kts:161 |
| implementation | androidx.security:security-crypto:1.1.0-alpha06 | app/build.gradle.kts:164 |
| testImplementation | junit:junit:4.13.2 | app/build.gradle.kts:166 |
| androidTestImplementation | androidx.test:runner:1.6.2 | app/build.gradle.kts:167 |
| androidTestImplementation | androidx.test.ext:junit:1.2.1 | app/build.gradle.kts:168 |
| androidTestImplementation | androidx.sqlite:sqlite-framework:2.7.0 | app/build.gradle.kts:169 |
| androidTestImplementation | androidx.room:room-testing:2.8.4 | app/build.gradle.kts:170 |

Build plugins: AGP 8.10.1; Kotlin Android/KAPT/Compose compiler 2.2.20 (`build.gradle.kts:2-5`); Gradle wrapper 8.11.1. Compose BOM 2025.04.01 resolves Compose UI 1.8.0 in the runtime report; it does not mean Views were migrated. CameraX variable is declared 1.6.2.

### Publisher status checks / gaps
Official Android/Kotlin/Zetetic release-note websites were attempted with urllib over the inherited proxy and returned `Tunnel connection failed: 403 Forbidden`. Their release-note compatibility/security status is **UNVERIFIED**. Alternate publisher source/Google Maven metadata was fetched successfully where stated below. Newer published metadata is not a compatible-upgrade recommendation (alpha versions are explicitly not stable replacements).

| Dependency/status | Evidence / scope |
| --- | --- |
| EncryptedSharedPreferences deprecated upstream | Official AndroidX source fetched 200 at https://raw.githubusercontent.com/androidx/androidx/androidx-main/security/security-crypto/src/main/java/androidx/security/crypto/EncryptedSharedPreferences.java, lines 79–81: @deprecated / @Deprecated. Do NOT replace encrypted storage with plaintext SharedPreferences just because of the deprecation. Pin 1.1.0-alpha06; publisher metadata release 1.1.0. |
| KAPT versus KSP | Lint emits KaptUsageInsteadOfKsp once; KAPT plugin is present. Kotlin release-note URL blocked and attempted raw documentation returned 404: current upstream maintenance/deprecation status UNVERIFIED. No KSP migration performed. |
| SQLCipher 4.20.0 | Official publisher README fetched 200: https://raw.githubusercontent.com/sqlcipher/sqlcipher-android/master/README.md lines 19,26 list 4.20.0. Version existence corroborated; newest/security/upgrade compatibility not inferred. |
| Other third-party pins | OkHttp 4.12.0, Gson 2.10.1, osmdroid 6.1.20, JNA 5.13.0, Vosk 0.3.75, ML Kit 17.0.2, JUnit 4.13.2: no official release-note compatibility or CVE review completed in A0; UNVERIFIED, not asserted current/safe or automatically upgraded. |

| Google publisher metadata key | Latest/release returned on 2026-11-11 | Official URL |
| --- | --- | --- |
| room | 2.8.5 | https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml |
| work | 2.12.0 | https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/maven-metadata.xml |
| security | 1.1.0 | https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/maven-metadata.xml |
| core | 1.19.1 | https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml |
| appcompat | 1.8.0 | https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/maven-metadata.xml |
| lifecycle | 2.12.0-alpha05 | https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/maven-metadata.xml |
| activity | 1.14.0-alpha03 | https://dl.google.com/dl/android/maven2/androidx/activity/activity-ktx/maven-metadata.xml |
| webkit | 1.19.0-alpha01 | https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/maven-metadata.xml |
| recyclerview | 1.4.0 | https://dl.google.com/dl/android/maven2/androidx/recyclerview/recyclerview/maven-metadata.xml |
| camera | 1.7.0-rc02 | https://dl.google.com/dl/android/maven2/androidx/camera/camera-core/maven-metadata.xml |
| sqlite | 2.8.0-alpha03 | https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite/maven-metadata.xml |
| agp | 9.6.0-alpha03 | https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml |

## Manifest inventory
Source: `app/src/main/AndroidManifest.xml`; application `.JarvisApp`, allowBackup=false, hardware acceleration=true, networkSecurityConfig=@xml/network_security_config. Explicit manifest declarations only; merged-library components/permissions may add entries and require a separate merged-manifest review.

| Permission | Attributes |
| --- | --- |
| android.permission.RECEIVE_BOOT_COMPLETED | — |
| android.permission.RECORD_AUDIO | — |
| android.permission.ACCESS_FINE_LOCATION | — |
| android.permission.ACCESS_COARSE_LOCATION | — |
| android.permission.INTERNET | — |
| android.permission.ACCESS_NETWORK_STATE | — |
| android.permission.MODIFY_AUDIO_SETTINGS | — |
| android.permission.BLUETOOTH | maxSdkVersion=30 |
| android.permission.BLUETOOTH_ADMIN | maxSdkVersion=30 |
| android.permission.BLUETOOTH_CONNECT | — |
| android.permission.FOREGROUND_SERVICE | — |
| android.permission.FOREGROUND_SERVICE_SPECIAL_USE | — |
| android.permission.FOREGROUND_SERVICE_MICROPHONE | — |
| android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK | — |
| android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION | — |
| android.permission.POST_NOTIFICATIONS | — |
| android.permission.WAKE_LOCK | — |
| android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | — |
| android.permission.CAMERA | — |
| com.android.alarm.permission.SET_ALARM | — |
| android.permission.VIBRATE | — |
| android.permission.READ_CONTACTS | — |
| android.permission.WRITE_SETTINGS | ignore=ProtectedPermissions |
| android.permission.CALL_PHONE | — |
| android.permission.QUERY_ALL_PACKAGES | ignore=QueryAllPackagesPermission |
| android.permission.SYSTEM_ALERT_WINDOW | — |
| com.termux.permission.RUN_COMMAND | — |

| Component | Name / manifest line | Exported | Required sender/bind permission | FGS type | Intent filters (actions/categories) |
| --- | --- | --- | --- | --- | --- |
| activity | .ui.directives.WakeWordActivity (:53) | false | — | — | — |
| activity | .emergency.EmergencyContactActivity (:54) | false | — | — | — |
| service | .emergency.EmergencyMonitorService (:55) | false | — | specialUse | — |
| activity | .ui.aura.DecisionLogsActivity (:58) | false | — | — | — |
| activity | .ui.aura.MediaStudioActivity (:59) | false | — | — | — |
| activity | .ui.aura.AvatarStudioActivity (:60) | false | — | — | — |
| activity | .ui.directives.VoiceModeActivity (:61) | false | — | — | — |
| activity | .ui.directives.MissionDeskActivity (:62) | false | — | — | — |
| activity | .ui.directives.IdeasActivity (:63) | false | — | — | — |
| activity | .ui.directives.EnglishTeacherActivity (:64) | false | — | — | — |
| activity | .ui.extras.BrainDumpActivity (:65) | false | — | — | — |
| activity | .ui.extras.HermesContextActivity (:66) | false | — | — | — |
| activity | .ui.extras.AirGesturesActivity (:67) | false | — | — | — |
| activity | .ui.extras.NetworkAuditActivity (:68) | false | — | — | — |
| activity | .ui.extras.AirspaceRadarActivity (:69) | false | — | — | — |
| activity | .ui.extras.PolyglotActivity (:70) | false | — | — | — |
| activity | .ui.notification.NotificationHistoryActivity (:72) | false | — | — | — |
| activity | .ui.vision.NeuralLensActivity (:76) | false | — | — | — |
| activity | .ui.settings.AuraControlActivity (:80) | false | — | — | — |
| activity | .ui.home.FeatureHubActivity (:84) | false | — | — | — |
| activity | .ui.wallpaper.VideoWallpaperActivity (:88) | false | — | — | — |
| activity | .ui.map.AmoledMapActivity (:92) | false | — | — | — |
| activity | .ui.home.MainActivity (:96) | true | — | — | android.intent.action.MAIN; android.intent.category.HOME; android.intent.category.DEFAULT |
| activity-alias | .DefaultIcon (:110) | true | — | — | android.intent.action.MAIN; android.intent.category.LAUNCHER |
| activity-alias | .AuraRedIcon (:114) | true | — | — | android.intent.action.MAIN; android.intent.category.LAUNCHER |
| activity-alias | .MidnightIcon (:118) | true | — | — | android.intent.action.MAIN; android.intent.category.LAUNCHER |
| activity-alias | .CrimsonIcon (:122) | true | — | — | android.intent.action.MAIN; android.intent.category.LAUNCHER |
| activity | .ui.whiteboard.WhiteboardActivity (:127) | false | — | — | — |
| activity | .ui.settings.SettingsActivity (:133) | false | — | — | — |
| activity | .ui.memory.MemoryActivity (:139) | false | — | — | — |
| activity | .ui.voice.VoiceEnrollmentActivity (:145) | false | — | — | — |
| activity | .ui.settings.ApiConnectionActivity (:151) | false | — | — | — |
| activity | .ui.settings.PersonaActivity (:157) | false | — | — | — |
| activity | .ui.permission.PermissionActivity (:163) | false | — | — | — |
| activity | .ui.settings.OverlayAvatarActivity (:169) | false | — | — | — |
| activity | .ui.settings.PrivacyShieldActivity (:174) | false | — | — | — |
| activity | .ui.settings.HandsFreeActivity (:180) | false | — | — | — |
| service | .ui.wallpaper.VideoWallpaperService (:186) | true | android.permission.BIND_WALLPAPER | — | android.service.wallpaper.WallpaperService |
| service | .background.JarvisConversationService (:199) | false | — | microphone&#124;mediaPlayback&#124;mediaProjection | — |
| service | .automation.accessibility.JarvisAccessibilityService (:205) | true | android.permission.BIND_ACCESSIBILITY_SERVICE | — | android.accessibilityservice.AccessibilityService |
| service | .automation.notification.JarvisNotificationListenerService (:220) | true | android.permission.BIND_NOTIFICATION_LISTENER_SERVICE | — | android.service.notification.NotificationListenerService |
| receiver | .automation.phone.CallScreeningReceiver (:231) | true | android.permission.READ_PHONE_STATE | — | android.intent.action.PHONE_STATE |
| receiver | .background.OverlayBootReceiver (:240) | true | — | — | android.intent.action.BOOT_COMPLETED; android.intent.action.MY_PACKAGE_REPLACED |
| receiver | .automation.mission.MissionApprovalReceiver (:250) | false | — | — | — |
| receiver | .automation.dev.TermuxCommandResultReceiver (:255) | false | — | — | — |

Protected service permissions in the component table are not all uses-permission requests. For example BIND_ACCESSIBILITY_SERVICE protects system binding; users still grant Accessibility in Settings. Inventory is not a claim all declared capabilities work on the phone.

## Prioritized top 10 risks (findings, not reproduced phone failures)

| Priority | Risk | Evidence / next verification |
| --- | --- | --- |
| 1 | Native 16 KB compatibility remains unverified | Lint Aligned16KB (3 locations) identifies **x86_64/libjnidispatch.so from JNA 5.13.0**, app/build.gradle.kts:150. These are repeated diagnostics for the same ABI/library, not three independent phone crash causes. Check all packaged ABIs on a 16 KB device later. |
| 2 | Main-thread secure-preference startup I/O | JarvisApp.kt:90 constructs AppPreferences synchronously; AppPreferences.kt:18–38 creates MasterKey/encrypted prefs and runs migrations. Room native initialization is already deferred; do not confuse these paths. Requires StrictMode/Perfetto on device. |
| 3 | Voice lifecycle/state coupling | JarvisConversationService.kt:1 — 4,181 lines, shared capture/socket/playback/tools. Existing collaborators are wired; B1 needs characterization before extracting, not parallel pipelines. |
| 4 | Accessibility lifecycle/policy coupling | JarvisAccessibilityService.kt:1 — 2,381 lines; 10 exact Main Handler occurrences across app. Preserve sensitive-input rules during E4; no decomposition done in A0. |
| 5 | Native migration/recovery evidence gap | app/schemas/README.md documents missing original v1–v5 snapshots and reconstructed v1/v5 fixtures. RoomMigrationInstrumentedTest exists but no device execution in this task. Original crash stack unavailable. |
| 6 | A4 retention requirement differs from current importer | ConversationRepository.kt:111 imports/verifies then calls clearLegacyHistory; new prompt asks one-release read-only backup + 10k-message bounded import. Requires ADR before changing retention; do not silently undo verified migration. |
| 7 | Checkpoint quarantine/version/retention gaps | PersistentMissionCheckpointStorage.kt:36–46,58–67 decodes rows with runCatching.getOrNull; failures can be omitted without quarantine. Existing process persistence disproves the all-in-memory assumption; B3 must preserve/upgrade it with a new forward DB version (not overwrite existing 5→6). |
| 8 | Unauthenticated optional memory backend | Root memory-backend/README.md:99–101 explicitly says no auth and user_id is not authentication; app.py:23 English-centric model. No backend deployment/auth test or remote memory call performed; C2 must be consent-gated and is outside Part 7 except its explicit task authorization. |
| 9 | Failure visibility / lifecycle cleanup hotspots | 66 literal empty catches (65 named Exception), locations above. TaskProgressOverlay.kt:549 flagged StaticFieldLeak by lint; it is a risk to inspect, not measured leakage. Do not blindly remove graceful cleanup catches. |
| 10 | Localization/accessibility debt | Lint: HardcodedText 424, SetTextI18n 386, ContentDescription 29, ClickableViewAccessibility 7, SmallSp 20. Existing warnings are not a new-error gate until A2 establishes a reviewable baseline; no UI rewrite authorized by this audit. |

## NOT RUN / boundaries
No APK assembly, installation, instrumentation execution, physical Android 16/HyperOS verification, Perfetto/latency benchmark, native corruption recovery experiment, real provider/API requests, model download, dependency vulnerability scan or history secret scan. A0 does not establish production safety, prove 60 FPS, or resolve the user-device crash. No runtime source/manifest/DB record change.

## Reproduction / source of tables
Static tables were generated with Python `Path("app/src/main/java").rglob("*.kt")`, `read_text().splitlines()`, `re.finditer` using the exact patterns stated above; JUnit/Lint/manifest parsed with `xml.etree.ElementTree`. Full command outputs are local ignored `work/v2-a0-baseline.log` / `work/v2-a0-runtime-dependencies.log`; publisher responses/JSON were captured in `/tmp/jarvis-v2-a0`. All source file paths above are relative to Part 7 unless explicitly root-qualified.

<details><summary>Complete resolved debugRuntimeClasspath (observed Gradle output)</summary>

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.11.1/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build

> Task :app:dependencies

------------------------------------------------------------
Project ':app'
------------------------------------------------------------

debugRuntimeClasspath - Runtime classpath of '/debug'.
+--- androidx.databinding:viewbinding:8.10.1
|    \--- androidx.annotation:annotation:1.0.0 -> 1.9.1
|         \--- androidx.annotation:annotation-jvm:1.9.1
|              \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20
|                   +--- org.jetbrains:annotations:13.0 -> 23.0.0
|                   +--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0 -> 1.9.10 (c)
|                   +--- org.jetbrains.kotlin:kotlin-stdlib-common:2.2.20 (c)
|                   \--- org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0 -> 1.9.10 (c)
+--- org.jetbrains.kotlin:kotlin-stdlib:2.2.20 (*)
+--- androidx.compose.ui:ui-tooling -> 1.8.0
|    \--- androidx.compose.ui:ui-tooling-android:1.8.0
|         +--- androidx.activity:activity-compose:1.7.0 -> 1.9.3
|         |    +--- androidx.activity:activity-ktx:1.9.3
|         |    |    +--- androidx.activity:activity:1.9.3
|         |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    |    |    +--- androidx.collection:collection:1.0.0 -> 1.5.0
|         |    |    |    |    \--- androidx.collection:collection-jvm:1.5.0
|         |    |    |    |         +--- androidx.annotation:annotation:1.9.1 (*)
|         |    |    |    |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |    |    |         +--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |    |    |    |         \--- androidx.collection:collection-ktx:1.5.0 (c)
|         |    |    |    +--- androidx.core:core:1.13.0 -> 1.13.1
|         |    |    |    |    +--- androidx.annotation:annotation:1.6.0 -> 1.9.1 (*)
|         |    |    |    |    +--- androidx.annotation:annotation-experimental:1.4.0 -> 1.5.0
|         |    |    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |    |    |    |    \--- org.jetbrains.kotlin:kotlin-stdlib:2.0.21 -> 2.2.20 (c)
|         |    |    |    |    +--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|         |    |    |    |    +--- androidx.concurrent:concurrent-futures:1.0.0 -> 1.1.0
|         |    |    |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    |    |    |    |    \--- com.google.guava:listenablefuture:1.0 -> 9999.0-empty-to-avoid-conflict-with-guava
|         |    |    |    |    +--- androidx.interpolator:interpolator:1.0.0
|         |    |    |    |    |    \--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2 -> 2.8.7
|         |    |    |    |    |    \--- androidx.lifecycle:lifecycle-runtime-android:2.8.7
|         |    |    |    |    |         +--- androidx.annotation:annotation:1.8.0 -> 1.9.1 (*)
|         |    |    |    |    |         +--- androidx.arch.core:core-common:2.2.0
|         |    |    |    |    |         |    \--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    |    |    |    |         +--- androidx.arch.core:core-runtime:2.2.0
|         |    |    |    |    |         |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    |    |    |    |         |    \--- androidx.arch.core:core-common:2.2.0 (*)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-common:2.8.7
|         |    |    |    |    |         |    \--- androidx.lifecycle:lifecycle-common-jvm:2.8.7
|         |    |    |    |    |         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |    |    |    |         |         +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    |    |         |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.9.0
|         |    |    |    |    |         |         |    \--- org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0
|         |    |    |    |    |         |         |         +--- org.jetbrains:annotations:23.0.0
|         |    |    |    |    |         |         |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0
|         |    |    |    |    |         |         |         |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 (c)
|         |    |    |    |    |         |         |         |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0 (c)
|         |    |    |    |    |         |         |         |    \--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0 (c)
|         |    |    |    |    |         |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:2.0.0 -> 2.2.20 (*)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    |    |    |    |         |         +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    |    |    |    |         |         \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.profileinstaller:profileinstaller:1.3.1 -> 1.4.0
|         |    |    |    |    |         |    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |    |    |    |         |    +--- androidx.concurrent:concurrent-futures:1.1.0 (*)
|         |    |    |    |    |         |    +--- androidx.startup:startup-runtime:1.1.1
|         |    |    |    |    |         |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    |    |    |    |         |    |    \--- androidx.tracing:tracing:1.0.0 -> 1.3.0
|         |    |    |    |    |         |    |         \--- androidx.tracing:tracing-android:1.3.0
|         |    |    |    |    |         |    |              +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |    |    |    |         |    |              +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |    |    |    |         |    |              +--- androidx.tracing:tracing-ktx:1.3.0 (c)
|         |    |    |    |    |         |    |              \--- org.jetbrains.kotlin:kotlin-stdlib:2.0.21 -> 2.2.20 (c)
|         |    |    |    |    |         |    \--- com.google.guava:listenablefuture:1.0 -> 9999.0-empty-to-avoid-conflict-with-guava
|         |    |    |    |    |         +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3 -> 1.9.0
|         |    |    |    |    |         |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0 (*)
|         |    |    |    |    |         |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0 (*)
|         |    |    |    |    |         |    \--- org.jetbrains.kotlin:kotlin-stdlib:2.0.0 -> 2.2.20 (*)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    |    |    |    |         +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    |    |    |    |         \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         |    |    |    |    +--- androidx.versionedparcelable:versionedparcelable:1.1.1
|         |    |    |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    |    |    |    |    \--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|         |    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    |    \--- androidx.core:core-ktx:1.13.1 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.1 -> 2.8.7 (*)
|         |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.1 -> 2.8.7
|         |    |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-android:2.8.7
|         |    |    |    |         +--- androidx.annotation:annotation:1.8.0 -> 1.9.1 (*)
|         |    |    |    |         +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3 -> 1.9.0 (*)
|         |    |    |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.9.0 (*)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |    |    |         +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    |    |    |         \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.1 -> 2.8.7
|         |    |    |    |    +--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|         |    |    |    |    +--- androidx.core:core-ktx:1.2.0 -> 1.13.1
|         |    |    |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    |    |    |    |    +--- androidx.core:core:1.13.1 (*)
|         |    |    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    |    |    \--- androidx.core:core:1.13.1 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7
|         |    |    |    |    |    +--- androidx.arch.core:core-common:2.2.0 (*)
|         |    |    |    |    |    +--- androidx.arch.core:core-runtime:2.2.0 (*)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (*)
|         |    |    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    |    |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (*)
|         |    |    |    |    +--- androidx.savedstate:savedstate:1.2.1
|         |    |    |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    |    |    |    |    +--- androidx.arch.core:core-common:2.1.0 -> 2.2.0 (*)
|         |    |    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.1 -> 2.8.7 (*)
|         |    |    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 2.2.20 (*)
|         |    |    |    |    |    \--- androidx.savedstate:savedstate-ktx:1.2.1 (c)
|         |    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3 -> 1.9.0 (*)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    |    |    +--- androidx.profileinstaller:profileinstaller:1.3.1 -> 1.4.0 (*)
|         |    |    |    +--- androidx.savedstate:savedstate:1.2.1 (*)
|         |    |    |    +--- androidx.tracing:tracing:1.0.0 -> 1.3.0 (*)
|         |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    +--- androidx.activity:activity-ktx:1.9.3 (c)
|         |    |    |    \--- androidx.activity:activity-compose:1.9.3 (c)
|         |    |    +--- androidx.core:core-ktx:1.13.0 -> 1.13.1 (*)
|         |    |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.6.1 -> 2.8.7
|         |    |    |    \--- androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.7
|         |    |    |         +--- androidx.annotation:annotation:1.8.0 -> 1.9.1 (*)
|         |    |    |         +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (*)
|         |    |    |         +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3 -> 1.9.0 (*)
|         |    |    |         +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    |    |         +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    |    |         \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         |    |    +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1 -> 2.8.7
|         |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (*)
|         |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3 -> 1.9.0 (*)
|         |    |    |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         |    |    +--- androidx.savedstate:savedstate-ktx:1.2.1
|         |    |    |    +--- androidx.savedstate:savedstate:1.2.1 (*)
|         |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 2.2.20 (*)
|         |    |    |    \--- androidx.savedstate:savedstate:1.2.1 (c)
|         |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |    +--- androidx.activity:activity:1.9.3 (c)
|         |    |    \--- androidx.activity:activity-compose:1.9.3 (c)
|         |    +--- androidx.compose.runtime:runtime:1.0.1 -> 1.8.0
|         |    |    \--- androidx.compose.runtime:runtime-android:1.8.0
|         |    |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |    |         +--- androidx.collection:collection:1.5.0 (*)
|         |    |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3 -> 1.9.0 (*)
|         |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.9.0 (*)
|         |    |         +--- androidx.compose.runtime:runtime-saveable:1.8.0 (c)
|         |    |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |    +--- androidx.compose.runtime:runtime-saveable:1.0.1 -> 1.8.0
|         |    |    \--- androidx.compose.runtime:runtime-saveable-android:1.8.0
|         |    |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |         +--- androidx.collection:collection:1.4.2 -> 1.5.0 (*)
|         |    |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |    |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |         +--- androidx.compose.runtime:runtime:1.8.0 (c)
|         |    |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |    +--- androidx.compose.ui:ui:1.0.1 -> 1.8.0
|         |    |    \--- androidx.compose.ui:ui-android:1.8.0
|         |    |         +--- androidx.activity:activity-ktx:1.7.0 -> 1.9.3 (*)
|         |    |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |    |         +--- androidx.autofill:autofill:1.0.0
|         |    |         |    \--- androidx.core:core:1.1.0 -> 1.13.1 (*)
|         |    |         +--- androidx.collection:collection:1.4.2 -> 1.5.0 (*)
|         |    |         +--- androidx.collection:collection:1.5.0 (*)
|         |    |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |    |         +--- androidx.compose.runtime:runtime-saveable:1.8.0 (*)
|         |    |         +--- androidx.compose.ui:ui-geometry:1.8.0
|         |    |         |    \--- androidx.compose.ui:ui-geometry-android:1.8.0
|         |    |         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |    |         |         +--- androidx.compose.ui:ui-util:1.8.0
|         |    |         |         |    \--- androidx.compose.ui:ui-util-android:1.8.0
|         |    |         |         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |    |         |         |         +--- androidx.collection:collection:1.4.3 -> 1.5.0 (*)
|         |    |         |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |         |         |         +--- androidx.compose.ui:ui:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-text:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|         |    |         |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (c)
|         |    |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |         |         +--- androidx.compose.ui:ui:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-text:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-util:1.8.0 (c)
|         |    |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |    |         +--- androidx.compose.ui:ui-graphics:1.8.0
|         |    |         |    \--- androidx.compose.ui:ui-graphics-android:1.8.0
|         |    |         |         +--- androidx.annotation:annotation:1.7.0 -> 1.9.1 (*)
|         |    |         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |    |         |         +--- androidx.collection:collection:1.4.2 -> 1.5.0 (*)
|         |    |         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |    |         |         +--- androidx.compose.ui:ui-unit:1.8.0
|         |    |         |         |    \--- androidx.compose.ui:ui-unit-android:1.8.0
|         |    |         |         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |         |         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |    |         |         |         +--- androidx.collection:collection:1.4.2 -> 1.5.0 (*)
|         |    |         |         |         +--- androidx.collection:collection-ktx:1.4.2 -> 1.5.0
|         |    |         |         |         |    +--- androidx.collection:collection:1.5.0 (*)
|         |    |         |         |         |    \--- androidx.collection:collection:1.5.0 (c)
|         |    |         |         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |    |         |         |         +--- androidx.compose.ui:ui-geometry:1.8.0 (*)
|         |    |         |         |         +--- androidx.compose.ui:ui-util:1.8.0 (*)
|         |    |         |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |         |         |         +--- androidx.compose.ui:ui:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-text:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|         |    |         |         |         +--- androidx.compose.ui:ui-util:1.8.0 (c)
|         |    |         |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |    |         |         +--- androidx.compose.ui:ui-util:1.8.0 (*)
|         |    |         |         +--- androidx.core:core:1.12.0 -> 1.13.1 (*)
|         |    |         |         +--- androidx.graphics:graphics-path:1.0.1
|         |    |         |         |    +--- androidx.core:core:1.12.0 -> 1.13.1 (*)
|         |    |         |         |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |         |         +--- androidx.compose.ui:ui:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-text:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-util:1.8.0 (c)
|         |    |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |    |         +--- androidx.compose.ui:ui-text:1.8.0
|         |    |         |    \--- androidx.compose.ui:ui-text-android:1.8.0
|         |    |         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |    |         |         +--- androidx.collection:collection:1.5.0 (*)
|         |    |         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |    |         |         +--- androidx.compose.runtime:runtime-saveable:1.8.0 (*)
|         |    |         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (*)
|         |    |         |         +--- androidx.compose.ui:ui-unit:1.8.0 (*)
|         |    |         |         +--- androidx.compose.ui:ui-util:1.8.0 (*)
|         |    |         |         +--- androidx.core:core:1.7.0 -> 1.13.1 (*)
|         |    |         |         +--- androidx.emoji2:emoji2:1.4.0
|         |    |         |         |    +--- androidx.annotation:annotation:1.2.0 -> 1.9.1 (*)
|         |    |         |         |    +--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
|         |    |         |         |    +--- androidx.core:core:1.3.0 -> 1.13.1 (*)
|         |    |         |         |    +--- androidx.lifecycle:lifecycle-process:2.4.1 -> 2.8.7
|         |    |         |         |    |    +--- androidx.annotation:annotation:1.2.0 -> 1.9.1 (*)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (*)
|         |    |         |         |    |    +--- androidx.startup:startup-runtime:1.1.1 (*)
|         |    |         |         |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    |         |         |    |    +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    |         |         |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         |    |         |         |    +--- androidx.startup:startup-runtime:1.0.0 -> 1.1.1 (*)
|         |    |         |         |    \--- androidx.emoji2:emoji2-views-helper:1.4.0 (c)
|         |    |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |         |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.9.0 (*)
|         |    |         |         +--- androidx.compose.ui:ui:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|         |    |         |         +--- androidx.compose.ui:ui-util:1.8.0 (c)
|         |    |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |    |         +--- androidx.compose.ui:ui-unit:1.8.0 (*)
|         |    |         +--- androidx.compose.ui:ui-util:1.8.0 (*)
|         |    |         +--- androidx.core:core:1.12.0 -> 1.13.1 (*)
|         |    |         +--- androidx.customview:customview-poolingcontainer:1.0.0
|         |    |         |    +--- androidx.core:core-ktx:1.5.0 -> 1.13.1 (*)
|         |    |         |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.6.21 -> 2.2.20 (*)
|         |    |         +--- androidx.emoji2:emoji2:1.2.0 -> 1.4.0 (*)
|         |    |         +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7
|         |    |         |    \--- androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7
|         |    |         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |    |         |         +--- androidx.compose.runtime:runtime:1.7.1 -> 1.8.0 (*)
|         |    |         |         +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (*)
|         |    |         |         +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (*)
|         |    |         |         +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    |         |         +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|         |    |         |         \--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    |         +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (*)
|         |    |         +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (*)
|         |    |         +--- androidx.profileinstaller:profileinstaller:1.4.0 (*)
|         |    |         +--- androidx.savedstate:savedstate-ktx:1.2.1 (*)
|         |    |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3 -> 1.9.0 (*)
|         |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.9.0 (*)
|         |    |         +--- org.jspecify:jspecify:1.0.0
|         |    |         +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|         |    |         +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|         |    |         +--- androidx.compose.ui:ui-text:1.8.0 (c)
|         |    |         +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|         |    |         +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|         |    |         +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|         |    |         +--- androidx.compose.ui:ui-util:1.8.0 (c)
|         |    |         +--- androidx.compose.foundation:foundation:1.7.0 -> 1.8.0 (c)
|         |    |         +--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |    |         \--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|         |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.1 -> 2.8.7 (*)
|         |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |    +--- androidx.activity:activity:1.9.3 (c)
|         |    \--- androidx.activity:activity-ktx:1.9.3 (c)
|         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         +--- androidx.compose.animation:animation:1.8.0
|         |    \--- androidx.compose.animation:animation-android:1.8.0
|         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |         +--- androidx.collection:collection:1.5.0 (*)
|         |         +--- androidx.compose.animation:animation-core:1.8.0
|         |         |    \--- androidx.compose.animation:animation-core-android:1.8.0
|         |         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |         |         +--- androidx.collection:collection:1.5.0 (*)
|         |         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui-unit:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui-util:1.8.0 (*)
|         |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |         |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.9.0 (*)
|         |         |         +--- androidx.compose.animation:animation:1.8.0 (c)
|         |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |         +--- androidx.compose.foundation:foundation-layout:1.8.0
|         |         |    \--- androidx.compose.foundation:foundation-layout-android:1.8.0
|         |         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |         |         +--- androidx.collection:collection:1.5.0 (*)
|         |         |         +--- androidx.compose.animation:animation-core:1.2.1 -> 1.8.0 (*)
|         |         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui-unit:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui-util:1.8.0 (*)
|         |         |         +--- androidx.core:core:1.7.0 -> 1.13.1 (*)
|         |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |         |         +--- androidx.compose.foundation:foundation:1.8.0 (c)
|         |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |         +--- androidx.compose.ui:ui:1.8.0 (*)
|         |         +--- androidx.compose.ui:ui-geometry:1.8.0 (*)
|         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (*)
|         |         +--- androidx.compose.ui:ui-util:1.8.0 (*)
|         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |         +--- androidx.compose.animation:animation-core:1.8.0 (c)
|         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         +--- androidx.compose.material:material:1.0.0 -> 1.8.0
|         |    \--- androidx.compose.material:material-android:1.8.0
|         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |         +--- androidx.compose.animation:animation:1.7.4 -> 1.8.0 (*)
|         |         +--- androidx.compose.animation:animation-core:1.7.4 -> 1.8.0 (*)
|         |         +--- androidx.compose.foundation:foundation:1.7.4 -> 1.8.0
|         |         |    \--- androidx.compose.foundation:foundation-android:1.8.0
|         |         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |         |         +--- androidx.collection:collection:1.5.0 (*)
|         |         |         +--- androidx.compose.animation:animation:1.8.0 (*)
|         |         |         +--- androidx.compose.foundation:foundation-layout:1.8.0 (*)
|         |         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui-text:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui-util:1.8.0 (*)
|         |         |         +--- androidx.core:core:1.13.1 (*)
|         |         |         +--- androidx.emoji2:emoji2:1.3.0 -> 1.4.0 (*)
|         |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |         |         +--- androidx.compose.foundation:foundation-layout:1.8.0 (c)
|         |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |         +--- androidx.compose.foundation:foundation-layout:1.7.4 -> 1.8.0 (*)
|         |         +--- androidx.compose.material:material-ripple:1.8.0
|         |         |    \--- androidx.compose.material:material-ripple-android:1.8.0
|         |         |         +--- androidx.collection:collection:1.5.0 (*)
|         |         |         +--- androidx.compose.animation:animation:1.7.1 -> 1.8.0 (*)
|         |         |         +--- androidx.compose.foundation:foundation:1.7.1 -> 1.8.0 (*)
|         |         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |         |         +--- androidx.compose.ui:ui-util:1.7.1 -> 1.8.0 (*)
|         |         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |         +--- androidx.compose.ui:ui:1.7.4 -> 1.8.0 (*)
|         |         +--- androidx.compose.ui:ui-text:1.7.4 -> 1.8.0 (*)
|         |         +--- androidx.compose.ui:ui-util:1.7.4 -> 1.8.0 (*)
|         |         +--- androidx.lifecycle:lifecycle-runtime:2.6.1 -> 2.8.7 (*)
|         |         +--- androidx.lifecycle:lifecycle-viewmodel:2.6.1 -> 2.8.7 (*)
|         |         +--- androidx.savedstate:savedstate:1.2.1 (*)
|         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         +--- androidx.compose.ui:ui:1.8.0 (*)
|         +--- androidx.compose.ui:ui-tooling-data:1.8.0
|         |    \--- androidx.compose.ui:ui-tooling-data-android:1.8.0
|         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |         +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |         +--- androidx.compose.ui:ui:1.8.0 (*)
|         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |         +--- androidx.compose.ui:ui:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-text:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-util:1.8.0 (c)
|         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
|         +--- androidx.compose.ui:ui-tooling-preview:1.8.0
|         |    \--- androidx.compose.ui:ui-tooling-preview-android:1.8.0
|         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |         +--- androidx.compose.runtime:runtime:1.8.0 (*)
|         |         +--- org.jetbrains.kotlin:kotlin-stdlib-common -> 2.2.20
|         |         |    \--- org.jetbrains.kotlin:kotlin-stdlib:2.2.20 (*)
|         |         +--- androidx.compose.ui:ui:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-text:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|         |         +--- androidx.compose.ui:ui-util:1.8.0 (c)
|         |         \--- org.jetbrains.kotlin:kotlin-stdlib-common:1.8.22 -> 2.2.20 (c)
|         +--- androidx.lifecycle:lifecycle-common:2.6.1 -> 2.8.7 (*)
|         +--- androidx.savedstate:savedstate-ktx:1.2.1 (*)
|         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         +--- androidx.compose.ui:ui:1.8.0 (c)
|         +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|         +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|         +--- androidx.compose.ui:ui-text:1.8.0 (c)
|         +--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|         +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|         +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|         +--- androidx.compose.ui:ui-util:1.8.0 (c)
|         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 -> 2.2.20 (c)
+--- androidx.compose:compose-bom:2025.04.01
|    +--- androidx.compose.material3:material3:1.3.2 (c)
|    +--- androidx.compose.ui:ui:1.8.0 (c)
|    +--- androidx.compose.ui:ui-tooling:1.8.0 (c)
|    +--- androidx.compose.ui:ui-tooling-preview:1.8.0 (c)
|    +--- androidx.compose.material3:material3-android:1.3.2 (c)
|    +--- androidx.compose.ui:ui-android:1.8.0 (c)
|    +--- androidx.compose.ui:ui-tooling-android:1.8.0 (c)
|    +--- androidx.compose.ui:ui-tooling-preview-android:1.8.0 (c)
|    +--- androidx.compose.runtime:runtime:1.8.0 (c)
|    +--- androidx.compose.animation:animation-core:1.8.0 (c)
|    +--- androidx.compose.foundation:foundation:1.8.0 (c)
|    +--- androidx.compose.foundation:foundation-layout:1.8.0 (c)
|    +--- androidx.compose.material:material-icons-core:1.7.8 (c)
|    +--- androidx.compose.material:material-ripple:1.8.0 (c)
|    +--- androidx.compose.ui:ui-text:1.8.0 (c)
|    +--- androidx.compose.ui:ui-util:1.8.0 (c)
|    +--- androidx.compose.runtime:runtime-saveable:1.8.0 (c)
|    +--- androidx.compose.ui:ui-geometry:1.8.0 (c)
|    +--- androidx.compose.ui:ui-graphics:1.8.0 (c)
|    +--- androidx.compose.ui:ui-unit:1.8.0 (c)
|    +--- androidx.compose.animation:animation:1.8.0 (c)
|    +--- androidx.compose.material:material:1.8.0 (c)
|    +--- androidx.compose.ui:ui-tooling-data:1.8.0 (c)
|    +--- androidx.compose.runtime:runtime-android:1.8.0 (c)
|    +--- androidx.compose.animation:animation-core-android:1.8.0 (c)
|    +--- androidx.compose.foundation:foundation-android:1.8.0 (c)
|    +--- androidx.compose.foundation:foundation-layout-android:1.8.0 (c)
|    +--- androidx.compose.material:material-icons-core-android:1.7.8 (c)
|    +--- androidx.compose.material:material-ripple-android:1.8.0 (c)
|    +--- androidx.compose.ui:ui-text-android:1.8.0 (c)
|    +--- androidx.compose.ui:ui-util-android:1.8.0 (c)
|    +--- androidx.compose.ui:ui-geometry-android:1.8.0 (c)
|    +--- androidx.compose.ui:ui-graphics-android:1.8.0 (c)
|    +--- androidx.compose.ui:ui-unit-android:1.8.0 (c)
|    +--- androidx.compose.runtime:runtime-saveable-android:1.8.0 (c)
|    +--- androidx.compose.ui:ui-tooling-data-android:1.8.0 (c)
|    +--- androidx.compose.animation:animation-android:1.8.0 (c)
|    \--- androidx.compose.material:material-android:1.8.0 (c)
+--- androidx.compose.ui:ui -> 1.8.0 (*)
+--- androidx.compose.material3:material3 -> 1.3.2
|    \--- androidx.compose.material3:material3-android:1.3.2
|         +--- androidx.activity:activity-compose:1.8.2 -> 1.9.3 (*)
|         +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         +--- androidx.annotation:annotation-experimental:1.4.0 -> 1.5.0 (*)
|         +--- androidx.collection:collection:1.4.0 -> 1.5.0 (*)
|         +--- androidx.compose.animation:animation-core:1.6.0 -> 1.8.0 (*)
|         +--- androidx.compose.foundation:foundation:1.7.0 -> 1.8.0 (*)
|         +--- androidx.compose.foundation:foundation-layout:1.7.0 -> 1.8.0 (*)
|         +--- androidx.compose.material:material-icons-core:1.6.0 -> 1.7.8
|         |    \--- androidx.compose.material:material-icons-core-android:1.7.8
|         |         +--- androidx.compose.ui:ui:1.6.0 -> 1.8.0 (*)
|         |         +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|         |         \--- org.jetbrains.kotlin:kotlin-stdlib-common:1.8.22 -> 2.2.20 (*)
|         +--- androidx.compose.material:material-ripple:1.7.0 -> 1.8.0 (*)
|         +--- androidx.compose.runtime:runtime:1.7.0 -> 1.8.0 (*)
|         +--- androidx.compose.ui:ui:1.6.0 -> 1.8.0 (*)
|         +--- androidx.compose.ui:ui-text:1.6.0 -> 1.8.0 (*)
|         +--- androidx.compose.ui:ui-util:1.6.0 -> 1.8.0 (*)
|         +--- androidx.lifecycle:lifecycle-common-java8:2.6.1 -> 2.8.7
|         |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (*)
|         |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|         |    +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|         |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|         \--- org.jetbrains.kotlin:kotlin-stdlib-common:1.8.22 -> 2.2.20 (*)
+--- androidx.compose.ui:ui-tooling-preview -> 1.8.0 (*)
+--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (*)
+--- androidx.core:core-ktx:1.13.1 (*)
+--- androidx.appcompat:appcompat:1.7.0
|    +--- androidx.activity:activity:1.7.0 -> 1.9.3 (*)
|    +--- androidx.annotation:annotation:1.3.0 -> 1.9.1 (*)
|    +--- androidx.appcompat:appcompat-resources:1.7.0
|    |    +--- androidx.annotation:annotation:1.2.0 -> 1.9.1 (*)
|    |    +--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|    |    +--- androidx.core:core:1.6.0 -> 1.13.1 (*)
|    |    +--- androidx.vectordrawable:vectordrawable:1.1.0
|    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    |    +--- androidx.core:core:1.1.0 -> 1.13.1 (*)
|    |    |    \--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
|    |    +--- androidx.vectordrawable:vectordrawable-animated:1.1.0
|    |    |    +--- androidx.vectordrawable:vectordrawable:1.1.0 (*)
|    |    |    +--- androidx.interpolator:interpolator:1.0.0 (*)
|    |    |    \--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
|    |    \--- androidx.appcompat:appcompat:1.7.0 (c)
|    +--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|    +--- androidx.core:core:1.13.0 -> 1.13.1 (*)
|    +--- androidx.core:core-ktx:1.13.0 -> 1.13.1 (*)
|    +--- androidx.cursoradapter:cursoradapter:1.0.0
|    |    \--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|    +--- androidx.drawerlayout:drawerlayout:1.0.0 -> 1.1.1
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    +--- androidx.core:core:1.2.0 -> 1.13.1 (*)
|    |    \--- androidx.customview:customview:1.1.0
|    |         +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |         +--- androidx.core:core:1.3.0 -> 1.13.1 (*)
|    |         \--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
|    +--- androidx.emoji2:emoji2:1.3.0 -> 1.4.0 (*)
|    +--- androidx.emoji2:emoji2-views-helper:1.2.0 -> 1.4.0
|    |    +--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
|    |    +--- androidx.core:core:1.3.0 -> 1.13.1 (*)
|    |    +--- androidx.emoji2:emoji2:1.4.0 (*)
|    |    \--- androidx.emoji2:emoji2:1.4.0 (c)
|    +--- androidx.fragment:fragment:1.5.4
|    |    +--- androidx.activity:activity:1.5.1 -> 1.9.3 (*)
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    +--- androidx.annotation:annotation-experimental:1.0.0 -> 1.5.0 (*)
|    |    +--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
|    |    +--- androidx.core:core-ktx:1.2.0 -> 1.13.1 (*)
|    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.5.1 -> 2.8.7 (*)
|    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.5.1 -> 2.8.7 (*)
|    |    +--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.5.1 -> 2.8.7 (*)
|    |    +--- androidx.loader:loader:1.0.0
|    |    |    +--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|    |    |    +--- androidx.core:core:1.0.0 -> 1.13.1 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.0.0 -> 2.8.7
|    |    |    |    +--- androidx.arch.core:core-common:2.2.0 (*)
|    |    |    |    +--- androidx.arch.core:core-runtime:2.2.0 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (*)
|    |    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|    |    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|    |    |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|    |    |    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.9.0 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|    |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|    |    |    \--- androidx.lifecycle:lifecycle-viewmodel:2.0.0 -> 2.8.7 (*)
|    |    +--- androidx.savedstate:savedstate:1.2.0 -> 1.2.1 (*)
|    |    +--- androidx.viewpager:viewpager:1.0.0
|    |    |    +--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|    |    |    +--- androidx.core:core:1.0.0 -> 1.13.1 (*)
|    |    |    \--- androidx.customview:customview:1.0.0 -> 1.1.0 (*)
|    |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.6.21 -> 2.2.20 (*)
|    +--- androidx.lifecycle:lifecycle-runtime:2.6.1 -> 2.8.7 (*)
|    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.1 -> 2.8.7 (*)
|    +--- androidx.profileinstaller:profileinstaller:1.3.1 -> 1.4.0 (*)
|    +--- androidx.resourceinspection:resourceinspection-annotation:1.0.1
|    |    \--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    +--- androidx.savedstate:savedstate:1.2.1 (*)
|    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|    \--- androidx.appcompat:appcompat-resources:1.7.0 (c)
+--- com.google.android.material:material:1.12.0
|    +--- org.jetbrains.kotlin:kotlin-bom:1.8.22
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (c)
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22 -> 1.9.10 (c)
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib-common:1.8.22 -> 2.2.20 (c)
|    |    \--- org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22 -> 1.9.10 (c)
|    +--- com.google.errorprone:error_prone_annotations:2.15.0 -> 2.28.0
|    +--- androidx.activity:activity:1.8.0 -> 1.9.3 (*)
|    +--- androidx.annotation:annotation:1.2.0 -> 1.9.1 (*)
|    +--- androidx.appcompat:appcompat:1.6.1 -> 1.7.0 (*)
|    +--- androidx.cardview:cardview:1.0.0
|    |    \--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|    +--- androidx.coordinatorlayout:coordinatorlayout:1.1.0
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    +--- androidx.core:core:1.1.0 -> 1.13.1 (*)
|    |    +--- androidx.customview:customview:1.0.0 -> 1.1.0 (*)
|    |    \--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|    +--- androidx.constraintlayout:constraintlayout:2.0.1 -> 2.1.4
|    |    +--- androidx.appcompat:appcompat:1.2.0 -> 1.7.0 (*)
|    |    +--- androidx.core:core:1.3.2 -> 1.13.1 (*)
|    |    \--- androidx.constraintlayout:constraintlayout-core:1.0.4
|    +--- androidx.core:core:1.6.0 -> 1.13.1 (*)
|    +--- androidx.drawerlayout:drawerlayout:1.1.1 (*)
|    +--- androidx.dynamicanimation:dynamicanimation:1.0.0
|    |    +--- androidx.core:core:1.0.0 -> 1.13.1 (*)
|    |    +--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|    |    \--- androidx.legacy:legacy-support-core-utils:1.0.0
|    |         +--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|    |         +--- androidx.core:core:1.0.0 -> 1.13.1 (*)
|    |         +--- androidx.documentfile:documentfile:1.0.0
|    |         |    \--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|    |         +--- androidx.loader:loader:1.0.0 (*)
|    |         +--- androidx.localbroadcastmanager:localbroadcastmanager:1.0.0
|    |         |    \--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|    |         \--- androidx.print:print:1.0.0
|    |              \--- androidx.annotation:annotation:1.0.0 -> 1.9.1 (*)
|    +--- androidx.annotation:annotation-experimental:1.0.0 -> 1.5.0 (*)
|    +--- androidx.fragment:fragment:1.2.5 -> 1.5.4 (*)
|    +--- androidx.lifecycle:lifecycle-runtime:2.0.0 -> 2.8.7 (*)
|    +--- androidx.recyclerview:recyclerview:1.0.0 -> 1.3.2
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    +--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|    |    +--- androidx.core:core:1.7.0 -> 1.13.1 (*)
|    |    +--- androidx.customview:customview:1.0.0 -> 1.1.0 (*)
|    |    +--- androidx.customview:customview-poolingcontainer:1.0.0 (*)
|    |    \--- androidx.viewpager2:viewpager2:1.1.0-beta02 (c)
|    +--- androidx.resourceinspection:resourceinspection-annotation:1.0.1 (*)
|    +--- androidx.transition:transition:1.5.0
|    |    +--- androidx.annotation:annotation:1.2.0 -> 1.9.1 (*)
|    |    +--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
|    |    +--- androidx.core:core:1.13.0 -> 1.13.1 (*)
|    |    \--- androidx.dynamicanimation:dynamicanimation:1.0.0 (*)
|    +--- androidx.vectordrawable:vectordrawable:1.1.0 (*)
|    \--- androidx.viewpager2:viewpager2:1.0.0 -> 1.1.0-beta02
|         +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|         +--- androidx.annotation:annotation-experimental:1.3.0 -> 1.5.0 (*)
|         +--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
|         +--- androidx.core:core:1.3.2 -> 1.13.1 (*)
|         +--- androidx.fragment:fragment:1.1.0 -> 1.5.4 (*)
|         \--- androidx.recyclerview:recyclerview:1.3.1-rc01 -> 1.3.2 (*)
+--- androidx.constraintlayout:constraintlayout:2.1.4 (*)
+--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6 -> 2.8.7 (*)
+--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.6 -> 2.8.7
|    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (*)
|    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (*)
|    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 -> 1.9.0 (*)
|    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-service:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|    +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
+--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.6 -> 2.8.7 (*)
+--- androidx.activity:activity-ktx:1.9.3 (*)
+--- androidx.recyclerview:recyclerview:1.3.2 (*)
+--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1 -> 1.9.0 (*)
+--- com.squareup.okhttp3:okhttp:4.12.0
|    +--- com.squareup.okio:okio:3.6.0
|    |    \--- com.squareup.okio:okio-jvm:3.6.0
|    |         +--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10
|    |         |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.9.10 -> 2.2.20 (*)
|    |         |    \--- org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10
|    |         |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.10 -> 2.2.20 (*)
|    |         \--- org.jetbrains.kotlin:kotlin-stdlib-common:1.9.10 -> 2.2.20 (*)
|    \--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21 -> 1.9.10 (*)
+--- com.google.code.gson:gson:2.10.1
+--- androidx.webkit:webkit:1.12.0
|    +--- androidx.annotation:annotation:1.2.0 -> 1.9.1 (*)
|    \--- androidx.core:core:1.1.0 -> 1.13.1 (*)
+--- com.google.mlkit:object-detection:17.0.2
|    +--- androidx.lifecycle:lifecycle-extensions:2.1.0
|    |    +--- androidx.lifecycle:lifecycle-runtime:2.1.0 -> 2.8.7 (*)
|    |    +--- androidx.arch.core:core-common:2.1.0 -> 2.2.0 (*)
|    |    +--- androidx.arch.core:core-runtime:2.1.0 -> 2.2.0 (*)
|    |    +--- androidx.fragment:fragment:1.0.0 -> 1.5.4 (*)
|    |    +--- androidx.lifecycle:lifecycle-common:2.1.0 -> 2.8.7 (*)
|    |    +--- androidx.lifecycle:lifecycle-livedata:2.1.0 -> 2.8.7 (*)
|    |    +--- androidx.lifecycle:lifecycle-process:2.1.0 -> 2.8.7 (*)
|    |    +--- androidx.lifecycle:lifecycle-service:2.1.0 -> 2.8.7
|    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (*)
|    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-common:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-common-java8:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata-ktx:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-process:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-runtime-compose:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-runtime-ktx:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.8.7 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7 (c)
|    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7 (c)
|    |    \--- androidx.lifecycle:lifecycle-viewmodel:2.1.0 -> 2.8.7 (*)
|    +--- com.google.android.datatransport:transport-api:2.2.1
|    |    \--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    +--- com.google.android.datatransport:transport-backend-cct:2.3.3
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    +--- com.google.android.datatransport:transport-api:2.2.1 (*)
|    |    +--- com.google.android.datatransport:transport-runtime:2.2.5 -> 2.2.6
|    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    |    +--- com.google.android.datatransport:transport-api:2.2.1 (*)
|    |    |    \--- javax.inject:javax.inject:1
|    |    +--- com.google.firebase:firebase-encoders:16.1.0
|    |    |    \--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    \--- com.google.firebase:firebase-encoders-json:17.1.0
|    |         +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |         \--- com.google.firebase:firebase-encoders:16.1.0 (*)
|    +--- com.google.android.datatransport:transport-runtime:2.2.6 (*)
|    +--- com.google.android.gms:play-services-base:18.5.0
|    |    +--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|    |    +--- androidx.core:core:1.2.0 -> 1.13.1 (*)
|    |    +--- androidx.fragment:fragment:1.0.0 -> 1.5.4 (*)
|    |    +--- com.google.android.gms:play-services-basement:18.4.0
|    |    |    +--- androidx.collection:collection:1.0.0 -> 1.5.0 (*)
|    |    |    +--- androidx.core:core:1.2.0 -> 1.13.1 (*)
|    |    |    \--- androidx.fragment:fragment:1.1.0 -> 1.5.4 (*)
|    |    \--- com.google.android.gms:play-services-tasks:18.2.0
|    |         \--- com.google.android.gms:play-services-basement:18.4.0 (*)
|    +--- com.google.android.gms:play-services-basement:18.4.0 (*)
|    +--- com.google.android.gms:play-services-tasks:18.2.0 (*)
|    +--- com.google.firebase:firebase-components:16.1.0
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
|    |    \--- com.google.firebase:firebase-annotations:16.0.0
|    +--- com.google.firebase:firebase-encoders:16.1.0 (*)
|    +--- com.google.firebase:firebase-encoders-json:17.1.0 (*)
|    +--- com.google.mlkit:common:18.11.0
|    |    +--- androidx.appcompat:appcompat:1.6.1 -> 1.7.0 (*)
|    |    +--- androidx.core:core:1.0.0 -> 1.13.1 (*)
|    |    +--- com.google.android.datatransport:transport-api:2.2.1 (*)
|    |    +--- com.google.android.datatransport:transport-backend-cct:2.3.3 (*)
|    |    +--- com.google.android.datatransport:transport-runtime:2.2.6 (*)
|    |    +--- com.google.android.gms:play-services-base:18.5.0 (*)
|    |    +--- com.google.android.gms:play-services-basement:18.4.0 (*)
|    |    +--- com.google.android.gms:play-services-tasks:18.2.0 (*)
|    |    +--- com.google.firebase:firebase-components:16.1.0 (*)
|    |    +--- com.google.firebase:firebase-encoders:16.1.0 (*)
|    |    \--- com.google.firebase:firebase-encoders-json:17.1.0 (*)
|    +--- com.google.mlkit:object-detection-common:18.0.0
|    |    +--- com.google.android.gms:play-services-basement:18.0.0 -> 18.4.0 (*)
|    |    +--- com.google.android.gms:play-services-tasks:18.0.1 -> 18.2.0 (*)
|    |    +--- com.google.android.odml:image:1.0.0-beta1
|    |    +--- com.google.mlkit:common:18.0.0 -> 18.11.0 (*)
|    |    +--- com.google.mlkit:vision-common:17.0.0 -> 17.3.0
|    |    |    +--- androidx.exifinterface:exifinterface:1.0.0 -> 1.4.2
|    |    |    |    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    |    |    |    \--- org.jspecify:jspecify:1.0.0
|    |    |    +--- com.google.android.datatransport:transport-api:2.2.1 (*)
|    |    |    +--- com.google.android.datatransport:transport-backend-cct:2.3.3 (*)
|    |    |    +--- com.google.android.datatransport:transport-runtime:2.2.6 (*)
|    |    |    +--- com.google.android.gms:play-services-base:18.1.0 -> 18.5.0 (*)
|    |    |    +--- com.google.android.gms:play-services-basement:18.1.0 -> 18.4.0 (*)
|    |    |    +--- com.google.android.gms:play-services-tasks:18.0.2 -> 18.2.0 (*)
|    |    |    +--- com.google.android.odml:image:1.0.0-beta1
|    |    |    +--- com.google.firebase:firebase-components:16.1.0 (*)
|    |    |    +--- com.google.firebase:firebase-encoders:16.1.0 (*)
|    |    |    +--- com.google.firebase:firebase-encoders-json:17.1.0 (*)
|    |    |    \--- com.google.mlkit:common:18.6.0 -> 18.11.0 (*)
|    |    +--- com.google.mlkit:vision-interfaces:16.0.0
|    |    |    +--- com.google.android.gms:play-services-basement:18.0.0 -> 18.4.0 (*)
|    |    |    \--- com.google.android.gms:play-services-tasks:18.0.1 -> 18.2.0 (*)
|    |    \--- com.squareup.okhttp3:okhttp:3.0.0 -> 4.12.0 (*)
|    +--- com.google.mlkit:vision-common:17.3.0 (*)
|    +--- com.google.mlkit:vision-internal-vkp:18.2.3
|    |    +--- com.google.android.datatransport:transport-api:2.2.1 (*)
|    |    +--- com.google.android.datatransport:transport-backend-cct:2.3.3 (*)
|    |    +--- com.google.android.datatransport:transport-runtime:2.2.6 (*)
|    |    +--- com.google.android.gms:play-services-base:18.5.0 (*)
|    |    +--- com.google.android.gms:play-services-basement:18.4.0 (*)
|    |    +--- com.google.android.gms:play-services-tasks:18.2.0 (*)
|    |    +--- com.google.firebase:firebase-components:16.1.0 (*)
|    |    +--- com.google.firebase:firebase-encoders:16.1.0 (*)
|    |    +--- com.google.firebase:firebase-encoders-json:17.1.0 (*)
|    |    +--- com.google.mlkit:common:18.11.0 (*)
|    |    +--- com.google.mlkit:vision-common:17.3.0 (*)
|    |    \--- com.squareup.okhttp3:okhttp:3.0.0 -> 4.12.0 (*)
|    \--- com.squareup.okhttp3:okhttp:3.0.0 -> 4.12.0 (*)
+--- androidx.camera:camera-core:1.6.2
|    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|    +--- androidx.arch.core:core-common:2.1.0 -> 2.2.0 (*)
|    +--- androidx.concurrent:concurrent-futures:1.1.0 (*)
|    +--- androidx.concurrent:concurrent-futures-ktx:1.1.0
|    |    +--- androidx.concurrent:concurrent-futures:1.1.0 (*)
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.3.71 -> 2.2.20 (*)
|    |    \--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.4 -> 1.9.0 (*)
|    +--- androidx.core:core:1.1.0 -> 1.13.1 (*)
|    +--- androidx.core:core-backported-fixes:1.0.0
|    |    +--- androidx.annotation:annotation:1.7.0 -> 1.9.1 (*)
|    |    \--- org.jetbrains.kotlin:kotlin-stdlib:2.0.21 -> 2.2.20 (*)
|    +--- androidx.exifinterface:exifinterface:1.4.2 (*)
|    +--- androidx.lifecycle:lifecycle-common:2.1.0 -> 2.8.7 (*)
|    +--- androidx.lifecycle:lifecycle-livedata:2.1.0 -> 2.8.7 (*)
|    +--- androidx.lifecycle:lifecycle-livedata-core:2.3.1 -> 2.8.7 (*)
|    +--- androidx.tracing:tracing:1.3.0 (*)
|    +--- com.google.auto.value:auto-value-annotations:1.6.3
|    +--- com.google.guava:listenablefuture:1.0 -> 9999.0-empty-to-avoid-conflict-with-guava
|    +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 (*)
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0 (*)
|    +--- org.jspecify:jspecify:1.0.0
|    +--- androidx.camera:camera-camera2:1.6.2 (c)
|    +--- androidx.camera:camera-lifecycle:1.6.2 (c)
|    +--- androidx.camera:camera-view:1.6.2 (c)
|    +--- androidx.camera:camera-camera2-pipe:1.6.2 (c)
|    \--- androidx.camera:camera-video:1.6.2 (c)
+--- androidx.camera:camera-camera2:1.6.2
|    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|    +--- androidx.arch.core:core-common:2.1.0 -> 2.2.0 (*)
|    +--- androidx.camera:camera-camera2-pipe:1.6.2
|    |    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    |    +--- androidx.camera.featurecombinationquery:featurecombinationquery:1.6.2
|    |    |    +--- androidx.annotation:annotation:1.7.0 -> 1.9.1 (*)
|    |    |    +--- com.google.auto.value:auto-value-annotations:1.6.3
|    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|    |    |    \--- org.jspecify:jspecify:1.0.0
|    |    +--- com.google.dagger:dagger:2.59
|    |    |    +--- jakarta.inject:jakarta.inject-api:2.0.1
|    |    |    +--- javax.inject:javax.inject:1
|    |    |    \--- org.jspecify:jspecify:1.0.0
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|    |    +--- org.jetbrains.kotlinx:atomicfu:0.28.0
|    |    |    \--- org.jetbrains.kotlinx:atomicfu-jvm:0.28.0
|    |    |         \--- org.jetbrains.kotlin:kotlin-stdlib:{prefer 2.1.0} -> 2.2.20 (*)
|    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 (*)
|    |    +--- androidx.camera:camera-camera2:1.6.2 (c)
|    |    +--- androidx.camera:camera-core:1.6.2 (c)
|    |    +--- androidx.camera:camera-lifecycle:1.6.2 (c)
|    |    +--- androidx.camera:camera-video:1.6.2 (c)
|    |    \--- androidx.camera:camera-view:1.6.2 (c)
|    +--- androidx.camera:camera-core:1.6.2 (*)
|    +--- androidx.concurrent:concurrent-futures-ktx:1.1.0 (*)
|    +--- androidx.core:core:1.3.2 -> 1.13.1 (*)
|    +--- androidx.core:core-backported-fixes:1.0.0 (*)
|    +--- androidx.lifecycle:lifecycle-livedata-core:2.1.0 -> 2.8.7 (*)
|    +--- com.google.dagger:dagger:2.59 (*)
|    +--- com.google.guava:listenablefuture:1.0 -> 9999.0-empty-to-avoid-conflict-with-guava
|    +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|    +--- org.jetbrains.kotlinx:atomicfu:0.28.0 (*)
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 (*)
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0 (*)
|    +--- org.jspecify:jspecify:1.0.0
|    +--- androidx.camera:camera-camera2-pipe:1.6.2 (c)
|    +--- androidx.camera:camera-core:1.6.2 (c)
|    +--- androidx.camera:camera-lifecycle:1.6.2 (c)
|    +--- androidx.camera:camera-view:1.6.2 (c)
|    \--- androidx.camera:camera-video:1.6.2 (c)
+--- androidx.camera:camera-lifecycle:1.6.2
|    +--- androidx.annotation:annotation:1.7.0 -> 1.9.1 (*)
|    +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|    +--- androidx.arch.core:core-common:2.2.0 (*)
|    +--- androidx.camera:camera-core:1.6.2 (*)
|    +--- androidx.concurrent:concurrent-futures-ktx:1.1.0 (*)
|    +--- androidx.core:core:1.1.0 -> 1.13.1 (*)
|    +--- androidx.lifecycle:lifecycle-common:2.1.0 -> 2.8.7 (*)
|    +--- androidx.tracing:tracing-ktx:1.2.0 -> 1.3.0
|    |    +--- androidx.tracing:tracing:1.3.0 (*)
|    |    \--- androidx.tracing:tracing:1.3.0 (c)
|    +--- com.google.auto.value:auto-value-annotations:1.6.3
|    +--- com.google.guava:listenablefuture:1.0 -> 9999.0-empty-to-avoid-conflict-with-guava
|    +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 (*)
|    +--- org.jspecify:jspecify:1.0.0
|    +--- androidx.camera:camera-camera2:1.6.2 (c)
|    +--- androidx.camera:camera-camera2-pipe:1.6.2 (c)
|    +--- androidx.camera:camera-core:1.6.2 (c)
|    +--- androidx.camera:camera-view:1.6.2 (c)
|    \--- androidx.camera:camera-video:1.6.2 (c)
+--- androidx.camera:camera-view:1.6.2
|    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|    +--- androidx.appcompat:appcompat:1.1.0 -> 1.7.0 (*)
|    +--- androidx.arch.core:core-common:2.2.0 (*)
|    +--- androidx.camera:camera-core:1.6.2 (*)
|    +--- androidx.camera:camera-lifecycle:1.6.2 (*)
|    +--- androidx.camera:camera-video:1.6.2
|    |    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    |    +--- androidx.annotation:annotation-experimental:1.4.1 -> 1.5.0 (*)
|    |    +--- androidx.arch.core:core-common:2.2.0 (*)
|    |    +--- androidx.camera:camera-core:1.6.2 (*)
|    |    +--- androidx.concurrent:concurrent-futures:1.1.0 (*)
|    |    +--- androidx.concurrent:concurrent-futures-ktx:1.1.0 (*)
|    |    +--- androidx.core:core:1.1.0 -> 1.13.1 (*)
|    |    +--- androidx.media3:media3-container:1.9.0
|    |    |    +--- androidx.annotation:annotation:1.6.0 -> 1.9.1 (*)
|    |    |    \--- androidx.media3:media3-common:1.9.0
|    |    |         +--- androidx.annotation:annotation:1.6.0 -> 1.9.1 (*)
|    |    |         +--- org.jetbrains.kotlin:kotlin-bom:1.8.0 -> 1.8.22 (*)
|    |    |         +--- com.google.guava:guava:33.3.1-android
|    |    |         |    +--- com.google.guava:failureaccess:1.0.2
|    |    |         |    +--- com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava
|    |    |         |    +--- com.google.code.findbugs:jsr305:3.0.2
|    |    |         |    +--- org.checkerframework:checker-qual:3.43.0
|    |    |         |    +--- com.google.errorprone:error_prone_annotations:2.28.0
|    |    |         |    \--- com.google.j2objc:j2objc-annotations:3.0.0
|    |    |         +--- androidx.annotation:annotation-experimental:1.3.1 -> 1.5.0 (*)
|    |    |         +--- androidx.media3:media3-container:1.9.0 (c)
|    |    |         \--- androidx.media3:media3-muxer:1.9.0 (c)
|    |    +--- androidx.media3:media3-muxer:1.9.0
|    |    |    +--- androidx.media3:media3-container:1.9.0 (*)
|    |    |    +--- androidx.annotation:annotation:1.6.0 -> 1.9.1 (*)
|    |    |    \--- androidx.media3:media3-common:1.9.0 (*)
|    |    +--- com.google.auto.value:auto-value-annotations:1.6.3
|    |    +--- com.google.guava:guava:32.0.1-android -> 33.3.1-android (*)
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 (*)
|    |    +--- org.jspecify:jspecify:1.0.0
|    |    +--- androidx.camera:camera-camera2:1.6.2 (c)
|    |    +--- androidx.camera:camera-camera2-pipe:1.6.2 (c)
|    |    +--- androidx.camera:camera-core:1.6.2 (c)
|    |    +--- androidx.camera:camera-lifecycle:1.6.2 (c)
|    |    \--- androidx.camera:camera-view:1.6.2 (c)
|    +--- androidx.camera.viewfinder:viewfinder-core:1.5.1
|    |    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    |    +--- androidx.core:core:1.7.0 -> 1.13.1 (*)
|    |    +--- androidx.tracing:tracing-ktx:1.2.0 -> 1.3.0 (*)
|    |    +--- com.google.auto.value:auto-value-annotations:1.6.3
|    |    +--- org.jetbrains.kotlinx:atomicfu:0.27.0 -> 0.28.0 (*)
|    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1 -> 1.9.0 (*)
|    |    \--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1 -> 1.9.0 (*)
|    +--- androidx.concurrent:concurrent-futures:1.1.0 (*)
|    +--- androidx.core:core:1.3.2 -> 1.13.1 (*)
|    +--- androidx.fragment:fragment:1.1.0 -> 1.5.4 (*)
|    +--- androidx.lifecycle:lifecycle-common:2.0.0 -> 2.8.7 (*)
|    +--- androidx.lifecycle:lifecycle-livedata:2.3.1 -> 2.8.7 (*)
|    +--- androidx.lifecycle:lifecycle-livedata-core:2.3.1 -> 2.8.7 (*)
|    +--- com.google.auto.value:auto-value-annotations:1.6.3
|    +--- com.google.guava:listenablefuture:1.0 -> 9999.0-empty-to-avoid-conflict-with-guava
|    +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|    +--- org.jspecify:jspecify:1.0.0
|    +--- androidx.camera:camera-camera2:1.6.2 (c)
|    +--- androidx.camera:camera-camera2-pipe:1.6.2 (c)
|    +--- androidx.camera:camera-core:1.6.2 (c)
|    +--- androidx.camera:camera-lifecycle:1.6.2 (c)
|    \--- androidx.camera:camera-video:1.6.2 (c)
+--- org.osmdroid:osmdroid-android:6.1.20
+--- net.java.dev.jna:jna:5.13.0
+--- com.alphacephei:vosk-android:0.3.75
+--- androidx.room:room-runtime:2.8.4
|    \--- androidx.room:room-runtime-android:2.8.4
|         +--- androidx.annotation:annotation:1.9.1 (*)
|         +--- androidx.annotation:annotation-experimental:1.5.0 (*)
|         +--- androidx.arch.core:core-runtime:2.2.0 (*)
|         +--- androidx.collection:collection:1.5.0 (*)
|         +--- androidx.room:room-common:2.8.4
|         |    \--- androidx.room:room-common-jvm:2.8.4
|         |         +--- androidx.annotation:annotation:1.9.1 (*)
|         |         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         |         +--- androidx.room:room-ktx:2.8.4 (c)
|         |         +--- androidx.room:room-runtime:2.8.4 (c)
|         |         \--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (c)
|         +--- androidx.sqlite:sqlite:2.6.2 -> 2.7.0
|         |    \--- androidx.sqlite:sqlite-android:2.7.0
|         |         +--- androidx.annotation:annotation:1.9.1 (*)
|         |         +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|         |         \--- androidx.sqlite:sqlite-framework:2.7.0 (c)
|         +--- androidx.sqlite:sqlite-framework:2.6.2 -> 2.7.0
|         |    \--- androidx.sqlite:sqlite-framework-android:2.7.0
|         |         +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|         |         +--- androidx.sqlite:sqlite:2.7.0 (*)
|         |         +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (*)
|         |         \--- androidx.sqlite:sqlite:2.7.0 (c)
|         +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|         +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1 -> 1.9.0 (*)
|         +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1 -> 1.9.0 (*)
|         +--- org.jspecify:jspecify:1.0.0
|         +--- androidx.room:room-common:2.8.4 (c)
|         +--- androidx.room:room-ktx:2.8.4 (c)
|         \--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (c)
+--- androidx.room:room-ktx:2.8.4
|    +--- androidx.room:room-common:2.8.4 (*)
|    +--- androidx.room:room-runtime:2.8.4 (*)
|    +--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.20 (*)
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1 -> 1.9.0 (*)
|    +--- androidx.room:room-common:2.8.4 (c)
|    +--- androidx.room:room-runtime:2.8.4 (c)
|    \--- org.jetbrains.kotlin:kotlin-stdlib:2.1.20 -> 2.2.20 (c)
+--- androidx.sqlite:sqlite:2.7.0 (*)
+--- net.zetetic:sqlcipher-android:4.20.0
+--- androidx.work:work-runtime-ktx:2.9.1
|    +--- androidx.work:work-runtime:2.9.1
|    |    +--- androidx.annotation:annotation-experimental:1.0.0 -> 1.5.0 (*)
|    |    +--- androidx.core:core:1.9.0 -> 1.13.1 (*)
|    |    +--- androidx.lifecycle:lifecycle-livedata:2.5.1 -> 2.8.7 (*)
|    |    +--- androidx.lifecycle:lifecycle-service:2.5.1 -> 2.8.7 (*)
|    |    +--- androidx.room:room-ktx:2.5.0 -> 2.8.4 (*)
|    |    +--- androidx.sqlite:sqlite-framework:2.3.0 -> 2.7.0 (*)
|    |    +--- androidx.startup:startup-runtime:1.1.1 (*)
|    |    +--- com.google.guava:listenablefuture:1.0 -> 9999.0-empty-to-avoid-conflict-with-guava
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.20 (*)
|    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1 -> 1.9.0 (*)
|    |    \--- androidx.work:work-runtime-ktx:2.9.1 (c)
|    \--- androidx.work:work-runtime:2.9.1 (c)
\--- androidx.security:security-crypto:1.1.0-alpha06
     +--- androidx.annotation:annotation:1.1.0 -> 1.9.1 (*)
     +--- androidx.annotation:annotation:1.2.0 -> 1.9.1 (*)
     +--- androidx.collection:collection:1.1.0 -> 1.5.0 (*)
     \--- com.google.crypto.tink:tink-android:1.8.0
          \--- com.google.code.gson:gson:2.8.9 -> 2.10.1

(c) - A dependency constraint, not a dependency. The dependency affected by the constraint occurs elsewhere in the tree.
(*) - Indicates repeated occurrences of a transitive dependency subtree. Gradle expands transitive dependency subtrees only once per project; repeat occurrences only display the root of the subtree, followed by this annotation.

A web-based, searchable dependency report is available by adding the --scan option.

BUILD SUCCESSFUL in 10s
1 actionable task: 1 executed
```
</details>
