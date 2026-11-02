# JARVIS OS V4 Engineering Progress

Last updated: 2026-11-02
Repository: https://github.com/levinhocall/Jarvis-os
Branch: main
Active project inspected: `Part 7/`
Package/application ID: `com.jarvis.assistant`

## Engineering rules

- Preserve the existing Part 7 project, package ID, navigation, working features, database records, and established architecture.
- Do not build APKs or run APK-producing Gradle tasks.
- Do not claim tests ran unless an execution environment actually ran them.
- Do not invent remote account, subscription, credit, or license state.
- Keep user consent for cloud memory, screen capture, accessibility, and consequential actions.
- Avoid merging or rewriting the historical Part 1–6 standalone projects into Part 7.

## Repository baseline

- Latest inspected main commit: `666d9215a51b6917829002d16fbfa018e0458a24`
- Latest commit message: "Release each audio effect independently across all recorder lifecycle paths"
- GitHub repository access: read/write permissions reported by GitHub integration.
- Repository root README describes Parts 1–7 as separate, self-contained Android Studio projects.
- Part 7 README identifies the active application module as `app`, application ID `com.jarvis.assistant`, min SDK 26, compile/target SDK 36, Java/Kotlin target 17, version 1.8.7 / code 143.
- Part 7 Gradle uses AGP 8.9.1, Kotlin Android 2.2.20, KAPT, ViewBinding, and XML/AppCompat-era UI dependencies.
- Part 7 Gradle does not declare Jetpack Compose dependencies. Its existing UI is Views/XML. A forced Compose migration would conflict with the strict architecture-preservation rule and is not authorized by this directive.

## Phase 0 — Initial repository audit

Status: IN PROGRESS

### Inspected source and configuration

- Root README and Part 7 README.
- Part 7 settings.gradle.kts, root build.gradle.kts, app/build.gradle.kts.
- Part 7 AndroidManifest.xml and accessibility_service_config.xml.
- JarvisApp.kt.
- JarvisConversationService.kt.
- GeminiLiveWebSocket.kt and GeminiLiveModels.kt.
- JarvisAccessibilityService.kt.
- TaskProgressOverlay.kt and OverlayAvatarActivity.kt.
- MemoryDatabase.kt and MemoryRepository.kt.
- MissionEngine.kt and JarvisUnifiedRuntime.kt.
- Existing floating-avatar phase reports.

### Confirmed existing implementation

- Floating overlay uses TYPE_APPLICATION_OVERLAY and checks Settings.canDrawOverlays.
- Overlay has avatar/task modes, drag handling, saved position/edge preferences, and an existing gallery with an allowlist of 17 bundled character identifiers.
- Overlay reaction code consumes the existing conversation state; the conversation service owns and destroys the overlay.
- Conversation service owns microphone, playback, Gemini Live WebSocket, wake-word standby, and TTS lifecycle.
- Gemini Live transport has reconnect handling, API-key rotation hooks, and duplicate tool-call ID suppression.
- Vosk is included as a local recognition dependency.
- Accessibility service is declared with BIND_ACCESSIBILITY_SERVICE and metadata; config requests interactive-window retrieval, view IDs, gestures, and screenshots.
- Room database version is 5 and declares explicit migrations 1→2→3→4→5 for memories, chat, missions, and notification history.
- MemoryRepository includes a local embedding provider, vector search, privacy sanitization, duplicate/replacement handling, correction, and soft/permanent deletion.
- MissionEngine persists missions, restores non-terminal missions, and avoids blindly replaying consequential in-flight actions.
- EncryptedSharedPreferences is used for API credentials and selected private preferences.
- Manifest declares microphone, media playback, media projection foreground-service types and overlay/accessibility permissions.

### Confirmed gaps / unresolved questions

- The repository is a collection of historical standalone projects. Part 7 is the newest project documented by the root README; it is the working target for V4. Do not mix code from earlier Parts without a compatibility review.
- User's requested "Kotlin + Jetpack Compose" baseline does not match Part 7's actual Gradle/UI implementation. Preserve Part 7 Views/XML unless the project owner separately authorizes a UI migration.
- No ElevenLabs implementation was found in the initial targeted source search.
- No Mem0 cloud-sync implementation was found in the initial targeted source search.
- Initial targeted searches did not identify a subscription/credits/license backend or a profile/account backend. These require a complete source search before being classified as absent.
- Gemini model constants include identifiers such as `gemini-3.8-live`, `gemini-3.8-flash`, and `gemini-3.1-flash-live-preview`. These must be checked against current official provider documentation before being treated as supported production models.
- Existing phase reports for avatar work state that build and device acceptance are pending. No build or device verification is claimed here.
- Repository-wide tree enumeration, full source review, test inventory, security review, and full wiring trace are not yet complete.

## Provider and feature compatibility checkpoint

### Gemini model catalog — official documentation checked 2026-11-02

- `gemini-3.8-live` is listed as the stable/default Live API model.
- `gemini-3.8-live-extended-thinking` is also listed as a Live API model.
- `gemini-3.1-flash-live-preview` is listed as a legacy preview model; Google recommends migration to Gemini 3.8 Live.
- The current official catalog does not list `gemini-3.1-flash-agent` as a public Gemini API model. In the inspected app code this appears to be an internal UI preference sentinel that is translated to the legacy 3.1 Live ID before the WebSocket setup. Do not send that sentinel to the API.
- The Live API docs show the same BidiGenerateContent WebSocket protocol family used by the project. The current project authenticates the WebSocket with an `x-goog-api-key` header; the official raw-WebSocket quickstart currently documents a `key=` query parameter (and ephemeral-token option). This auth-method difference needs an explicit compatibility test and a safer client credential strategy; do not silently move permanent keys into URLs.
- The current Gemini model catalog should be treated as the source of truth before expanding the selector.

Official references:
- https://ai.google.dev/gemini-api/docs/models
- https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live
- https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket

### Voice providers

- Existing `VoiceSynthesisRouter` uses Android `TextToSpeech`; the Gemini Live WebSocket is a separate native audio conversation path.
- No ElevenLabs provider/API-key implementation was found in targeted Part 7 searches.
- ElevenLabs documents a REST streaming TTS endpoint at `POST /v1/text-to-speech/{voice_id}/stream`, with `xi-api-key`, `model_id`, and streamed audio bytes. The endpoint/model/output format must be selected against actual account entitlements; do not assume every PCM format or voice-library operation is available to every plan.
- Official references:
  - https://elevenlabs.io/docs/api-reference/text-to-speech/stream
  - https://elevenlabs.io/docs/api-reference/streaming

### Mem0 cloud memory

- No Mem0 implementation was found in targeted Part 7 source searches.
- Mem0 Platform's official quickstart documents authenticated API calls, add-memory, search-memory, and per-user scoping via `user_id`. This supports an optional cloud adapter design, but it does not remove the need for local-first operation, explicit opt-in, conflict rules, deletion propagation, and secure credential handling in JARVIS.
- Official reference: https://docs.mem0.ai/platform/quickstart

### Profile, account, and subscription

- Existing settings expose a local profile-name edit and voice-enrollment state; this is not a full account/profile system.
- Targeted source searches did not identify email/profile-image management, a deployed authentication backend, subscription entitlement service, credits ledger, or license verification endpoint. The Part 7 README itself states there is no deployed verification backend or agreed verification protocol.
- These features must remain clearly unavailable/unverified until a real backend contract exists. UI must not fabricate subscription, credit, or verification status.

## Phase tracker

| Workstream | Status | Notes |
|---|---|---|
| Phase 0 — Repository and architecture audit | IN PROGRESS | Initial source/config inspection recorded; full tree and execution-path audit pending |
| Phase 1 — Avatar ecosystem | IN PROGRESS | Existing gallery, overlay, positioning, and state reactions found; persisted avatar identifier validation implemented and regression test added; mode/lifecycle review pending |
| Phase 2 — Memory ecosystem | IN PROGRESS | Local Room, chat, vector retrieval, deletion and migrations found; cloud consent/sync and recovery need review |
| Phase 3 — Profile/account/subscription | BLOCKED | Local name edit/voice enrollment exist; account identity, email/photo, subscription/credits, and license backend not found in targeted audit; backend contract required |
| Phase 4 — AI model and voice ecosystem | IN PROGRESS | Gemini 3.8 Live verified as stable; 3.1 Live is legacy preview; ElevenLabs absent; current voice router is Android TTS plus Gemini Live; integration paths need design/review |
| Phase 5 — Settings integration | IN PROGRESS | Existing settings activities found; all controls need execution-path verification |
| Phase 6 — Deep implementation | NOT STARTED | Begin after source inventory and interface map |
| Phase 7 — Sub-agent engineering | BLOCKED | No multi-agent execution tool has been established in this session; sequential work is available |
| Phase 8 — Cross-module integration | NOT STARTED | Requires workstream implementation |
| Phase 9 — Automated tests and code review | IN PROGRESS | Existing migration instrumentation test found; test inventory and execution capability pending |
| Phase 10 — Bug-fix loop | NOT STARTED | Begin as defects are confirmed |

## Implementation checkpoint — avatar preference validation

Status: IMPLEMENTED (source committed); TESTED status remains pending.

- Finding: `AppPreferences.taskOverlayAvatar` validated values in its setter, but its getter returned the raw SharedPreferences value. A stale, manually modified, or malformed stored value could therefore bypass the setter allowlist and be interpolated into the WebView asset URL by `TaskProgressOverlay`.
- Change: centralized the 17 permitted bundled avatar identifiers in `AppPreferences`; getter now falls back to `ghost` unless the stored value is allowlisted; setter uses the same allowlist.
- Regression test added to `AppPreferencesSecurityInstrumentedTest.invalidPersistedAvatarFallsBackToBundledDefault`: seeds an invalid persisted path, asserts the getter returns `ghost`, and verifies invalid writes also resolve to `ghost`.
- Commits:
  - `d6cfffe78a35f10d9dda804f3d2cd8ae06461687` — preference validation fix.
  - `82250afe237a26499de7866a6facdb9c0961ba1c` — regression test.
- Files modified:
  - `Part 7/app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt`
  - `Part 7/app/src/androidTest/java/com/jarvis/assistant/data/preferences/AppPreferencesSecurityInstrumentedTest.kt`
- Verification: both changed source regions and the test insertion were fetched again from `main`. No test task was executed. No APK was built.

## Verification ledger

- GitHub repository metadata read: VERIFIED.
- main head commit inspected: VERIFIED.
- Part 7 Gradle configuration read: VERIFIED.
- Part 7 manifest and accessibility XML read: VERIFIED.
- Core avatar, voice, memory, mission, and accessibility source files partially inspected: VERIFIED.
- Full repository tree enumerated: NOT STARTED.
- Full source implementation reviewed: NOT STARTED.
- Unit/instrumentation tests executed: NOT RUN.
- APK build: PROHIBITED / NOT RUN.
- Physical-device testing: OUT OF SCOPE / NOT RUN.
- GitHub write/commit for progress checkpoint: VERIFIED (`0d5089b742ac926889921e3174bc79b207c84008`).

## Next execution steps

1. Enumerate and classify the complete Part 7 source/resource/test tree without mixing historical project snapshots.
2. Inspect the complete service, preferences, data, settings, navigation, and test execution paths.
3. Trace avatar mode persistence and service/process lifecycle.
4. Trace memory CRUD, retrieval, export/import, migration, encryption, and consent boundaries.
5. Trace provider/model/personality selection through the live audio session.
6. Identify actual account/profile/subscription capabilities and backend blockers.
7. Implement focused, backward-compatible source changes with tests.
8. Update this file after every meaningful implementation group.

No APK task may be run during this work.
