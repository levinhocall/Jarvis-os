# Android 16 / HyperOS repair and home upgrade

## Audit and changes

- User's shell trace is a denied privileged-permission grant, not evidence that the accessibility binder is connected or disconnected. Ordinary accessibility use does not require WRITE_SECURE_SETTINGS. No permission bypass was added.
- Old accessibility instances previously marked shared health disconnected during teardown even after a replacement connected. Only the owning current instance can now publish disconnect. Native regression coverage exercises this instance-replacement race; it does not establish OEM process-kill recovery.
- AudioTrack previously discarded unwritten PCM bytes after a short hardware write. PcmWritePump consumes the remainder and stops when interrupted. Playback now uses USAGE_ASSISTANT. Unit tests cover partial writes, cancellation and hardware failures.
- Frequency analysis squared an Int-sized full-scale PCM difference before conversion, overflowing into negative energy/NaN. Differences are squared as Double. A full-scale regression verifies finite output. This can prevent invalid analysis data; physical microphone accuracy and network jitter are not declared fixed without handset evidence.
- ExplicitMessageAuthorization recognizes bounded direct English/Hinglish send commands from the real user transcript. Approval is granted only to a deterministic WhatsApp prepare/send mission matching the exact recipient and message. Questions, negations, quoted commands, changed payloads and other tools retain their existing gates. Contact/profile ambiguity remains a clarification. No real external message was sent during development.
- The old visible particle sphere is replaced by a real-time procedural vortex sphere and lit metallic orbital bands, retaining the existing native state/audio bridge. No prerecorded animation. Missing PCM decays to silence instead of invented speech waveforms. Error state now has a distinct restrained amber visual.
- MainActivity applies the requested violet dark appearance once on upgrade; users can subsequently choose a theme. Dark surfaces use violet tokens and a night gradient. This changes the home rendering and palette; it is not a claim of pixel-identical reproduction of every screen in the reference.
- Opt-in Emergency Contact uses encrypted local settings, a free-fall/impact/stillness candidate detector, a visible 30-second cancel countdown and Android Telecom call request to the chosen trusted number. It is not general car-crash recognition or a medical alarm. Notifications and call permission are required; blocked notification channels abort the countdown. A five-minute automatic-alert cooldown limits repeat candidates. No real call or physical fall was tested.

## Verification scope

Browser WebGL checks exercise shader compilation and idle/listening/thinking/speaking/error transitions with explicitly injected test amplitude. This is renderer testing, not acoustic recognition or Android FPS/battery evidence. Android emulator checks cover activity lifecycle and service logic; HyperOS-specific process termination, actual WhatsApp delivery, acoustic pickup/stutter and physical fall/contact calling require handset verification.

Development source validation and final CI results are recorded after execution. Existing on-device memories are preserved. CI signing in earlier builds varied by runner; an installed version check was requested to distinguish a failed APK update from an unchanged interface. No user-data reset or uninstall is part of this repair.

## Repository integration

Before publication, origin/main had advanced to 93f1bb7 with additional avatar, home-control, utility, wake-matcher and audit changes. Those commits were merged and preserved. The merged home uses the same controller actions with visible Chat/Voice/Vision/Tools category cards; the redundant mic row is hidden because the bottom Talk control remains available. Its hidden animation loops do not run. Validation is repeated on this merged source, not inferred from the earlier checkout's successful checks.

The accessibility instance-replacement regression uses two test-owned service objects to test lifecycle bookkeeping. It does not simulate a real HyperOS binder or establish automatic recovery after OEM termination. A separate clean Android emulator screenshot is captured for visual review; no personal handset content is captured.

The remote branch also contained an empty `.github/workflows/main.yml`, confirmed by GitHub as a separate invalid-workflow failure on push. It is replaced by a real exact-source Gradle-wrapper integrity check. Android APK and runtime workflows remain separate.

## Runtime verification follow-up

Run 42684674955 completed 24 Android tests with one failure: screenshot capture timed out while launching MainActivity a second time. The earlier recreation fixture finished a pre-recreation object and did not explicitly close the replacement. Lifecycle tests now use ActivityScenario.recreate/close, and capture starts a clean task. 23 cases passed in that run, including the accessibility replacement and native microphone regressions. This establishes a fixture correction, not proof of an OEM lifecycle root cause.

Run 42686651157 passed home capture but failed the expanded recreation fixture while waiting for RESUMED (last state PAUSED). The clean emulator fixture now grants required mic/location/notification permissions before these launches, preserves screen-specific failure context, and keeps all recreation checks. Cache-based screenshot export was unavailable after testing; a MediaStore test-evidence copy and export fallback preserve the clean-emulator capture. Failed runs remain documented and are not counted as passing verification.

## Verified delivery

Source: `29f401aed6b1cb1b4eb24e47cb9329c79c82aa9b`, pushed directly to main. The preceding implementation commits and upstream merge are preserved in history.

- Local merged-source JVM suite: 449 tests, zero failures, one environment skip; lint and instrumentation Kotlin compilation passed.
- [APK CI #321](https://github.com/levinhocall/Jarvis-os/actions/runs/42689051279): successful.
- [Android 16 runtime CI](https://github.com/levinhocall/Jarvis-os/actions/runs/42689051683): successful, 24 tests, zero failures/errors/skips. Actual clean-emulator [home screenshot](images/home-1.10.0.jpg) was exported from this run and visually reviewed. This proves the new home/orb renders on the emulator; it does not establish handset frame rate or pixel-identical Orby fidelity.
- [Download debug APK](https://github.com/levinhocall/Jarvis-os/releases/download/debug-ci-321/app-debug.apk): package `com.jarvis.assistant`, version 1.10.0 (148), target SDK 36, minimum SDK 26; 167,936,063 bytes. Downloaded artifact checksum and APK signature verified locally.
- SHA-256: `62f430b141bd909501ab49c15470f7c1fb8a56068e2bdb7b39dc183a75ac7c80`.
- Signing certificate SHA-256: `9c579fd629d2a3997d51e93e02b884007ad0bde5fa98b1e0c38baae76d8953e3`.

CI debug signing still varies by runner: a private stable signing key is not configured. Updating an installation signed with a different key may be rejected. Do not uninstall an existing installation without a verified export/backup of local memories. No private signing key was published or user data erased.

Remaining handset verification: HyperOS 3 accessibility binding after background/OEM termination; exact-contact WhatsApp delivery; real microphone pickup, interruptions and playback stutter; possible-fall sensing, countdown cancellation and trusted-contact calling; FPS, thermal and battery measurements. Android background restrictions were not bypassed. Automatic emergency monitoring remains opt-in and off by default.

Full changed-file lists: [implementation](https://github.com/levinhocall/Jarvis-os/commit/0c32623), [integrated home](https://github.com/levinhocall/Jarvis-os/commit/d372375), [workflow repair](https://github.com/levinhocall/Jarvis-os/commit/a5b3890), [lifecycle fixture](https://github.com/levinhocall/Jarvis-os/commit/cbccbae), [final runtime fixture and capture](https://github.com/levinhocall/Jarvis-os/commit/29f401a). Machine-readable results are in [HYPEROS-EVIDENCE.json](HYPEROS-EVIDENCE.json).
