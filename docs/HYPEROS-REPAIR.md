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
