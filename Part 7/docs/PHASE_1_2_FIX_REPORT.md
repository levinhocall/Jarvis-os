# Phase 1.2 — Audit Findings Fix Report

Branch: `phase-1.2/stabilization` (not merged, not pushed to main). Scope: `Part 7/` only, plus one CI trigger line (see Notes).
Verification method: GitHub Actions on pushed commits (no Android SDK locally). **No device/emulator run was performed**, so runtime behaviour is unverified.

## CI results (real)
| Commit | Result |
|---|---|
| 6c48bb3 (F4+F7+CI trigger) | success |
| 85b4850 (F6+F1+F2) | first run failed in "Verify Gradle environment" (transient SSL error downloading the Gradle distribution, not code); rerun: success |
| 944514c (F3/F5+F8+F9, HEAD) | success: unit tests, migration check, lint, assembleDebug, APK checksum, publish |

Intermediate commits e782118 and ae03b9b had their runs cancelled by concurrency; only the stacked results above count.

## Findings
| ID | Root cause | Fix | Files | Tests |
|---|---|---|---|---|
| F4 | `CoroutineScope(Dispatchers.IO).launch` with no owner/handler in SecurityGuardManager, ExpenseLedgerTracker; unowned scope in the dev workspace coordinator; worker init clarity | `launchPersisting` + app-owned `applicationScope`; coordinator owns/cancels its scope and job; `coroutineScope{}` in ModelDownloadWorker | `util/BackgroundPersistence.kt`, `JarvisApp.kt`, `SecurityGuardManager.kt`, `ExpenseLedgerTracker.kt`, `SoftwareEngineerWorkspaceState.kt`, `ModelDownloadWorker.kt` | `BackgroundPersistenceTest` (5) |
| F7 | Two unrelated classes both named `ScreenContext` | `core.world.ScreenContext` → `WorldScreenSummary`, KDoc cross-refs | `WorldModelModels.kt`, `WorldModelCoordinator.kt`, `ScreenGroundingModels.kt` | `WorldModelTest` addition |
| F6 | Package cycles agent↔mission, mission↔sandbox | Extracted neutral `automation.tooling` (ToolModels, ToolRegistry), imports rewritten by script | `automation/tooling/*` + ~12 importers, 7 tests | existing suites compile and pass in CI; script check: agent→mission 0 edges |
| F1 | `ProcessBuilder("bash","-c",cmd)` host shell sink; hardcoded `/root/jarvis-os-ref` | `ShellPolicy`: classification, argv exec without a shell, approval bound to exact command + workdir, opt-in host mode, timeout | `automation/dev/ShellPolicy.kt`, `DevToolRegistry.kt`, `AutonomousDevLoop.kt`, `CodingDevFragment.kt` | `ShellPolicyTest` + 2 updated suites |
| F2 | Orb WebViews loaded from `file://` with broad access | `WebViewAssetLoader` on `https://appassets.androidplatform.net`, file access off, navigation/sub-resource allow-list, reduced-motion gating | `ui/orb/OrbWebViewSecurity.kt`, `HomeFragment.kt`, `OrbHelper.kt`, `assets/orb.html` | `OrbWebViewSecurityTest` |
| F3/F5 | `AuditDatabase` is plaintext SQLite holding action names/invokers/details | **Not encrypted.** Traced: `getInstance` has zero callers and `CapabilitySandbox` is always built with `auditDao = null`, so `jarvis_audit.db` is never created and no rows exist. The plaintext builder now fails closed so it cannot be wired up unencrypted | `security/sandbox/AuditEntities.kt` | `AuditStorageGuardTest` (source-level) |
| F8 | Possible remote memory sync/embedding | Already inert: `MemorySyncWorker` is a no-op, `RemoteNeuralEmbeddingProvider` is local-only, endpoint is `""` and unread. Nothing removed (kept for old WorkManager requests); guard added | none (tests only) | `MemorySyncIsolationTest`, existing `LocalOnlyEmbeddingTest` |
| F9 | Five high-risk permissions without central rationale | Documented each with call sites and denial behaviour; no permission added/removed; manifest drift test | `docs/PERMISSION_GOVERNANCE.md` | `SensitivePermissionGovernanceTest` |

## Dead-code cleanup
Nothing deleted. Candidates from the audit (VoskModelDownloader, AutonomousGoalAgent, OpenAiCompatibleProvider, UpdatesFragment, etc.) were not proven unused (reflection/XML/WorkManager/tests not fully traced), so by the no-blind-deletion rule they are retained and deferred.

## Limitations (stated plainly)
- Source-scan tests (F2/F3/F8/F9) guard against regressions; they do not prove runtime behaviour.
- F1 host-mode and approval flow, F2 orb rendering from the https asset origin, and F4 cancellation under real lifecycle were not run on a device.
- F3/F5 is mitigated, not solved: audit persistence stays disabled, so the signed audit ledger is in-memory only.

## Remaining risks
- `ContactCaller` and `DeviceActionDispatcher` start `ACTION_CALL` with no confirmed CALL_PHONE denial path (documented in PERMISSION_GOVERNANCE.md).
- Layering edges still present: automation→ui.aura, data.directives→agent, background→ui.
- Ledger info log still prints merchant/amount summary (sensitive-log filtering).

## Deferred
- 1.4: AuraOptions extraction and remaining layering edges.
- 1.5: sensitive-log filtering, runtime consent UI audit.
- 1.7: encrypted audit store with tested migration (reuse DatabaseEncryption/DatabaseKeyStore or fold into MemoryDatabase, schema exported from its first version); DB migration tests.
- 1.9: device/instrumented tests for F1, F2, F4; verified dead-code removal.

## Notes
- `.github/workflows/android.yml` (outside Part 7) gained only `phase-1.2/**` in the push trigger so feature branches build and publish a prerelease APK. A failed attempt using `actions/upload-artifact` (startup_failure) was reverted.
- The repo's empty `.github/workflows/main.yml` fails on every push independently of this work.
