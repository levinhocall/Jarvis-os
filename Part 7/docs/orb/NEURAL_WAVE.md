# Neural Wave implementation

Baseline main: 36e1dba503877ffa8b032ded3220730fbee4aa53. Work is confined to Part 7, on phaseA/neural-wave-orb; no push or main merge authorized for this task.

## Verified existing architecture

- `ui/home/HomeFragment.kt`: Home hosts `orb.html` through `ui/orb/OrbWebHost.kt`.
- `ui/directives/VoiceModeActivity.kt` and `res/layout/activity_voice_mode.xml`: existing XML/ViewBinding full-screen voice controls and transcripts. `VortexOrbView` uses the same host.
- `assets/orb.html`, `eye-behavior.js`: legacy eye scene. `plasma-orb.js`: alternate fluid mode with generation-aware smoothing, hidden/reduced-motion scheduling and context-failure signal.
- `data/model/VoicePresentationSnapshot.kt`, `ui/orb/OrbPresentationMapper.kt`: immutable real service facts; confirmed speech is distinct from RMS, actual playback and clock confidence gate output amplitude.
- `background/JarvisConversationService.kt`, `audio/AudioRecorder.kt`, `audio/AudioPlayer.kt`, `network/GeminiLiveWebSocket.kt`: existing capture, playback and session owners; this visual change adds no audio engine.
- `ui/directives/PremiumOrbView.kt`: native fallback; `ui/orb/OrbAppearance.kt` and existing `AppPreferences.orbEyeOptions`: retained settings storage.
- `automation/mission/MissionEngine.activeMission`: actual EXECUTING mission signal. Host observes only while visible; waiting/paused/planning are not represented as execution.

## Rendering and truthfulness

Neural Wave is an original local Three.js scene using the existing host, packaged library, animation lifecycle and error bridge. A bounded fragment shader draws flowing tilted mesh strands, halo, energy core and projector ellipse. A fixed GPU point buffer draws the spherical shell (up to2800 full-screen,1200 compact,480 saver before density scaling). No image/video loop, remote asset, additional renderer dependency or privileged bridge.

Default missing renderer preference selects Neural Wave. Explicit previous renderer preferences remain available through Aura Control. Home uses compact budget, Voice Mode full budget. Existing intensity/density/speed/glow/size/reduced-motion/power settings remain; neural palette and audio responsiveness use the same preferences JSON.

Central bars are a measured RMS/envelope visualization, not a reconstructed PCM waveform or frequency spectrum. Their height is zero-energy at silence and is never driven by randomized audio. Mic capture/mute/permission and playback timing are inherited from the existing mapper. Android TTS without PCM clock has state-only ambient movement with zero precise output envelope. Processing comes from pending response; no fake thinking timers. Interruption generations reject stale packets; output resets immediately when speaking stops even within one generation.

JavaScript tests use synthetic presentation inputs to verify renderer behavior; they do not prove real microphone or Bluetooth latency. All screenshot previews are desktop Chromium/SwiftShader, not Android device screenshots. No 60FPS, p95 latency, battery or visual-equivalence claim is made without phone measurements.

## Validation

Results are recorded after final source freeze below. Android device/emulator is unavailable; installation, rotation, TalkBack, native fallback appearance, real mic/playback response, network/lock transitions and thermal/battery behavior remain NOT TESTED on Android. Existing JVM and renderer tests exercise available state/lifecycle contracts only.

## Current verification notes

- First Android compile failed in AuraControlActivity's new palette action call (incorrect closing parenthesis); corrected before re-running the complete Gradle gate.
- Desktop Neural Wave test initially used a fixed80ms settle delay for its reduced-motion assertion. Under concurrent compiler/software-GPU load the one requested static frame arrived later. The test now waits for that actual frame before checking no further rendering; production scheduling was not weakened.
- Both new Neural Wave and retained plasma/legacy WebGL suites passed shader compilation, presentation transitions, stale/interrupt behavior, hidden and reduced-motion scheduling, context-loss notification, double-disposal/resource release and constructor-failure notification.
- Desktop screenshot uses synthetic output=.6; it proves visual rendering only. It is not a microphone test or real-device screenshot.
- Typed voice messages use the existing service's sendTextMessage path and are enabled only while its snapshot reports active LIVE connection. Existing mic, interrupt, stop, settings, transcript and exit controls remain.

## Final results

Final command: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon` using JDK17/SDK36. BUILD SUCCESSFUL in2m42s. The preceding run executed615 JVM tests with0 failures,0 errors,1 skipped; final resource-only correction reused that UP-TO-DATE result. Lint passed with no new issues; unchanged baseline filters1380 historical errors and2 hints. Root background overdraw was fixed by using the existing Voice theme background, not suppressing lint.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Signature, package com.jarvis.assistant,min26/target36 and16KB ZIP alignment verified. Packaged neural-wave.js/plasma-orb.js/orb.html byte-match the checked source. Existing x86_64 JNA4KB ELF limitation remains; all ARM64 ELF LOAD segments have≥16KB alignment. This is a local debug key; do not erase existing local data to bypass an update signature mismatch.

Renderer checks passed in desktop Chromium/SwiftShader. [Preview](neural-wave-desktop.png) uses a synthetic speaking envelope; it is not an Android screenshot. Native Canvas fallback appearance, phone microphone/output timing, Android gestures/navigation/rotation, actual60FPS and battery performance are NOT TESTED. Execution accent currently covers MissionEngine EXECUTING; independent non-mission tool dispatches are not inferred as execution. Exact perceptual reference matching remains a device review task.

Choose **Aura Control → Orb renderer → Neural Wave** if an explicit old renderer was saved. New/missing renderer preference uses Neural Wave; old settings and modes remain selectable. Colors: Aura Control → Neural Wave color theme. Existing motion/lighting dialog controls density, glow, intensity, speed, size and responsiveness.

Full changed-file list and artifact hash: [neural-wave-verification.json](neural-wave-verification.json). No CI, remote push or main merge performed for this change.
