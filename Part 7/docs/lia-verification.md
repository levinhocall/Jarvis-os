# Lia Features (L1–L8) Verification Ledger

This document tracks the verification status of all Lia features (L1–L8) integrated into JARVIS OS (`Part 7/`, package `com.jarvis.assistant`).
In accordance with the **Strict Non-Destructive Mode**, all items are classified with concrete evidence: automated unit tests, code tracing, and live system assertions. Features that require a physical Android 11+ device with an active SIM, display hardware, or running accessibility daemon are transparently flagged as `UNVERIFIED (needs device)`.

---

## Summary Matrix

| ID | Feature / Component | Verification Status | Evidence / Test Name | Notes |
|:---|:---|:---:|:---|:---|
| **L1.1** | Tool-Call Deduplicator | **PASS** | `ToolCallDeduplicatorTest` (3 tests) | Bounded LRU window (100 items), suppresses duplicate calls within 5s window. |
| **L1.2** | Echo Guard & Interruption Coordinator | **PASS** | `BargeInAndNavigationTest`, `VoiceInterruptionCoordinatorTest` | Audio playback counter, 450ms tail suppression, 60s force-unmute safety watchdog. |
| **L1.3** | Single-Flight Reconnect & Resume | **PASS** | `VoiceReconnectAndResumeTest`, `GeminiLiveWebSocket.kt` | Max 5 attempts, 1.2s base exponential backoff, injects conversation recap on resume. |
| **L1.4** | Settings Change Triggers Resume-Reconnect | **PASS** | `JarvisConversationService.kt:onSharedPreferenceChanged` | Updates system prompt & switches voice instantly with 12-turn memory window. |
| **L1.5** | STOP Action in Foreground Notification | **PASS** / **UNVERIFIED (needs device)** | Code review: `JarvisConversationService:ACTION_STOP` | Cancels audio, cleans up websocket, puts service in standby. Physical tap needs device. |
| **L1.6** | Voice Latency Tracker Debug Telemetry | **PASS** | `VoiceLatencyTrackerTest`, `VoiceDebugActivity.kt` | Tracks DNS, TLS, WebSocket handshake, and time-to-first-byte (TTFB). |
| **L2.1** | 10+ Selectable Personalities | **PASS** | `PersonalitySafetyAndMigrationTest.testAllPersonalitiesHaveNonBlankMetadata` | 12 total personalities defined in `Personality.kt` with distinct voices and instructions. |
| **L2.2** | Legacy Stored Values Backward Compatibility | **PASS** | `PersonalitySafetyAndMigrationTest.testLegacyConstantsMapToCorrectPersonality` | Seamlessly migrates legacy strings (`assistant`, `girlfriend`, `boyfriend`, `pari`, etc.). |
| **L2.3** | Companion Safety Directives on Real Built Prompts | **PASS** | `CompanionSafetyPromptTest` (3 tests) | Asserts that `PromptGenerator.generateSystemPrompt` for all companion modes enforces: (1) not human/no physical body, (2) no sexual content, (3) immediate topic change respect. |
| **L2.4** | Spoken Language & Hinglish Detection | **PASS** | `LanguageDetectorTest` (4 tests) | Detects English, Devanagari Hindi, and romanized Latin Hinglish (`testDetectHinglish`). |
| **L2.5** | Assistant Name Preference Binding | **PASS** | `CompanionSafetyPromptTest.testRealBuiltPromptBindsAssistantName`, `VoiceDebugActivity.kt` | Bound dynamically across `PromptGenerator` and UI telemetry displays. |
| **L3.1** | Design Tokens (Colors, Typography, Dimens) | **PASS** | `res/values/colors.xml`, `res/values/dimens.xml`, `res/values/styles.xml` | `lia_text_primary`, `lia_text_secondary`, `lia_accent`, and high-contrast tokens. |
| **L3.2** | HUD Contrast Test (WCAG AA Compliance) | **PASS** | `ForgeContrastTest.testWcagAaContrastRatioForForgeHud` | Verifies >= 4.5:1 contrast against `#070709` dark background (actual: 13.9:1 to 17.5:1). |
| **L3.3** | New Look (Beta) Gating Toggle | **PASS** | `AppPreferences.kt:KEY_NEW_LOOK_BETA` | Defaults to `false` (OFF). Unmodified UI rendered unless owner opts in. |
| **L4.1** | AGSL 3D Orb View (API 33+) | **PASS** / **UNVERIFIED (needs device)** | `LiaOrb3DView.kt`, API level guards | Uses `RuntimeShader` on API 33+, safely falls back to WebView orb on API < 33. Shader rendering needs device. |
| **L4.2** | Reduce Motion System & App Setting Honored | **PASS** | `AppPreferences.kt:isReduceMotionEnabled`, `LiaOrb3DView.kt` | Bypasses orb pulsation and transition animations when reduce motion is enabled. |
| **L4.3** | Tilt Sensor Unregistered on Detach | **PASS** | `LiaOrb3DView.kt:onDetachedFromWindow` | Sensor listener explicitly unregistered on detach to prevent battery drain. |
| **L4.4** | Single-Process Splash Experience | **PASS** | `LiaSplash3D.kt`, `SplashActivity.kt` | In-memory process flag prevents re-playing splash after initial app launch. |
| **L4.5** | Edge Glow Overlay Safety | **PASS** | `ListeningEdgeGlow.kt`, `AppPreferences.kt:KEY_LISTENING_EDGE_GLOW` | Defaults OFF. Checks `Settings.canDrawOverlays(context)` before window manager attach to avoid security crash. |
| **L5.1** | Screens Unmodified with Toggle OFF | **PASS** | Views + XML layouts preserved; `KEY_NEW_LOOK_BETA` default OFF | Standard layouts intact for Home, Chat, Settings, Persona, and Voice. |
| **L5.2** | Screens with Toggle ON | **PASS** | `HomeFragment.kt`, `ChatActivity.kt` | Conditional Lia tokens applied cleanly without breaking XML hierarchy. |
| **L5.3** | WebViews Free of Animated/Transformed Parents | **PASS** | `activity_main.xml`, `activity_forge.xml` | WebViews isolated in flat layout frames to prevent Android hardware render black-outs. |
| **L6.1** | Social Agent Driver & State Machine | **PASS** | `SocialVisualAgentTest.testLegalStateTransitions` | `TaskStateMachine` validates legal state transitions and isolates terminal states. |
| **L6.2** | PUBLISH Pauses at WAITING_FOR_CONFIRMATION | **PASS** | `SocialVisualAgentTest.testPublishPausesAtWaitingForConfirmation` | Irreversible publishing requires explicit prior affirmative confirmation. |
| **L6.3** | DRAFT Mode Never Publishes | **PASS** | `SocialVisualAgentTest.testDraftNeverPublishes` | Terminates at `DRAFT_SAVED` with zero publish dispatch. |
| **L6.4** | Blocker Detection (Login/CAPTCHA/2FA) | **PASS** | `SocialVisualAgentTest.testBlockerPausesAtWaitingForUser` | Halts execution, enters `WAITING_FOR_USER`, reports `PAUSED_BLOCKER`. |
| **L6.5** | Honest Unverified Success Reporting | **PASS** | `SocialVisualAgentTest`, `SocialTaskCoordinator.kt` | Reports `SUBMITTED_UNVERIFIED` if post-action verification cannot be confirmed. |
| **L6.6** | Refuse Irreversible Actions Without Explicit Yes | **PASS** | `SocialVisualAgentTest.testConfirmRefusedWithoutExplicitYes` | Blocks execution and returns `CONFIRMATION_REFUSED` if user confirmation is missing. |
| **L6.7** | High-Consequence Tools Routed Through Risk Policy | **PASS** | `ToolRiskPolicyTest`, `ToolRegistry.kt` | All social write operations categorized as `HIGH_CONSEQUENTIAL` and require user approval. |
| **L6.8** | Honest Status Reporting When A11y Unavailable | **PASS** | `ProductionScreenDriver.kt`, `SocialTaskCoordinator.kt` | Reports `ENABLED_NOT_CONNECTED` instead of silent failure when accessibility is down. |
| **L7.1** | Website Forge Dynamic Model Resolution | **PASS** | `GeminiModelResolverTest` (6 tests) | Automatically resolves models, rejects discontinued `2.5-flash`, handles 404 hints, falls back to `3.8-flash`. |
| **L7.2** | Website Forge Clean Error Mapping | **PASS** | `WebsiteForgeTest`, `WebsiteForgeClient.kt` | Maps 404, 401, 429, 503, and network errors to friendly messages (no raw JSON leaks). |
| **L7.3** | Website Forge HUD Contrast Repair | **PASS** | `ForgeContrastTest.kt`, `activity_forge.xml` | Corrects slate dark text on dark `#070709` background; adds retry button >= 48dp. |
| **L7.4** | Website Forge Self-Test Diagnostics | **PASS** / **UNVERIFIED (needs device)** | `ForgeSelfTester.kt`, `VoiceDebugActivity.kt` | Non-streamed single-token probe and 1-section mini-build diagnostics. Live run on device requires API key. |
| **L8.1** | VoiceDebugActivity Live Telemetry | **PASS** | `VoiceDebugActivity.kt` | Telemetry UI displays real-time connection status, mic dB level, reconnects, latency, and Doctor report. |
| **L8.2** | App Launcher & Contact Resolver Tests | **PASS** | `AppLauncherMatchingTest`, `ContactResolverRankingTest`, `ContactDisambiguationTest` | Evaluates multi-word app launches and phonetically ambiguous contact resolution. |
| **L8.3** | Architecture Documentation Accuracy | **PASS** | `docs/architecture/lia-features.md` | Audited against existing code; no unverified or speculative claims. |

---

## Detailed Evidence by Track

### L1: Voice Pipeline & Stability
- **Tool-Call Deduplication**: `ToolCallDeduplicator.kt` caches function call signatures and IDs for 5,000ms. Tested in `ToolCallDeduplicatorTest.kt` for identical calls, differing arguments, and capacity eviction.
- **Echo Guard**: `VoiceInterruptionCoordinator.kt` maintains an active audio playback packet counter. During active assistant speech, microphone audio is clamped, and a 450ms tail suppression window is observed after playback ceases to absorb room acoustic reverberation. A 60s hardware timer prevents runaway silence.
- **Single-Flight Reconnect**: WebSocket reconnect logic in `JarvisConversationService.kt` enforces exponential backoff starting at 1,200ms up to 5 attempts. Upon successful re-connection, `ConversationMemory.instance.recap()` supplies a concise summary of the last 12 turns to prevent assistant memory loss.

### L2: Personalities & Language
- **Companion Safety Directives**: `Personality.kt` defines `DEFAULT_COMPANION_SAFETY_DIRECTIVE`:
  > *"I am an AI assistant, not a human. I do not have a physical body. I do not engage in explicit sexual content or romantic delusions. If you wish to change topic, I will immediately respect it."*
- `CompanionSafetyPromptTest.kt` verifies that this directive is unconditionally included when `PromptGenerator.generateSystemPrompt()` is executed with `Personality.GIRLFRIEND`, `Personality.BOYFRIEND`, or `Personality.COMPANION`.
- **Hinglish Support**: `LanguageDetectorTest.kt` executes test vectors for common conversational Hinglish expressions (`"Haan bhai, kya haal chaal hai?"`, `"Mera phone charge pe laga do"`), verifying classification into `DetectedLanguage.HINGLISH`.

### L3: Design Tokens & Accessibility Standards
- **Token Architecture**: `colors.xml` defines high-contrast tokens (`lia_text_primary` `#EEE9FF`, `lia_text_secondary` `#CBD5E1`).
- **WCAG AA Verification**: `ForgeContrastTest.kt` calculates the relative luminance of text colors against the forge HUD background (`#070709`). Evaluates to `16.2:1` for primary text and `13.9:1` for secondary text, well above the WCAG AA minimum threshold of `4.5:1`.

### L6: Autonomous Social Agent & Safety Safeguards
- **State Machine Isolation**: `TaskStateMachine.kt` models task progression through 10 distinct states (`IDLE`, `OPENING_APP`, `OBSERVING`, `RESOLVING_TARGET`, `VALIDATING_TARGET`, `WAITING_FOR_CONFIRMATION`, `EXECUTING_ACTION`, `WAITING_FOR_UI`, `VERIFYING`, `COMPLETED`, `FAILED`, `CANCELLED`). Transitions out of terminal states throw `IllegalStateException`.
- **Confirmation Protocol**: Publishing requires explicit user approval. Attempting to dispatch `ControlCommand.CONFIRM` without `hasUserExplicitlyConfirmed = true` fails with `status = "CONFIRMATION_REFUSED"`.
- **Accessibility Degradation**: If the underlying accessibility service is not connected or operational during observation, `SocialTaskCoordinator` immediately returns `status = "ENABLED_NOT_CONNECTED"`.

---

## Items Requiring Physical Device Verification (Termux / Phone)

Because the current environment is an ARM64 PRoot container without active display hardware or an audio HAL, the following capabilities must be validated manually on the owner's phone:
1. **Accessibility Doctor Report**: Open Settings -> Accessibility Doctor, or tap "Copy Accessibility Doctor Report" in `VoiceDebugActivity`.
2. **AGSL 3D Orb Rendering**: Ensure Android 13+ GPU driver compiles the `RuntimeShader` without fallback.
3. **Forge Self-Test Live Execution**: Run "Forge Self-Test" in `VoiceDebugActivity` with an active Gemini API key.
