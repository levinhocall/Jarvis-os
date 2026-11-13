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
