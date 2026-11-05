# Wake-word reference flow

Reference: user-uploaded 52-second screen recording, `Screenrecorder-2026-11-04-19-18-12-218.mp4`. Inspected sampled frames, including full-resolution frames at 0, 5 and 35 seconds. The recording shows a MYRA tutorial with microphone test/status, sensitivity, name-only configuration, automatic Hey/Wake up phrase examples, and an offline model downloading at 95%. It later shows orb customization. It does not demonstrate a completed acoustic wake detection. No claim made by the tutorial is treated as verified engineering evidence or as an instruction to override the user's request.

## Changes

- Reworked WakeWordActivity into native themed cards: Microphone, Sensitivity, Custom Wake Word, Offline Model and Background Behavior.
- Background listening switch follows service state. Microphone test has a separate start/stop action and status; actual capture and test mode are exposed by WakeDiagnostics instead of inferred from a decorative animation.
- Saving a name immediately updates “Current wake word”, “Hey [name]” and “Wake up [name]” examples. Existing phonetic alias support remains available.
- Sensitivity retains live 0–100% adjustment, with accurate explanation of confidence thresholds and false wakes.
- Model download displays measured percentage in a progress bar, queued/verification/failure state, cancel and retry controls. Already bundled models are prepared locally; no unnecessary mandatory download is added.
- Optional silent wake transitions suppress app wake/standby chimes; OS microphone indicators remain intact. The option defaults off to preserve existing behavior. Pocket listening remains explicitly opt-in.
- The wake setup uses the reference's dedicated light control-surface palette; no MYRA artwork or branding is copied.
- Stop handles an in-flight start request and avoids displaying a permanent stopping state when already off.

## Visual parity revision

The first integration preserved the app-theme card styling and did not match the recording closely enough. Version 1.9.3 replaces that screen presentation with the reference hierarchy: fixed light-gray canvas, compact Microphone toolbar, live capture dot, white elevated rounded cards, black headings, muted descriptions, red primary actions, red sensitivity/progress controls, a compact Save action, and Offline Mode (Vosk) switch inside the model card. The status dot turns green only after the recorder is actually active. The microphone card contains the local test action; the model card owns persistent background listening, matching the recording's information structure.

JARVIS text, behavior, accessibility descriptions and privacy disclosures remain original. The recording's MYRA name, character art, icons and other branded assets are not copied.

## Verification

Added Android instrumentation for saved-name phrase-preview updates. Extended the real foreground microphone start/stop test to assert capture and test-state indicators. Existing model/matcher/archive safety tests remain. Final CI and artifact results are recorded below.

Real speech accuracy, OEM background/pocket restrictions, and the complete official-server download remain physical/integration verification items. This UI change does not establish zero latency or universal custom-name recognition.

## Verified delivery

Source commit: `4fad7a8ba9f37752ab25d0170777479c7f52a6a7` (pushed to main).

- Local unit tests: 426 tests, 0 failures/errors, 1 environment-dependent skip.
- Local lint: 0 errors, 1,260 warnings; Android instrumentation Kotlin compilation passed.
- [Android 16 runtime CI](https://github.com/levinhocall/Jarvis-os/actions/runs/41898423395): 22 tests, 0 failures/errors/skips. The fixed light layout structure, saved-name phrase preview, and real microphone service start/stop indicator assertions passed.
- [APK CI](https://github.com/levinhocall/Jarvis-os/actions/runs/41898423489): tests, migrations, lint, assembly and release publication passed.
- [Download JARVIS 1.9.3 (147)](https://github.com/levinhocall/Jarvis-os/releases/download/debug-ci-303/app-debug.apk): actual published file downloaded; checksum and APK signature verified. Package `com.jarvis.assistant`, target SDK 36.
- APK SHA-256: `eab39262b5b4f7c408b1e57c1e6a70dfb54f00ac616a6d28d84bdba229ea32e9`. Size: 167,670,568 bytes.
- Debug certificate SHA-256: `727f0e54c778fd8f6b5d701c9f8b29962a3c7d489ae3cf932432a9ad9d39e7d3`. It differs from previous CI builds. Do not uninstall an existing installation without a verified memory backup.

Open Aura → Offline Wake Word. Save the name, confirm the generated phrase examples, run Test microphone, then enable Background wake listening. Pocket listening and silent transitions are explicit options. Physical acoustic/pocket accuracy and the full external model-download path remain unverified.
