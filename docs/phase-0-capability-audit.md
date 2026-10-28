# Phase 0 — Capability Audit and Verification Baseline

Date: 2026-10-28
Repository: `levinhocall/Jarvis-os`
Target app module: `Part 7`
Package/application ID to preserve: `com.jarvis.assistant`

## Scope and guardrails

Phase 0 is an audit and verification baseline only. It must not redesign the home screen, change the voice orb, alter the theme/navigation, rename the package, or introduce user-facing settings UI. Do not merge the experimental `phase0/capability-audit` branch wholesale: its SettingsFragment and settings-layout changes add a smoke-test entry point and change UI, which conflicts with the project guardrails. Diagnostic ideas can be reused later only if they can be reached without changing the existing UI or if the user explicitly approves a UI change.

## Repository facts verified

- The Android module exists under `Part 7/` and has a Gradle wrapper.
- The main manifest declares `com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService` with `android.permission.BIND_ACCESSIBILITY_SERVICE`, the accessibility-service intent, and service metadata.
- The main manifest declares `JarvisConversationService` as a foreground service with microphone, media-playback, and media-projection types.
- The accessibility XML advertises interactive-window retrieval, view IDs, gesture support, screenshot capability, and window/content event types. Manifest/XML configuration does not prove that actions work on a real device.
- AudioRecorder has startup cleanup, recorder recovery, and effect re-binding paths. A targeted guard was added so recovery does not recreate the recorder after the stop flag changes during its recovery delay (commit `1630315181e7209fd0db7187edb9335551418b0d`).
- The GitHub Actions workflow runs from `Part 7`, targets Java 17/Gradle wrapper, and requests unit tests and a debug APK build.
- The observed Actions run `40636925761` ended with `startup_failure` and no job started. This is not evidence of a Kotlin compile/test failure; repository/Actions-side cause remains unconfirmed.
- The experimental `phase0/capability-audit` branch contains an opt-in `MayaSmokeTestActivity` plus SettingsFragment/layout changes. It is not merged because it changes existing settings UI and the branch's diagnostics must be reviewed for compatibility before reuse.

## Capability status at Phase 0

Status vocabulary: **Present** means code/configuration was found; **Partial** means some implementation exists but integration or runtime behavior is not established; **Unverified** means there is no successful build/device evidence available in this audit.

| Capability | Phase 0 status | Evidence / next verification |
|---|---|---|
| Existing UI and voice orb preserved | Guardrail | Do not modify during phases unless a user explicitly approves. |
| Accessibility service registration | Present | Manifest and XML found; verify enablement and Android 13+ restricted settings on device in Phase 6. |
| Accessibility event/node/gesture actions | Unverified | Audit lifecycle, node discovery, gestures, threading and action results; then device-test. |
| Audio capture and recovery | Partial | Startup/recovery paths exist; test stop/restart, errors, permission denial and route changes. |
| Echo cancellation/noise suppression | Partial | Effect binding exists; verify supported devices and correct communication audio mode. |
| Continuous conversation / barge-in | Unverified | Trace service integration and interruption state transitions; device-test echo and user interruption. |
| Wake word / standby listening | Unverified | Confirm actual engine wiring, permission/foreground-service lifecycle, battery behavior and false-trigger handling. |
| Screen capture | Partial | Manifest/service infrastructure exists; verify consent, frame lifecycle and stop/restart. |
| AI provider/network call | Partial | Provider code exists; test with a valid user-configured key; never log secrets. |
| GitHub CI / APK artifact | Blocked before job startup | Investigate repository Actions settings/runner startup; do not treat as source build failure. |
| Final APK/device acceptance | Not yet run | User plans to build APK with Antigravity after the final phase. |

## Phase 0 exit criteria

- [x] Record repository/module/package and scope guardrails.
- [x] Audit manifest and accessibility-service configuration presence.
- [x] Record the audio recovery lifecycle guard already pushed.
- [x] Separate CI startup failure from code compilation results.
- [x] Review experimental smoke-test branch and avoid importing UI changes.
- [ ] Obtain a successful CI job or an independent build result.
- [ ] Run a device smoke test. These two items require working build execution/device access and are not claimed as complete.

## Next phase

Phase 1 begins only after the user reviews and approves Phase 0. Phase 1 should focus on voice-command-to-app-launch reliability, without changing the existing UI or voice orb. Keep each phase isolated, commit its changes, and report evidence before waiting for approval to continue.
