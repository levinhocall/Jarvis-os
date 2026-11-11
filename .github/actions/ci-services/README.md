# Local verification services

This repository's Actions allowlist rejected external `uses: actions/upload-artifact@v4` before a runner job started. This local Node 24 action uses pinned official GitHub SDK packages instead. It does not change repository policy or request a writable contents token.

Run `npm ci --ignore-scripts --no-audit --no-fund` in this directory before using the action; `npm test` runs eight fake-client/filesystem tests without a GitHub credential or upload. package-lock.json pins transitive resolution/integrity. node_modules is ignored, never committed.

Modes:

- `reports`: `kind=unit` or `lint` chooses explicit `Part 7/app/build` report roots. File/ancestor symlinks, unrecognized kinds/names and more than 50 MiB / 4,096 files are rejected. No reports means no upload is claimed. A successful artifact service ID is required before success is logged. Artifacts retain for 14 days.
- `restore-cache`: dependency/wrapper caches only, under GRADLE_USER_HOME or ~/.gradle. A miss or cache service failure is visible and does not alter the Gradle result.
- `save-cache`: same paths; refused except push events on refs/heads/main. Workflow additionally gates saves on successful checks. PRs do not write caches.

No preferences, databases, app files, arbitrary workspace archive, Gradle user properties, user audio/transcripts or runtime credentials are uploaded by this wrapper. Test/lint report artifacts are explicitly requested engineering evidence; tests use fixtures rather than live account data.

SDKs: @actions/artifact 6.3.1 / @actions/cache 6.3.0 (official GitHub-maintained packages, MIT). Temporary complete dependency install measured about 135 MiB; no Android runtime/APK dependency. See Part 7/docs/adr/001-v2-trustworthy-ci.md for alternatives, risks and rollback. Actual GitHub runtime upload/cache behavior must be verified in a hosted run; fake-client tests do not prove a live upload.
