# Fluid plasma implementation evidence

Starting branch: `phaseA/stability-accessibility`; starting HEAD: `5f285af`.
Working tree was clean. Changes are scoped to Part 7. No push/main merge authorized.

## Baseline

`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --offline`
completed successfully in 17 s. All 30 tasks were UP-TO-DATE: this is cached baseline
verification, not a fresh test execution. Log: local ignored `work/plasma-baseline.log`.

## Audit and integration boundaries

- HomeFragment and VortexOrbView host packaged `assets/orb.html` with Three.js.
  Existing eye/ring scenes and appearance settings must remain available.
- VoiceModeActivity used programmatic Views; production UI is not being migrated to Compose.
- JarvisConversationService owns capture, accepted VAD speech, requests, interruption and
  transport. Its public flows remain available. AudioPipelineManager delegates to the same
  AudioRecorder; MicrophoneOwnership remains the sole hardware admission policy.
- Existing SPEAKING signals include queued PCM, and RMS-derived synthetic output bands were
  found in the service. Those cannot establish audible playback or frequency analysis.
- AudioPlayer already has bounded PCM admission, partial-write handling, terminal release
  and epoch invalidation. Playback-clock observation will extend this owner.
- Both orb hosts called process-wide WebView pauseTimers/resumeTimers. Host visibility must
  instead stop the individual renderer.
- Existing WebGL initialization failure only displayed text; the new host needs native fallback.
- Preserve preceding accessibility health generations, bounded mutation/event work, gesture
  ticket gate and snapshot ownership. No orb bridge may call accessibility or privileged APIs.
- Open baseline risks: native Binder/audio initialization timing, legacy node ownership,
  OEM disconnect/reconnect behavior and real-device voice/ANR measurements remain unverified.

## M1 contract

Immutable service-owned facts distinguish capture, accepted speech, pending requests,
actual playback, source, timing confidence, mute, connection, focus and error explanations.
Every asynchronous producer carries the session identity. Interruption generation survives
StateFlow conflation even when INTERRUPTED immediately becomes LISTENING. Legacy ordinal
OrbState and existing public flows are preserved. Renderer state is a deterministic projection;
it does not own, start or reconnect a voice session.

Further milestone evidence is appended only after execution.

M1 gate: `:app:testDebugUnitTest --tests '*PlasmaPresentationTest' --no-daemon
--offline` executed successfully (3m 4s); 5 tests, zero failures. Includes noise vs
speech, queued vs audible output, interruption conflation, old session rejection,
stale envelopes, TTS confidence and playback during transport disconnect.
Existing Kotlin/Room processor warnings remain; they are not suppressed.

## M2 renderer gate

Original `plasma-orb.js` uses the packaged Three.js with one analytic shaded volume,
bounded four-octave noise, no full ray march, no eye/rings/particles. Mode is selected
before allocating legacy geometry. Canvas fallback extends PremiumOrbView. OrbWebHost
owns local-only loading, failure signal, visibility, coalesced presentation and disposal.
The only JavaScript interface method reports renderer failure; it cannot control Android.
Existing preferences default to the legacy scene; fluid plasma is a new explicit selection.

Executed: `node scripts/test-plasma-motion.cjs` (14 checks PASS),
`node scripts/test-eye-behavior.cjs` (PASS), and
`node scripts/test-plasma-webgl.cjs` (PASS). Desktop Chromium/SwiftShader compiled/rendered
one shader program, all eight states, epoch reset, hidden/reduced-motion pause, context
loss, idempotent disposal, forced initialization failure and legacy scene loading.
A browser screenshot was inspected. This is not Android visual/performance certification.
Native fallback rendering/device context-loss handoff: NOT TESTED; no Android device/emulator
is available. Native compile is included in the subsequent Gradle gate/final verification.

M2 native compile + M3 gate: first Gradle attempt failed because WebViewClientCompat's
platform onReceivedError overload is final. Corrected to WebResourceErrorCompat.
Rerun `:app:testDebugUnitTest --tests '*PlasmaPresentationTest' --tests
'*PcmEnvelopeFollowerTest' --tests '*LoudspeakerBargeIn*' --tests '*MicrophoneOwnershipTest'
--no-daemon --offline` completed successfully in 3m 25s. Native host/fallback compiled.
Display-only follower consumes existing PCM, full-scale RMS, -55 dB gate/soft knee,
35 ms attack and 200 ms release. Existing VAD thresholds are unchanged. No second recorder.
Phone microphone/noise/route measurements remain NOT TESTED.

## M4 playback clock design and limits

AudioPlayer remains the only PCM playback owner. Accepted writes are divided into
10 ms metadata blocks in a fixed 256-slot timeline. Queued data is never sampled as
output. A player-owned 20 ms observer uses a recent AudioTimestamp projected onto the
monotonic clock, otherwise the unsigned/wrap-aware playback head. The observer stops
when inactive/released; it is not a second audio engine. Partial writes advance only
accepted frame counts. Flush/new tracks reset the timeline; route changes discard
old envelopes and temporarily lower timing availability without resetting written
frame positions. Session/revision guards reject late observations, including pause.

Hardware timestamp/head confidence is explicit. Neither proves acoustic arrival at a
Bluetooth speaker. Android/local and external TTS callbacks supply STATE_ONLY output,
with zero synthetic audio amplitude/bands. TTS generation guards reject obsolete callbacks.
Permission/VAD and accessibility ownership are unchanged. Existing public flows survive.

Initial M4 compile failed on an existing nullable summary being passed to the new TTS
presentation wrapper; wrapper now accepts/ignores blank or null summaries. Deterministic
clock/timeline and existing voice tests are run before the M4 commit. Native AudioTrack,
Bluetooth timing, focus changes and route switching remain device verification requirements.

M4 main gate: `:app:testDebugUnitTest --tests '*Pcm*Test' --tests
'*PlasmaPresentationTest' --tests '*LoudspeakerBargeIn*' --tests
'*MicrophoneOwnershipTest' --tests '*Voice*Test' --no-daemon --offline`
PASS in 3m 27s: 54 tests, zero failures/errors/skips. Hardware synchronization is still
NOT TESTED. Additional no-hardware AudioPlayer pause/flush/release ownership tests
are tracked separately; they do not simulate a successful AudioTrack playback.

M4 additional gate: `:app:testDebugUnitTest --tests '*PlaybackObservationLifecycleTest'
--tests '*PcmEnvelopeTimelineTest' --no-daemon --offline` PASS in 1m 48s.
Seven tests executed: frame mapping, partial writes, bounded metadata, timestamp/head
validation, route discard, pause/same-epoch flush rejection and terminal release.

## M5/M6 screens, lifecycle and regression gates

VoiceModeActivity now inflates activity_voice_mode.xml with ViewBinding. Home and Voice
Mode consume the same service snapshot; neither opens a recorder/session. Back keeps
service ownership, End uses existing ACTION_STOP_ALL. Controls retain start, mute,
interrupt, settings and collapsible transcript. Insets, scrollable constrained content,
large-text controls and meaningful status text accompany the orb. Existing legacy
renderer/appearance selections are retained. Choose Fluid Plasma in Aura Control >
Orb rendering (or its quick-switch banner); selecting an old preset restores legacy.

Per-host visibility replaces all production WebView pauseTimers/resumeTimers calls.
The host coalesces IPC, sends epoch invalidation immediately, reacts to power-save and
system motion settings only while visible, and removes observers/bridge/views on disposal.
Renderer failure displays PremiumOrbView without touching voice/accessibility ownership.
The native fallback is deliberately simpler, with cached gradients/paths and no plasma
rings. It responds to the same facts and settings, decays old levels and limits animation.

M5 compile + selected tests PASS (2m 54s). M6 full test suite + Android-test compilation
PASS (4m 5s): 576 tests, 575 passed, one skipped, zero failures/errors; accessibility
namespace 36 tests, audio namespace 75, network namespace 15. Instrumentation sources
compiled only; Android instrumentation execution is NOT RUN. Final rerun below includes
subsequent permission-label, theme-contrast and cleanup corrections.

The two-host browser regression exposed a real 30 Hz resume freeze: first-frame elapsed
time was fixed at 16.67 ms, below the saver budget, so the frame never committed. Fixed
by initializing elapsed to the current budget, and added explicit saver hide/resume
coverage. Desktop WebGL regression then PASS, including geometry/program disposal and
one host continuing while another is hidden. A prior timeout was not counted as a pass.

Accessibility production source/config/manifest diff against 5f285af is empty. No
claim of OEM service reconnection, gesture success or screen access on Android is made.

## M7 final check attempts

First combined final command (`testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
assembleDebug`) executed unit tests successfully but failed lint on seven new issues:
root background overdraw, three button-bar style findings, URI KTX use, and two touch
accessibility findings. No lint-baseline edits or suppressions were added for these seven
findings. The host explicitly enables JavaScript for trusted packaged assets; its existing
SetJavaScriptEnabled annotation documents that deliberate renderer capability.
The screen now uses a dedicated dark window theme with no duplicate root paint, proper
button-bar styles and toUri. OrbTouchWebView implements performClick/performLongClick
while preserving legacy WebGL drag events; the host no longer uses an unlabelled touch
listener. Native controls remain accessible alternatives. Landscape orb height is bounded
by the actual scroll viewport so the hero is not sized from landscape width alone.

Browser checks passed again after source changes: motion (14 checks), legacy eye behavior,
and WebGL compilation/states/hide-resume/saver/reduced-motion/interruption/context loss/
initialization failure/resource disposal. No phone FPS/latency/battery numbers are available.

The corrected combined check then passed in 7m 3s, including APK assembly and zero new
lint issues. A subsequent source/bytecode comparison caught a final landscape sizing edit
that had been made during that running build: the compiled class still used the older
width-only calculation. That APK is not the final delivery. All 605 source/test/asset text
files were fingerprinted, production edits were stopped, and the combined check was run
again against the frozen tree. Final artifact hash and verification appear in VERIFICATION.md.

`plasma-desktop-preview.png` is an actual local Three.js render in desktop Chromium/
SwiftShader. It is not a phone screenshot, generated illustration or performance benchmark.

The first frozen-source rerun passed in 5m 22s; bytecode inspection confirmed the
landscape sizing calculation uses 65% of the actual scroll viewport. A final
reduced-motion inspection found audio-only packets still requested static redraws.
WebGL and Canvas now redraw that mode on visual-state/session/generation changes,
not each audio packet. The browser test sends ten continuous audio updates while
reduced motion is enabled and verifies the frame count remains unchanged (PASS).

Final delivery gate: the same four-task Gradle command passed in **5m 34s** with
20 tasks executed and 51 up-to-date. The unit test task executed: **576 tests,
575 passed, one skipped, zero failures/errors**. All 605 fingerprinted source,
asset and test files were unchanged during this final build. Lint reports no new
errors/warnings under the unchanged existing baseline (1389 errors and two hints
filtered); this is not a clean-baseline claim. Debug APK v2 signature verification
passed. Android tests compiled but were not executed. See VERIFICATION.md for the
exact artifact checksum, signing certificate, source revision and remaining gates.
