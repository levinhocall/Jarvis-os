# Build and download APKs without ChatGPT

The Android CI workflow is stored in this repository and runs on GitHub-hosted runners. Running it and downloading its output do not require a ChatGPT subscription.

## Build a fresh APK

1. Sign in to the GitHub account with write access to this repository.
2. Open [Actions → Android CI](https://github.com/levinhocall/Jarvis-os/actions/workflows/android.yml).
3. Choose **Run workflow**, select **main**, and leave **Keep the tested APK in a permanent GitHub prerelease** enabled.
4. Press **Run workflow** and open the new run. Wait for **Test, build and publish APK** to finish successfully.
5. Open [Releases](https://github.com/levinhocall/Jarvis-os/releases). Open the newest **JARVIS OS debug APK (CI …)** prerelease and download **app-debug.apk** from **Assets**.

A push that changes `Part 7/**` or the Android workflow also starts the pipeline automatically. Documentation-only pushes do not start another Android build.

## Download an existing CI build later

Download the APK again from its release's **Assets**. GitHub Releases do not use the 14-day expiration of workflow artifacts. Keep the release/tag if you want that exact APK available later. Its notes identify the source commit and CI run, and `app-debug.apk.sha256` verifies the download. No locally built APK is substituted for a CI release.

## What the workflow verifies

The workflow fetches the exact run commit, checks the official Gradle wrapper SHA-256, verifies the Gradle distribution checksum, selects the runner's JDK 17, installs Android SDK 36/build-tools 35.0.0, then runs unit tests, lint and `assembleDebug`. It generates a checksum and publishes the APK only after those steps pass. GitHub's automatic short-lived token is scoped to the job; no personal access token, provider key or release-signing secret is required.

It uses the runner's Git, Java, SDK manager and GitHub CLI directly instead of external setup/upload actions. A separate [CI Runner Diagnostics](https://github.com/levinhocall/Jarvis-os/actions/workflows/ci-diagnostics.yml) workflow checks runner availability without checkout, actions, Gradle or Android dependencies.

## Test-build signing and phone data

These are debug-signed test builds, not production releases. A fresh runner may use a different debug signing key. Re-downloading the **same release APK** preserves its signing certificate; a newly built APK may not update an installation signed with a different key. Back up important local memories before replacing an incompatible installation. Do not publish a signing private key as a release asset. Stable production signing requires a separately configured protected signing key.

## Failure handling

Open the failed step in the run and read its log. A startup failure with no jobs is different from a Gradle compilation failure. Run **CI Runner Diagnostics** to distinguish runner availability from build/workflow setup. The 2026-11-03 investigation demonstrated a successful minimal runner check, then a fully successful Android pipeline after removing action-resolution dependencies. Merely updating the setup/upload action versions had not fixed startup. The individual blocked action/service-policy reason was not exposed by the available run API; billing was not established as the cause.

## Verified CI release

[Android CI #292](https://github.com/levinhocall/Jarvis-os/actions/runs/41585971587) completed successfully: unit tests, lint, APK assembly, checksum and publication all passed. Its [permanent debug release](https://github.com/levinhocall/Jarvis-os/releases/tag/debug-ci-292) contains the real GitHub-runner APK, checksum and version metadata. The published APK was independently downloaded and passed both SHA-256 and APK signature verification. See [CI-APK-EVIDENCE.json](CI-APK-EVIDENCE.json). Physical-device testing remains separate.
