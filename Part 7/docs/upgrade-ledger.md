# JARVIS OS — Upgrade Ledger

Classification key:
- **WORKING**: Retain behavior, public signatures, layouts, naming, formatting. Extend only additively.
- **BROKEN / STUB**: Minimal safe fix + regression test.
- **MISSING**: Create following existing conventions.

---

## File Classifications

| File Path | Classification | Evidence / Reason |
|---|---|---|
| `Part 7/app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt` | WORKING | Add additive properties: `assistantName`, `isReduceMotionEnabled`, `isNewLookBetaEnabled`, `isListeningEdgeGlowEnabled`. |
| `Part 7/app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt` | WORKING | Add `ToolCallDeduplicator` integration, tool declarations from `ToolRegistry`, silent ignore of `sessionResumptionUpdate`/`goAway`, single-flight reconnect. |
| `Part 7/app/src/main/java/com/jarvis/assistant/audio/VoiceInterruptionCoordinator.kt` | WORKING | Extend with Echo Guard (playback counter, 450ms tail, 60s watchdog force-unmute). |
| `Part 7/app/src/main/java/com/jarvis/assistant/network/ToolCallDeduplicator.kt` | MISSING | New bounded LRU seen-call-id deduplicator. |
| `Part 7/app/src/main/java/com/jarvis/assistant/audio/VoiceLatencyTracker.kt` | MISSING | New per-turn timestamp and latency metrics tracker. |
| `Part 7/app/src/main/java/com/jarvis/assistant/data/model/Personality.kt` | MISSING | Additive enum with 10+ modes, display names, icons, companion safety rules, backward-compatible mapping. |
| `Part 7/app/src/main/java/com/jarvis/assistant/data/model/LanguageDetector.kt` | MISSING | Turn-by-turn spoken language detector (Devanagari, Hinglish markers, English). |
| `Part 7/app/src/main/java/com/jarvis/assistant/data/memory/ConversationMemory.kt` | MISSING | Synchronized 12-turn rolling conversation window for session resume recap. |
| `Part 7/app/src/main/java/com/jarvis/assistant/util/PromptGenerator.kt` | WORKING | Additive overload accepting `assistantName`, `Personality` enum, and resume recap text. |
| `Part 7/app/src/main/java/com/jarvis/assistant/ui/settings/PersonaActivity.kt` | WORKING | Wire 10 personality cards, immediate resume-reconnect on change. |
| `Part 7/app/src/main/java/com/jarvis/assistant/ui/fx/LiaOrb3DView.kt` | MISSING | API 33+ AGSL RuntimeShader 3D sphere with sensor tilt and conversation state palettes. |
| `Part 7/app/src/main/java/com/jarvis/assistant/ui/fx/LiaSplash3D.kt` | MISSING | Single-process intro animation. |
| `Part 7/app/src/main/java/com/jarvis/assistant/ui/fx/ListeningEdgeGlow.kt` | MISSING | Slim border overlay window for active listening presence. |
| `Part 7/app/src/main/java/com/jarvis/assistant/agent/ScreenDriver.kt` | MISSING | Testable boundary abstraction for Android accessibility actions. |
| `Part 7/app/src/main/java/com/jarvis/assistant/agent/TaskStateMachine.kt` | MISSING | Strict transition-verified state machine for autonomous UI actions. |
| `Part 7/app/src/main/java/com/jarvis/assistant/forge/ForgeController.kt` | MISSING | Process-wide website forge controller and build state management. |
| `Part 7/app/src/main/java/com/jarvis/assistant/forge/WebsiteForgeClient.kt` | BROKEN | Picks first flash model from ListModels (`gemini-2.5-flash`), 404 throws raw JSON, lacks model resolver integration. |
| `Part 7/app/src/main/java/com/jarvis/assistant/ui/forge/ForgeActivity.kt` | BROKEN | In failed/idle HUD, buttons lack high-contrast colors and retry capability, raw JSON error displayed. |
| `Part 7/app/build.gradle.kts` | WORKING | Add `BUILD_GIT_SHA` and `BUILD_TIMESTAMP` build config fields for build stamp verification. |
| `Part 7/app/src/main/AndroidManifest.xml` | BROKEN | Contains `android:directBootAware="true"` on `JarvisAccessibilityService`, which causes crashes or failed startup during direct-boot before credential-encrypted storage and KeyStore are available. |
| `Part 7/app/src/main/res/xml/accessibility_service_config.xml` | BROKEN | Uses `typeAllMask` flooding main UI thread with all system events; narrow to union of monitored events. |
| `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/JarvisAccessibilityService.kt` | BROKEN | `onUnbind` returned true without calling super, heavy work on main thread in `onAccessibilityEvent`, missing breadcrumbs, and `ensureJarvisServiceActive` starting foreground service without permission guards. |
| `Part 7/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt` | BROKEN | Unchecked `startForeground` elevating to `mediaProjection` and `microphone` causing fatal `SecurityException` on API 34. |
| `Part 7/app/src/main/java/com/jarvis/assistant/util/PermissionHelper.kt` | BROKEN | `isAccessibilityServiceEnabled` returns true when service is only listed in settings but instance is null and not working. Add `isAccessibilityServiceWorking()` with live probe. |
| `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityDoctor.kt` | MISSING | New comprehensive privacy-safe diagnostic report generator for accessibility. |
| `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityBreadcrumbs.kt` | MISSING | New persistent 200-entry ring buffer for service lifecycle and exceptions. |
| `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/A11yStatusDeriver.kt` | MISSING | Pure function deriving `{NOT_ENABLED, ENABLED_NOT_CONNECTED, CONNECTED_NO_EVENTS, WORKING, DEGRADED, CRASH_LOOP}` with full unit test coverage. |
| `Part 7/app/src/main/java/com/jarvis/assistant/automation/accessibility/A11yWatchdog.kt` | MISSING | Watchdog detecting stale bindings and notifying user with deep link. |
| `Part 7/app/src/main/java/com/jarvis/assistant/ui/accessibility/AccessibilityFixActivity.kt` | MISSING | Guided step-by-step fix screen with live probe verification. |
| `Part 7/app/src/main/java/com/jarvis/assistant/background/ForegroundTypeSelector.kt` | MISSING | Pure, unit-tested FGS type selector for safe `startForeground` on Android 10-14. |
| `Part 7/app/src/main/java/com/jarvis/assistant/network/GeminiModelResolver.kt` | MISSING | Shared model resolver that parses versions, rejects deprecated 2.5, falls back on 404, handles replacement hints, and caches. |
| `Part 7/app/src/main/res/layout/activity_forge.xml` | BROKEN | Uses `text_primary` (`#0F172A`) on `bg_primary` (`#070709`), resulting in invisible black text on black background. |
| `Part 7/app/src/test/java/com/jarvis/assistant/personality/CompanionSafetyPromptTest.kt` | MISSING | Unit test asserting that PromptGenerator with companion personas strictly outputs the mandatory non-negotiable safety lines and assistant_name binding. |
| `Part 7/docs/lia-verification.md` | MISSING | Verification ledger recording row-by-row PASS/FAIL/UNVERIFIED evidence for Lia L1-L8 features. |
| `Part 7/app/src/main/java/com/jarvis/assistant/agent/ProductionScreenDriver.kt` | BROKEN | Returns explicit `ENABLED_NOT_CONNECTED` when accessibility service is down instead of generic failure. |
| `Part 7/app/src/main/java/com/jarvis/assistant/agent/social/SocialTaskCoordinator.kt` | BROKEN | Detects missing/disconnected accessibility service on empty observations and transitions to `FAILED` with `ENABLED_NOT_CONNECTED`. |

---

## Baseline Verification
- Date: 2026-10-25
- Commit baseline: `b8979f5` (lia-L8)
- Test count: 382 unit tests passing, 0 failures, 0 errors.
- Build status: `./gradlew :app:testDebugUnitTest :app:assembleDebug` SUCCESSFUL.

