# Fluid Plasma: device validation still required

Status: **NOT RUN** on Android hardware/emulator. The available adb device list was
empty; no emulator/KVM was available. Desktop Chromium/SwiftShader results do not
establish Android frame rate, acoustic latency, battery use or OEM lifecycle behavior.

## Reproduce the local checks

Use JDK 17, Android SDK 36 and the existing Gradle wrapper from `Part 7/`:

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug
node scripts/test-plasma-motion.cjs
node scripts/test-eye-behavior.cjs
node scripts/test-plasma-webgl.cjs
```

The browser check uses an already installed Node Playwright + Chromium development
runtime and a loopback-only HTTP server. It adds no Android dependency or network
service to the app. `PLASMA_SCREENSHOT=/absolute/path/image.png` records a desktop
render, not an Android screenshot. Instrumentation compilation is not test execution.

## Install and preserve existing data

1. Verify the APK signing certificate against the installed app before updating. Keep
   the existing application/Keystore/database. Do not uninstall personal data to work
   around a signature mismatch. A local debug key can differ from GitHub CI's key.
2. Use an authorized disposable profile/test device for permission/network stress.
3. Select Aura Control > Orb rendering > Fluid Plasma (or its quick-switch banner).
   The default remains the existing renderer; no stored style is silently overwritten.
4. Exercise both Home and Voice Mode. Back leaves the existing foreground conversation
   alive; End stops it. The mute and interruption controls remain native and accessible
   even if WebGL is unavailable.

## Runtime matrix

Prioritize Android 16 / HyperOS 3 and an API 26–32 device, then API 33–35. Record device
model, OS/build, WebView provider/version, refresh rate, route, power mode and room noise.

- Fresh start, microphone/notification denied, revoke during capture, regrant, retry.
- 100 start/stop cycles; repeated Home/Voice/Settings transitions, rotation where supported,
  small/split-screen windows, 200% font scale, TalkBack and display cutouts.
- Silence versus background noise versus accepted user speech. Muted mic never shows
  LISTENING/USER_SPEAKING. Pause/quiet returns to ambient motion with decaying levels.
- Interrupt during output, during network reconnect and immediately after new PCM arrives.
  Confirm no previous audio generation reappears in sound or visuals.
- Wired speaker/headset/Bluetooth route changes; hardware timestamp and playback-head
  fallback. TTS must use ambient, state-only animation rather than artificial syllable pulses.
- Screen lock/unlock, app background/foreground, notification shade, OEM power restrictions,
  process termination/recreation, low-memory and thermal pressure. No unsupported restart.
- Disable WebGL / force context loss. Native fallback and buttons remain usable, current
  service/session and microphone owner remain unchanged. Recreating the screen creates a fresh host; an existing failed host keeps its native fallback;
  there is no unbounded retry loop or automatic voice reconnect caused by the renderer.
- Existing Accessibility: enabled-but-disconnected guidance, service disable/re-enable,
  screen snapshots, keyboard/dialog/multi-window transitions, gesture cancel/timeout,
  queue overload and authorized automation. Do not bypass secure/protected windows or
  auto-enable Accessibility. Run existing regressions as well as a real authorized task.
- Run `connectedDebugAndroidTest` on a test device only after reviewing its existing DB
  fixtures and credential/permission requirements. New host test exercises a real WebView
  bridge failure and native-view handoff without requesting a mic or starting a session.

## Measurement protocol (no measured phone numbers yet)

Use the same device, screen brightness, route, power mode and workload for baseline and
candidate. Warm up shaders, then collect at least 5 minutes per workload (idle, continuous
conversation, interruptions, saver mode), plus a long soak separately. Use Android Studio
Profiler/Perfetto FrameTimeline and audio scheduling tracks. Record missed presentation
frame deadlines / total frames, p50/p95 frame times, main/render-thread CPU, process heap,
WebView renderer memory, audio underruns, temperature and battery delta. Count live hosts
before/after repeated navigation; do not infer leak freedom just from source cleanup.

For the <=80 ms p95 envelope-response targets, correlate a known PCM onset in the existing
capture/output callback with the first displayed reactive frame. Debug trace counters or
an external high-frame-rate recording can establish that boundary; no audio content should
be logged. Validate AudioTrack timestamps against an actual output reference (for example
wired loopback measured externally) before calling the result playback-aligned. Bluetooth
buffer/acoustic delay is not established by a playback head or by a desktop browser test.
No benchmark instrumentation or timing numbers are fabricated by the renderer.

Targets: capable-device 60 Hz, controlled 30 Hz fallback, <5% missed deadlines in the
specified workload, and mic/playback-aligned visual p95 <=80 ms. Report the trace, duration,
frame/sample count, quality changes, device temperature and failures alongside each result.
If the clock cannot be validated, report timing unavailable. Do not convert configured
20 ms meter / 33 ms IPC intervals into measured end-to-end latency.

A 2-hour voice soak and 24-hour background run remain NOT RUN. Physical-device startup,
installation/upgrade, data-preservation and voice/automation smoke tests remain NOT RUN.
