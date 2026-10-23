# JARVIS OS — Autonomous Agent & Cognitive System Architecture

## 1. High-Level System Architecture

```mermaid
graph TD
    User([User Voice / Touch / UI]) --> |Audio / Text / Gesture| UnifiedRuntime[JarvisUnifiedRuntime]
    
    subgraph Perception Layer
        A11yService[JarvisAccessibilityService] --> |Node Tree & Events| A11yHealth[AccessibilityHealthModel]
        ScreenCap[ScreenCaptureBridge] --> |Rate-Limited Frames| MultimodalVision[Vision & Screen Grounding]
        AudioIn[AudioPipelineManager] --> |16kHz PCM & VAD| HotwordVosk[Vosk Wake Word & Barge-In]
    end

    subgraph Security & Guardrails
        UnifiedRuntime --> Sanitizer[UntrustedContentSanitizer]
        Sanitizer --> Sandbox[CapabilitySandbox & SSRF Guard]
        Sandbox --> AuditLedger[(Room Audit Database)]
        Sandbox --> RiskPolicy[Risk Policy & Word-Boundary Gate]
    end

    subgraph Cognitive Routing & Inference
        RiskPolicy --> CognitiveRouter[Cognitive Router]
        CognitiveRouter --> |Reflex Hardware| OfflineExecutor[OfflineReflexExecutor]
        CognitiveRouter --> |Cloud Fast / Reasoning| KeyPool[ApiKeyRotator & Key Pool]
        KeyPool --> GeminiProvider[Gemini 1.5/2.0 Live Provider]
        KeyPool --> OpenAiProvider[OpenAI Compatible Provider]
    end

    subgraph Autonomous Agent Execution
        GeminiProvider --> LlmPlanner[LlmPlanner]
        LlmPlanner --> ToolRegistry[Unified ToolRegistry]
        ToolRegistry --> AgentLoop[SelfHealingAgentLoop]
        ToolRegistry --> DevAgent[Autonomous Dev Studio & Termux]
        ToolRegistry --> MissionEng[MissionEngine & WorkManager]
    end

    subgraph Persistence Layer
        DevAgent --> MemoryRepo[MemoryRepository]
        MissionEng --> RoomMissions[(Room Missions Table)]
        MemoryRepo --> RoomMemory[(Room Memory Items)]
    end
```

---

## 2. Component Catalog

| Component | Responsibility | Dependencies | Threading Model | Failure Mode |
| :--- | :--- | :--- | :--- | :--- |
| **`JarvisUnifiedRuntime`** | Central orchestrator for request ingress, boot self-tests, and routing. | `CapabilitySandbox`, `CognitiveRouter`, `MissionEngine` | `Dispatchers.Default` | Returns safe fallback or security violation diagnostic. |
| **`VoiceSessionManager`** | Manages WebSocket live session, reconnect backoff, and heartbeat. | OkHttp WebSocket, Coroutines | `Dispatchers.IO` | Exponential backoff (max 5 attempts), transitions to `ERROR`. |
| **`AudioPipelineManager`** | Low-latency 16kHz PCM capture, hardware AEC, and loudspeaker barge-in. | `AudioRecord`, `AcousticEchoCanceler` | Dedicated IO thread | Graceful audio device fallback, zeroed frame keepalive. |
| **`ScreenCaptureBridge`** | Rate-limited frame capture (JPEG quality 65%) with MediaProjection. | `MediaProjection`, `ScreenCaptureManager` | Asynchronous handler | Skips frames if rate limit (< 800ms) or drops gracefully. |
| **`DeviceActionDispatcher`** | Dispatches hardware controls, phone calls, volume, and app intents. | Android Framework Services | Main / IO dispatch | Reports structured failure or missing permission. |
| **`CognitiveRouter`** | Classifies taxonomy, enforces on-device privacy tier, tracks EMA latency. | ConcurrentHashMap, ProviderHealth | Thread-safe synchronized | Fails over through configured provider chain to local reflex. |
| **`ApiKeyRotator`** | Round-robin key rotation with 429 exponential backoff cooldown. | Gson, SecureSharedPreferences | Thread-safe synchronized | Skips rate-limited keys; uses fallback primary key. |
| **`AutonomousDevLoop`** | Executes 6-phase engineering tasks (Inspect, Plan, Execute, Test, Verify, Report). | `SoftwareEngineeringAgent`, `TermuxBridge`, `MemoryRepository` | `Dispatchers.IO` | Rollback on test failure, reports non-zero exit codes. |
| **`MissionEngine`** | Long-horizon multi-step mission orchestrator with step-level persistence. | Room `MissionDao`, WorkManager | `Dispatchers.IO` | Suspends non-idempotent steps as `UNVERIFIED` across reboots. |
| **`CapabilitySandbox`** | SSRF prevention (DNS resolution, private IP ranges) and path traversal checks. | `AuditDatabase`, java.nio.file.Path | IO / Default | Blocks malicious directives and logs cryptographic tamper-evident record. |

---

## 3. State Transition Diagrams

### 3.1 Voice Session Lifecycle
```mermaid
stateDiagram-v2
    [*] --> DISCONNECTED
    DISCONNECTED --> CONNECTING : startSession()
    CONNECTING --> LIVE : Handshake & Auth Success
    CONNECTING --> RECONNECTING : Handshake Failed
    LIVE --> RECONNECTING : Connection Dropped / 429 Rate Limit
    RECONNECTING --> LIVE : Reconnect Succeeded
    RECONNECTING --> ERROR : Max Retries Exceeded (5)
    ERROR --> DISCONNECTED : clearError() / Manual Reset
    LIVE --> DISCONNECTED : stopSession()
```

### 3.2 Circuit Breaker State Machine
```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN : 3 Consecutive Errors (Threshold Reached)
    OPEN --> HALF_OPEN : Cooldown Period Elapses (30,000ms)
    HALF_OPEN --> CLOSED : Probe Request Succeeds
    HALF_OPEN --> OPEN : Probe Request Fails
```

### 3.3 Mission Execution Lifecycle
```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> RUNNING : startMission()
    RUNNING --> STEP_EXECUTION : Next Step Available
    STEP_EXECUTION --> STEP_VERIFIED : Outcome Confirmed
    STEP_EXECUTION --> UNVERIFIED : Non-idempotent Action Interrupted
    STEP_VERIFIED --> RUNNING : Has Remaining Steps
    STEP_VERIFIED --> COMPLETED : All Steps Passed
    STEP_EXECUTION --> FAILED : Execution Error & No Retry
    FAILED --> [*]
    COMPLETED --> [*]
```

---

## 4. Security & Sandbox Boundary Model

### 4.1 SSRF & Network Security Gates
Any URL or network request generated by an agent must pass `CapabilitySandbox.validateUrl()`:
1. **IP Literal & Encoding Defense**: Resolves decimal integers (e.g. `2130706433`), hex addresses, and octal notations.
2. **DNS Pre-flight Resolution**: Resolves all `InetAddress` records to verify no private, link-local, loopback, or cloud metadata endpoints (`169.254.169.254`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `fc00::/7`, `fe80::/10`) are targeted.
3. **Redirect Hop Tracking**: Manually enforces a strict 5-hop redirect limit with full re-validation at each hop to eliminate DNS rebinding attacks.

### 4.2 Workspace Path Traversal Defense
File access is constrained via canonical `Path.startsWith` component matching:
- Prevents substring escape attacks (e.g. `/data/app` vs `/data/app2`).
- Blocks access to Android protected system directories (`/proc`, `/sys`, `/dev`, `/system`, `/etc`, `/data/system`).

### 4.3 Destructive Git Operation Auditing
Commands like `git push -f`, `git branch -D`, `git reset --hard`, and `git clean -fd` are intercepted by `SoftwareEngineeringAgent.auditGitOperation()`:
- Automatically halts automated execution.
- Generates a user-facing authorization dialog before execution.
