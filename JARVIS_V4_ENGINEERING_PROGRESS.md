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
| Phase 0 — Repository and architecture audit | VERIFIED | Recursive repository inventory, Part 7 build/manifest/source/test/CI/branch audit, architecture map, findings register, and follow-up priorities documented in PHASE_0_REPOSITORY_AUDIT.md; runtime/build verification remains NOT RUN |
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

## Phase 0 completion checkpoint — deep static audit

Status: VERIFIED (static repository audit and architecture inventory).

- Audited main HEAD before report: `838f63b9bd3a46994eab7e206954f9fc6dbbfc8b`.
- Recursive Git tree: 1,575 entries; seven independent project snapshots identified.
- Part 7 inventory: 466 tracked files, 195 production Kotlin files, 55 unit-test source files, 6 instrumentation-test source files, 132 resource XML files, and 43 asset entries.
- Confirmed Part 7 is Kotlin 2.2.20 / AGP 8.9.1 / Gradle 8.11.1 / compileSdk 36 / targetSdk 36 / minSdk 26 / Java 17 / application ID `com.jarvis.assistant`.
- Confirmed Part 7 uses XML/AppCompat/ViewBinding and does not declare Jetpack Compose dependencies. Preserve the actual active project rather than force a rewrite.
- Reviewed application bootstrap, foreground conversation service, Gemini Live transport/model constants, accessibility service/config, overlay, mission engine, unified runtime, Room/SQLCipher database and key handling, migration tests, memory/vector code, chat repositories, manifest permissions, CI workflow, branch list, open PRs, and recent Actions run outcomes.
- Identified and documented P1/P2 risks: synchronous Room/SQLCipher startup; Room schema export disabled; SharedPreferences + Room dual chat history; in-memory default long-horizon mission checkpoints; migration tests not end-to-end Room/SQLCipher; unverified production call sites for several agent/voice collaborators; overlay lifecycle/mode/edge-snap gaps; deterministic hashed embeddings rather than a pretrained semantic model; broad special permissions; stale architecture docs; absent Mem0/ElevenLabs/account/subscription backends.
- Found 15 branches and reviewed relevant open PRs. PR #6/#7 contain APK assembly and are incompatible with the no-APK directive. PR #3 includes unrelated features, compileSdk downgrade, and a privileged permission; do not merge as an audit-only change.
- Current main workflow is manual-only and runs unit tests plus non-blocking lint, without APK assembly. Recent Actions runs fail at startup before creating jobs; no current-head verification exists.
- Created `PHASE_0_REPOSITORY_AUDIT.md` with architecture map, inventory, findings register, do-not-merge list, and next engineering order.
- Audit report commit: `8bdafb11e3cd4af5597bb021f60e5d77e9427c40`.
- No Gradle task, APK build, unit test, instrumentation test, lint, or physical-device test was executed.

## Follow-up remediation checkpoint — overlay docking

- Finding P0-09 (overlay drag did not snap to a dock edge) is now **FIXED IN SOURCE**.
- Added pure geometry policy `OverlayDockingPolicy` that clamps off-screen coordinates, chooses the nearest of four edges deterministically, applies an 8dp horizontal inset, and preserves the along-edge position.
- Connected drag release in `TaskProgressOverlay` to snap and persist the selected edge plus final coordinates.
- Added three JVM regression tests: left-edge snap, top-edge snap, and out-of-bounds coordinate clamping.
- Commits: `466d969` (policy), `2190961` (tests), `8aaaa27` (overlay integration), `35af3a3` (deterministic corner tie-break).
- Tests are authored but **NOT RUN**. No APK build or device test was performed.
- Remaining parts of P0-09 (mode semantics, lifecycle/process recovery, display insets and service restart) remain OPEN.

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


## Remediation batch — remaining P1 persistence/startup issues

Status: SOURCE CHANGES COMMITTED; execution verification pending.

### Fixed in source

1. **Long-horizon mission persistence**
   - Added `PersistentMissionCheckpointStorage` backed by Android Keystore AES-256-GCM EncryptedSharedPreferences.
   - Persists complete mission snapshots, milestones/steps, logs, output variables, non-idempotent execution IDs, and checkpoints.
   - Writes use synchronous `commit()`; a failed durable write throws instead of silently continuing.
   - Coordinator restores saved mission snapshots on construction. Missions found in EXECUTING/PLANNING/REPLANNING are forced to PAUSED after process recreation; no unattended automatic replay.
   - Production `JarvisApp` wiring supplies the persistent storage to `JarvisUnifiedRuntime`.
   - Added JVM coordinator recreation regression test and Android instrumentation test for encrypted storage/replay guard.
   - Key commits: `812efb1`, `b5c855f`, `d7ee144`, `03c64da`, `a078314`, `bb084a3`, `2ade49b`, `2378934`.

2. **Chat history single source of truth**
   - Main UI now observes Room session/message flows via `ConversationTurnMapper`, rather than the legacy SharedPreferences `ChatRepository` StateFlow.
   - Voice conversation finalization and offline local replies persist to Room; duplicate writes to legacy chat preferences were removed.
   - `MemoryManager` no longer writes independent chat turns to the legacy store. Its short-term cache is process-local; durable transcript/context remains in Room.
   - Added idempotent historical-turn merge using stable message IDs. Legacy preference history is cleared only after Room import succeeds, so an interrupted import can be retried.
   - Added mapper JVM tests and a Room instrumentation test for retry-safe historical import.
   - Key commits: `f3c508b`, `34c64ac`, `0b55de1`, `17f4e15`, `1dea1fe`, `793daae`, `68ed54d`, `2b5b175`, `2afdfb7`, `58dd0a3`.

3. **Database startup blocking**
   - Removed synchronous `runBlocking(Dispatchers.IO)` encryption preparation and eager `openHelper.writableDatabase` from `MemoryDatabase.getInstance()`.
   - Added a deferred SQLCipher open-helper factory. SQLCipher library loading, Keystore access, plaintext migration, and DB opening occur on the first Room database open, and the wrapper rejects main-thread opening.
   - The existing Room corruption callback still marks key initialization complete after a successful open.
   - Key commits: `b771c6d`, `c1913d2`.
   - This is a static implementation change; first-open thread behavior has not yet been exercised on a device.

4. **Room schema export**
   - Enabled Room schema export and configured KAPT to write snapshots to `Part 7/app/schemas`.
   - Added a schema directory policy note. The version-5 JSON snapshot will be generated by the next successful compile; it has not been generated in this session, and historical v1–v4 snapshots are not fabricated.
   - Key commits: `fa75970`, `0cb8f22`, `a70d032`.

5. **CI signal quality**
   - Removed `continue-on-error: true` from Android lint, so lint failures now fail the manual verification workflow.
   - Workflow still runs unit tests and lint only; it does not run assembleDebug/assembleRelease or produce an APK.
   - Key commit: `3bec8e5`.

### Earlier overlay fix retained

- Drag release now docks the floating avatar to the nearest edge, clamps off-screen coordinates, persists edge and position, and has three regression tests.
- Key commits: `466d969`, `2190961`, `8aaaa27`, `35af3a3`, `ccd272f`.

### Not yet fixed / requires external contracts or runtime evidence

- CI runner startup failures previously returned `startup_failure` with zero jobs. This is a GitHub Actions runner/service startup condition, not a failing Gradle job. The available GitHub integration in this session cannot dispatch a new manual workflow run, so current HEAD has not been verified by Actions.
- Room v5 schema snapshot and real SQLCipher Room migration validation are still pending a successful Gradle/instrumentation run.
- Android 16 accessibility restricted-settings, foreground-service, overlay insets, and lifecycle behavior require physical-device testing.
- Voice collaborators (`VoiceSessionManager`, `AudioPipelineManager`) and selected agent/dispatcher classes remain unverified for production wiring. They were not forcibly injected into the monolithic conversation service because that would duplicate active audio/socket ownership without a tested integration contract.
- Semantic embeddings remain deterministic local hashed/lexical vectors, not a pretrained neural embedding model.
- Mem0 cloud sync, account/profile authentication, subscription/credits/license backend, and external provider integrations require real service endpoints, credentials, consent rules, and data contracts. No fake backend or entitlement state was added.
- Overlay WORKING/ALWAYS mode semantics and service restart/boot recovery remain partially open.
- The mission persistence test is added but not executed. The SQLCipher migration tests, new chat tests, mapper tests, overlay tests, full unit suite, lint, and device checks have not been run.

### Verification boundary

- Changes above were committed to `main` through GitHub Contents API and key files were fetched again from the branch after commits.
- No Gradle command was executed.
- No unit or instrumentation test was executed.
- No APK was built.
- Source commits are not equivalent to a successful compile or runtime acceptance.


### Follow-up wiring correction

- Voice-session startup validation now accepts the active rotated Gemini key before falling back to the primary API key, matching the key-selection path used by the foreground conversation service.
- The encrypted mission persistence instrumentation test now leaves a second pending milestone step so it verifies the intended interrupted-EXECUTING → PAUSED recovery path rather than a naturally completed mission.
- Commits: `29743c0`, `01ab4eb`.


### Chat-history UI and race-condition follow-up

- Fixed the remaining legacy-store UI paths:
  - Chat History bottom sheet now reads the last 100 Room messages across sessions.
  - Clear History now transactionally deletes all Room messages and sessions, then clears the legacy preference store.
  - New Chat no longer clears legacy history prematurely; it archives the current Room session and starts a fresh one.
- Legacy history migration now runs when the main ViewModel restores the conversation, before normal new-session use. Service startup retains the same idempotent fallback.
- Serialized legacy-history read/import/clear, new-session creation, and full-history deletion with the repository write mutex. This closes a race where an in-progress migration could restore messages immediately after the user cleared history.
- ConversationTurnMapper now groups by session ID before pairing user/assistant messages, preventing a pending user message from one session being paired with an assistant message from another.
- Added instrumentation coverage for clearing all sessions/messages and a JVM regression test for cross-session message pairing.
- Commits: `cece692`, `ac5d05d`, `379b8dd`, `13b6b03`, `17a68e4`, `bb95b1a`, `a7f69b8`, `9876242`, `5ab37ac`, `778dbe9`.
- Verification remains pending: these tests were added but not executed in this session.
