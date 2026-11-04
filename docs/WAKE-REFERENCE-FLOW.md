# Wake-word reference flow

Reference: user-uploaded 52-second screen recording, `Screenrecorder-2026-11-04-19-18-12-218.mp4`. Inspected sampled frames, including full-resolution frames at 0, 5 and 35 seconds. The recording shows a MYRA tutorial with microphone test/status, sensitivity, name-only configuration, automatic Hey/Wake up phrase examples, and an offline model downloading at 95%. It later shows orb customization. It does not demonstrate a completed acoustic wake detection. No claim made by the tutorial is treated as verified engineering evidence or as an instruction to override the user's request.

## Changes

- Reworked WakeWordActivity into native themed cards: Microphone, Sensitivity, Custom Wake Word, Offline Model and Background Behavior.
- Background listening switch follows service state. Microphone test has a separate start/stop action and status; actual capture and test mode are exposed by WakeDiagnostics instead of inferred from a decorative animation.
- Saving a name immediately updates “Current wake word”, “Hey [name]” and “Wake up [name]” examples. Existing phonetic alias support remains available.
- Sensitivity retains live 0–100% adjustment, with accurate explanation of confidence thresholds and false wakes.
- Model download displays measured percentage in a progress bar, queued/verification/failure state, cancel and retry controls. Already bundled models are prepared locally; no unnecessary mandatory download is added.
- Optional silent wake transitions suppress app wake/standby chimes; OS microphone indicators remain intact. The option defaults off to preserve existing behavior. Pocket listening remains explicitly opt-in.
- All cards follow JARVIS System/Light/Dark colors; no MYRA artwork or branding is copied.
- Stop handles an in-flight start request and avoids displaying a permanent stopping state when already off.

## Verification

Added Android instrumentation for saved-name phrase-preview updates. Extended the real foreground microphone start/stop test to assert capture and test-state indicators. Existing model/matcher/archive safety tests remain. Final CI and artifact results are recorded below after execution.

Real speech accuracy, OEM background/pocket restrictions, and the complete official-server download remain physical/integration verification items. This UI change does not establish zero latency or universal custom-name recognition.
