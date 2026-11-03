# More Directives implementation and acceptance evidence

This extends the existing Kotlin Views/ViewBinding Android app (`com.jarvis.assistant`, target SDK 36). It does not replace it with Compose or change existing provider/service architecture.

## Implementation checklist

- [x] Canonical nine-tile registry shared by the main deck and feature hub; native cards and dedicated activities.
- [x] Voice Mode: native audio-reactive orb, immersive window, shared live-service state/transcripts, interrupt, mute and stop controls. Uses the existing Gemini Live microphone/service pipeline.
- [x] Neural Lens: opt-in CameraX latest-frame analysis and ML Kit streaming object tracking/classification; bounded scan frequency; camera/analyzer lifecycle cleanup. Existing still capture, gallery and Gemini analysis retained.
- [x] AMOLED Map: black canvas renders actual OSRM route, GPS and destination vectors; cached geometry for gestures; optional existing OSM raster view. Raster networking/animations disabled in vector mode.
- [x] Aura Control: existing appearance controls plus real battery/memory telemetry and app-level scan/background-review power policy.
- [x] Notifications: RecyclerView diffing, search/unread filtering, opt-in encrypted archive, credential filtering and exclusion of ongoing/group-summary/own-app noise for new archived entries.
- [x] Home Theme: persisted System / Light / Dark cycle using the existing theme manager and AppCompat configuration handling.
- [x] Mission Mode: explicit UI Agent / Core LLM selection with enforced tool allowlists, route persisted through approval/resume, exact-action approval, pause/resume, cancellation, unknown-outcome acknowledgement and stored history. UI inspection excludes Jarvis private screens and protected/password contexts.
- [x] My Ideas: encrypted Room/SQLCipher local records, explicit save/delete, on-device-only dictation when available, separate AI refinements, paged list. Optional WorkManager review remains OFF until consent; only ideas are read, not the personal-memory table.
- [x] English Teacher: four persona prompts, six levels, six goals, actual text/audio assessment, local encrypted profile/progress, streak/overall/fluency/pronunciation metrics and offline reply speech. Text never generates pronunciation scores.
- [x] Room 6->7 additive migration; existing memory/mission/notification/sync tables retained.

## Verification

Host migration check: `python3 scripts/verify-directive-migration.py` (from Part 7). It executes the migration SQL from MemoryDatabase, compares columns/indexes against the exported Room 7 schema, and verifies seeded rows survive in all six previous tables. This checks SQLite schema/data preservation, not Android Keystore/SQLCipher operation on a phone.

New JVM tests cover trainer/level/goal choices, streak dates, empty/invalid assessment handling, audio-only pronunciation, route persistence and fail-closed tool rejection, consequential UI click approval, notification noise filtering, theme cycling and power policy.

Local `:app:testDebugUnitTest :app:lintDebug` completed successfully: 415 tests, 0 failures, 0 errors, 1 skipped (the pre-existing host/Termux environment detection test). Lint reported 0 errors and 1,194 warnings; localization and deprecated API warnings remain. Some final UI/error-handling edits followed the local run, so GitHub CI verifies the committed source independently.

GitHub CI #293 verifies implementation commit `b2c439f1952e15d727134c53d85e9adeb006afd2`: unit tests, migration preservation and lint passed. APK build and permanent release publication also passed. All CI stages completed successfully. No device/emulator results are claimed.

## Remaining physical-device acceptance

- [ ] Android 16 install and existing encrypted database upgrade using the SAME signing key; reboot and offline reopening of memories/ideas/progress.
- [ ] TalkBack, large font, narrow displays, display cutouts, gesture navigation, all nine tile targets and global theme recreation.
- [ ] Measure cold/warm opening and frame timing with a profiler. Zero latency and zero stutter are not established by compilation or unit tests.
- [ ] Gemini Live connection, audio reactivity, speech interruption/barge-in, mic denial, background/foreground and concurrent microphone use.
- [ ] ML Kit first-use model download, offline subsequent scans, camera denial, rotation/background/gallery transitions and thermal/battery behavior.
- [ ] Real GPS/routes and raster/vector toggle; test route service failures and map gestures.
- [ ] Accessibility-supported third-party app mission with explicit approval; cancellation, process death and uncertain-result recovery. Booking support depends on the real provider/app; no reservation is reported merely because a reminder was created.
- [ ] Idea save during provider outage; device-only dictation availability; opt-in review, cancellation and system Battery Saver.
- [ ] Four teachers with live provider credentials; actual audio samples, interruptions, offline TTS initialization and longitudinal score/streak display.

## Deliberate boundaries

- ML Kit gives tracked objects and broad supported categories, not arbitrary detailed object identity. Its unbundled model may need a first-use download.
- The black map is a route/GPS vector guide, not a complete downloadable vector street-map database. OSM raster remains optional. Destination lookup and routing need their existing remote services.
- Theme changes may recreate activities through Android/AppCompat. UI and network timing require measurement; no "zero redraw" guarantee is made.
- Power controls govern Jarvis scan frequency and optional idea review, not Android CPU scheduling or other applications.
- English scores are provider estimates, not certified exam results. All submitted learning text/audio is explicitly sent to the configured Gemini provider; stored progress stays local. Temporary recordings are removed after submission/cancellation.
- Idea review is inexact periodic WorkManager work, at most three unreviewed ideas per execution, subject to network/battery constraints. An already transmitted request cannot be recalled by disabling the toggle.
- Device-only dictation is offered only when Android exposes an on-device recognizer; there is no silent cloud fallback.
- Local data is primary. New tables are not automatically synchronized remotely and no remote AI owns the stored originals.

## Modified files in implementation commit

Source commit: `b2c439f1952e15d727134c53d85e9adeb006afd2`.

- `.github/workflows/android.yml`
- `Part 7/app/build.gradle.kts`
- `Part 7/app/schemas/com.jarvis.assistant.data.memory.MemoryDatabase/7.json`
- `Part 7/app/src/main/AndroidManifest.xml`
- `Part 7/app/src/main/java/com/jarvis/assistant/JarvisApp.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/agent/LlmPlanner.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/mission/MissionEngine.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/mission/MissionExecutionRoute.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/mission/ToolRegistry.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/notification/JarvisNotificationListenerService.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/automation/notification/NotificationArchivePolicy.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/directives/DirectiveAiClient.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/directives/DirectiveData.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/directives/IdeaReviewWorker.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/directives/LearningPolicy.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/memory/MemoryDatabase.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/security/sandbox/RiskPolicy.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/DirectiveActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/DirectiveGrid.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/EnglishTeacherActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/IdeasActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/LocalDictation.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/MissionDeskActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/VoiceModeActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/home/FeatureHubActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/map/AmoledMapActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/map/TacticalVectorView.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/notification/NotificationHistoryActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/AuraControlActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/ui/vision/NeuralLensActivity.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/util/DirectivePowerPolicy.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/util/HomeThemePolicy.kt`
- `Part 7/app/src/main/java/com/jarvis/assistant/util/ThemeManager.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/mission/MissionRoutePolicyTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/automation/notification/NotificationArchivePolicyTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/data/directives/LearningPolicyTest.kt`
- `Part 7/app/src/test/java/com/jarvis/assistant/util/DirectivePoliciesTest.kt`
- `Part 7/scripts/verify-directive-migration.py`
- `docs/MORE-DIRECTIVES-V4.md`

## Published APK and provenance

- CI: https://github.com/levinhocall/Jarvis-os/actions/runs/41628491276 (success).
- Release: https://github.com/levinhocall/Jarvis-os/releases/tag/debug-ci-293
- APK: https://github.com/levinhocall/Jarvis-os/releases/download/debug-ci-293/app-debug.apk
- Downloaded APK size: 166,789,826 bytes.
- SHA-256: `4c6b8311e2080708144d4ce6e22b2e426520c87110946b586bf18b7774bf40b4`. Matches the release checksum and GitHub asset digest.
- `apksigner verify --verbose --print-certs`: passed, APK Signature Scheme v2.
- Package: `com.jarvis.assistant`; version 1.8.7 (143); min SDK 26; target/compile SDK 36.
- Development certificate SHA-256: `2a19f2c53ba6531914a873fb03bc09531b0431aa28c0229c02eb2aecb66232c1`.

The CI runner generates a debug signing key, so this certificate differs from CI #292. Android will not install it over an APK signed with another key. Preserve/export local data before considering uninstalling; uninstalling erases app-private memory and encryption keys. Stable upgrade signing remains a release-engineering prerequisite; no production signing secret was invented or committed.

The larger universal debug APK includes ML Kit object-detection dependencies; release-size/ABI optimization has not been performed. Live provider credentials, a physical phone, and performance profiling remain necessary for the acceptance checklist above.
