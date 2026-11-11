# CI startup failure evidence and workflow split

On 2026-11-11 the public run summary for [Android CI 42452256296](https://github.com/levinhocall/Jarvis-os/actions/runs/42452256296) (commit `e38b37aa20b86b662da9950830ac6e9096be081e`) displayed this exact annotation:

> The action actions/upload-artifact@v4 is not allowed in levinhocall/Jarvis-os because all actions must be from a repository owned by levinhocall.

The jobs API returned `jobs: []`. This was pre-run Actions policy validation, not a Gradle, SQLCipher, YAML-syntax or custom-fetch runtime failure. That particular workflow used the disallowed upload action. Do not extrapolate this cause to every historical failed run without its annotation.

Current-main predecessor [42979318685](https://github.com/levinhocall/Jarvis-os/actions/runs/42979318685), SHA `56b37aa`, succeeded with the existing shell-based exact-SHA sparse fetch, tests, migration checks, lint and Android-test Kotlin compilation. Therefore replacing that fetch with `actions/checkout` would reintroduce the same owner-only action restriction rather than repair this failure.

- `android.yml`: push + pull_request + optional dispatch, `contents: read` only, tests/lint in `Part 7`, migration preservation checks and Android-test compilation. No APK tasks, release writes, third-party actions or secrets passed to PR builds.
- `android-apk.yml`: manual dispatch only. Retains verification before APK assembly, SHA-256 checksum, exact-source tag reservation and optional permanent prerelease publication. `publish_release` is retained. Tags use `debug-ci-apk-<run_id>` because a newly separated workflow has its own run-number sequence, which could collide with existing `debug-ci-<run_number>` tags. Existing tags/releases are unchanged. No APK workflow was dispatched during this task.
- `main.yml`: inspected; repository integrity check uses the same working custom fetch and validates wrapper checksum; no APK tasks.
- `android-runtime.yml`: inspected; already manual-only. It deliberately assembles/installs app and test APKs to run `connectedDebugAndroidTest`, so it remains deferred until runtime testing is authorized.

PR description must include the exact annotation above, not an invented YAML or permission failure. Workflow parse/structure checks and local Gradle results are distinct from hosted execution; record the actual new hosted run results in the delivery report.
