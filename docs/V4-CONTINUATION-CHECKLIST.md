# V4 continuation checklist — 2026-11-03

Baseline: `bb1e406`, clean checkout supplied on `work`; switched to `main` at the same commit. Remote main was verified at the same SHA using the configured proxy with network permission. Part 7 is the active Android application; Parts 1–6 are historical projects and remain untouched. Package `com.jarvis.assistant`, target SDK 36, Views/XML/ViewBinding UI, Kotlin. No Compose migration is planned.

This checklist supersedes historical completion claims where the current source differs. Source presence does not establish runtime acceptance.

| # | Workstream | Actual baseline / remaining acceptance |
|---|---|---|
| 1 | Orchestration | Council preflight wired to unified runtime; production MissionEngine remains action authority. Verification claims corrected: executor evidence is not independent verification; execution role cleared on all exits. |
| 2 | Independent LLM agents | Three text-only reviewers via LlmPlanner, no tool execution. Default-off consent gate added in API settings. |
| 3 | Parallel workers | Per-request three-call limit exists; global three-worker admission added with cancellation and timeout regression coverage. |
| 4 | Budgets/reconciliation | Atomic per-request call count, 18s reviewer / 24s batch deadline, role-keyed reports. Test failure isolation and cross-request bounds. Token/cost accounting remains absent. |
| 5 | Bidirectional sync | Disabled worker retained for old WorkManager jobs. Requires explicit opt-in client, endpoint security and safe conflict UX before reactivation. |
| 6 | Conflicts/tombstones | DAO fields/backend tombstones exist; disabled client provides no propagation. Backend scope isolation repaired; conditional writes and monotonic timestamps added. Never silently overwrite local facts. |
| 7 | Persistent cursors | Room memory_sync_state table exists but no active client uses it. Clock rollback/concurrent mutation acceptance remains open. |
| 8 | Local memory | Room v6 / SQLCipher / Keystore, CRUD, import/export and migrations exist. Android restart, migration and wrong-key instrumentation required. |
| 9 | Backend/deployment | FastAPI + SQLite + sentence-transformers, loopback Compose and systemd assets exist. No attached host, endpoint or deployment credentials. Deployment is unverified. Correct stale integration documentation. |
| 10 | Gemini Live | WebSocket transport and service wired. Requires real credential/handshake/reconnect tests. |
| 11 | ElevenLabs | Router and API settings wired; private speech stays offline. Requires real credential, playback and entitlement checks. |
| 12 | Neural embeddings | Backend has pretrained model; Android uses deterministic hashes. No local neural runtime/model artifact. Do not label hashing neural. |
| 13 | Android 16/accessibility | SDK 36 and bound accessibility service exist. Physical permission, gesture, FGS and lifecycle tests required. |
| 14 | Continuous voice/barge-in | Existing service/audio pipeline and regression tests. Hardware AEC, focus and interruption acceptance required. |
| 15 | Mission recovery/history | Room MissionEngine persistence and encrypted long-horizon checkpoints exist. Extended coordinator executor remains disconnected; avoid a second execution authority. |
| 16 | Security | Local encryption, permission and approval gates exist. Full exported-component/payload audit and encrypted exports remain open. |
| 17 | Integration/lifecycle | Execute available JVM/backend checks; no APK builds, no CI, no instrumentation APK generation. Physical device acceptance remains open. |

## Execution ledger

Record exact commands, results, modifications and remaining limitations in the continuation report. Do not turn unchecked rows into completion claims.
