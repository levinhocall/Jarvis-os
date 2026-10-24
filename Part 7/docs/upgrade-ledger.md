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
| `Part 7/app/src/main/java/com/jarvis/assistant/forge/WebsiteForgeClient.kt` | MISSING | SSE-streaming Gemini website generator with auto-repair and stage detection. |
| `Part 7/app/src/main/java/com/jarvis/assistant/ui/forge/ForgeActivity.kt` | MISSING | Immersive Website Forge HUD and result inspection UI. |
