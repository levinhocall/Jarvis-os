# JARVIS OS — Part 7 — Phase 1.1 Repository Audit

Scope: `Part 7/` only. Parts 1–6 untouched. Base commit: `7a9fe7e` (main).
Method: static inspection (file scans, grep, import graph, manifest/Gradle/CI review). Scripts were throw-away and are not committed.

## 0. What was and was NOT verified

| Item | Status | Evidence |
|---|---|---|
| Static scan of Kotlin/XML/Gradle/manifest | Done | counts in section 1 |
| Compile / unit tests / lint / assembleDebug | **Not run locally** (no Android SDK; Google Maven unreachable from this workspace) | — |
| CI on `7a9fe7e` (Android CI: unit tests + lint + assembleDebug) | **Success** | GitHub Actions run `42413179965` |
| Instrumented tests (`android-runtime.yml`, Android 16 emulator) | Not checked for this commit | — |
| Runtime behaviour of any feature | **Not verified** (no device) | — |
| "Broken imports / missing resources" | Compiles in CI at `7a9fe7e`, so no compile-time breakage; unused resources not analysed | CI |

Because runtime was not exercised, feature status below is **"CI-compiled + test-referenced" vs "CI-compiled only"**, not "working". Nothing is called working without a device test.

## 1. Inventory

- 335 Kotlin files (~63.7k LOC), 142 XML, 23 HTML assets, 0 Java. ViewBinding/XML UI (no Compose).
- `applicationId com.jarvis.assistant`, compileSdk/targetSdk 36, minSdk 26, versionCode 147 / 1.9.3.
- Tests: 72 unit-test files, 8 instrumented-test files.
- CI: `android.yml` (unit tests → lint → assembleDebug, on push to main for `Part 7/**`), `android-runtime.yml` (Android 16 emulator, `connectedDebugAndroidTest`), `ci-diagnostics.yml`.
- Shizuku: **no reference** in `.kt/.kts/.xml/.toml` (case-insensitive grep). ✔
- Services: `JarvisConversationService` (FGS: microphone|mediaPlayback|mediaProjection), `JarvisAccessibilityService`, `JarvisNotificationListenerService`, plus a wallpaper service. Only **one** foreground microphone service declared. ✔
- Databases: `MemoryDatabase` (Room v8 + SQLCipher, migrations 1→8) and `AuditDatabase` (Room v1, plaintext) — see F3.
- Secrets: no hardcoded key-like strings found. API key lives in `EncryptedSharedPreferences` (`AppPreferences`). `allowBackup=false`, cleartext disabled.

## 2. Findings (ranked)

| ID | Sev | Finding | Evidence | Proposed phase |
|---|---|---|---|---|
| F1 | High | `bash -c <command>` executed on host. `DevToolRegistry` path is gated (sandbox phrase filter + approval bound to exact command text) — acceptable control. `CodingDevFragment.runHostCommand` has no gate but is only called with hard-coded literals (incl. `git push origin main`). Host-dev detection hard-codes `/root/jarvis-os-ref`. Inert on phones, but dev-machine artefact in production code and a shell sink that must not become reachable from model output. | `automation/dev/DevToolRegistry.kt:264,372`; `ui/dev/CodingDevFragment.kt:240-561,703-705` | 1.5 |
| F2 | Med | Orb WebView enables `allowUniversalAccessFromFileURLs` + `allowFileAccessFromFileURLs` with a JS bridge. `orb.html` has no remote URLs and no navigation interception, so it is low risk today, but one remote load would escalate it. | `ui/orb/OrbHelper.kt:50-52` | 1.5 (switch to `WebViewAssetLoader`, already used in `TaskProgressOverlay`) |
| F3 | Med | Second Room DB `jarvis_audit.db` is **unencrypted** and `exportSchema=false`. It stores `actionName`, `invoker`, `details` (free text). Violates "single DB / encrypted data" intent. | `security/sandbox/AuditEntities.kt:38-55` | 1.7 |
| F4 | Med | Five unscoped `CoroutineScope(...)` launches (no cancellation owner): `SecurityGuardManager.kt:231,258`, `ExpenseLedgerTracker.kt:141`, `SoftwareEngineerWorkspaceState.kt:25`, `ModelDownloadWorker.kt:71`. | grep | 1.2 |
| F5 | Med | Room schemas exported only for v6–v8. Migration chain v1→v8 is covered by `RoomMigrationInstrumentedTest`, but it calls migrations directly (not `MigrationTestHelper` against schemas); emulator CI result for current commit not confirmed. | `app/schemas/…/MemoryDatabase/{6,7,8}.json` | 1.7 / 1.9 |
| F6 | Low | Cycles between feature packages (below). Top-level graph is one big SCC. | section 4 | 1.2 |
| F7 | Low | Duplicate type name `ScreenContext` in two packages (different shapes). | `automation/grounding/ScreenGroundingModels.kt:72`, `core/world/WorldModelModels.kt:20` | 1.2 |
| F8 | Low | `MemorySyncWorker` + `MEMORY_SYNC_ENDPOINT=""` + `RemoteNeuralEmbeddingProvider`: cloud sync is disabled in Gradle but the code path remains. | `app/build.gradle.kts`, `data/memory/sync/` | 1.7 (verify then decide; do not delete blindly) |
| F9 | Low | Permissions are broad (`QUERY_ALL_PACKAGES`, `WRITE_SETTINGS`, `CALL_PHONE`, `SYSTEM_ALERT_WINDOW`, Termux `RUN_COMMAND`). Acceptable for a personal APK; should be registered centrally. | `AndroidManifest.xml` | 1.4 |
| F10 | Info | Exported components are guarded: accessibility/notification services by `BIND_*` permission, `CallScreeningReceiver` by `READ_PHONE_STATE`, `OverlayBootReceiver` filters action, mission/Termux receivers `exported=false`. | manifest + `OverlayBootReceiver.kt:17` | — |

Prompt-injection control today: `CapabilitySandbox.validateInputText` (NFKC + zero-width stripping + phrase blocklist). A blocklist is a weak primary defence; the real control is exact-command approval (F1). Phase 1.5 should make approval + risk class the primary gate.

## 3. Duplicate / dead-code candidates (NOT verified — do not delete yet)

Heuristic: top-level type with no reference in other main files, res, manifest, assets. 105 hits, 47 with ≤2 self-references. Most are model/serialization classes used inside one file (false positives). Genuine-looking candidates with **no test reference**:

`VoskModelDownloader`, `AutonomousGoalAgent`, `SoftwareEngineerWorkspaceCoordinator`, `OpenAiCompatibleProvider`, `MemorySyncWorker`, `UpdatesFragment`, `AmbientSnapshot`, `BenchmarkTestResult`, `SuggestionStatus`, `IndependentVerificationReport`, `ActionExecutionResult`, `TaskContext`, `EmbeddingVector`, `SearchMode`.

Each needs reflection / DI / WorkManager-by-name / layout-tag check before any removal (rule: no deletion without dependency + usage verification).

Not a finding: manifest `.DefaultIcon/.AuraRedIcon/.CrimsonIcon/.MidnightIcon` are `activity-alias` names, not classes.

## 4. Dependency map (import-level, approximate)

Wildcard and same-package references are not counted; treat counts as relative weight.

Top level (from → to):

- `ui` → root 50, data 29, automation 17, util 16, background 5, audio 4, vision 2, security 1
- `automation` → data 11, security 9, root 8, util 6, core 4, vision 3, ui 1, audio 1
- `core` → automation 13, data 4, security 2, audio 1
- `background` → audio 5, util 3, root 2, data/network/ui/vision 1 each
- `vision` → automation 8, background/data/root/core 1 each
- `security` → automation 2, data 1, root 1
- `audio` → data 2, root 2, automation 1; `data` → root 3, automation 1; `util` → root 3, automation 3, data 1; `network` → data 1

Layering violations worth fixing in 1.2: `data → automation`, `security → automation`, `automation → ui`, `background → ui`.

Mutual dependencies (depth 2):

- `automation.agent` ↔ `automation.mission` (5/1)
- `automation.mission` ↔ `security.sandbox` (3/2)
- `automation.agent` ↔ `vision` (1/4); `automation.accessibility` ↔ `vision` (1/3); `automation.accessibility` ↔ `util` (1/3)
- `background` ↔ `ui.home` (1/2); `background` ↔ `vision` (1/1)
- `data.directives` ↔ `data.memory` (1/1)
- `ui.chat` ↔ `ui.home` (2/1); `ui.home` ↔ `ui.settings` (1/2)
- root (`JarvisApp`) ↔ `audio`, `automation.mission`, `automation.proactive`, `automation.youtube`, `data.memory`, `security` (application-singleton access)

One 27-package strongly connected component spans `automation.*`, `security.*`, `data.*`, `background`, `audio`, `vision`, `util`, `ui.*`.

## 5. Feature status by evidence

"Tested" = referenced by unit tests; "Instr." = referenced by instrumented tests. None of these is a runtime-verified claim.

| Area (package) | Files | Evidence level |
|---|---|---|
| `data.memory`, `data.chat`, `data.mission` | 26 | CI-compiled, unit-tested, instrumented (migrations, SQLCipher, keystore, transactions) |
| `automation.mission`, `automation.routing`, `automation.grounding`, `automation.integration`, `automation.offline`, `automation.proactive`, `automation.skills`, `automation.dev`, `automation.council` | ~50 | CI-compiled, unit-tested |
| `audio`, `audio.wake`, `background` | 26 | CI-compiled, partly unit-tested; wake has instrumented refs |
| `security`, `security.sandbox`, `core.*` | ~19 | CI-compiled, unit-tested |
| `automation.agent`, `vision` | 17 | CI-compiled, partly unit-tested (8/26 and 5/14 types) |
| `automation.accessibility`, `automation.notification`, `automation.command` | 8 | CI-compiled, ~half of types test-referenced |
| `automation.phone` (call screening), `automation.contacts`, `automation.whatsapp`, `automation.youtube`, `automation.bridge`, `automation.briefing` | 13 | **CI-compiled only, no tests** |
| `ui.*` (home, settings, chat, directives, aura, extras, memory, dev, map, whiteboard, wallpaper, voice, widget, orb, avatar, permission) | ~70 | **CI-compiled only**; a launch smoke test and a few instrumented refs exist |

No area is classified "broken": there is no evidence of breakage, and equally no runtime evidence of correctness.

## 6. Existing documentation to reconcile

Repo already contains `PHASE_0_REPOSITORY_AUDIT.md`, `JARVIS_V4_ENGINEERING_PROGRESS.md`, `Part 7/docs/V4_SOURCE_AUDIT_2026-11-04.md`. This audit does not replace them; Phase 1.2 should reconcile overlap.

## 7. Recommended order for Phase 1.2 (each its own branch, CI-gated before main)

1. Scope the five unscoped coroutines (F4) — low risk, testable.
2. Rename/merge `ScreenContext` (F7).
3. Break `automation.agent ↔ automation.mission` and `automation.mission ↔ security.sandbox` via small interfaces (F6).
4. Move shell execution behind one `ShellPolicy` and delete the host-dev path hack (F1) — coordinate with 1.5.
5. Orb WebView: asset loader, drop universal file access (F2).
6. Encrypt or fold `AuditDatabase` into the encrypted store, with migration (F3, F5).

## 8. Phase 1.1 acceptance

- Parts 1–6 untouched: ✔ (this change adds one file under `Part 7/docs/`).
- No Shizuku dependency: ✔.
- Duplicate microphone FGS: none found ✔. Duplicate memory DB: none; one extra **audit** DB (F3). Duplicate mission executor: not found by static scan (not exhaustively proven).
- Build and tests: **not run locally**; last CI on main (`7a9fe7e`) succeeded.
