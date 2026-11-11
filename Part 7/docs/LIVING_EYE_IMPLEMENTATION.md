# Living holographic eye — local implementation checkpoint

Scope: Part 7 only; no push or merge authorized for this work. Existing JARVIS branding, XML/ViewBinding shell, service/audio ownership, privacy and tool execution are preserved.

## Audit / architecture

Inspected orb.html, VortexOrbView, HomeFragment, VoiceModeActivity, AuraControlActivity, AppPreferences, VisualMotionSettings, ConversationState, MissionEngine/MissionModels and the service audio-level publishers. Home and Voice already load bundled Three.js via restricted WebViewAssetLoader HTTPS URLs. Playback amplitude is published from AudioPlayer's actual PCM callback while SPEAKING; microphone amplitude comes from the existing recorder/VAD paths. No new recorder, session, native bridge, renderer dependency or wake lock is introduced.

The existing WebGL engine can render the eye safely, so it is retained. A procedural sphere shader replaces the previous sine-wave surface with radial iris fibres, pupil, limbal/inner iris detail, curved eyelid aperture mask, metallic shell detail and specular lighting. A separate transparent physical-material shell supplies highlights/depth. Thin structural rings and preallocated particles remain; the eye faces the viewer with restrained motion instead of continuously turning away.

eye-behavior.js owns deterministic-testable visual timing: irregular blink intervals, occasional double blink, asymmetric closure/reopening easing, sleep/wake, restrained gaze and pupil movement, amplitude smoothing and interrupted-state reset. OrbVisualState maps real Android service states without commanding the service. Verified mission COMPLETED can produce a brief success visual when the service returns to idle; UNVERIFIED/PARTIALLY_COMPLETED do not trigger it. This is a visual cue, not emotion inference or a substitute for action verification.

## Settings / lifecycle

Aura Control has a Living Eye dialog for blink, movement, speed, intensity, glow, particles and size. It saves through existing AppPreferences; it does not create another preference store. Existing theme, power profile and reduced-motion controls are reused. Low-RAM/system saver reduce particle density and frame target. The renderer targets 60 Hz when capable and degrades to 30 Hz after sustained slow frames; saver targets 20 Hz. These are targets, not measured phone FPS.

Reduced motion draws on state/configuration changes rather than continuously animating. Visibility and page disposal control RAF; GPU geometry/materials/renderer resources are released on disposal. Native WebViews retain their existing pause/resume/destroy lifecycle. Home tap/long-press/navigation continue through existing handlers.

## Verification status

Node deterministic behavior tests executed successfully: timing/closure/reopen/double blink, sleep/wake, PCM attack/stale decay, interrupted speech, reduced motion, disabled blink, invalid values. Chromium/SwiftShader renders the actual shaders without page errors; open/closed screenshots differ, reduced-motion captures remain identical, and disposal completes without page errors. An initial browser check included a favicon 404 as an error; the subsequent check specifically verifies JavaScript/shader execution and passes. This is browser verification, not Android-device visual evidence.

Android compilation/unit/lint/APK checks are pending at this checkpoint. No new Android runtime test, physical microphone/Live test or measured phone performance is claimed. Existing older Android CI evidence does not verify this new eye. Native Settings persistence uses the existing preference mechanism but still requires runtime/device verification. High-contrast/TalkBack behavior, OEM WebGL recovery, true voice synchronization and visual fidelity to the supplied reference need phone review. The shader is procedural/stylized; photorealistic equivalence is not asserted.

## Files changed

- assets/orb.html; assets/eye-behavior.js
- ui/orb/OrbVisualState.kt; ui/orb/OrbAppearance.kt
- ui/home/HomeFragment.kt; ui/directives/VortexOrbView.kt
- ui/settings/AuraControlActivity.kt; data/preferences/AppPreferences.kt
- test/ui/orb/OrbVisualStateTest.kt; scripts/test-eye-behavior.cjs
- androidTest/ui/OrbAppearanceInstrumentedTest.kt (compiled, not executed)

All paths above are under Part 7/app/src except the script and this report under Part 7. No Parts 1–6 or GitHub workflows are changed.

## Final executed checks

- Android `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `compileDebugAndroidTestKotlin`: successful, 7m 12s. JVM: 496 tests, zero failures/errors, one existing environment skip. Existing warnings remain.
- Final asset packaging and instrumentation-source compilation: successful, 35s. The new settings test covers a recreated preferences instance, corrupt settings fallback and bounds; it has **not** executed on Android and does not prove process-death persistence.
- Node behavior checks rerun successfully after final changes. Chromium/SwiftShader checks passed final shaders, real-input amplitude response (injected numeric PCM levels, not actual microphone recording), immediate interruption reset, reduced-motion stability, sleep aperture, theme updates, zero microphone requests and repeated disposal/resize without JavaScript errors. The live renderer also reached natural blink closure and reopened without manually requesting sleep.
- APK signature verification passed. Packaged orb.html and eye-behavior.js match final source bytes exactly. No attached devices were listed by `adb devices`; no Android emulator/physical visual or audio test was executed for this change.

Local development APK: `/workspace/Jarvis-os/work/jarvis-living-eye-debug.apk` (168,021,394 bytes).

SHA-256: `04d793df69f62fb4338083090fd419dac8228bca1ad73830719fc8dfa98d1667`.

Development certificate SHA-256: `3c14012da6e74f0ecf536c4c4bbd8047b803732b6c598cfc4a9bfd905053d3c6`. It differs from previously downloaded CI #321's certificate, so in-place update compatibility is not established. Do not erase an existing installation without a usable memory export/backup. Stable production signing remains separate from this local debug build.

Changes are local on `feature/living-holographic-eye`, uncommitted and unpushed. No main merge or workflow change was made. Full production acceptance remains pending device visual/audio/lifecycle/permission verification, accessibility review, real performance/battery measurements and reference-fidelity review. The implementation supplies a functioning procedural eye; it does not certify photorealism, emotion detection or 60 FPS.
