# ADR 004 — explicit APK request after verified main integration

Date: 2026-11-11. Accepted for the user's explicit request to merge current code into main and build/download a CI APK. This does not enable A4 importer/backup commits 3–4 or claim native/device verification.

## Context / options

The verified staged source is integrated into main at 07e86a0. Direct `gh workflow run apk.yml --ref main -f publish_release=true` fails at the workflow GET with **Forbidden** in this execution environment; the connected GitHub tools have no new workflow-dispatch operation. Git push works. Do not fabricate a run or ask the user to repeat an inaccessible CLI action.

Options: require the user to click Run workflow; add APK assembly to ordinary test/PR pushes; or add an explicit trusted tag request that starts the existing manual workflow inside GitHub with a properly scoped Actions token. Choose the third: it keeps the test/lint gate separate and the existing `apk.yml` manual dispatch available.

## Decision / security boundary

`apk-request.yml` runs only for an explicitly pushed `apk-request-*` tag. It checks that the tag SHA equals current main and has a successful **ci.yml push run on main at that exact SHA** before dispatching apk.yml at the requested tag. It does not merge, skip tests, expose tokens or execute user/model-supplied shell commands. All variable expansions are quoted; the workflow name and publish setting are fixed. Its only extra permission is actions:write for dispatch; contents stays read. PR/normal CI tokens are unchanged. No new dependency, Android permission or app behavior.

Tag creation is an authorized release request, not an automatic build on normal main/development/PR pushes. Push the request tag only after observing green main verification. The APK workflow re-runs tests/lint, compiles native tests, builds a debug APK and publishes the existing prerelease assets. The request's successful dispatch is **not proof** of APK success; inspect the actual downstream run and release assets.

Add APK signature/package verification using the already installed SDK before publishing, and include its certificate report alongside the APK/checksum. No signing keys are committed/cached/uploaded. This remains a development-signed debug build; no installed-phone signature match, native migration execution, install test or production-release claim.

## Verification / rollback

Run actionlint on all workflows and check the unchanged Android source against dd8f095. Hosted main CI must pass before creating the request tag; inspect request/downstream jobs and actual release metadata/digest before delivering a link. No new library install or app test is needed for YAML-only changes; existing app verification is re-executed by hosted CI.

Rollback: remove the request workflow or stop creating request tags; manual apk.yml remains available. This does not roll back an already-opened v9 database. Do not perform a destructive downgrade or delete memories to install an APK. If GitHub denies dispatch permissions, report that observed blocker; do not claim a completed APK.
