# Maya Phase Ledger

## Phase 0 — Capability audit + smoke-test harness

- **Branch:** `phase0/capability-audit`
- **Base:** `fix/a11y-forge-lia`
- **Status:** IMPLEMENTED IN SOURCE; BUILD AND DEVICE TESTS UNVERIFIED.
- **Added:** `Part 7/docs/maya-capability-matrix.md`; `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/MayaSmokeTestActivity.kt`.
- **Changed:** `Part 7/app/src/main/res/layout/fragment_settings.xml` adds a Settings entry; `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/SettingsFragment.kt` opens the new screen; `Part 7/app/src/main/AndroidManifest.xml` registers the internal activity.
- **Harness coverage:** temporary 100dp overlay circle; Maps/YouTube/WhatsApp intent checks; user-consented one-frame capture; Gemini text request; Gemini image request using the captured JPEG; Hinglish TTS; copyable report.
- **Existing behavior:** no existing screens, package names, or voice/accessibility runtime implementation was intentionally rewritten. The harness is opt-in.
- **UNVERIFIED:** Gradle build and unit tests; Android device runtime; OEM overlay permission behavior; MediaProjection foreground-service requirements on the target phone; Gemini key/quota/network result; whether TTS audio is audible; app-intent behavior on the user device.
- **Known gaps:** source code has not yet been compiled or smoke-tested. Gemini image test requires successful screen capture first. The report indicates request acceptance/results, not full product readiness.
- **FUTURE:** Phase 1 voice action router; Phase 2 avatar overlay; Phase 3 reactions; Phase 4 screen companion; Phase 5 whiteboard; Phase 6 accessibility actions gated on Doctor evidence; Phase 7 polish.
