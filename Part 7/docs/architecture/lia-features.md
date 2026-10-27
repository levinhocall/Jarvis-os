# JARVIS OS — Lia AI Features & Modernization Architecture

## 1. Overview & Vision
JARVIS OS has been upgraded with the **Lia AI Features Suite (V3)**, transforming the personal Android assistant into an ultra-modern, visually immersive, and highly capable agentic platform. 

The upgrade adheres to strict architectural constraints:
- **View / XML Architecture Preservation**: Existing View layouts and navigation are preserved without forcing disruptive Jetpack Compose rewrites.
- **Personal-Use Scope**: Focuses on raw capability, security, and developer ergonomics, bypassing unnecessary app-store entitlement checks or external licensing.
- **Configurable Identity**: Configurable `assistant_name` (default: `"Jarvis"`, customizable to `"Lia"` or user-defined nicknames).
- **Non-Destructive Modern Look**: The aesthetic overhaul is gated behind the `new_look_beta` setting toggle (`KEY_NEW_LOOK_BETA`, default: `false`), allowing users to switch between classic tactical JARVIS and the modern "Pearl of Light" glassmorphic UI.

---

## 2. Voice Pipeline & Low-Level Audio Telemetry

### 2.1 Dynamic Tool Declarations & Tool Call Deduplication
- **Dynamic Tool Declarations**: Tools registered in `ToolRegistry` are dynamically emitted in Gemini Live session setups, supporting runtime extensions without hardcoded schema limitations.
- **`ToolCallDeduplicator`**: Eliminates duplicate tool invocations across rapid audio packets within a 1500ms sliding window using MD5-hashed parameter signatures.

### 2.2 Echo Guard & Watchdog Force-Unmute
- **Acoustic Echo Suppression**: When rendering assistant speech through the device loudspeaker, microphone capture is attenuated to prevent audio self-feedback.
- **Watchdog Protection**: In `VoiceInterruptionCoordinator`, a 60-second watchdog detects stalled audio buffers and automatically forces unmuting, preventing permanent mic silence. A trigger counter records all occurrences for telemetry.
- **Hardware AEC**: Automatically binds Android's hardware `AcousticEchoCanceler` when available on the hardware audio session.

### 2.3 Turn-by-Turn Latency Tracking (`VoiceLatencyTracker`)
Measures turn performance across the voice loop:
- **TTFT (Time To First Token)**: Time from VAD speech termination to first model text/token chunk.
- **TTFA (Time To First Audio)**: Time from VAD speech termination to first playable audio chunk written to `AudioTrack`.
- **Statistical Aggregates**: Computes average, minimum, maximum, $p_{50}$ (median), and $p_{95}$ latency over the most recent 20 completed turns.
- **Interruption Safety**: When the user barges in, in-flight measurements are cleanly cancelled without corrupting historical metrics.

---

## 3. Personality Brain & Language Intelligence

### 3.1 Twelve Dynamic Personalities
JARVIS OS provides 12 distinct persona modes in `Personality.kt`:
1. **Jarvis Pro**: Strategic, ultra-capable assistant created by Nazib.
2. **Friendly Mode**: Warm, approachable conversational best friend.
3. **Funny Mode**: Quick-witted, humorous banter and playful remarks.
4. **Companion Mode**: Empathetic, supportive, and emotionally attuned.
5. **Teacher Mode**: Structured, pedagogical explanations for learning concepts.
6. **Developer Mode**: Code-first, technical, debugging and architecture-oriented.
7. **Hungry / Foodie Mode**: Passionate food lover, culinary recipes, and restaurant recommendations.
8. **Nautanki Mode**: Dramatic, Bollywood-inspired humorous expressions.
9. **Motivator Mode**: High-energy, inspiring, and disciplinary life coach.
10. **Storyteller Mode**: Immersive, vivid narrative weaver.
11. **Philosophy Mode**: Deep, contemplative, and reflective thinker.
12. **Fitness Mode**: Energetic, health-conscious workout coach.

#### Companion Safety Directive
All companion personas enforce non-negotiable safety guardrails:
> *"I am an AI assistant, not a human. I do not have a physical body. I do not engage in explicit sexual content or romantic delusions. If you wish to change topic, I will immediately respect it."*

### 3.2 Language Brain (`LanguageDetector`)
Pure-logic utterance detection:
- **Devanagari Script** (`\u0900..\u097F`): Classified as `HINDI`.
- **Conversational Romanized Hindi**: Evaluates 60+ Hinglish marker tokens (e.g. *kya, karo, bhai, achha, batao, suno, shukriya*); classified as `HINGLISH`.
- **Latin Script**: Classified as `ENGLISH`.
- Emits real-time language changes to `JarvisConversationService.detectedLanguage`.

---

## 4. Visual Design: "A Pearl of Light in the Night"

### 4.1 Color Tokens & Theming
- **Backgrounds**: Obsidian `#0B0F19`, Deep Surface `#111827`, Midnight Card `#1F2937`.
- **Pearlescent Glow**: Pearl White `#F9FAFB`, Moonlit Indigo `#6366F1`, Cyan Core `#38BDF8`, Amber Gold `#FBBF24`.
- **Typography**: Instrument Serif for expressive titles and numbers; Manrope for legible telemetry and body copy.

### 4.2 Custom Glass Components
- **`GlassCardView`**: Layered translucent container with subtle 1dp border stroke, rounded corners, and elevation glow.
- **`PrimaryPillButton`**: Haptic-responsive pill action button with touch-state scaling and contrast text.
- **`GlassInputBar`**: Pill-shaped frosted input bar with inline mic and send actions.
- **Reduce Motion**: Integrated with `AppPreferences.isReduceMotionEnabled` to disable shaders and intense animations for accessibility and battery conservation.

---

## 5. 3D Presence: AGSL Orb, Splash & Edge Glow

### 5.1 Real-Time AGSL Orb (`LiaOrb3DView`)
- On Android 13+ (API 33+), uses hardware `RuntimeShader` with custom AGSL (Android Graphics Shading Language) calculating:
  - Multi-octave simplex/Perlin noise displacement.
  - State-driven color palettes (Cyan/Indigo for Idle, Emerald for Listening, Purple for Thinking, Amber/White for Speaking).
  - Audio level reactive pulsing based on real-time microphone volume.
- Automatically falls back to high-performance 2D Canvas radial gradient rendering on older Android versions or when reduce motion is active.

### 5.2 Immersive Splash (`LiaSplash3D`)
- Smooth intro entry sequence with expanding energy rings and soft particle decay.

### 5.3 Listening Edge Glow (`ListeningEdgeGlow`)
- Displays a radiant edge illumination border along the device display while JARVIS is actively capturing audio.

---

## 6. Social Visual Agent

Enables zero-touch task automation inside third-party social apps via accessibility drivers:
- **`ScreenDriver`**: Performs node queries, exact/fuzzy text matching, scrolling, and clicks.
- **`TaskStateMachine`**: 6-state execution loop (`IDLE`, `NAVIGATING`, `SEARCHING`, `INTERACTING`, `VERIFYING`, `COMPLETED`, `FAILED`) with 15s step timeouts and back-button auto-recovery.
- **Supported Platforms**:
  - **Instagram**: Search profiles, follow users, send direct messages.
  - **Facebook**: Search pages, friends, and interact with posts.
  - **WhatsApp**: Search contacts, send direct messages and media.
- **Tool Registry Binding**: Registered as `execute_social_media_task` and `social_media_task_control` passing through `RiskPolicy`.

---

## 7. Website Forge (Live 3D Website Generator)

### 7.1 Offline Three.js 3D Reactor Scene
- Bundled locally in `assets/forge/`: Three.js r160 ES modules, UnrealBloomPass, EffectComposer, and custom shaders.
- **Zero External Network Dependencies**: Operates 100% offline via `WebViewAssetLoader`.
- Visual components: Pulsing reactor core, 300 orbital glyph particles, chromatic shockwaves, floating stage glass slabs, and telemetry beam.
- 2D Canvas fallback (`ForgeFallbackScene`) for low-memory devices or WebGL context failures.

### 7.2 Six Progressive Assembly Stages
1. **BLUEPRINT**: Architecture, typography, and color palette design.
2. **STRUCTURE**: Semantic HTML5 layout, hero, grid, and navigation containers.
3. **STYLING**: Tailwind CSS styling, responsive flexboxes, gradients, and shadows.
4. **MOTION**: GSAP interactive animations, hover effects, and entrance transitions.
5. **VISUALS**: Lucide/FontAwesome icon integration, Unsplash/Picsum image placeholders.
6. **FINALIZING**: Idempotent safety nets, SEO metadata, and self-contained packaging.

### 7.3 Streaming Generation & UI State Engine
- **`WebsiteForgeClient`**: Streams chunks from Gemini 2.5 Flash via Server-Sent Events (`streamGenerateContent?alt=sse`).
- **`SseParser`**: Gson-based streaming parser extracting text increments without blocking the thread.
- **`ForgeStageDetector`**: Monotonically advances stages based on code signatures (`<!DOCTYPE`, `<body`, `tailwind`, `gsap`, etc.).
- **`ForgeHtml`**: Strips markdown fences, safely repairs truncated script/style tags if stream closes prematurely, and injects fade-in safety scripts.
- **`ForgeActivity`**: Fullscreen HUD with prompt chip, large serif progress percentage, cancel button, and floating action toolbar (Share via `FileProvider`, Download to `Downloads/`, Open in Browser, Edit, Rebuild).

---

## 8. Diagnostics, Testing & Verification

### 8.1 Voice Diagnostics Screen (`VoiceDebugActivity`)
Accessible via long-pressing the Settings footer or intent launch:
- Live connection status, conversation state, audio recording level.
- Assistant name, active personality, configured vs detected language.
- Real-time latency telemetry: completed turn count, TTFT & TTFA averages, $p_{50}$, $p_{95}$, and last turn breakdown.
- Echo Guard status, force-unmute watchdog counter, and hardware AEC capability.
- Interactive actions: "Reset Metrics", "Switch Persona", and "Refresh Telemetry".

### 8.2 Logic Tests
- **`AppLauncherMatchingTest`**: Validates exact label matching priority, case-insensitivity, short query (< 3 chars) safety, prefix/word-prefix matching (length >= 3), rejection of loose interior substrings, spoken aliases, and dual-app ordinal stripping.
- **`ContactResolverRankingTest`**: Validates strict ranking (`Exact > Prefix > Contains`), phone number direct short-circuit, trailing index disambiguation, nickname alias mapping, and no-permission safety fallback.
- **`WebsiteForgeTest`**: 20 comprehensive unit tests verifying SSE parsing, HTML sanitization, stage detection, and natural language intent matching.

---

## 9. Configuration & Feature Flags

| Preference Key | Type | Default | Description |
|---|---|---|---|
| `key_assistant_name` | `String` | `"Jarvis"` | Spoken and visual assistant name |
| `key_personality` | `String` | `"jarvis_pro"` | Active personality mode |
| `key_language` | `String` | `""` (Auto) | Preferred conversation language |
| `key_new_look_beta` | `Boolean` | `false` | Enables modern Pearl of Light glassmorphic UI |
| `key_reduce_motion` | `Boolean` | `false` | Disables 3D shaders and complex animations |
| `key_silent_execution` | `Boolean` | `false` | Suppresses vocal narration during task execution |
