# Maya Capability Matrix — Phase 0

**Base audited:** `fix/a11y-forge-lia`  
**Audit mode:** source inspection only. No device smoke-test has been run by this audit.  
**Rule:** Source presence is not proof of runtime success.

| Item | Status | Evidence | Gap / next step |
|---|---|---|---|
| 1. `SYSTEM_ALERT_WINDOW` overlay | **PARTIAL** | `Part 7/app/src/main/AndroidManifest.xml` declares `android.permission.SYSTEM_ALERT_WINDOW`; `Part 7/app/src/main/java/com/jarvis/assistant/util/ActivityLauncherHelper.kt` checks `Settings.canDrawOverlays(context)`. | This confirms permission-aware launch logic, not that a Maya avatar overlay exists or stays visible. Verify the existing overlay/avatar implementation and test on device. |
| 2. Foreground service types + manifest | **PARTIAL** | `Part 7/app/src/main/AndroidManifest.xml` declares a foreground service with `microphone|mediaPlayback|mediaProjection`. | Verify the exact service declaration, runtime start paths, notification behavior and Android 14+ requirements on device. |
| 3. MediaProjection capture | **IMPLEMENTED / DEVICE UNVERIFIED** | `Part 7/app/src/main/java/com/jarvis/assistant/vision/ScreenCaptureManager.kt` owns `MediaProjection`, `VirtualDisplay`/frame handling, callbacks and cleanup; `Part 7/app/src/main/java/com/jarvis/assistant/ui/home/MainActivity.kt` requests capture consent. | Consent, a real captured frame, lifecycle cleanup and Android-version behavior still require device testing. |
| 4. Gemini multimodal / Live (text, image, audio) | **PARTIAL** | `Part 7/app/src/main/java/com/jarvis/assistant/network/GeminiModelResolver.kt` discovers model candidates and retries compatible models; `Part 7/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt` contains TTS/session-related voice flow; screen capture code documents frame streaming toward Gemini Live. | This does not prove an end-to-end text + image request succeeds with the currently saved key/model, nor that Gemini Live audio is available. Run explicit API and device tests. |
| 5. STT + TTS pipeline | **PARTIAL** | `Part 7/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt` contains `TextToSpeech` initialization and wake/listening flow. | Source contains pipeline components, but first-wake response, recognizer recovery and speech output need repeatable device tests. |
| 6. Action router (model output → app action) | **PARTIAL** | Existing voice service and action-related code exist under `Part 7/app/src/main/java/com/jarvis/assistant/`. | No single Phase-0-verified typed Maya `ActionRouter` has been established by this audit. Phase 1 should inspect and add only missing pieces. |
| 7. Intent launcher (Maps, YouTube, WhatsApp, dialer) | **PARTIAL** | `Part 7/app/src/main/java/com/jarvis/assistant/util/ActivityLauncherHelper.kt` provides shared app/activity launch handling; existing app action code also appears in the project. | Verify each exact command on-device, including missing-app and no-handler paths. |
| 8. Structured panel / whiteboard rendering | **MISSING / NOT ESTABLISHED** | No dedicated Maya whiteboard schema/renderer was established from the Phase-0 source inspection. | Phase 5 only; do not implement during Phase 0. |
| 9. Avatar / emoji animation assets | **PARTIAL / NOT ESTABLISHED** | Existing avatar/overlay-related settings are referenced from `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/SettingsFragment.kt`. | Confirm actual reusable assets and runtime overlay renderer before choosing reuse. Do not generate or add art during Phase 0. |
| 10. Accessibility (status only) | **IMPLEMENTED / DEVICE UNVERIFIED** | `Part 7/app/src/main/AndroidManifest.xml` declares `.automation.accessibility.JarvisAccessibilityService`; `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt` implements an `AccessibilityService` and records connection lifecycle. | This proves code is present, not that Android settings show it enabled or that the service is connected/receiving usable events. Do not change accessibility in Phase 0. |

## Phase 0 verification boundary

- **Verified by source inspection:** the files and APIs listed above exist in the audited branch.
- **UNVERIFIED:** APK compilation, unit tests, overlay visibility, app launching on a real phone, MediaProjection consent/frame capture, Gemini text/image network calls, STT/TTS behavior, accessibility connection and battery/OEM behavior.
- **Not claimed:** any capability is fully WORKING on-device.
- **Scope guard:** Phase 0 is audit + diagnostic harness only. No Phase 1–7 features or accessibility fixes.
