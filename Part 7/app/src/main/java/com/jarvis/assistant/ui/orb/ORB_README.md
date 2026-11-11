# Compose Voice Core

Base package: com.jarvis.assistant. Existing UI is XML/ViewBinding; themes are in
res/values/themes.xml / values-v27/themes.xml, applied by ThemeManager. The existing
ConversationState enum remains authoritative. No ViewModel, navigation or DI changes.
Compose compiler and first-party AndroidX Compose dependencies are enabled as authorized.
No additional third-party graphics/audio library, network or analytics is introduced.

Mount JarvisVoiceCore in an existing ComposeView to observe the current service. It
does not start capture. No screen is replaced automatically. Existing WebGL eye changes
from the preceding task remain intact. CORE/GHOST/AVATAR is saved in the existing
AppPreferences appearance JSON; Settings owns choosing which renderer to mount.

Use the State overload of VoiceOrb for audio updates confined to draw.
The required value overload is also provided; a changing value parameter necessarily
recomposes its caller. Frame state is read only in Canvas's draw lambda. Actual layout
inspector verification is still required; this architecture is not an empirical claim
of zero recompositions. Supply OrbDebugCounters through LocalOrbDebugCounters to compare
composition and draw counts on device. LocalOrbReflection controls the optional reflection.

AGSL is isolated to API 33+ and remembers one renderer/shader. API 26–32 uses cached
native gradients, primitives, blobs and precomputed tick/particle arrays. Gradients are
allocated on size/palette changes, not steady frames. FFT/window/twiddle/PCM buffers are
reused; immutable StateFlow emission allocates one levels value per 64ms frame. Absolute
zero-allocation emission is incompatible with that immutable public API.

Standalone AudioLevelTracker.start requires RECORD_AUDIO, a STARTED lifecycle,
and the existing exclusive microphone lease. The constructor unwraps an Activity context
or accepts an explicit Lifecycle; application-context-only capture is refused. Construct,
start and close it on Main; DSP/capture run on its dedicated thread. It does not request
permission or start a service. It stops on lifecycle stop and closes on destroy. Prefer feeding existing-service
levels instead; the adapter's collection stops below STARTED. Existing service RMS is
real, but three true spectral bands are not published there; the adapter emits zeros
for unavailable bands. Do not describe derived RMS multiples as FFT. For real spectral
animation, feed actual PCM into SpectrumAnalyzer from an authorized existing pipeline.

1024 samples at 16kHz already require 64ms; 50ms attack adds settling time. Under-80ms
perceptible response and under-4ms rendering are measurement targets, not guarantees.
The quality guard measures CPU draw submission (12ms for 30 consecutive draws), with an
additional sustained frame-jank guard. GPU completion requires Perfetto/device profiling.
API 26/33 rendering parity, all state visuals, AGSL runtime compilation, lifecycle,
permission denial and power use require emulator/device checks, not just compilation.
Reduced motion freezes procedural motion and ignores audio deformation. Optional haptics
are omitted. Colors are mixed in linear RGB for state palette changes.

Build/test evidence is recorded after executing Gradle; no APK/device claim is implied
by these source files. No push or merge is performed by this integration.

Six-line integration inside an existing ComposeView's setContent (standard Compose,
lifecycle-compose and flow imports; this does not create a second mic):

```kotlin
val context = LocalContext.current; val owner = LocalLifecycleOwner.current
val tracker = remember { AudioLevelTracker(context) }; val levels = tracker.levels.collectAsStateWithLifecycle()
val assistant by JarvisConversationService.conversationState.collectAsStateWithLifecycle()
LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { JarvisConversationService.audioLevel.collect { tracker.feedExternalLevels(OrbAudioLevels(rms = it)) } } }
DisposableEffect(tracker) { onDispose { tracker.close() } }
VoiceOrb(assistant.toVoiceOrbState(), levels)
```

For standalone permission-authorized spectrum testing, call start from LifecycleStartEffect
and stop in onStopOrDispose. The app service adapter instead accepts a muted flag from the
existing ViewModel and observes connection status. It requires no ViewModel/DI edits.
Assumptions: retain the project's minSdk 26/targetSdk 36; AndroidX Compose is first-party;
existing AppPreferences.orbEyeOptions from the pending Living Eye implementation is retained;
CORE is a component, while GHOST/AVATAR routing belongs to the host Settings screen.

Executed verification: assembleDebug, testDebugUnitTest and lintDebug succeeded in
5m 16s (work/voice-core-final-checks.log). JVM total: 502, zero failures/errors,
one existing environment skip. No compiler warnings pointed to these new orb files.
The tests exercise normalization, attack/release, three known sine bands, linear-light
color math, wake/thinking behavior and initial preview state. Earlier compile attempts
exposed a shadowed size parameter and a mutable shader smart-cast; both were fixed and
the final complete checks rerun. Existing unrelated project warnings remain.

No Android device is attached here. API 26 fallback visuals, API 33 RuntimeShader
execution, permission-denial capture, lifecycle recording cleanup, settings restart,
no-per-frame-recomposition counters, latency and GPU/battery performance have NOT been
verified on a device/emulator. Previews are source definitions, not captured device
evidence. These are explicit remaining acceptance checks. Shader construction failure
falls back to Canvas, but that fallback has not been exercised on Android here.

Previous pending WebGL Living Eye work is preserved. This new Compose component is
opt-in and is not mounted on the existing Home automatically. No source push/merge
or ViewModel/navigation/DI changes were performed for this addition.
