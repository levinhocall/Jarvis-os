# ADR 001 — V2 A2 verification, artifacts and cache under owner-only Actions policy

Status: accepted for the explicitly requested A2 CI change; no Android runtime behavior change. Date: 2026-11-11. Source baseline: 2084fe3 plus A0/A1 documentation-only commits. This is not one of the pack's DESIGN-FIRST runtime tasks; A3 has a separate review gate.

## Context and evidence

The V2 pack requests `ci.yml` with independent unit/lint jobs, an existing-issue lint baseline, report artifacts, Gradle cache and schema drift checks; APK publication remains manual in `apk.yml`. Current android.yml already runs tests/lint without APK assembly, and android-apk.yml already isolates manual publication. Existing workflows are actionlint 1.7.12 clean. `CI_FAILURE_ANALYSIS.md` records the exact GitHub startup annotation: actions/upload-artifact@v4 is disallowed because all actions must be from repositories owned by levinhocall; zero jobs started. The current shell exact-SHA fetch has succeeded in both push and PR runs. `gh auth status` rejects the environment token; connector reads and git pushes work.

A0 freshly ran all unit/lint tasks: 514 JVM cases, zero failures/errors, one skip, 1,398 lint warnings and two hints. No APK build. The task now explicitly requests report artifacts and dependency cache; merely inserting `uses: actions/upload-artifact` / `actions/cache` would recreate startup failure even behind an `if` condition.

## Options considered

1. Ask to weaken repository Actions policy or create owner-owned action forks: unnecessary account/policy mutations and ongoing fork maintenance; rejected.
2. Use only logs/step summaries: works with current policy but does not meet requested downloadable report artifacts/cache; retained only as diagnostics fallback, not called equivalent.
3. Vendor compiled third-party action bundles: megabytes of opaque vendored code and difficult review; rejected.
4. A small **local repository action**, using pinned official GitHub artifact/cache JavaScript SDKs through Node 24. GitHub supplies the runtime context to local JS actions. No external `uses:` action; no contents:write on PR verification. Chosen, subject to actual hosted execution proving compatibility with the repo policy/runtime service.

## Decision

- Rename verification android.yml → ci.yml and manual android-apk.yml → apk.yml; preserve manual release logic, exact-SHA tags, checksums and permanent prereleases. Existing run/release URLs remain historical evidence; no APK dispatch until final authorized release stage.
- Unit and lint are independent jobs on push/PR, with working-directory Part 7, JDK 17, bounded timeout and concurrency cancellation. Both use the existing exact-SHA sparse fetch. Workflow/local-action changes also trigger CI.
- Add a committed generated `app/lint-baseline.xml`; warningsAsErrors makes a *new* warning fail. Do not suppress issue categories or auto-update the baseline in CI. Deliberately inject/remove a temporary resource warning locally to verify the gate fails, then rerun clean.
- Detect changed/untracked `app/schemas` after compilation; never auto-commit generated schemas.
- Local CI action uploads only bounded XML/HTML/text test/lint reports below explicit build-report roots, rejecting escaping paths/symlinks. Upload errors fail the report step; missing files are reported honestly. No app data, .env, preference/DB files, credentials or arbitrary workspace archive.
- Cache only Gradle dependency modules/wrapper distributions; not Gradle user properties or app data. Cache failures are visible warnings, not test successes/failures; save only on successful main pushes. No cache writes from PRs.
- No new manifest permission, exported component, Android dependency, runtime logger or app network behavior.

## Dependencies / size / license / maintenance

CI-only dependencies, exact versions resolved on 2026-11-11 from npm publisher metadata: `@actions/artifact` **6.3.1** and `@actions/cache` **6.3.0**, official GitHub-maintained SDK packages, MIT licenses. Root SDK unpacked metadata: 332,872 and 345,924 bytes; complete temporary install of 264 packages measured **135 MiB** (`du -sh`, rounded) outside this repo. **APK impact: zero bytes**; these run only on the CI runner. Commit package-lock integrity pins; install using npm ci --ignore-scripts. Transitive licenses/vulnerabilities are not all audited by this ADR. Alternatives are the disallowed external Actions or local opaque vendoring; the small source adapter plus lockfile is the maintainable policy-compatible option. Verify SDK/runtime behavior in hosted CI; do not claim successful artifact upload from local fake-client tests.

Actionlint 1.7.12 is a temporary development tool, downloaded from the official rhysd/actionlint GitHub release and checksum verified; it is not an Android dependency or committed binary.

## Risks, threading and rollback

No audio, database or UI threading changes. CI jobs run in isolated runners; local JS action owns its awaited async SDK operations and fails before reporting upload success if the upload fails. Node/GitHub runtime compatibility, artifact service availability, cache size/quota and metadata path differences can still fail hosted verification; inspect actual logs. A cache miss is expected on a new key.

GitHub owner-only policy is not changed. Local-action allowance must be demonstrated by a real branch/PR run. If policy or runtime blocks the adapter, document the exact rejection and retain read-only tests/lint rather than reintroducing blocked external actions or silently claiming uploads.

Rollback: revert the A2 commit(s), restoring previous android.yml/android-apk.yml and lint configuration/baseline. This changes no app data or schema. Do not merge or dispatch APK while phase gates remain outstanding.


## Initial baseline review after hosted execution

Hosted a142178/46c2fd7 runs executed jobs and uploaded actual artifacts but correctly failed the lint gate on 23 additional metadata-based dependency-version notices absent from the local cached baseline. Safe diagnostic output proved all were 22 GradleDependency + one wrapper-version notice for **existing** pinned source dependencies. HOSTED_DEPENDENCY_NOTICES.json preserves exact source/run/locations. Seed the initial baseline with these reviewed exact notices as well (1,423 total entries); do not disable categories, auto-update baselines in CI, upgrade unrelated dependencies or misreport the failed runs as green. Local-action allowance/report uploads are demonstrated; cache miss observed, warm-hit/save unverified on feature branches.
