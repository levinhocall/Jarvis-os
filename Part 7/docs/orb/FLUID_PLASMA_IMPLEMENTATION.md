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
