# JARVIS OS — Lia AI Features Gap Report (Phase L0)

**Date**: 2026-10-24  
**Project**: `Part 7/` (`com.jarvis.assistant`)  
**Baseline Test Status**: Clean build and all unit tests passing (`BUILD SUCCESSFUL`, commit `7b30d87`).

---

## 1. Executive Summary & Strategy
In accordance with **LIA FEATURES UPGRADE PROMPT (V3)**:
- View/XML architecture is strictly preserved; no Compose rewrite.
- All new agent capabilities/tools register in unified `ToolRegistry` and pass `RiskPolicy`.
- Package name `com.jarvis.assistant` is retained.
- Configurable `assistant_name` (default "Jarvis") replaces hardcoded names.
- All visible UI additions (new theme, 3D AGSL orb, splash, edge glow) are gated behind `KEY_NEW_LOOK_BETA` (Settings toggle "New look (beta)", default OFF) so existing screens remain default and pixel-for-pixel intact.
- Backward compatibility is preserved across all existing stored preferences.

---

## 2. Comprehensive Gap Analysis Matrix

### Ref Part 02: Settings & Key Storage (`AppPreferences.kt`)
| Feature Item | Status | Evidence / Location | Action Required |
|---|---|---|---|
| Encrypted Gemini key & Provider keys | PRESENT | `AppPreferences.kt:15-29, 137-145` | Retain; already encrypted. |
| `assistant_name` preference (default "Jarvis") | MISSING | `AppPreferences.kt:40-80` (not declared) | Add `KEY_ASSISTANT_NAME` with default "Jarvis". |
| `reduce_motion` preference | MISSING | `AppPreferences.kt:40-80` (not declared) | Add `KEY_REDUCE_MOTION` defaulting to system animation state. |
| `new_look_beta` preference | MISSING | `AppPreferences.kt:40-80` (not declared) | Add `KEY_NEW_LOOK_BETA` defaulting to `false`. |
| `listening_edge_glow` preference | MISSING | `AppPreferences.kt:40-80` (not declared) | Add `KEY_LISTENING_EDGE_GLOW` defaulting to `false`. |

---

### Ref Part 03: 10 Personalities + Language Brain
| Feature Item | Status | Evidence / Location | Action Required |
|---|---|---|---|
| Personality Modes as String Constants | PRESENT | `GeminiConstants.kt:55-72`, `PromptGenerator.kt:61-99` | Retain for legacy stored preference compatibility. |
| `Personality` enum with metadata | MISSING | N/A | Add `enum class Personality(displayName, description, icon, systemInstruction)` alongside string constants. |
| 10+ Selectable Personalities | MISSING | `GeminiConstants.kt:64-72` has 7 modes | Add NORMAL, FRIENDLY, FUNNY, COMPANION, TEACHER, DEVELOPER, HUNGRY, NAUTANKI + existing modes. |
| Mandatory companion safety rules | MISSING | `PromptGenerator.kt:62-76` (lacks explicit human/body disclaimers) | Enforce safety lines: never claim human body, no explicit content, respect topic change. |
| Turn-by-turn `LanguageDetector` | MISSING | N/A | Implement `LanguageDetector` (Devanagari $\to$ HINDI, Hinglish marker count/ratio $\to$ HINGLISH, etc.). |
| `ConversationMemory` rolling recap | MISSING | N/A | Implement rolling window of last 12 turns, `recap()` capped to 1600 chars in-memory. |
| `PromptGenerator` system prompt composition | PARTIAL | `PromptGenerator.kt:101-120` | Compose base (`{assistant_name}`) + tools + personality + language policy + safety + resume recap. |
| Persona Selection Activity | PARTIAL | `PersonaActivity.kt:85-88` (only 4 options) | Wire `activity_persona.xml` to 10 cards with immediate resume-reconnect on change. |

---

### Ref Part 04: Gemini Live Client (`GeminiLiveWebSocket.kt`)
| Feature Item | Status | Evidence / Location | Action Required |
|---|---|---|---|
| Audio response modality & voice name | PRESENT | `GeminiLiveWebSocket.kt:222-229` | Retain. |
| Input & output audio transcription config | PRESENT | `GeminiLiveWebSocket.kt:219-220` | Retain. |
| Unified `ToolRegistry` declarations | MISSING | `GeminiLiveWebSocket.kt:240-500` (hardcoded inline JSON) | Map function declarations from `ToolRegistry.getAllTools()`. |
| No `automaticActivityDetection` | PRESENT | `GeminiLiveWebSocket.kt:211-239` (not set) | Retain. |
| Handles text and binary WebSocket frames | PRESENT | `GeminiLiveWebSocket.kt:98-104` | Retain. |
| Ignores `sessionResumptionUpdate` / `goAway` | PARTIAL | `GeminiLiveWebSocket.kt:560-610` | Ensure silent ignore for session resumption and goAway frames. |
| Model fallback on rejection | PRESENT | `GeminiLiveWebSocket.kt:142-149` | Retain. |
| Single model definition constant | PRESENT | `GeminiConstants.DEFAULT_MODEL` | Retain. |
| `ToolCallDeduplicator` | MISSING | N/A | Add bounded LRU/FIFO seen-call-id deduplicator + unit test. |
| `VoiceLatencyTracker` | MISSING | N/A | Add per-turn latency tracker measuring turn deltas + unit test. |

---

### Ref Part 05: Audio Pipeline & Echo Guard
| Feature Item | Status | Evidence / Location | Action Required |
|---|---|---|---|
| Hardware AEC/NS/AGC configuration | PRESENT | `AudioRecorder.kt:58`, `VoiceInterruptionCoordinator.kt:165-186` | Retain. |
| Audio playback 24 kHz mono PCM16 | PRESENT | `AudioPlayer.kt:45-65` | Retain. |
| Hot path logging minimization | PRESENT | `AudioPlayer.kt`, `AudioRecorder.kt` | Retain. |
| Echo Guard: playback counter | MISSING | `VoiceInterruptionCoordinator.kt` | Add playback buffer counter tracking active speech playback. |
| Echo Guard: 450 ms tail mute | MISSING | `VoiceInterruptionCoordinator.kt` | Keep mic muted for 450 ms tail after playback completes. |
| Echo Guard: 60 s watchdog force-unmute | MISSING | `VoiceInterruptionCoordinator.kt` | Add 60-second force-unmute watchdog against dropped buffer events. |

---

### Ref Part 06: Session Management & Service (`JarvisConversationService.kt`)
| Feature Item | Status | Evidence / Location | Action Required |
|---|---|---|---|
| Epoch tracking for stale audio chunk suppression | PRESENT | `VoiceInterruptionCoordinator.kt:20, 157-159` | Retain. |
| Single-flight auto-reconnect | PARTIAL | `GeminiLiveWebSocket.kt:850-910` | Standardize to 1.2s delay, max 5 attempts, then ERROR state. |
| Resume with short recap | MISSING | `GeminiLiveWebSocket.kt:211-220` | Inject `ConversationMemory.recap()` into prompt on resume. |
| Live state exposed as StateFlow | PRESENT | `JarvisConversationService.kt` | Retain. |
| Foreground service Stop action | PRESENT | `JarvisConversationService.kt:notification` | Retain. |

---

### Ref Parts 07–15: Advanced & UI Capabilities
| Ref Part | Feature | Existing Status | Action Required |
|---|---|---|---|
| 07 | Phone Actions (`AppLauncher`, `ContactResolver`) | PRESENT | Add unit tests for exact vs prefix ranking & number short-circuit. |
| 08 | Text Chat (`ChatFragment`) | PRESENT | Add model discovery & Forge intent recognition. |
| 09 | Design System & XML Tokens | MISSING | Add night & light color tokens, font families, glass card styles. |
| 10 | 3D Orb & Visual Presence | PARTIAL (`OrbHelper` WebView) | Add AGSL `LiaOrb3DView`, `LiaSplash3D`, `ListeningEdgeGlow`. |
| 11 | Restyled 3D Look | MISSING | Gated behind `new_look_beta` setting toggle (default OFF). |
| 12 | Social Visual Agent | PARTIAL (`WhatsAppAssistant`) | Add `ScreenDriver`, `TaskStateMachine`, Instagram/Facebook drivers. |
| 14 | Website Forge | MISSING | Add `ForgeController`, `WebsiteForgeClient`, local Three.js r160 ES scene. |
| 15 | Personal-Use Debug & Diagnostics | MISSING | Add Voice Pipeline Debug UI and latency stats. |

---

## 3. Implementation Order
1. **Phase L1**: Voice pipeline audit and backfill (`ToolCallDeduplicator`, Echo Guard counter/tail/watchdog, `VoiceLatencyTracker`, unified tools live setup).
2. **Phase L2**: Personalities enum, companion safety rules, `LanguageDetector`, `ConversationMemory`, `assistant_name` pref, Persona Activity wiring.
3. **Phase L3**: Design system tokens in XML (`colors.xml`, `themes.xml`, glass styles, font bundle, reduce-motion support).
4. **Phase L4**: 3D Presence (`LiaOrb3DView` AGSL, `LiaSplash3D`, `ListeningEdgeGlow`).
5. **Phase L5**: Screen restyling behind `new_look_beta` toggle.
6. **Phase L6**: Social visual agent (`ScreenDriver`, `TaskStateMachine`, Instagram/Facebook/WhatsApp task flow).
7. **Phase L7**: Website Forge live 3D website builder.
8. **Phase L8**: Test backfilling, Voice Debug screen, documentation.
