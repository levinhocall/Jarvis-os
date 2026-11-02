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


### Verification trigger and final chat UI polish

- Android CI now triggers on pushes and pull requests affecting `Part 7/**` or the workflow itself, while retaining manual dispatch. It runs unit tests and lint only; it does not assemble an APK.
- Chat History now clears the adapter's retained list when the result becomes empty.
- New Chat confirmation now accurately says prior conversations remain available in History (the old session is archived, not deleted).
- Commits: `8b60385`, `b22371d`, `5ff9e94`.
- GitHub commit status was queried immediately after enabling the push trigger and had no reported status at that moment. Do not interpret an empty status response as a passing run; check the Actions result after the runner starts.


### Final race-safety and regression follow-up

- Legacy-history read/import/clear now executes under the same repository mutex as new-session creation and full-history deletion. A clear action can no longer race with a previously-read legacy snapshot and resurrect it afterward.
- The service's compatibility migration and the UI's startup migration both use the serialized repository API.
- Added a mapper regression test proving pending messages are never paired across separate conversation sessions.
- Confirmed from current `main` source that Chat History no longer reads the legacy preference store, the main chat UI observes Room, and MemoryManager no longer receives ChatRepository.
- Commits: `09d7154`, `a65cdff`, `d0048a5`, `326c7d9`, `9876242`, `5ab37ac`, `778dbe9`, `b22371d`, `5ff9e94`, `d703fea`.
- Automated CI triggers are configured for main pushes/PRs affecting Part 7 and the workflow. The GitHub status endpoint returned no status records for the inspected commits at the time of the last check. CI is therefore **not verified**.


### Universal action dispatcher concurrency remediation

- Finding: circuit-breaker state used mutable fields inside a `ConcurrentHashMap`. Concurrent tool executions could race while incrementing failure counts, opening/resetting a breaker, or reading its state.
- Change: `CircuitState` is now immutable. Breaker creation/expiry transitions, failure increments, and explicit resets use atomic map `compute` operations; successful execution replaces the state with a clean immutable snapshot.
- Commit: `80507531d6e397eb756b23321082f5d030c08d46`.
- Verification: the updated source was fetched back from `main` and inspected. Unit tests and compilation have not been run.

### CI runner status after latest source push

- The push-triggered Android CI workflow did start a new run for dispatcher commit `80507531d6e397eb756b23321082f5d030c08d46` (run #196, ID `41412161267`).
- GitHub reported `startup_failure`; the run created **zero jobs**. This is not a Gradle compile/test failure because no job started, and it is not a passing verification.
- Run URL: https://github.com/levinhocall/Jarvis-os/actions/runs/41412161267
- The underlying Actions runner/service issue remains an external verification blocker. No APK task is configured or requested.

### Floating avatar WORKING / ALWAYS mode correction

- Finding: the service treated an idle session/standby while the app was backgrounded as sufficient to keep the avatar visible in `working` mode. That contradicted the preference contract that WORKING mode shows the overlay only while an actual task snapshot exists.
- Change: idle avatar visibility is now controlled only by `taskOverlayMode == "always"`. A real `AgentTaskSnapshot` continues to display task progress in either mode. Removed unrelated app-foreground/session/standby flows from the idle-visibility combine path and removed those values from the overlay refresh fingerprint.
- Commits: `7447c6670188aafb2110485023f7c53c95bc3a24`, `0a83d7c775dedd34c98c00e3b57ae7290d9d57c7`.
- Verification: updated service source still requires compile/unit verification and manual Android 16 overlay lifecycle checks.
- Added `concurrentFailuresAtomicallyOpenCircuitBreaker` to `UniversalActionTest` to exercise simultaneous failing tool calls and assert subsequent calls are blocked once the threshold is reached.
- Test commit: `fdae4785797df2c4420c650a54173debcc8a7e22`. Test source was fetched back and inspected; execution remains pending.

### Voice collaborator hardening and regression coverage

- `VoiceSessionManager` now serializes connect/disconnect callbacks with a coroutine mutex. A new session waits for an in-flight teardown barrier; stale handshakes are disconnected before releasing the connection lock; session state callbacks are synchronized.
- Added a regression test proving a rapid stop/restart does not start the next connection before the previous disconnect callback completes.
- `AudioPipelineManager` now transfers an owned copy of every PCM frame to its downstream callback. Previously only muted frames were copied; normal and barge-in frames reused the capture buffer and could be corrupted if a consumer retained/queued it.
- Added a regression test for audio-frame ownership.
- Commits: `88a42c99b3f3912524662407b168745c42808293`, `6a7462951e010ff103fb8d5af9b35a24265f85fa`, `254138cb5ebb9bcd3ff3e6242069d75ceaec9ab7`, `eabc45ffc0abf48985736d4e0a7e0601ec4fc2ae`, `e63d297c3e0d349ce11c3b73a7ae2f4be22cd8d8`.
- These collaborators are hardened and tested in source, but they are **not yet the production audio/socket owner**. `JarvisConversationService` still owns the active `AudioRecorder` and `GeminiLiveWebSocket`. Replacing those simultaneously without an end-to-end integration test would risk breaking the live voice path; the managers are not falsely marked as production-wired.

### Remaining blockers that cannot be truthfully closed from repository edits alone

- Android 16 Accessibility restricted-settings, foreground-service policy, microphone routing/AEC, overlay cutouts/insets, and boot/restart recovery require a physical Android 16 device and user-granted special access. Static source review cannot certify these.
- Neural semantic embeddings require selecting and bundling/licensing a concrete model and validating its APK/runtime footprint; the current lexical/hash representation is not a neural embedding system.
- Mem0 cloud sync, account/authentication/subscription/credits, and external TTS/provider integrations require real endpoint contracts, credentials, and data-consent decisions. No fake backend or placeholder success path was added.
- `AutonomousGoalAgent` remains a narrow legacy product-search flow; the production general mission path is `JarvisUnifiedRuntime` → `MissionEngine`/`LongHorizonMissionCoordinator`. `SelfHealingAgentLoop` and `UniversalActionDispatcher` remain separate frameworks without a safe one-to-one adapter to the production `ToolRegistry`; they were not wired as a second execution authority.
- CI remains blocked before job creation. Latest runs #197–#204 all concluded `startup_failure`; run #204 is https://github.com/levinhocall/Jarvis-os/actions/runs/41416628979. GitHub returned zero jobs for the inspected run, so Gradle, lint, and tests did not execute. This is not a code test failure and not a pass.
- No APK was assembled.

## V4 integration batch — voice, mission recovery, overlay boot recovery, neural memory and external TTS

Status: **SOURCE IMPLEMENTED IN PART; runtime verification and remaining integrations are still open.** No APK was built.

### Implemented in source

1. **Overlay boot/app-update recovery**
   - Added `OverlayBootReceiver` for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
   - It starts an overlay-only service path only when the existing overlay is enabled, set to `always`, and Android's draw-over-other-apps access is already granted.
   - The new `ACTION_RESTORE_OVERLAY` path starts the service as media-playback-only and deliberately does not start microphone capture, wake-word listening, or a voice session from boot.
   - Commits: `26854ab`, `adf7256`, `417dcf6`.
   - Not yet verified on Android 16 hardware. Android may still defer/block startup under OEM battery policies or foreground-service policy; the permission must already be granted.

2. **MissionEngine + self-healing orchestration**
   - Production `MissionEngine.executeMission` now invokes the shared `SelfHealingAgentLoop` around the existing `executeSingleStep`/ToolRegistry authority. It does not introduce a second tool executor.
   - Idempotent failures can retry only after the recovery callback re-captures a fresh, non-secure Accessibility screen for accessibility tools.
   - A tool result that may have mutated state but lacks verification is never retried. The mission is marked UNVERIFIED and later steps are stopped.
   - Failed recovery stops before a blind retry.
   - Added regression tests for mutation-without-verification and failed recovery.
   - Commits: `3a7f052`, `7e6c5ef`, `47397fb`.
   - This wires self-healing into the production sequential mission engine. A general multi-agent/sub-agent delegation scheduler is **not** yet implemented; the current LLM planner and ToolRegistry remain the production planning/execution path.

3. **Self-hosted Mem0-style personal memory backend**
   - Added `memory-backend/` FastAPI service using SQLite/WAL and pretrained `sentence-transformers/all-MiniLM-L6-v2` (384-dimensional neural embeddings).
   - Added add/search/list/get/update/soft-delete APIs plus batch `/v1/embeddings`.
   - Docker Compose persists the database and model cache. Port 8765 binds to loopback by default.
   - This is a Mem0-style, self-hosted alternative, **not** the official Mem0 Cloud product and not a claim of full Mem0 API parity.
   - No login, signup, account, subscription, or application authentication layer was added. `user_id` is only a data-partition label, not a login identity.
   - Commits: `d50c1fe`, `472019d`, `d20459c`, `f7f5a61`, `5a3ed7b`, `f6524d4`.

4. **Android neural embedding and sync foundation**
   - Added `RemoteNeuralEmbeddingProvider`: uses the backend's MiniLM embeddings when configured/reachable and falls back to local 384-D deterministic vectors when offline.
   - MemoryRepository gradually re-embeds up to 24 stale legacy rows per semantic-search request.
   - Added Room sync query that includes deleted/superseded rows; WorkManager queues six-hour connected-network sync only when `JARVIS_MEMORY_SYNC_ENDPOINT` is configured.
   - Stable per-row IDs make repeated uploads idempotent; local deleted/superseded/sensitive/expired memories are not uploaded and previously synced rows receive remote soft-delete.
   - Commits: `b4bf041`, `268ba82`, `cb2007f`, `ac5661e`, `3e763bf`, `2099176`, `f6524d4`, `bb9418d`, `effb180`, `b9f8953`, `b84fe70`.
   - Current sync is **one-way local-to-server backup/sync**, not bidirectional multi-device merge. The endpoint is not configured in this repository. Full remote-to-local reconciliation and conflict resolution remain open.

5. **External voice provider**
   - Added ElevenLabs REST TTS using the existing encrypted provider-key pool under provider ID `elevenlabs`.
   - API Connection settings now expose an ElevenLabs key entry/management row. When a key exists, the TTS router tries ElevenLabs multilingual v2; on network/API/playback failure it falls back to Android TextToSpeech.
   - Voice ID is configurable through `JARVIS_ELEVENLABS_VOICE_ID`; default is the provider's standard Rachel voice ID.
   - Commits: `8891b81`, `15f9ab5`, `667aa72`.
   - Requires the user's own ElevenLabs API key and a plan that permits the selected voice/model. No app login/auth was introduced. Provider call and playback have not been live-tested.

### Still open / not to be marked complete

- **Production WebSocket/recorder manager ownership:** `JarvisConversationService` still owns the active `GeminiLiveWebSocket` and `AudioRecorder`. The hardened `VoiceSessionManager` and `AudioPipelineManager` remain collaborators not yet substituted as the actual live transport/capture owners. Forcing them into the current callback-heavy voice service without an integration test would risk two reconnect loops or duplicate microphone ownership. This item remains OPEN.
- **Full agent orchestration:** self-healing is now wired into MissionEngine, but delegated sub-agents, independent worker scheduling, shared mission budgets, and result reconciliation are not complete.
- **Bidirectional memory sync:** remote-to-local pull, cross-device conflict resolution, remote tombstone download, and sync cursor persistence remain open. Current implementation pushes local state to the private backend.
- **Backend deployment/configuration:** Docker service source is committed, but it has not been deployed to a host. The Android endpoint remains unset; the first backend start must download the MiniLM model. Keep the service private; the Compose default binds only to loopback.
- **Physical Android 16 checks:** boot receiver startup, OEM background restrictions, overlay restoration, voice audio routing/AEC, and external TTS playback need device verification.
- **Build/test verification:** no Gradle compile, unit test, instrumentation test, lint, or APK build has been run in this pass. Source commits and authored tests are not proof of a successful build.
- **CI:** prior Actions runs failed with `startup_failure` before job creation (zero jobs). The latest commits still require a successful workflow run; do not infer success from a push trigger.

### No-auth decision

- No login, signup, user account, subscription, or app authentication system has been added.
- External API keys are only provider credentials for optional ElevenLabs TTS. The memory backend's `user_id` is a partition label only.
- The backend intentionally has no application auth. It must remain loopback/private-LAN or behind trusted network-level controls; never expose the unauthenticated memory API directly to the public internet.

### Verification boundary

- GitHub source commits: VERIFIED as pushed to `main`.
- Android/Gradle compilation: NOT RUN.
- Unit/instrumentation tests: NOT RUN.
- Backend container/model startup: NOT RUN.
- Remote memory round-trip: NOT RUN.
- ElevenLabs live request/audio playback: NOT RUN.
- Android 16 boot/overlay/voice device test: NOT RUN.
- APK build: NOT RUN, per instruction.


### Follow-up hardening after the integration batch
- ElevenLabs playback now cancels the previous HTTP request/player before starting a new utterance, reports external playback as speaking, and stops stale external audio when switching back to local TTS. Commit: `050bfaf`, `a28ec5c`.
- Overlay-only boot restoration now returns `START_NOT_STICKY` so a system null-intent restart cannot accidentally enter the normal microphone standby path. Commit: `0d52367`.
- These are source-level hardening changes; they have not been compiled or device-tested.

## Follow-up integration batch — production voice ownership and backend health

Status: **VOICE OWNERSHIP IS NOW WIRED IN SOURCE; build/device verification is still pending.** No APK was built.

### Production voice manager wiring

- JarvisConversationService now constructs VoiceSessionManager as the single WebSocket session/reconnect authority for the live conversation.
- GeminiLiveWebSocket has an explicit external reconnect-owner mode. In that mode, transport failures, quota/key rotation and session renewal notify the service/session manager instead of scheduling a second internal retry loop.
- API-key rotation is preserved across manager-driven reconnects.
- Fatal provider access/model/configuration errors move the session manager to ERROR rather than retrying indefinitely. Invalid individual keys can still rotate to another configured key.
- VoiceSessionManager ignores duplicate close/drop callbacks after the first LIVE→RECONNECTING transition and exposes a teardown barrier so a new session waits for the previous socket to close.
- Added AudioPipelineManager adapter construction around the existing AudioRecorder. The production service now uses this adapter while preserving the recorder's existing AEC/AGC/noise-suppression, VAD metadata, pre-speech buffering and barge-in callback. The adapter does not create a second AudioRecord.
- Microphone hardware initialization now runs on the IO dispatcher, and an asynchronous start/stop race explicitly closes the recorder if the conversation has already ended.
- Added regression tests for duplicate socket drops, fatal transport failures, waiting for socket teardown, and delegated recorder ownership.
- Key commits: 69e4be6, 9fc92b6, b3a710b, 389de5a, 4a1c126, e2d083d, b93b27a, d7eefd7, 824010f, 5c032db, c19e579, 4726e01, 0dc5510, d81d903, 08030fe, aac487a.
- This is a source-level production wiring change. It has NOT been compiled, executed against Gemini Live, or tested on the target Android 16 device.

### Backend health monitoring

- Added a Docker Compose healthcheck against /health, with 30-second intervals, retries and a 20-minute startup grace period for the first MiniLM model download.
- Kept the default loopback-only bind and persistent Docker volumes.
- Added setup documentation for checking backend health.
- Key commits: 8869b09, 5f3afb7, 14384cf.
- Healthcheck and restart policy do not make the service publicly hosted or guarantee an unhealthy container is automatically restarted by Compose. A real VPS deployment and operational monitoring still require a host.

### Remaining V4 work — current status

- **Multi-agent orchestration — source integrated:** the production `JarvisUnifiedRuntime` preflight now invokes three independent Gemini text-only reviewers (Planner, Critic, Verifier) concurrently. The shared per-mission budget is capped at 3 calls, 3 concurrent workers, 24 seconds overall, and 400 output tokens per reviewer. Reports are reconciled into `CouncilDeliberation`; a Critic `BLOCK:` result stops execution. Sub-agents have no ToolRegistry executor and cannot directly operate the device. MissionEngine/ToolRegistry remains the sole execution authority. Tests were added for parallelism, shared budget, production preflight and critic blocking; they have not run.
- **Bidirectional memory sync — source integrated:** Room database version 6 adds stable per-memory sync IDs, sync acknowledgements and a durable compound pull cursor. The Android worker now uploads local changes, downloads remote changes, merges by stable sync ID, uses server update order after local push for conflict resolution, and propagates remote/local tombstones. Backend listing supports `include_deleted=true` and stable `(updated_at,id)` keyset pagination. Local secrets remain local; their remote copies are tombstoned without erasing the local secret. Backend cursor/tombstone tests were added. End-to-end multi-device sync has not been run.
- **Always-on backend deployment — deployment package prepared, host deployment still open:** added systemd boot startup, periodic health recovery service/timer, and a recovery script, in addition to Docker restart policy, persistent volumes and healthcheck. No VPS/host has been provisioned or deployed from this session. Android `JARVIS_MEMORY_SYNC_ENDPOINT` remains unset by default. Actual 24/7 operation requires a host and private TLS/VPN route; do not expose the personal-memory API directly to the public internet.
- **Runtime verification — open:** Gemini Live, ElevenLabs TTS, MiniLM model download/embeddings, remote sync round-trip, and Android 16 microphone/AEC/barge-in/overlay/boot behavior still require live credentials, a running backend and a physical device.
- **Build verification — open:** Gradle compilation, unit tests, instrumentation tests and lint have not completed. GitHub Actions run #280 ended in `startup_failure` before any job was created (zero jobs): https://github.com/levinhocall/Jarvis-os/actions/runs/41464231616. This is not a code test failure and not a passing verification.
- **APK generation remains excluded from the current work scope:** do not assemble, sign or publish an APK in this phase. The user will handle APK generation last.

### Verification boundary

- Multi-agent orchestration source and tests: pushed to `main`; not compiled or executed.
- Bidirectional sync source, Room v5→v6 migration, backend cursor/tombstone API and backend tests: pushed to `main`; not run against a live backend.
- VPS deployment units/scripts: pushed to `main`; not installed on a host.
- Kotlin compilation, unit/instrumentation tests and lint: NOT VERIFIED.
- Gemini Live, ElevenLabs, neural embedding model and remote memory round-trip: NOT LIVE-TESTED.
- Android 16 physical-device acceptance: NOT TESTED.
- APK: NOT BUILT, as requested.

### Final hardening after the V4 scope update

- Production council preflight now calls the parallel reviewer pipeline from the path used by JarvisUnifiedRuntime; ordinary one-step commands bypass delegation to preserve responsiveness. Critic blocking, bounded failure isolation and advisory review summaries in successful mission results are enabled.
- Room deletion APIs now preserve local tombstones for sync instead of physically erasing rows before the remote can receive the deletion. User edits and pinned/status changes update timestamps used by the sync worker.
- Cross-device merge keys only on stable sync IDs; device-local SQLite row IDs are not used to identify remote records. Server update order is authoritative after the local push phase, avoiding device-clock skew.
- Privacy guard: a remote tombstone created by the local secret-scrubbing path cannot erase the original sensitive local-only memory.
- Added backend unit tests for same-timestamp keyset pagination and tombstone visibility. Android now schedules an initial sync at app startup plus six-hour periodic sync. Test command is documented in memory-backend/README.md.
- Added systemd startup and periodic health-recovery units/scripts for an Ubuntu/Debian VPS. They are deployment assets only; no host has been installed or provisioned.
- Latest Android CI inspection remains run #280, `startup_failure`, zero jobs: https://github.com/levinhocall/Jarvis-os/actions/runs/41464231616.
- Additional commits: `0d15efa`, `3dcd46d`, `91f29d2`, `4fcbf3c`, `f8e13c0`, `d98eae8`, `7efe64c`, `286c896`, `b9fbc09`, `e40f759`, `3038671`, `90107ce`, `6752619`, `ab58472`, `01d7c4e`, `663dfdc`, `3a76979`, `f88ee1c`, `3155418`, `a2b6edf`, `63c2300`, `cfde06c`, `6a6394e`.

APK assembly/signing/publishing remains deliberately outside this phase.


## 2026-11-03 — User decision: strictly on-device memory

Status: SOURCE CHANGE COMMITTED TO MAIN; runtime/build verification NOT RUN.

- User explicitly chose phone-local memory instead of cloud/Claude/hosted memory storage.
- Part 7 production wiring now constructs `MemoryRepository(memoryDatabase.memoryDao())`, which selects the existing `DeterministicLocalEmbeddingProvider` default. Memory records and embeddings remain in the existing Room database protected by the SQLCipher-backed open-helper path.
- Removed the production call that scheduled `MemorySyncScheduler`; no periodic or initial remote memory sync is enqueued by application startup.
- Gradle now hard-sets `MEMORY_SYNC_ENDPOINT` to an empty string. The legacy sync worker/provider and schema-v6 sync metadata are retained but dormant to avoid destructive schema changes and preserve existing user data. They are not part of the active production path.
- Cloudflare D1 is not required for JARVIS personal memory. The newly created `jarvis_memory` D1 database is unused and may be left empty or deleted by the owner.
- This is a local-only storage decision, not proof of successful SQLCipher runtime encryption, migration, recovery, or device persistence. Those still require execution tests.
- No APK was built. No Gradle, unit, instrumentation, lint, or physical-device test was run in this session.

### V4 remaining work after local-memory decision

1. **Memory security and persistence — OPEN:** execute SQLCipher + Room tests on a real Android runtime; verify keystore key lifecycle, plaintext-to-encrypted migration, rollback/crash recovery, corruption recovery, backup/export policy, and confirm no memory network traffic.
2. **Voice/runtime — OPEN:** physical-device tests for Gemini Live connection/auth, continuous conversation, interruption/barge-in, echo cancellation, TTS routing, microphone foreground-service behavior, and Android 16 restrictions. ElevenLabs integration remains absent/unverified unless separately prioritized.
3. **Accessibility/device control — OPEN:** Android 16 restricted-settings flow, service connection/reconnection, node discovery, event filtering, gestures, overlay permission/lifecycle, and real device-control execution.
4. **Multi-agent orchestration — SOURCE INTEGRATED, NOT VERIFIED:** run bounded-concurrency/budget/council reconciliation tests; verify failure/timeouts and ensure agents cannot invoke device tools without the mission executor's approval boundary.
5. **Missions and recovery — PARTIALLY IMPLEMENTED:** execute process-death restoration, interrupted-step safety, idempotency and checkpoint durability tests; verify every action result is reconciled rather than simulated.
6. **Camera/OCR/GPS/navigation — NOT RUNTIME VERIFIED:** test camera preview/capture, OCR accuracy, location permissions, route calculation and offline/error states on-device.
7. **Settings/profile/account/subscription — PARTIAL/BLOCKED:** verify every settings control's actual effect; no real hosted identity/subscription/credits verification backend is established, so do not show fabricated entitlement states.
8. **Build/CI — OPEN:** fix GitHub Actions startup_failure, run Gradle wrapper validation, unit tests and lint. Instrumentation tests need an emulator/device. APK build remains deferred by user request.
9. **Full integration audit — OPEN:** trace all production call paths, remove simulated-success paths, inspect secrets/logging/permissions, reconcile stale documentation and update the final V4 acceptance matrix.

### Verification truth

The above is a source-level configuration change only. It is not a claim that V4 is complete, that tests pass, or that the phone has been runtime-verified. Current baseline remains GitHub main HEAD plus these commits; build and device verification are still outstanding.

## 2026-11-03 — Continuation: enforce phone-only memory at executable boundaries

This entry supersedes earlier remote-sync deployment recommendations. Baseline checkout was clean at
`35ddb608396d95fdfd335a5f5a81740e95aafe27`; remote `main` was confirmed at the same SHA using `git ls-remote`.
The supplied checkout branch was `work`; a local `main` was created at that commit as directed.

### Implementation batch 1

- `JarvisApp.kt`, `MemorySyncScheduler.kt`, `MemorySyncWorker.kt`: cancel both legacy unique work names at startup. The persisted worker class now returns without database access or network calls. No upload implementation remains in that worker; schema-v6 metadata remains intact.
- `EmbeddingProvider.kt`: former Gemini and self-hosted adapters retain constructor compatibility but use only deterministic local vectors. They never read provider credentials or invoke an injected fallback. Model names identify the actual local representation so older vectors are re-embedded locally.
- `JarvisConversationService.kt`: removed automatic stored-profile, long-term-memory and historical-transcript injection into Gemini Live setup prompts. Current explicit conversation still uses the selected cloud voice provider; this is not an offline voice implementation.
- `MultiAgentCouncil.kt`: removed personal-memory retrieval from model reviewer context and handoffs; stopped logging the request text at deliberation start.
- `MemoryDatabase.kt`: fixed an invalid `RoomDatabase.Callback.onCorruption` override (corruption belongs to the SQLite open-helper callback). The SQLCipher helper now receives a forwarding SQLite callback that fails closed on corruption without invoking the default destructive handler. Existing migrations and database version remain unchanged. SQLCipher preparation/probe corruption behavior still needs device testing.
- `LocalOnlyEmbeddingTest.kt`: added two JVM regressions for legacy-adapter credential isolation, endpoint isolation and rejection of an injected network-capable fallback. Authored, NOT EXECUTED yet.

### Verification so far

- `git diff --check`: PASS.
- Remote baseline query: PASS after enabling command network access through the configured proxy.
- Initial Gradle attempts: blocked before compilation by unwritable default Gradle cache, then direct JVM network/DNS failures. Retried with a workspace cache and explicit JVM proxy configuration; final outcome recorded in the next entry.
- No Android runtime, emulator or physical-device testing. No APK-producing task invoked.
- Source changes are not proof of runtime privacy: local command outputs, external TTS, screen tools and user-directed search still require a broader outbound-payload audit.

### Implementation batch 2 — lifecycle, recovery and untrusted import hardening

- `VoiceSessionManager.kt`: cap each connection callback at 30 seconds, propagate coroutine cancellation, ignore establishment callbacks after stop/fatal error. Added stopped-session regression in `ConversationServiceCollaboratorsTest.kt`.
- `DatabaseEncryption.kt`: explicit non-destructive corruption handlers on every framework SQLite and SQLCipher migration/probe handle. This closes a second destructive-default path before Room creates its helper. API signatures checked against SQLCipher's tagged v4.20.0 source; Android execution still pending.
- `LongHorizonMissionCoordinator.kt`: checkpoint restoration preserves the union of current and checkpoint non-idempotent replay guards; restoring older state cannot authorize a completed consequential action again. Added stale-checkpoint regression in `LongHorizonMissionTest.kt`. Removed mission-goal text from creation logs.
- `LocationWeatherHelper.kt`: cancellable location continuation unregisters listeners on timeout/cancellation, late completion and registration races. No location permission bypass added.
- `TaskProgressOverlay.kt`: clear the active overlay reference and cancel animations on removal, avoiding status callbacks targeting a detached overlay.
- `MemoryRepository.kt`: ignore JSON null rows/null keys before import grouping. Gson can construct these despite Kotlin non-null declarations. Added malformed-backup regression in `MultiChatAndMemoryEngineTest.kt`.

These fixes are source changes pending the verification results below. No UI redesign, schema change or changes to Parts 1–6.
