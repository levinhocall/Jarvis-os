# Aura and crash recovery — verification record

## Reproduced crash and repair
Android 16 emulator run 41692523996 reproduced a fatal main-thread exception from DeferredSqlCipherOpenHelperFactory.getDelegate → RoomDatabase.inTransaction → DBUtil.performSuspending. Room checks transaction state on the collector thread against an already-open database. The custom guard incorrectly rejected cached helper access. Commit 735fbbf returns the cached helper before enforcing the cold-open background-thread guard. Encryption and local data are preserved; no destructive migration or main-thread queries were enabled.

Run 41694643158 passed encryptedRoomAndReportedScreensOpen after that fix. Two pre-existing encryption fixtures failed because they used the instrumentation APK context; this change corrects them to the target app context and retains unique fixture files/aliases. Expanded Android 16 runtime verification subsequently passed all 20 tests on source e6bca1e (run 41776063802).

## Implemented
- Premium audio-reactive native voice orb, lighter WebGL home orb, opt-in Vosk wake screen, lazy/atomic offline model extraction, native recognizer cleanup and bounded readiness, transient multicolor wake border. Existing bundled speech model is about 68 MiB; speaker model is optional at runtime. No extra model download is required.
- Aura controls: orb quick switch, four launcher aliases, conversation language selector (50+ choices, provider support varies), existing themes and haptics, noise suppression controls, silent execution, accessibility settings guidance and opt-in red tap indicator.
- Avatar studio: 19 shapes × 6 accessories/styles = 114 combinations, size, edge and visibility controls; double tap cancels active tasks.
- Sixteen original abstract cyberpunk video loops, user-owned video import, system live wallpaper picker, optional main-deck video background. These are original abstract assets, not licensed anime clips. Lock-screen wallpaper support depends on the launcher/OEM.
- Opt-in background UI mission delegation while live voice continues, using existing mission engine, cancellation and ownership checks.
- Opt-in autonomous WorkManager review with encrypted local decision logs (Room v8 additive migration), quiet hours 23:00–07:00 in phone timezone, battery/recent-notification counts, bounded model decisions and optional notifications. Scheduling is best effort, not exact 30-minute delivery. It does not autonomously send messages or make purchases.
- Real Gemini grounded-search and image-generation calls gated by settings, image validation and MediaStore save. WhatsApp sharing opens its contact picker for user confirmation; automatic contact resolution/delivery is not claimed.
- Storage failure reporting, notification action layout fixes and system-bar insets on affected screens.

All new background/cloud features are opt-in. Phone-local encrypted memory remains primary. Remote provider access is not needed to retain local memories.

## Executed checks before final CI
- Local Gradle unit tests: 418 tests, 0 failures, 0 errors, 1 environment-dependent skip.
- Local Android lint and Android instrumentation Kotlin compilation: successful; 0 lint errors, 1,244 warnings (warning backlog remains).
- Host migration 6→7: schema matches exported Room schema; six existing seeded tables preserved.
- Host migration 7→8: schema matches exported Room schema; nine existing seeded tables preserved.
- Media and script checks: 16 H.264 silent MP4 files inspected; orb/avatar JavaScript syntax checked.
- ARM64 Vosk and JNA ELF load alignment inspected for 16 KiB support.

## Remaining verification and limitations
Android 16 emulator: 20 tests, 0 failures/errors/skips. This includes all 12 selected screen launches/recreation, four launcher icons, Vosk model loading/silent input, native SQLCipher/key wrapping, memory migrations/transactions, conversation persistence and mission replay protection. Final APK CI #299 passed unit tests, both migrations, lint, assembly and permanent release publication. Physical Mi/HyperOS accessibility activation/reconnection, microphone wake detection with speech, background battery restrictions, audio echo/noise behavior, wallpaper/launcher behavior, overlay gestures and live Gemini/WhatsApp provider interactions require device verification. No zero-latency or perfect background-audio rejection claim is made. Cloud features require configured compatible provider access. Decision review uses notification counts, not unrestricted call/SMS databases. Existing CI debug signing keys vary by runner: a differently signed APK cannot update an existing installation. Do not uninstall without a verified local-memory backup; uninstall erases app data. Stable private CI signing remains an owner configuration prerequisite.

## Modified files
- `.github/workflows/android-runtime.yml`
- `.github/workflows/android.yml`
- `Part 7/app/build.gradle.kts`
- `Part 7/app/src/androidTest/java/com/jarvis/assistant/data/memory/DatabaseKeyStoreInstrumentedTest.kt`
- `Part 7/app/src/androidTest/java/com/jarvis/assistant/data/memory/RoomMigrationInstrumentedTest.kt`
- `Part 7/app/src/androidTest/java/com/jarvis/assistant/data/memory/SqlCipherRuntimeInstrumentedTest.kt`
- `Part 7/app/src/androidTest/java/com/jarvis/assistant/ui/LaunchSmokeInstrumentedTest.kt`
- `Part 7/app/src/main/AndroidManifest.xml`
- `Part 7/app/src/main/assets/orb.html`
- `Part 7/app/src/main/java/com/jarvis/assistant/JarvisApp.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/audio/VoskModelManager.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/agent/CancellableCouncilRequest.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/mission/MissionEngine.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/notification/JarvisNotificationListenerService.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/directives/DirectiveData.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/memory/MemoryDatabase.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/agent/TaskProgressOverlay.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/DirectiveActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/VoiceModeActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/memory/MemoryActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/notification/NotificationHistoryActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/AuraControlActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/wallpaper/VideoWallpaperActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt`
- `Part 7/scripts/verify-directive-migration.py`
- `Part 7/app/schemas/com.jarvis.assistant.data.memory.MemoryDatabase/8.json`
- `Part 7/app/src/main/assets/avatar-studio.html`
- `Part 7/app/src/main/java/com/jarvis/assistant/core/diagnostics/StorageHealth.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/AppIconSwitcher.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/AuraCloudClient.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/AuraControls.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/AuraOptions.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/AutonomyPolicy.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/AutonomyWorker.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/AvatarStudioActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/DecisionLogsActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/MediaStudioActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/aura/TapIndicator.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/PremiumOrbView.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/WakeGlow.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/WakeWordActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/wallpaper/InAppWallpaper.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/wallpaper/WallpaperCatalog.kt`
- `Part 7/app/src/main/res/drawable/ic_aura_crimson.xml`
- `Part 7/app/src/main/res/drawable/ic_aura_midnight.xml`
- `Part 7/app/src/main/res/drawable/ic_aura_red.xml`
- `Part 7/app/src/main/res/raw/aura_aurora.mp4`
- `Part 7/app/src/main/res/raw/aura_cobalt_dreamer.mp4`
- `Part 7/app/src/main/res/raw/aura_crimson_tide.mp4`
- `Part 7/app/src/main/res/raw/aura_ember.mp4`
- `Part 7/app/src/main/res/raw/aura_fallen_angel.mp4`
- `Part 7/app/src/main/res/raw/aura_frost_muse.mp4`
- `Part 7/app/src/main/res/raw/aura_frozen_tear.mp4`
- `Part 7/app/src/main/res/raw/aura_golden_hour.mp4`
- `Part 7/app/src/main/res/raw/aura_inferno.mp4`
- `Part 7/app/src/main/res/raw/aura_midnight_lace.mp4`
- `Part 7/app/src/main/res/raw/aura_neon_drift.mp4`
- `Part 7/app/src/main/res/raw/aura_red_dusk.mp4`
- `Part 7/app/src/main/res/raw/aura_sakura_blush.mp4`
- `Part 7/app/src/main/res/raw/aura_shadow_call.mp4`
- `Part 7/app/src/main/res/raw/aura_skyline.mp4`
- `Part 7/app/src/main/res/raw/aura_violet_gaze.mp4`
- `Part 7/app/src/test/java/com/jarvis/assistant/ui/aura/AutonomyPolicyTest.kt`
- `Part 7/scripts/generate-aura-wallpapers.py`

## Delivery commits
- `735fbbf`: reproduced Room cached-helper crash repair.
- `0459306`: Aura implementation, local persistence migration and expanded tests.
- `43b253e`: release-tag lookup exit-status correction.
- `e6bca1e`: wake permission lifecycle, visible failure states and additional screen coverage.
- `119a5e2`: remove private voice-tool arguments from logs (only production difference from the Android runtime test snapshot).

Runtime suite: https://github.com/levinhocall/Jarvis-os/actions/runs/41776063802
Final-source APK CI: https://github.com/levinhocall/Jarvis-os/actions/runs/41776590790

## Published APK verification
[Download JARVIS 1.9.0 (144)](https://github.com/levinhocall/Jarvis-os/releases/download/debug-ci-299/app-debug.apk) · [Permanent release](https://github.com/levinhocall/Jarvis-os/releases/tag/debug-ci-299).

The actual CI asset was downloaded and verified with sha256sum and apksigner. Size: 167,637,800 bytes. SHA-256: `e9ea56dd67e5c8093a54b31df40dc289f6d258c95457872bc0abc2d4a9bbd85e`. Package `com.jarvis.assistant`, target SDK 36, version 1.9.0 (144). Debug certificate SHA-256: `e4aadb7a67463f409d75b43f8d187b256b24125c1c307a2cf2f3437d74166115`. This differs from CI #293; do not uninstall the previous installation without a verified backup.

[Machine-readable evidence](AURA-CRASH-EVIDENCE.json) · [Build/download without ChatGPT](CI-APK-DOWNLOAD.md). No physical handset or paid-provider end-to-end verification was performed.
