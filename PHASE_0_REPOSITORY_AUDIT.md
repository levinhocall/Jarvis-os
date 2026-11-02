# JARVIS OS — Phase 0 Deep Repository & Architecture Audit

Audit date: 2026-11-02
Repository: https://github.com/levinhocall/Jarvis-os
Audited ref: `main`
Audited HEAD: `838f63b9bd3a46994eab7e206954f9fc6dbbfc8b`
Active application snapshot: `Part 7/`
Namespace / application ID: `com.jarvis.assistant`

## 1. Executive result

**Phase 0 static repository audit: VERIFIED.**

The repository tree, historical project layout, active Part 7 build configuration, Android manifest, accessibility configuration, application bootstrap, foreground voice service, Gemini transport, mission orchestration, memory/database/security components, test inventory, CI workflow, branch list, open pull requests, and recent workflow outcomes were inspected.

This is an audit checkpoint, not a claim that the application compiles or that tests pass. No Gradle task was executed. No APK was built. No device testing was performed.

### High-confidence conclusions

1. The repository contains seven separate Android Studio project snapshots. Part 7 is the latest project documented by the root README and is the correct V4 implementation target. Parts 1–6 must not be merged wholesale into Part 7.
2. Part 7 is Kotlin + Android Views/XML/AppCompat/ViewBinding, **not Jetpack Compose**. Its Gradle file does not declare Compose. A Compose conversion would be a major rewrite and conflicts with preserving the active codebase.
3. Part 7 already contains substantial voice, accessibility, mission, memory, vision, and security implementations. The main risk is not simply missing source files; it is inconsistent ownership, duplicate/overlapping execution paths, unverified wiring, and unexecuted tests.
4. Room is configured with SQLCipher and Android Keystore-wrapped database key material. The database has explicit V1→V5 migrations and corruption snapshot preservation. However, schema export is disabled and the current migration instrumentation test manually calls migration objects using framework SQLite rather than validating Room's actual encrypted open/migration path.
5. `JarvisApp.onCreate()` synchronously opens `MemoryDatabase`; `MemoryDatabase.getInstance()` invokes `runBlocking(Dispatchers.IO)` for encryption preparation and opens Room's writable database before returning. This makes potentially expensive database encryption/migration work part of application startup and is a startup-latency/ANR risk.
6. There are two conversation persistence paths: legacy `ChatRepository` backed by `AppPreferences` JSON history and Room-backed `ConversationRepository`. Both are wired into the application; `MemoryManager` still writes through the legacy repository while chat/session code uses the Room repository. This requires an explicit source-of-truth and migration/reconciliation decision.
7. Several architecture classes exist but production call sites were not found in targeted reference searches: `AudioPipelineManager`, `VoiceSessionManager`, `AutonomousGoalAgent`, `SelfHealingAgentLoop`, and `UniversalActionDispatcher`. They must be treated as unverified/disconnected until call paths are demonstrated; class presence and unit tests are not proof of production integration.
8. The current GitHub Actions workflow is manual-dispatch only and runs unit tests plus lint; lint is non-blocking. The latest inspected workflow run (#192) ended in `startup_failure` with zero jobs, and the 20 most recent listed runs had the same conclusion. These runs were on earlier commits, not the audited HEAD. No CI verification exists for the audited HEAD.
9. Two open Phase 0 pull requests are not safe to merge as-is: one introduces unrelated features, downgrades compileSdk, and adds a protected permission; the other assembles a debug APK, which is explicitly prohibited by the project owner's current directive.

## 2. Repository inventory

The recursive Git tree at the audited HEAD contains 1,575 tree entries.

| Project snapshot | Tracked files | Kotlin files | XML files | Test source files |
|---|---:|---:|---:|---:|
| Part 1 | 57 | 19 | 24 | 0 |
| Part 2 | 73 | 22 | 21 | 0 |
| Part 3 | 114 | 49 | 34 | 0 |
| Part 4 | 133 | 49 | 37 | 0 |
| Part 5 | 136 | 52 | 37 | 0 |
| Part 6 | 144 | 54 | 40 | 0 |
| **Part 7** | **466** | **256** | **133** | **61** |

Part 7 breakdown:
- 195 production Kotlin files under `app/src/main/java`.
- 55 local unit-test Kotlin files under `app/src/test`.
- 6 Android instrumentation-test Kotlin files under `app/src/androidTest`.
- 132 XML resources under `app/src/main/res` (one additional XML file is outside res).
- 43 tracked asset entries.
- Bundled Vosk acoustic/language model assets and 17 overlay character HTML assets are present.

The seven Parts are separate self-contained projects, not one multi-module Gradle build. Part 7 has its own `settings.gradle.kts`, root build file, app module, wrapper, resources, tests, and docs.

## 3. Active Part 7 build and platform configuration

| Setting | Observed value |
|---|---|
| Namespace | `com.jarvis.assistant` |
| Application ID | `com.jarvis.assistant` |
| minSdk | 26 |
| compileSdk | 36 |
| targetSdk | 36 |
| Java/Kotlin JVM target | 17 |
| AGP | 8.9.1 |
| Kotlin Android / KAPT | 2.2.20 |
| Gradle wrapper | 8.11.1 |
| App version | 1.8.7 / versionCode 143 |
| UI | Android Views, XML, AppCompat, Material, ViewBinding |
| DI | Manual application-level object graph in `JarvisApp`; no Hilt/Koin setup found |
| Version catalog | No `gradle/libs.versions.toml` found |

Important preservation correction:
- The user's requested Kotlin + Jetpack Compose description does not match the active Part 7 project. Part 7 has no Compose plugin/dependencies and uses XML layouts and Fragments.
- The current package/application ID is correct and must remain unchanged.
- Do not convert Part 7 to Compose during V4 completion unless the owner explicitly changes the preservation requirement.

The root README badges still advertise Kotlin 2.0.20 and Gradle 8.9, which do not match Part 7's actual Kotlin 2.2.20 and Gradle 8.11.1 configuration. This is documentation drift.

## 4. Architecture map — observed production ownership

```text
Android UI (MainActivity + Home/Chat/Coding/Settings Fragments + Activities)
  ├─ MainViewModel
  ├─ Settings / Persona / API / Memory / Permission / Avatar screens
  └─ observes service StateFlows
        |
        v
JarvisApp (Application bootstrap / manual dependency graph)
  ├─ AppPreferences / encrypted preferences
  ├─ ChatRepository (legacy SharedPreferences history)
  ├─ MemoryDatabase (Room + SQLCipher)
  │    ├─ MemoryRepository
  │    ├─ ConversationRepository (Room chat sessions/messages)
  │    ├─ MissionDao
  │    └─ NotificationHistoryDao
  ├─ MemoryManager
  ├─ CommandExecutor
  ├─ PhoneControlManager / ContactResolver
  ├─ WhatsAppAssistant / YouTubeAutomationManager
  ├─ JarvisUnifiedRuntime
  └─ MissionEngine DAO + LlmPlanner + GeminiFunctionCallProvider

JarvisConversationService (foreground service; audio/session owner)
  ├─ microphone / AudioRecord / AudioPlayer lifecycle
  ├─ Vosk recognition and standby/wake-word path
  ├─ GeminiLiveWebSocket (native audio conversation)
  ├─ voice state / connection / transcript StateFlows
  ├─ barge-in and interruption collaborators
  ├─ screen-capture service coordination
  └─ TaskProgressOverlay lifecycle

Automation
  ├─ AgentTaskEngine + SerializedActionExecutor
  ├─ MissionEngine + ToolRegistry + approval receiver/notifier
  ├─ LongHorizonMissionCoordinator (separate coordinator)
  ├─ JarvisAccessibilityService (node discovery, gestures, UI automation)
  ├─ CommandExecutor / device integrations
  └─ vision/grounding and screen capture orchestration

Memory
  ├─ MemoryRepository + MemoryDao
  ├─ ConversationRepository + ChatDao
  ├─ MemoryManager
  ├─ MemoryAwareContextBuilder
  ├─ vector/EmbeddingProvider + VectorSearchEngine
  ├─ EpisodicVectorMemory / RecallCoreManager
  └─ PersonalMemoryGraph
```

### Bootstrap observations

- `JarvisApp` is the composition root and manually constructs repositories, managers, and automation integrations.
- `MissionEngine` is an object with mutable global DAO/planner references and its own long-lived coroutine scope.
- `JarvisUnifiedRuntime` constructs routing, offline, provenance, mission, developer-agent, evaluation, reliability, ambient, and world-model collaborators.
- `JarvisConversationService` is 4,088 lines and owns a broad set of voice/session responsibilities. This is a high-coupling lifecycle boundary and should be changed only with narrow, tested seams.
- MainActivity uses Fragments and XML/ViewBinding. Navigation is manually coordinated in the activity; no Navigation Component graph was found.
- No actual multi-agent execution service is wired by the ChatGPT/GitHub integration in this audit session. Repository classes named agent/council are app-side components, not proof that external engineering sub-agents executed.

## 5. Feature inventory

### 5.1 Voice and AI

**Existing source confirmed**
- Gemini Live WebSocket transport with setup handshake, audio streaming, transcript callbacks, reconnect/backoff, key rotation hooks, and duplicate tool-call ID suppression.
- REST Gemini function-call provider used by the mission planner.
- Vosk local recognition model manager and bundled model assets.
- Android TextToSpeech router.
- Audio recorder/player, adaptive VAD, headset routing, voice interruption coordination, owner-voice verification, and voice diagnostics.
- Personality and language preference models exist.

**Gaps / wiring questions**
- The production conversation service itself still owns a very large portion of the voice pipeline.
- `AudioPipelineManager` and `VoiceSessionManager` have source and tests, but targeted reference searches did not find a production construction/call site. The service currently owns its own audio/session lifecycle.
- `VoiceSynthesisRouter` is Android TextToSpeech; Gemini Live native audio is a separate path. There is no confirmed provider abstraction that switches all voice output between these engines.
- No ElevenLabs implementation or ElevenLabs credential flow was found in targeted Part 7 searches.
- The official model IDs must be validated before selection. Current constants include stable `models/gemini-3.8-live`, legacy preview `models/gemini-3.1-flash-live-preview`, and `models/gemini-3.1-flash-agent` as a UI sentinel that the WebSocket constructor maps to the 3.1 preview ID. The 3.8 Flash REST model is used by the mission planner. Do not send the internal sentinel as a provider model.
- The root architecture document still describes Gemini 1.5/2.0 and is stale relative to the current model constants.

### 5.2 Accessibility and overlay

**Existing source confirmed**
- Manifest service declaration uses `android.permission.BIND_ACCESSIBILITY_SERVICE`, an accessibility intent filter, and metadata XML.
- XML config enables window-content retrieval, view IDs, interactive windows, gestures, screenshots, and selected UI/notification events.
- `onServiceConnected()` marks service health, applies dynamic flags/events, and schedules a non-blocking service check.
- `onAccessibilityEvent()` handles monitored event tracking and several WhatsApp/YouTube automation flows with localized exception handling.
- `onDestroy()` and `onUnbind()` cancel pending handler callbacks and clear armed automation state.
- Floating overlay uses `TYPE_APPLICATION_OVERLAY`, checks `Settings.canDrawOverlays`, loads local bundled character assets through WebViewAssetLoader, and has an allowlist of 17 character IDs.
- Overlay has avatar/task display modes, saved free-position coordinates, edge placement options, drag support, and reactions to conversation states.

**Gaps / wiring questions**
- Overlay is owned by `JarvisConversationService`; its lifecycle is not an independent persistent component. The manifest has no `RECEIVE_BOOT_COMPLETED` permission or boot receiver for overlay restoration. ALWAYS mode therefore depends on the foreground service being alive and reattaching the overlay.
- In WORKING mode, the service's idle-avatar condition is tied to app background state plus session/standby. An active foreground voice session with no AgentTask snapshot may not show the idle overlay. This needs explicit state-by-state acceptance tests.
- Drag release stores coordinates but does not automatically snap to the nearest edge. The UI edge setting is distinct from drag snapping.
- Position clamping uses display metrics and a fixed top inset; cutouts, gesture navigation, status/navigation bars, and display changes require further validation.
- The accessibility service is marked `directBootAware=true`; its access to credential-protected preferences/database before first unlock needs review.
- Android 13+ sideloaded restricted-settings flows and Android 16 service/FGS behavior cannot be certified from static source alone.

### 5.3 Memory and persistence

**Existing source confirmed**
- Room database version 5 contains memories, chat sessions/messages, missions, and notification history.
- Explicit migrations V1→V2→V3→V4→V5 are present.
- SQLCipher Android is configured through a custom `SupportOpenHelperFactory`.
- Database key material is a random passphrase wrapped by a non-exportable Android Keystore AES-256-GCM key; the wrapped material and recovery markers live under `noBackupFilesDir`.
- Plaintext database migration uses a staging file, WAL checkpoint, integrity checks, table row-count/content digests, and a two-rename swap with recovery policy.
- Corruption callback attempts to preserve database and sidecar snapshots under app-private no-backup storage.
- Memory repository has remember/correct/soft-delete/permanent-delete, export/import, expiry cleanup, sanitization, embedding persistence, and hybrid retrieval APIs.
- ConversationRepository has session creation, archive/pin/rename/delete, message search, paging, and append-turn operations.

**Critical gaps**
- `@Database(exportSchema = false)` means Room schema snapshots are not exported. This weakens migration review and CI migration-diff safety.
- The current RoomMigrationInstrumentedTest manually invokes migration objects against a framework SQLite helper. It validates SQL operations and some data/table expectations, but not Room's real migration selection/validation with SQLCipher.
- SQLCipher runtime/key-store instrumentation tests exist but were not executed.
- `MemoryDatabase.getInstance()` blocks the calling thread while running encryption preparation in `runBlocking(Dispatchers.IO)` and opening Room. `JarvisApp.onCreate()` calls it synchronously; large first-run migration/encryption work can delay app startup.
- Chat history exists in both `ChatRepository`/AppPreferences JSON and Room `ConversationRepository`. MemoryManager writes legacy chat turns while chat/session code uses Room. A canonical store and one-time migration/reconciliation plan are needed.
- The default `MemoryRepository` provider is `DeterministicLocalEmbeddingProvider`: 128-dimensional subword/character hashing with hand-authored semantic clusters. `EpisodicVectorMemory` separately uses TF/IDF-style token vectors and lexical overlap. These are deterministic local retrieval features, not a pretrained neural semantic embedding model. A cloud Gemini embedding provider exists but is not the default.
- No Mem0 cloud sync adapter, consent UI, conflict resolution, or deletion propagation was found.

### 5.4 Mission and agent systems

**Existing source confirmed**
- `AgentTaskEngine` is the authoritative in-process accessibility task state machine with plan/observe/act/verify/recover states and serialized execution coordination.
- `MissionEngine` persists mission state via Room DAO, restores non-terminal missions, and preserves pending approvals rather than blindly replaying consequential in-flight actions.
- Tool registry, mission approval notifier/receiver/parser, risk policy, sandbox, and verification evidence models exist.
- `JarvisUnifiedRuntime` instantiates a `LongHorizonMissionCoordinator`.

**Gaps / wiring questions**
- `MissionEngine` and `LongHorizonMissionCoordinator` are two distinct mission orchestration systems. The latter defaults to `InMemoryCheckpointStorage`; its checkpoints are not durable unless another storage implementation is injected. Their responsibilities and authority boundary need to be made explicit.
- Targeted searches did not find production callers for `AutonomousGoalAgent`, `SelfHealingAgentLoop`, or `UniversalActionDispatcher`; only source declarations, tests, or architecture references were found. Confirm before treating them as live production execution engines.
- The architecture document describes a unified ToolRegistry → SelfHealingAgentLoop pipeline, but the production call path needs reconciliation with the actual `AgentTaskEngine` and `MissionEngine` wiring.
- The app-side `MultiAgentCouncil` does not mean external sub-agents were spawned during this audit.

### 5.5 Profile, account, subscription

- Settings currently expose a local profile-name editor and voice enrollment.
- No verified email/profile-image account backend, authentication provider, account recovery/deletion API, subscription entitlement backend, credits ledger, or license-verification service was found in targeted Part 7 source/documentation searches.
- Part 7 README explicitly says no deployed verification backend or agreed verification API protocol exists.
- These must be treated as integration blockers, not simulated local subscription state.

### 5.6 Vision, maps, device integrations

- CameraX dependencies and vision analyzer sources exist.
- Screen capture and screen intelligence/grounding/orchestration components exist.
- OSM/osmdroid dependency and map activity exist; location/weather helper is present.
- Phone, contacts, WhatsApp, YouTube, notification listener, wallpaper, and Termux integration classes exist.
- Each integration still requires permission/lifecycle/error-path review before it can be declared fully reliable on Android 16.

## 6. Manifest and security review

### Positive controls observed
- `android:allowBackup="false"`.
- API keys and private profile/voice values use encrypted preferences with guarded migration behavior.
- SQLCipher database encryption and Keystore-wrapped key material are implemented.
- Accessibility service is protected by `BIND_ACCESSIBILITY_SERVICE`.
- Notification listener is protected by `BIND_NOTIFICATION_LISTENER_SERVICE`.
- Termux result receiver is non-exported.
- Mission approval receiver is non-exported.
- Gemini WebSocket source sends the API key in the `x-goog-api-key` request header rather than embedding it in the URL.
- Capability sandbox, prompt-injection sanitizer, risk policy, sensitive-input policy, and consent policy source exists.

### Permission surface requiring justification
The manifest declares microphone, fine/coarse location, internet/network state, audio settings, Bluetooth connect, foreground-service microphone/media playback/media projection, notifications, wake lock, ignore-battery-optimization request, camera, contacts, write-settings special access, phone call, query-all-packages, overlay, and Termux run-command permission.

This is a powerful permission set. Each permission needs a user-visible feature mapping, runtime/special-access explanation, denial fallback, and data-retention boundary. In particular:
- `QUERY_ALL_PACKAGES` needs a distribution-policy justification if Play distribution is planned.
- `WRITE_SETTINGS`, overlay, accessibility, notification listener, screen capture, and battery-optimization exemption are special access surfaces, not ordinary runtime permissions.
- Microphone and media-projection foreground-service types need operation-specific start/stop paths and Android 14–16 compliance review.
- The exported phone-state receiver and exported system-bound services need explicit caller/permission validation.
- Do not add `WRITE_SECURE_SETTINGS`; it is a privileged permission unavailable to ordinary third-party apps.

No penetration test or Android runtime permission test was performed.

## 7. CI, tests, branches, and pull requests

### Test inventory
- 55 local unit-test source files.
- 6 Android instrumentation-test source files.
- Coverage includes voice/VAD/barge-in, accessibility health, agent task execution, mission/approval, routing, memory/vector retrieval, runtime, privacy filters, consent, security sandbox, and persistence/migrations.
- Presence of tests is not execution evidence.

### Current main workflow
File: `.github/workflows/android.yml`
- Trigger: `workflow_dispatch` only.
- JDK 17 and Gradle wrapper validation.
- Runs `:app:testDebugUnitTest`.
- Runs `:app:lintDebug` with `continue-on-error: true`.
- Uploads unit-test and lint reports.
- Does not call `assembleDebug` or `assembleRelease`.

This workflow is compatible with the owner's no-APK restriction as currently written. It is not automatically triggered by main pushes or PRs.

### GitHub Actions evidence
- Repository reports 192 workflow runs.
- Latest inspected run #192: `startup_failure`, zero jobs, commit `23dec1afbb7bb06f020448b3b395538da8ecc12e`.
- The 20 most recent listed runs all reported `startup_failure`.
- The audited HEAD is `838f63b9bd3a46994eab7e206954f9fc6dbbfc8b`; no workflow run for this HEAD was found in the inspected run list.
- Therefore no current-head compile, unit-test, lint, instrumentation, APK, or device result is claimed.
- The current integration did not expose a workflow-dispatch action. No workflow was triggered.

### Branch / PR review
The repository has 15 branches, including:
- `main`
- `phase0/capability-audit`
- `phase0/stabilization`
- `phase0/ci-stabilization`
- `phase1/completion-and-verification`
- `phase1/security-guard-lockout-hardening`
- `hardening/p0-p2-production-readiness`
- `fix/accessibility-voice-android16`
- `fix/phases-1-4-security-voice-launcher`
- `fix/wake-a11y-reliability-2026-10`
- `feature/aurix-upgrade`
- additional build/accessibility branches.

Open PRs of particular relevance:
- **PR #3 — Phase0/capability audit:** contains broad unrelated feature additions, a `compileSdk = 34` change against main's 36, `WRITE_SECURE_SETTINGS`, boot receiver additions, and website Forge/debug activities/assets. It is not a clean audit-only change and should not be merged without rebase and scope/security review.
- **PR #6 — Phase 0 CI stabilization:** includes an `assembleDebug` step. Do not merge under the current no-APK directive.
- **PR #7 — Phase 0 CI/build verification:** also includes `:app:assembleDebug`. Do not merge under the current no-APK directive. Its base is behind the audited main state.
- **PR #1 — AURIX upgrade:** contains potentially useful approval/security work but is a separate unmerged feature tranche; review its exact current diff and integration status before any merge.

No branch was merged or force-updated during this audit.

## 8. Findings register

Severity is a prioritization for follow-up engineering, not a claim that a runtime exploit or crash was reproduced.

| ID | Severity | Finding | Evidence / impact | Follow-up |
|---|---|---|---|---|
| P0-01 | P1 | No successful verification for current main | Recent Actions runs fail before jobs; current HEAD has no run | Restore a reliable test-only CI run; do not add APK assembly |
| P0-02 | P1 | Synchronous database initialization in Application startup | `JarvisApp.onCreate → MemoryDatabase.getInstance → runBlocking(IO) + writableDatabase` | Move expensive open/migration off the main startup path with safe readiness gating |
| P0-03 | P1 | Room schema export disabled | `exportSchema=false` | Export schema snapshots and version-control migration artifacts |
| P0-04 | P1 | Two chat persistence stores | SharedPreferences ChatRepository and Room ConversationRepository both wired | Choose canonical Room store; migrate legacy JSON once and remove dual writes safely |
| P0-05 | P1 | Long-horizon mission checkpoint default is in-memory | `InMemoryCheckpointStorage` default | Inject durable Room-backed checkpoint storage or clearly limit the feature |
| P0-06 | P1 | Room/SQLCipher migration path not end-to-end tested | Existing migration test manually applies migrations with framework SQLite | Add actual Room + SQLCipher upgrade, key-loss, crash-window, and corruption-recovery tests |
| P0-07 | P2 | Duplicate/disconnected execution components | Voice/agent/dispatcher classes lack confirmed production call sites | Produce call-site map and either wire them or mark/remove only after owner approval |
| P0-08 | P2 | Voice service is highly coupled | 4,088-line foreground service owns many resources and state transitions | Extract collaborators incrementally behind tests; avoid rewrite |
| P0-09 | P2 | Overlay lifecycle/position gaps | Service-owned overlay; no boot receiver; working idle visibility limited; drag does not edge-snap | Define exact mode semantics and add lifecycle/state/position tests |
| P0-10 | P2 | Retrieval terminology overstates embedding semantics | Default is deterministic hash/subword embedding; second path is TF/IDF lexical vector | Document actual behavior; use a validated semantic embedding model only if/when configured |
| P0-11 | P2 | Architecture docs are stale | Old model IDs and components shown as live without verified production callers | Update docs only after source-of-truth wiring map is finalized |
| P0-12 | P2 | Permission surface is broad | Multiple powerful runtime/special-access permissions | Permission-to-feature matrix, denial paths, retention, and policy review |
| P0-13 | P2 | Active project framework differs from requested Compose description | Part 7 is Views/XML, no Compose dependency | Preserve existing UI unless explicit migration authorization is given |
| P0-14 | P2 | Cloud/account providers absent | No Mem0, ElevenLabs, auth, subscription/credits backend found | Build adapters only against real provider/backend contracts; no fake state |
| P0-15 | P2 | Unmerged branches contain conflicting scope | Phase 0 PRs add features or APK assembly; some are based on old main | Review/rebase individually; never merge by branch name alone |

## 9. Do-not-merge / do-not-do list

- Do not merge PR #6 or #7 while they contain APK assembly.
- Do not merge PR #3 as a Phase 0 audit; it is not audit-only and contains a protected permission and SDK downgrade.
- Do not import historical Part 1–6 projects into Part 7 wholesale.
- Do not convert Part 7 to Compose without a separate owner decision.
- Do not add a fake account/subscription/credit backend.
- Do not describe deterministic hashed vectors as a pretrained semantic model.
- Do not treat unit-test source files as passing test results.
- Do not claim Android 16 runtime compatibility without an actual supported test environment.
- Do not run APK-producing Gradle tasks.

## 10. Recommended next engineering order

1. Resolve verification infrastructure with a test-only workflow that runs unit tests and reports lint; no APK task.
2. Define the canonical persistence path for chat and memory; plan safe migration from SharedPreferences JSON to Room.
3. Fix database startup sequencing and enable Room schema exports; add end-to-end SQLCipher/migration/recovery tests.
4. Reconcile MissionEngine and LongHorizonMissionCoordinator durability/ownership.
5. Complete the production call-site map for agent and voice collaborator classes.
6. Complete overlay state/mode/process-death/boot/edge-position semantics.
7. Then proceed to Phase 1 feature implementation without changing unrelated modules.

## 11. Verification ledger

| Check | Result |
|---|---|
| Repository metadata / default branch | VERIFIED |
| Recursive main tree fetched and classified | VERIFIED |
| Parts 1–7 project snapshot inventory | VERIFIED |
| Part 7 build configuration / wrapper | VERIFIED |
| Part 7 manifest / accessibility XML | VERIFIED |
| Core app bootstrap / service / memory / mission / accessibility source reviewed | VERIFIED |
| Test source inventory | VERIFIED |
| Branches / open PRs / recent Actions runs inspected | VERIFIED |
| Current-head Gradle compile | NOT RUN |
| Unit tests | NOT RUN |
| Instrumentation tests | NOT RUN |
| Android lint | NOT RUN |
| APK build | PROHIBITED / NOT RUN |
| Physical device test | OUT OF SCOPE / NOT RUN |

**Phase 0 exit:** Static repository inventory, architecture map, existing-feature inventory, major wiring risks, security/performance risks, branch/CI risks, and follow-up priorities are documented. Runtime/build verification remains a separate explicit gate and has not been fabricated.
