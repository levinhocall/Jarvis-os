# Fluid Plasma Orb: final local verification

Date: 2026-11-13 UTC. Branch: `phaseA/stability-accessibility`.
Baseline: `5f285af`. Tested source revision: `fbdc388058970c76dedf13ce72a904b06648356b`.
A subsequent documentation-only commit records this report; it does not change the APK.
All implementation commits are local. **No push, main merge, release publication or CI run.**

## Delivered behavior and boundaries

- Original cyan-blue fluid plasma renderer using the existing packaged Three.js and WebView,
  with a simpler native PremiumOrbView fallback. Existing eye/ring modes remain available.
- One immutable read-only service presentation snapshot and deterministic mapper; no new
  recorder, service, voice-session owner, database, dependency or privileged bridge.
- Existing mic PCM supplies a gated/smoothed display envelope. Accepted VAD speech is a
  separate fact. AudioPlayer observes accepted PCM frames against the playback clock;
  queued PCM alone cannot imply audible playback. Interruption/session generations reject
  obsolete observations. TTS without reliable PCM timing uses state-only ambient motion.
- XML/ViewBinding Voice Mode with status, start/end, mute, interrupt, settings and collapsible
  transcript. Home and Voice Mode observe the same service; Back keeps the session alive.
- Per-host visibility, disposal, reduced motion, adaptive/saver quality, local asset loading
  and renderer failure isolation. No process-wide WebView timer suspension.

Enable via **Aura Control → Orb rendering → Fluid Plasma**, or its quick-switch banner.
Existing installs keep the legacy renderer unless the user selects the new mode. Existing
appearance JSON keys and navigation are preserved. No stored data/schema/package changes.
Parts 1–6, .github, AndroidManifest.xml and the automation/accessibility production tree
have **no diff** from the baseline. Preceding stability/accessibility fixes remain present.

## Milestone status

| Milestone | Implemented and locally verified | Remaining gate |
| --- | --- | --- |
| M1 presentation | Mapper/store, session ordering, stale/conflicting facts and interruption tests | Real session event timing |
| M2 renderer | Shader compiled/rendered in desktop Chromium/SwiftShader, eight states, init/context failures | Android visual quality and native handoff |
| M3 microphone | Existing capture feed, gate/attack/release/clipping/mute tests; VAD thresholds unchanged | Physical mic/noise/permission behavior |
| M4 playback | Bounded timeline, partial writes, clock validation, flush/route/epoch and cleanup tests | AudioTrack routes, Bluetooth and acoustic clock validation |
| M5 screens | Both screens compiled against the same facts; responsive XML and native controls | Phone rotation, large text, TalkBack and navigation |
| M6 lifecycle | Per-host stop/resume, reduced motion, disposal, sibling-host independence; regression tests | Android lock/unlock, process death and Accessibility behavior |
| M7 delivery | JVM, lint, Android-test compilation, JavaScript checks, debug assembly and signature | Device performance/soak/install/upgrade acceptance |

This is a locally verified implementation and debug APK, **not full device acceptance**.

## Exact verification

Working directory: `Part 7/`. JDK 17.0.21, existing Gradle 8.11.1 wrapper, SDK 36.
Final command:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --offline
```

**PASS: BUILD SUCCESSFUL in 5m 34s; 71 actionable tasks, 20 executed, 51 up-to-date.**
The unit test task executed. Compilation is also covered by assembly; Android-test Kotlin
compilation was up-to-date from the preceding successful compilation. This was incremental,
not a clean build. Source/asset/test fingerprints (605 files) remained identical throughout
the final run. The previous APK built during an edit is superseded by the checksum below.

- JVM: **576 tests, 575 passed, one skipped, zero failures/errors**.
- Accessibility namespace: **36 tests passed**. Audio: **75 passed**. Network: **15 passed**.
  These are JVM regressions, not Android service/AudioRecord/AudioTrack execution.
- Skipped: `DevEnvironmentAndTermuxBridgeTest.detectEnvironment correctly identifies host
  dev environment`; its host-toolchain fixture assumption was unavailable.
- Lint: **zero new errors/warnings**, with the unchanged existing baseline filtering
  **1389 errors and two hints**; 32 baseline entries unmatched. This does not establish
  that the whole application is free of lint problems. Existing compiler warnings remain.
- Android instrumentation: sources compiled; **NOT RUN**. Includes preference preservation
  and real-WebView failure/fallback test sources, plus existing device suites.
- `node scripts/test-plasma-motion.cjs`: **PASS, 14 checks**.
- `node scripts/test-eye-behavior.cjs`: **PASS**, legacy behavior regression.
- `node scripts/test-plasma-webgl.cjs`: **PASS**. Actual desktop shader compilation/rendering,
  eight states, interruption, hidden/reduced-motion stop (including continuous audio packets),
  saver resume, sibling-host independence, context loss, initialization failure, legacy
  scene loading and geometry/program disposal. Browser runtime is a development dependency,
  not a new Android library. Native Canvas rendering is not established by these tests.
- `apksigner verify --verbose --print-certs`: **PASS**, one debug signer, v2 signature valid.
- `aapt2 dump badging`: `com.jarvis.assistant`, version **1.11.0 (149)**, min API **26**,
  target API **36**. Existing version values unchanged.
- `git diff --check`: **PASS**. Scope verification: only Part 7 files changed.

Local logs/reports (build output is intentionally not committed):
`work/plasma-delivery.log`, `work/plasma-source-freeze.json`,
`Part 7/app/build/test-results/testDebugUnitTest/`,
`Part 7/app/build/reports/lint-results-debug.txt`.
Earlier failures and fixes are recorded in [FLUID_PLASMA_IMPLEMENTATION.md](FLUID_PLASMA_IMPLEMENTATION.md).

## APK

Path: `Part 7/app/build/outputs/apk/debug/app-debug.apk`

Size: **177,169,883 bytes**.

SHA-256:

```text
b697a3c2ef9ab77e2b731a9036493ec0f4a5c67e81f79fc2a8dbac96b4cad1f7
```

Debug signing certificate SHA-256:

```text
3c14012da6e74f0ecf536c4c4bbd8047b803732b6c598cfc4a9bfd905053d3c6
```

APK install/upgrade and compatibility with the certificate of the user's installed APK
are **NOT TESTED**. Verify certificates before updating; preserve the existing app and
its data if they differ. This is a debug build, not an R8/release-signing verification.

## Unverified behavior, performance and limitations

No Android device/emulator was available. Actual Gemini/Vosk conversations, physical mic
input, speaker/headset/Bluetooth output, permission revocation, focus loss, phone network
switching, Android 16/HyperOS behavior, renderer recovery and native fallback visuals are
**NOT TESTED**. Accessibility screen reading/gestures/reconnection on a phone are also
**NOT TESTED**; unchanged production source plus JVM regressions do not prove those work.

100 start/stop cycles, lock/unlock, installation, existing-version upgrade, process death,
data-preservation smoke tests, a two-hour voice soak and 24-hour background test were
**NOT RUN**. No measured FPS, missed-frame, latency, CPU/GPU, memory/leak or battery result
is available. 60/30 Hz and p95 ≤80 ms remain **targets**, not results. Desktop SwiftShader
renders and configured observer intervals cannot substantiate those numbers.

Playback timestamps/head establish a software clock, not acoustic delivery at a Bluetooth
speaker. State-only TTS intentionally lacks syllable-synchronized amplitude. A failed host
keeps its native fallback until screen recreation; it does not retry/restart the voice
service. Existing default orb mode remains unchanged. The Canvas fallback is simpler than
WebGL, and its visual parity is not measured. [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md)
defines the outstanding measurement and device matrix.

[plasma-desktop-preview.png](plasma-desktop-preview.png) is an actual desktop render,
not an Android screenshot or a generated design mockup.

## Exact file changes from baseline to tested source

A = added; M = modified. This report itself is an additional documentation-only file.

- **M** `Part 7/app/src/androidTest/java/com/jarvis/assistant/ui/OrbAppearanceInstrumentedTest.kt`
- **A** `Part 7/app/src/androidTest/java/com/jarvis/assistant/ui/PlasmaOrbHostInstrumentedTest.kt`
- **M** `Part 7/app/src/main/assets/orb.html`
- **A** `Part 7/app/src/main/assets/plasma-orb.js`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt`
- **A** `Part 7/app/src/main/java/com/jarvis/assistant/audio/PcmEnvelopeFollower.kt`
- **A** `Part 7/app/src/main/java/com/jarvis/assistant/audio/PcmEnvelopeTimeline.kt`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/audio/VoiceSynthesisRouter.kt`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt`
- **A** `Part 7/app/src/main/java/com/jarvis/assistant/background/VoicePresentationStore.kt`
- **A** `Part 7/app/src/main/java/com/jarvis/assistant/data/model/VoicePresentationSnapshot.kt`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/PremiumOrbView.kt`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/VoiceModeActivity.kt`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/ui/directives/VortexOrbView.kt`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/ui/orb/OrbAppearance.kt`
- **A** `Part 7/app/src/main/java/com/jarvis/assistant/ui/orb/OrbPresentationLabels.kt`
- **A** `Part 7/app/src/main/java/com/jarvis/assistant/ui/orb/OrbPresentationMapper.kt`
- **A** `Part 7/app/src/main/java/com/jarvis/assistant/ui/orb/OrbTouchWebView.kt`
- **A** `Part 7/app/src/main/java/com/jarvis/assistant/ui/orb/OrbWebHost.kt`
- **M** `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/AuraControlActivity.kt`
- **A** `Part 7/app/src/main/res/layout/activity_voice_mode.xml`
- **M** `Part 7/app/src/main/res/layout/fragment_home.xml`
- **A** `Part 7/app/src/main/res/values/plasma_orb.xml`
- **A** `Part 7/app/src/test/java/com/jarvis/assistant/audio/PcmEnvelopeFollowerTest.kt`
- **A** `Part 7/app/src/test/java/com/jarvis/assistant/audio/PcmEnvelopeTimelineTest.kt`
- **A** `Part 7/app/src/test/java/com/jarvis/assistant/audio/PlaybackObservationLifecycleTest.kt`
- **A** `Part 7/app/src/test/java/com/jarvis/assistant/ui/orb/PlasmaPresentationTest.kt`
- **A** `Part 7/docs/orb/DEVICE_VALIDATION.md`
- **A** `Part 7/docs/orb/FLUID_PLASMA_IMPLEMENTATION.md`
- **A** `Part 7/docs/orb/plasma-desktop-preview.png`
- **A** `Part 7/scripts/test-plasma-motion.cjs`
- **A** `Part 7/scripts/test-plasma-webgl.cjs`

## Local implementation commits

- `35ca756`: immutable voice presentation contract and mapping tests.
- `047fa72`: plasma scene, shared host and native fallback foundation.
- `f55ac27`: observe existing microphone PCM without changing speech detection.
- `723cf73`: playback-aligned output envelope and generation guards.
- `8bb7509`: release tracks even if routing-listener cleanup fails.
- `fbdc388`: XML voice screens, shared presentation, lifecycle/power handling and regression coverage.
