# Part 7 production call-site map

Audited from source at `56b37aa627abed2178680993fecbe1b5af458a72` (2026-11-11), not from the historical progress reports. Line numbers below refer to that revision. Paths are relative to `Part 7/`. Static reachability is evidence of wiring, not a device execution test.

| Class | Verdict | Production caller / ownership | Test use |
| --- | --- | --- | --- |
| `background/AudioPipelineManager` | **WIRED** | `app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:1957` constructs it with the **existing** `AudioRecorder`; `:1963` calls `startRecording()`. `:151` propagates mute, `:3632` stops recording, `:3633` releases the reference. | `app/src/test/java/com/jarvis/assistant/background/ConversationServiceCollaboratorsTest.kt:205,217,238,248` covers capture/delegation, frame copying and fatal errors. Not test-only. |
| `background/VoiceSessionManager` | **WIRED** | `app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:1651` constructs it around the existing Live socket, `:1662` starts the session; `:1086` reports established, `:1088` dropped, `:1554` fatal errors. Shutdown at `:1050` (`awaitStopped`) and `:3643` (`stopSession`). | `app/src/test/java/com/jarvis/assistant/background/ConversationServiceCollaboratorsTest.kt:22,37,57,73,97,129,151,175` constructs it. Not test-only. |
| `automation/agent/AutonomousGoalAgent` | **DEAD** | Object declared at `app/src/main/java/com/jarvis/assistant/automation/agent/AutonomousGoalAgent.kt:37`; `executeGoal` at `:50`, `cancelActiveGoal` at `:45`. No external production references, reflection lookup, or manifest registration found. Its outgoing references do not establish an incoming execution path. | No test references found. Unreachable in the inspected app, not proof it is safe to delete. |
| `automation/agent/SelfHealingAgentLoop` | **WIRED** | `app/src/main/java/com/jarvis/assistant/automation/mission/MissionEngine.kt:1369,1378` builds step/mission data and `:1388` calls `executeStepWithSelfHealing`. This is inside `executeMission` (`:1172`), reached by `startMission` (`:497,512,603`). Live service starts missions at `app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt:2132,2278`. MissionEngine remains the actual tool execution authority. | `app/src/test/java/com/jarvis/assistant/automation/agent/SelfHealingAgentLoopTest.kt:66,104,141,169,203` tests bounded recovery and reporting. Not test-only. |
| `automation/integration/UniversalActionDispatcher` | **TEST-ONLY** | Object declared at `app/src/main/java/com/jarvis/assistant/automation/integration/UniversalActionDispatcher.kt:10`; `executeAction` at `:27`. No incoming production references found. | `app/src/test/java/com/jarvis/assistant/automation/integration/UniversalActionTest.kt:42,59,79,95,122,150,161,187,200` calls `executeAction`; setup/reset at `:18-21`. |

## Search and interpretation

Executed `rg -n 'AudioPipelineManager|VoiceSessionManager|AutonomousGoalAgent|SelfHealingAgentLoop|UniversalActionDispatcher' 'Part 7/app/src'`, then read callers and lifecycle paths, and searched instance names (`audioPipelineManager`, `voiceSessionManager`) as well. Also checked assets/manifest and alternate call names for disconnected objects. Comments naming a class, imports and type declarations alone are not executable call sites.

Definitions: **WIRED** = reachable production caller; **TEST-ONLY** = invoked by tests but no incoming production caller; **DEAD** = neither production nor test entry found. No named class was modified, deleted or newly wired by this audit.

## V2 A1 extension — production entry chains

Audited again at `2084fe301cf60bcd2bd745048b2c9ef5a75b38e2`; runtime source remains unchanged from the source-pinned map above. Baseline execution is recorded in `docs/engineering/BASELINE.md`: 514 JVM tests, 0 failures/errors, 1 skip; lint 0 errors. This extension changes documentation only and makes no device execution claim.

```text
AndroidManifest.xml:43 application .JarvisApp
  -> JarvisApp.onCreate:86 (manual DI)
     -> repositories:105-107, persistent mission coordinator:126-127
     -> IdeaReviewWorker.schedule:92 / AutonomyWorker.schedule:93
        -> WorkManager periodic requests (IdeaReviewWorker.kt:37; AutonomyWorker.kt:53)

AndroidManifest.xml:110 launcher alias -> .ui.home.MainActivity (:95)
  -> MainActivity homeFragment:50 / ActivityMainBinding.inflate:155
     -> HomeFragment.inflate:69 -> fragment_home.xml:233,246 -> WaveformView
     -> activity_main.xml:279 -> CurvedDockLineView
     -> user controls -> JarvisConversationService (manifest :199)
        -> VoiceSessionManager :1651 -> startSession :1662 -> existing Gemini Live socket
        -> AudioPipelineManager :1957 -> startRecording :1963 -> existing AudioRecorder
        -> startMission :2132,2278 -> MissionEngine.startMission :497
           -> executeMission :1172 -> SelfHealingAgentLoop.executeStepWithSelfHealing :1388
           -> callback executeSingleStep -> existing ToolRegistry

AndroidManifest.xml:220 notification listener / :205 accessibility binding
  -> Android binds the existing registered service, not a newly created AI engine

MainActivity codingFragment:52 -> CodingDevFragment:49 AutonomousDevLoop
  -> AutonomousDevLoop.kt:109,138,180 -> DevToolRegistry
  -> DevToolRegistry.kt:500-508 init registry map
     -> ReadFileDevTool / EditFileDevTool / InspectStructureDevTool /
        TermuxBridgeDevTool / ArtifactVerificationDevTool (same-file classes)

AndroidManifest.xml:127 WhiteboardActivity -> WhiteboardActivity.kt:61 DrawingBoardView
AndroidManifest.xml:67 AirGesturesActivity -> AirGesturesActivity.kt:91 AirGestureController
AndroidManifest.xml:240 OverlayBootReceiver -> onReceive:17-29
  -> action allowlist + existing opt-in + overlay permission
  -> existing JarvisConversationService.ACTION_RESTORE_OVERLAY
```

Line numbers are source pointers, not an exhaustive control-flow proof. Manifest exact component line references should be read against the source revision (formatting can move them). Android lifecycle bindings and XML inflation are incoming references even when no Kotlin code spells a constructor.

### Named candidates: overlap and next action

| Candidate | Verdict | Purpose / actual production overlap | Recommendation / effort |
| --- | --- | --- | --- |
| AudioPipelineManager | WIRED | Existing service capture adapter; service's recorder/VAD/barge-in callback is reused. | Keep; characterize before B1 extraction. |
| VoiceSessionManager | WIRED | Sole Live reconnect owner; GeminiLiveWebSocket configured with externalReconnectOwner=true at service :1645. | Keep; B1 must wrap/reuse it. |
| SelfHealingAgentLoop | WIRED | Bounded per-step recovery inside MissionEngine; it does not own a second mission engine. | Keep; preserve approval and observable verification callbacks. |
| AutonomousGoalAgent | DEAD | Alternate bounded serial CommandExecutor goal loop; production goals already use MissionEngine/LlmPlanner. No incoming reference including tests. | **MERGE INTO** MissionEngine only if a concrete missing behavior is found; otherwise **DELETE LATER**, M. Do not wire it merely to improve a reference count. |
| UniversalActionDispatcher | TEST-ONLY | Alternative validation/timeouts/circuit-breaker dispatcher; actual action path uses ToolRegistry + MissionEngine and capability/risk policy. Tests call it; production does not. | **MERGE INTO** the existing execution boundary after policy/verification contract comparison, M. Do not add a competing execution authority. |

## Repo-wide zero-external-reference scan

Method: enumerate all top-level `class`/`object` declarations under `app/src/main/java/**/*.kt` with a lexical comment/string mask to determine top-level brace depth; enums/data/sealed classes included. Build an identifier index from **raw** main Kotlin text (including comments/strings, conservatively) and select declarations whose name occurs in no other main Kotlin file. Separately search tests, Android XML/assets, class-name/reflection loading and registrations. Declaration size is the lexically balanced source span, **not the size of the containing file**. This is a static candidate scan, not R8 reachability or proof of runtime behavior. Name collisions and dynamic construction can produce false positives/negatives.

Scan output: **503** top-level class/object declarations; **119** have no raw symbol reference in another main Kotlin file. Top 30 below, ordered by declaration span. Same-file mentions include logging strings and constructors; they are not automatically calls.

| Declaration / file:line | Lines | Other main Kotlin files | Test files / metadata mentions | Verdict | Purpose / incoming evidence / overlap | Recommendation |
| --- | --- | --- | --- | --- | --- | --- |
| `OrbHelper` — `app/src/main/java/com/jarvis/assistant/ui/orb/OrbHelper.kt:15` | 254 | 0 | 0 / 1 | SHADOWED | Legacy WebView HUD bridge; HomeFragment.kt:179,239 directly hosts the current appassets orb. Only metadata mentions are comments in assets/hud/index.html:670,808. | DELETE LATER, M; never wire its permissive legacy WebView settings. |
| `UniversalInformationEngine` — `app/src/main/java/com/jarvis/assistant/automation/intelligence/UniversalInformationEngine.kt:8` | 227 | 0 | 1 / 0 | TEST-ONLY | Information/task parsing prototype; overlaps existing command/brain routing. Test: UniversalInformationEngineTest. | MERGE INTO existing runtime only for a tested missing parser capability, M. |
| `ScreenIntelligenceEngine` — `app/src/main/java/com/jarvis/assistant/vision/ScreenIntelligenceEngine.kt:24` | 181 | 0 | 1 / 0 | TEST-ONLY | Screen diagnosis wrapper; active mission execution uses ScreenGroundingEngine/MissionEngine. JarvisEvolutionMasterTest.kt:66 calls it. | MERGE INTO current screen-grounding boundary, M. |
| `AdaptivePersonalizationEngine` — `app/src/main/java/com/jarvis/assistant/data/personalization/AdaptivePersonalizationEngine.kt:6` | 164 | 0 | 1 / 0 | TEST-ONLY | Preference resolution prototype; active profile learning uses MemoryManager and AppPreferences/Room. | MERGE INTO existing preference/memory repositories, M. |
| `PersonalMemoryGraph` — `app/src/main/java/com/jarvis/assistant/data/memory/PersonalMemoryGraph.kt:50` | 156 | 0 | 2 / 0 | TEST-ONLY | Graph memory prototype; active memory uses MemoryRepository with Room. Local self-reference alone is not a production entry. | MERGE INTO canonical Room memory only with migration/consent design, L. |
| `AutonomousGoalAgent` — `app/src/main/java/com/jarvis/assistant/automation/agent/AutonomousGoalAgent.kt:37` | 141 | 0 | 0 / 0 | DEAD | Alternate serial goal runner; overlap and absence evidence in named-candidate table. | MERGE INTO / DELETE LATER, M. |
| `WaveformView` — `app/src/main/java/com/jarvis/assistant/ui/home/WaveformView.kt:23` | 125 | 0 | 0 / 1 | WIRED (XML) | fragment_home.xml:233,246 inflated by HomeFragment.kt:69. | KEEP; XML reference is real wiring. |
| `GeminiProvider` — `app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt:61` | 124 | 0 | 1 / 0 | TEST-ONLY | REST provider abstraction in LlmProvider.kt; current planner uses GeminiFunctionCallProvider via JarvisApp.kt:133. | WIRE through existing coordinator in D1 after contract tests, M. |
| `CurvedDockLineView` — `app/src/main/java/com/jarvis/assistant/ui/widget/CurvedDockLineView.kt:15` | 121 | 0 | 0 / 1 | WIRED (XML) | activity_main.xml:279 inflated by MainActivity.kt:155. | KEEP; no Kotlin constructor needed. |
| `SoftwareEngineerWorkspaceCoordinator` — `app/src/main/java/com/jarvis/assistant/automation/dev/SoftwareEngineerWorkspaceState.kt:22` | 115 | 0 | 0 / 0 | DEAD | Alternate Dev workspace state coordinator; current CodingDevFragment.kt:47,49 owns SoftwareEngineeringAgent/AutonomousDevLoop. | MERGE INTO existing Dev screen state when justified, M. |
| `UniversalActionDispatcher` — `app/src/main/java/com/jarvis/assistant/automation/integration/UniversalActionDispatcher.kt:10` | 115 | 0 | 1 / 0 | TEST-ONLY | Alternative tool dispatcher; UniversalActionTest calls it; named table above. | MERGE INTO existing ToolRegistry boundary, M. |
| `VoskModelDownloader` — `app/src/main/java/com/jarvis/assistant/audio/VoskModelDownloader.kt:17` | 105 | 0 | 0 / 0 | SHADOWED | Uncalled alternate downloader; WakeWordActivity.kt:117 uses audio/wake/ModelDownloadWorker, service uses VoskModelManager. | DELETE LATER after download feature parity audit, M. |
| `UpdatesFragment` — `app/src/main/java/com/jarvis/assistant/ui/home/UpdatesFragment.kt:13` | 101 | 0 | 0 / 0 | SHADOWED | No fragment route/layout-class registration found; notification history is an Activity in the current manifest. | DELETE LATER after UI route parity review, S. |
| `WakePhraseMatcher` — `app/src/main/java/com/jarvis/assistant/audio/WakePhraseMatcher.kt:13` | 86 | 0 | 1 / 0 | TEST-ONLY | Alternate matcher; service uses audio/wake/WakeMatcher at JarvisConversationService.kt:3860. | MERGE INTO WakeMatcher only for proven recognition/false-activation improvements, M. |
| `OpenAiCompatibleProvider` — `app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt:189` | 84 | 0 | 0 / 0 | DEAD | Provider implementation in the currently uncalled LlmProvider.kt abstraction. | WIRE in D1 only after consent, capability and contract design, M. |
| `ChatHistoryBottomSheet` — `app/src/main/java/com/jarvis/assistant/ui/chat/ChatHistoryBottomSheet.kt:20` | 83 | 0 | 0 / 0 | SHADOWED | Only its own factory constructor at :100 references it. No external .show/newInstance entry. Current app uses ChatFragment/Room history. | DELETE LATER after history UI parity review, S. |
| `DrawingBoardView` — `app/src/main/java/com/jarvis/assistant/ui/whiteboard/WhiteboardActivity.kt:166` | 82 | 0 | 0 / 0 | WIRED (same file) | Manifest WhiteboardActivity:127 -> WhiteboardActivity.kt:61 constructor. | KEEP private/local implementation. |
| `EditFileDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:117` | 80 | 0 | 0 / 0 | WIRED (registry) | DevToolRegistry.kt:502 registration; AutonomousDevLoop.kt:180 dispatch; CodingDevFragment.kt:49 entry. | KEEP; preserve approval and sandbox checks. |
| `ApiKeyRotator` — `app/src/main/java/com/jarvis/assistant/automation/routing/ApiKeyRotator.kt:11` | 68 | 0 | 1 / 0 | TEST-ONLY | Alternative rotator; production service invokes AppPreferences key-pool callbacks (:1642-1644). | MERGE INTO one key-pool owner if justified, M. |
| `AudioLevelTracker` — `app/src/main/java/com/jarvis/assistant/ui/orb/AudioLevelTracker.kt:26` | 59 | 0 | 0 / 0 | DEAD | Optional standalone orb capture helper; no production construction found. JarvisOrbAdapter observes the existing service instead. | DELETE LATER or WIRE only to an explicit optional UI capture mode, M; never a second active mic. |
| `PremiumOrbView` — `app/src/main/java/com/jarvis/assistant/ui/directives/PremiumOrbView.kt:9` | 56 | 0 | 0 / 0 | SHADOWED | Unused Canvas prototype; VoiceModeActivity.kt:22,38 constructs VortexOrbView and Home uses current WebView orb. | DELETE LATER after visual/settings parity review, S. |
| `AirGestureController` — `app/src/main/java/com/jarvis/assistant/ui/extras/AirGesturesActivity.kt:22` | 55 | 0 | 0 / 0 | WIRED (same file) | Manifest AirGesturesActivity:67 -> AirGesturesActivity.kt:91 constructor. | KEEP lifecycle-bound sensor controller. |
| `ReadFileDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:68` | 48 | 0 | 0 / 0 | WIRED (registry) | DevToolRegistry.kt:501 registration -> AutonomousDevLoop.kt:180 getTool execution. | KEEP; preserve sensitive-path policy. |
| `TermuxBridgeDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:416` | 46 | 0 | 1 / 0 | WIRED (registry) | DevToolRegistry.kt:507 registration -> AutonomousDevLoop. Also test use. | KEEP; no unrestricted AI-to-shell path introduced. |
| `InspectStructureDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:198` | 45 | 0 | 0 / 0 | WIRED (registry) | DevToolRegistry.kt:503 registration -> AutonomousDevLoop.kt:138. | KEEP; same-file class is registered. |
| `SpectrumAnalyzer` — `app/src/main/java/com/jarvis/assistant/ui/orb/AudioLevelTracker.kt:99` | 39 | 0 | 1 / 0 | TEST-ONLY in app reachability | Used locally by uninstantiated AudioLevelTracker (:50) and by SpectrumAnalyzerTest; no live entry path found. | MERGE INTO existing audio analytics only with measured need, M. |
| `InMemoryCheckpointStorage` — `app/src/main/java/com/jarvis/assistant/automation/mission/LongHorizonMissionModels.kt:101` | 34 | 0 | 3 / 0 | TEST-ONLY | Explicit test fake; production app injects PersistentMissionCheckpointStorage at JarvisApp.kt:127. | KEEP as a fake; do not wire as production fallback. |
| `ArtifactVerificationDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:463` | 33 | 0 | 0 / 0 | WIRED (registry) | DevToolRegistry.kt:508 registration -> AutonomousDevLoop.kt:298. | KEEP; preserve grounded verification. |
| `TaskContext` — `app/src/main/java/com/jarvis/assistant/automation/agent/AgentModels.kt:132` | 30 | 0 | 0 / 0 | DEAD | Task/session data type in AgentModels.kt without incoming symbol references. Existing MissionEngine owns current mission context. | DELETE LATER after model/API compatibility review, S. |
| `OverlayBootReceiver` — `app/src/main/java/com/jarvis/assistant/background/OverlayBootReceiver.kt:15` | 24 | 0 | 0 / 1 | WIRED (manifest) | Manifest boot/package-replaced receiver -> OverlayBootReceiver.kt:17-29 checks + existing service intent. | KEEP; no boot mic exemption implied. |

Effort S/M/L is relative review/integration scope, not a delivery-time promise. **SHADOWED** means the inspected class has no incoming execution path while a different current component handles its intended responsibility. **TEST-ONLY** may have outgoing production-looking calls, but no root entry was found. Tests referencing a type alone do not establish live behavior.

No `ServiceLoader`, `Class.forName`, or `loadClass` hit was found in production Kotlin/XML, and no `META-INF/services` registration was found in main resources. Android itself still loads manifest/XML classes; those references are checked above. Comments, asset method stubs and logging tags were not treated as constructors. Dynamic registry/same-file references were traced to actual UI/Android entry points before assigning WIRED.

### Complete candidate-name inventory

Names below are the complete zero-external-main-Kotlin symbol scan, not a deletion list. Only the top 30 received the detailed review above; remaining candidates require the same metadata/entry-point review before a final verdict.

- `OrbHelper` — `app/src/main/java/com/jarvis/assistant/ui/orb/OrbHelper.kt:15` (254 declaration lines)
- `UniversalInformationEngine` — `app/src/main/java/com/jarvis/assistant/automation/intelligence/UniversalInformationEngine.kt:8` (227 declaration lines)
- `ScreenIntelligenceEngine` — `app/src/main/java/com/jarvis/assistant/vision/ScreenIntelligenceEngine.kt:24` (181 declaration lines)
- `AdaptivePersonalizationEngine` — `app/src/main/java/com/jarvis/assistant/data/personalization/AdaptivePersonalizationEngine.kt:6` (164 declaration lines)
- `PersonalMemoryGraph` — `app/src/main/java/com/jarvis/assistant/data/memory/PersonalMemoryGraph.kt:50` (156 declaration lines)
- `AutonomousGoalAgent` — `app/src/main/java/com/jarvis/assistant/automation/agent/AutonomousGoalAgent.kt:37` (141 declaration lines)
- `WaveformView` — `app/src/main/java/com/jarvis/assistant/ui/home/WaveformView.kt:23` (125 declaration lines)
- `GeminiProvider` — `app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt:61` (124 declaration lines)
- `CurvedDockLineView` — `app/src/main/java/com/jarvis/assistant/ui/widget/CurvedDockLineView.kt:15` (121 declaration lines)
- `SoftwareEngineerWorkspaceCoordinator` — `app/src/main/java/com/jarvis/assistant/automation/dev/SoftwareEngineerWorkspaceState.kt:22` (115 declaration lines)
- `UniversalActionDispatcher` — `app/src/main/java/com/jarvis/assistant/automation/integration/UniversalActionDispatcher.kt:10` (115 declaration lines)
- `VoskModelDownloader` — `app/src/main/java/com/jarvis/assistant/audio/VoskModelDownloader.kt:17` (105 declaration lines)
- `UpdatesFragment` — `app/src/main/java/com/jarvis/assistant/ui/home/UpdatesFragment.kt:13` (101 declaration lines)
- `WakePhraseMatcher` — `app/src/main/java/com/jarvis/assistant/audio/WakePhraseMatcher.kt:13` (86 declaration lines)
- `OpenAiCompatibleProvider` — `app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt:189` (84 declaration lines)
- `ChatHistoryBottomSheet` — `app/src/main/java/com/jarvis/assistant/ui/chat/ChatHistoryBottomSheet.kt:20` (83 declaration lines)
- `DrawingBoardView` — `app/src/main/java/com/jarvis/assistant/ui/whiteboard/WhiteboardActivity.kt:166` (82 declaration lines)
- `EditFileDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:117` (80 declaration lines)
- `ApiKeyRotator` — `app/src/main/java/com/jarvis/assistant/automation/routing/ApiKeyRotator.kt:11` (68 declaration lines)
- `AudioLevelTracker` — `app/src/main/java/com/jarvis/assistant/ui/orb/AudioLevelTracker.kt:26` (59 declaration lines)
- `PremiumOrbView` — `app/src/main/java/com/jarvis/assistant/ui/directives/PremiumOrbView.kt:9` (56 declaration lines)
- `AirGestureController` — `app/src/main/java/com/jarvis/assistant/ui/extras/AirGesturesActivity.kt:22` (55 declaration lines)
- `ReadFileDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:68` (48 declaration lines)
- `TermuxBridgeDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:416` (46 declaration lines)
- `InspectStructureDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:198` (45 declaration lines)
- `SpectrumAnalyzer` — `app/src/main/java/com/jarvis/assistant/ui/orb/AudioLevelTracker.kt:99` (39 declaration lines)
- `InMemoryCheckpointStorage` — `app/src/main/java/com/jarvis/assistant/automation/mission/LongHorizonMissionModels.kt:101` (34 declaration lines)
- `ArtifactVerificationDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:463` (33 declaration lines)
- `TaskContext` — `app/src/main/java/com/jarvis/assistant/automation/agent/AgentModels.kt:132` (30 declaration lines)
- `OverlayBootReceiver` — `app/src/main/java/com/jarvis/assistant/background/OverlayBootReceiver.kt:15` (24 declaration lines)
- `GeminiModelSelection` — `app/src/main/java/com/jarvis/assistant/network/GeminiModelCatalog.kt:18` (24 declaration lines)
- `HermesFiles` — `app/src/main/java/com/jarvis/assistant/ui/extras/HermesContextActivity.kt:23` (22 declaration lines)
- `TaskActionResult` — `app/src/main/java/com/jarvis/assistant/vision/VisualTaskOrchestrator.kt:24` (21 declaration lines)
- `LearningAssessment` — `app/src/main/java/com/jarvis/assistant/data/directives/DirectiveAiClient.kt:105` (20 declaration lines)
- `TermuxCommandResultReceiver` — `app/src/main/java/com/jarvis/assistant/automation/dev/TermuxBridge.kt:17` (19 declaration lines)
- `EmbeddingVector` — `app/src/main/java/com/jarvis/assistant/data/memory/vector/EmbeddingModels.kt:25` (19 declaration lines)
- `AuditDatabase` — `app/src/main/java/com/jarvis/assistant/security/sandbox/AuditEntities.kt:39` (18 declaration lines)
- `VadDecision` — `app/src/main/java/com/jarvis/assistant/audio/AdaptiveVadEngine.kt:6` (17 declaration lines)
- `AccessibilityDiagnosticReport` — `app/src/main/java/com/jarvis/assistant/automation/accessibility/AccessibilityHealthModel.kt:40` (17 declaration lines)
- `GraphMemoryNode` — `app/src/main/java/com/jarvis/assistant/data/memory/PersonalMemoryGraph.kt:26` (16 declaration lines)
- `LlmResponse` — `app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt:38` (13 declaration lines)
- `AgentTaskStatus` — `app/src/main/java/com/jarvis/assistant/automation/agent/AgentModels.kt:86` (11 declaration lines)
- `GoalExecutionResult` — `app/src/main/java/com/jarvis/assistant/automation/agent/AutonomousGoalAgent.kt:26` (11 declaration lines)
- `CouncilDeliberation` — `app/src/main/java/com/jarvis/assistant/automation/council/MultiAgentCouncil.kt:15` (11 declaration lines)
- `RiskEvaluation` — `app/src/main/java/com/jarvis/assistant/security/sandbox/RiskPolicy.kt:6` (11 declaration lines)
- `SettingsActivity` — `app/src/main/java/com/jarvis/assistant/ui/settings/SettingsActivity.kt:7` (11 declaration lines)
- `CameraVisionResult` — `app/src/main/java/com/jarvis/assistant/vision/CameraVisionAnalyzer.kt:20` (11 declaration lines)
- `WorkspaceUiState` — `app/src/main/java/com/jarvis/assistant/automation/dev/SoftwareEngineerWorkspaceState.kt:11` (10 declaration lines)
- `LedgerEntry` — `app/src/main/java/com/jarvis/assistant/automation/ledger/ExpenseLedgerTracker.kt:21` (10 declaration lines)
- `RemoteNeuralEmbeddingProvider` — `app/src/main/java/com/jarvis/assistant/data/memory/vector/EmbeddingProvider.kt:100` (10 declaration lines)
- `DeveloperInfo` — `app/src/main/java/com/jarvis/assistant/security/DeveloperInfo.kt:7` (10 declaration lines)
- `ScreenDiagnosis` — `app/src/main/java/com/jarvis/assistant/vision/ScreenIntelligenceEngine.kt:13` (10 declaration lines)
- `CachedNotification` — `app/src/main/java/com/jarvis/assistant/automation/notification/JarvisNotificationListenerService.kt:19` (9 declaration lines)
- `GeminiEmbeddingProvider` — `app/src/main/java/com/jarvis/assistant/data/memory/vector/EmbeddingProvider.kt:89` (9 declaration lines)
- `ActionExecutionResult` — `app/src/main/java/com/jarvis/assistant/automation/agent/AgentModels.kt:123` (8 declaration lines)
- `ToolExecutionResult` — `app/src/main/java/com/jarvis/assistant/automation/mission/MissionModels.kt:71` (8 declaration lines)
- `DeferredAction` — `app/src/main/java/com/jarvis/assistant/automation/offline/OfflineIntelligenceRouter.kt:20` (8 declaration lines)
- `ScheduledProactiveItem` — `app/src/main/java/com/jarvis/assistant/automation/proactive/ProactiveAssistantCoordinator.kt:15` (8 declaration lines)
- `LlmRequest` — `app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt:29` (8 declaration lines)
- `SessionLifecycleState` — `app/src/main/java/com/jarvis/assistant/background/VoiceSessionManager.kt:24` (8 declaration lines)
- `AmbientSnapshot` — `app/src/main/java/com/jarvis/assistant/core/ambient/AmbientExperienceModels.kt:14` (8 declaration lines)
- `BenchmarkTestResult` — `app/src/main/java/com/jarvis/assistant/core/eval/EvaluationModels.kt:37` (8 declaration lines)
- `TacticalScanResult` — `app/src/main/java/com/jarvis/assistant/vision/TacticalScannerManager.kt:12` (8 declaration lines)
- `VoiceMatchResult` — `app/src/main/java/com/jarvis/assistant/audio/OwnerVoiceManager.kt:9` (7 declaration lines)
- `GoalStep` — `app/src/main/java/com/jarvis/assistant/automation/agent/AutonomousGoalAgent.kt:18` (7 declaration lines)
- `SanitizedNotification` — `app/src/main/java/com/jarvis/assistant/automation/notification/NotificationPrivacyFilter.kt:3` (7 declaration lines)
- `ProviderHealthStatus` — `app/src/main/java/com/jarvis/assistant/automation/routing/LlmProvider.kt:21` (7 declaration lines)
- `SkillParameter` — `app/src/main/java/com/jarvis/assistant/automation/skills/SkillModels.kt:7` (7 declaration lines)
- `ConversationStateTransition` — `app/src/main/java/com/jarvis/assistant/core/ambient/AmbientExperienceCoordinator.kt:12` (7 declaration lines)
- `MemoryProvenance` — `app/src/main/java/com/jarvis/assistant/data/memory/PersonalMemoryGraph.kt:18` (7 declaration lines)
- `SetupPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:87` (7 declaration lines)
- `WeatherData` — `app/src/main/java/com/jarvis/assistant/util/LocationWeatherHelper.kt:26` (7 declaration lines)
- `IndependentVerificationReport` — `app/src/main/java/com/jarvis/assistant/automation/council/CouncilModels.kt:29` (6 declaration lines)
- `SuggestionStatus` — `app/src/main/java/com/jarvis/assistant/automation/proactive/ProactiveEngineModels.kt:27` (6 declaration lines)
- `OfflineReflexResult` — `app/src/main/java/com/jarvis/assistant/automation/reflex/OfflineReflexExecutor.kt:16` (6 declaration lines)
- `PendingWhatsAppDraft` — `app/src/main/java/com/jarvis/assistant/automation/whatsapp/WhatsAppAssistant.kt:16` (6 declaration lines)
- `MemoryAwarePrompt` — `app/src/main/java/com/jarvis/assistant/data/memory/MemoryAwareContextBuilder.kt:7` (6 declaration lines)
- `MemorySensitivity` — `app/src/main/java/com/jarvis/assistant/data/memory/PersonalMemoryGraph.kt:11` (6 declaration lines)
- `ContradictionReport` — `app/src/main/java/com/jarvis/assistant/data/memory/PersonalMemoryGraph.kt:43` (6 declaration lines)
- `RealtimeAudioInput` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:126` (6 declaration lines)
- `RealtimeAudioPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:133` (6 declaration lines)
- `ProviderUsageStats` — `app/src/main/java/com/jarvis/assistant/data/preferences/AppPreferences.kt:826` (6 declaration lines)
- `HomeStatusState` — `app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt:890` (6 declaration lines)
- `OrbSettings` — `app/src/main/java/com/jarvis/assistant/ui/orb/OrbSettings.kt:9` (6 declaration lines)
- `CrossDeviceShareResult` — `app/src/main/java/com/jarvis/assistant/automation/bridge/CrossDeviceBridge.kt:13` (5 declaration lines)
- `ParsedCommandResult` — `app/src/main/java/com/jarvis/assistant/automation/command/CommandParser.kt:6` (5 declaration lines)
- `SanitizationResult` — `app/src/main/java/com/jarvis/assistant/automation/intelligence/PromptInjectionSanitizer.kt:5` (5 declaration lines)
- `TransactionType` — `app/src/main/java/com/jarvis/assistant/automation/ledger/ExpenseLedgerTracker.kt:15` (5 declaration lines)
- `CapabilityAvailability` — `app/src/main/java/com/jarvis/assistant/automation/offline/OfflineIntelligenceRouter.kt:14` (5 declaration lines)
- `CallScreeningResult` — `app/src/main/java/com/jarvis/assistant/automation/phone/CallScreeningReceiver.kt:213` (5 declaration lines)
- `ProtocolType` — `app/src/main/java/com/jarvis/assistant/automation/protocol/ProtocolManager.kt:12` (5 declaration lines)
- `ProtocolExecutionResult` — `app/src/main/java/com/jarvis/assistant/automation/protocol/ProtocolManager.kt:18` (5 declaration lines)
- `ActionDispatchResult` — `app/src/main/java/com/jarvis/assistant/background/DeviceActionDispatcher.kt:11` (5 declaration lines)
- `ScreenVerificationResult` — `app/src/main/java/com/jarvis/assistant/core/world/WorldModelCoordinator.kt:6` (5 declaration lines)
- `ServerContentPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:143` (5 declaration lines)
- `SearchMode` — `app/src/main/java/com/jarvis/assistant/data/memory/vector/EmbeddingModels.kt:12` (4 declaration lines)
- `GenerationConfigPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:95` (4 declaration lines)
- `ModelPartPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:153` (4 declaration lines)
- `InlineAudioPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:158` (4 declaration lines)
- `OrbDebugCounters` — `app/src/main/java/com/jarvis/assistant/ui/orb/OrbMotion.kt:6` (4 declaration lines)
- `CouncilHttpResponse` — `app/src/main/java/com/jarvis/assistant/automation/agent/CancellableCouncilRequest.kt:11` (3 declaration lines)
- `LearningMetricRow` — `app/src/main/java/com/jarvis/assistant/data/directives/DirectiveData.kt:33` (3 declaration lines)
- `MemorySyncWorker` — `app/src/main/java/com/jarvis/assistant/data/memory/sync/MemorySyncWorker.kt:8` (3 declaration lines)
- `GeminiBidiSetup` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:83` (3 declaration lines)
- `SpeechConfigPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:102` (3 declaration lines)
- `VoiceConfigPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:106` (3 declaration lines)
- `PrebuiltVoiceConfigPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:110` (3 declaration lines)
- `ContentPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:114` (3 declaration lines)
- `PartTextPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:118` (3 declaration lines)
- `GeminiRealtimeInput` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:122` (3 declaration lines)
- `GeminiBidiServerResponse` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:139` (3 declaration lines)
- `ModelTurnPayload` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:149` (3 declaration lines)
- `AvailableGeminiModel` — `app/src/main/java/com/jarvis/assistant/network/GeminiModelCatalog.kt:15` (3 declaration lines)
- `GeminiModelResolver` — `app/src/main/java/com/jarvis/assistant/network/GeminiModelCatalog.kt:44` (3 declaration lines)
- `OrbUiState` — `app/src/main/java/com/jarvis/assistant/ui/orb/OrbState.kt:18` (3 declaration lines)
- `GitStatusAndDiffDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:244` (1 declaration lines)
- `ExecuteApprovedCommandDevTool` — `app/src/main/java/com/jarvis/assistant/automation/dev/DevToolRegistry.kt:322` (1 declaration lines)
- `EmptyConfig` — `app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt:100` (1 declaration lines)
- `OrbStyle` — `app/src/main/java/com/jarvis/assistant/ui/orb/OrbSettings.kt:6` (1 declaration lines)

### Verification boundary

No Kotlin, XML, manifest, database, worker, rendering or execution implementation changed in A1. A0's completed tests/lint cover the identical production source; A1 ran lexical reference indexing, registry/manifest/layout inspection and `git diff --check`. No R8 stripping analysis, APK assembly, app installation or live mission/audio execution was performed. DEAD/SHADOWED recommendations are explicitly deferred, not permission to delete code now.
