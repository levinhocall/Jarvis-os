# Part 7 V4 — Source Audit (2026-11-04)

Scope: development branch feature/v4-mobile-assistant-upgrades. Static review only; no Gradle compilation, instrumentation or device battery measurements were performed.

## Release decision: BLOCK

### Critical
1. WakePhraseMatcher is not called from the existing Vosk recognition lifecycle. Custom call signs, sensitivity and cooldown are therefore not active in the application.
2. VoskModelDownloader checks only the presence of required files. It never constructs org.vosk.Model before activation, so a structurally present but corrupt model can be installed.

### High
3. VoskModelDownloader deletes the existing active model before renaming the new directory. A failed rename leaves no usable previous model. Implement versioned installs and atomic pointer switch/rollback.
4. Downloader has no single-flight lock, no explicit coroutine cancellation checks during blocking IO, and no durable WorkManager integration. Concurrent installs share staging/archive paths.
5. WakeWordActivity has no call-sign input, sensitivity slider or live local microphone test; newly added matcher is not configurable from UI.
6. No verified tests of single microphone ownership between standby Vosk and Gemini Live. Android background/FGS restrictions and microphone privacy toggles need real-device tests.

### Medium
7. WakePhraseMatcher permits bare call-sign activation and uses Levenshtein similarity alone. Short call signs at high sensitivity may false-trigger on unrelated words; add minimum phonetic evidence and false-positive calibration.
8. Model download progress during extraction is based on ZIP entry count capped at 1000, not actual extraction bytes. The progress bar can stall around 70% then jump to 100%.
9. MobileUtilityActions reports clock app launch rather than confirmed alarm/timer creation. Clarify user-facing semantics and test intent resolution.
10. New SMS parsing accepts phone numbers only, not contact names; contact-resolution integration is incomplete.
11. Voice parser strips punctuation and lowercases input before extracting SMS body, altering the user's intended message capitalization and punctuation.
12. Startup animation is a static text fade rather than the requested unique orb boot sequence; no accessibility/device performance testing.

## Verification status
- Inspected source: CommandExecutor, PhoneControlManager, WakePhraseMatcher, VoskModelDownloader, MobileUtilityActions, CommandParser, VoskModelManager, AudioRecorder, WakeWordActivity, AndroidManifest and Gradle configuration.
- Tests added in previous commits are not evidence of passing tests.
- A fetch of WakePhraseMatcherTest.kt on this branch returned 404 despite an earlier create-file success response. Reconcile branch tree before claiming tests are present.
- No debug APK was produced.

## Fix order
1. Stabilize model installation with versioned rollback and explicit Model validation.
2. Integrate matcher into one existing Vosk standby pipeline; ensure mic ownership and cooldown.
3. Add settings + mic test, validate sensitivity against real recordings.
4. Fix SMS body preservation, contact resolution, alarm/timer UX.
5. Run compile/test/lint/instrumented/device benchmarks.
6. Merge only after all gates pass.
