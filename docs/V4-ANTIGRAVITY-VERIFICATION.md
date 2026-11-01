# JARVIS OS V4 — Antigravity Build & Device Verification Handoff

## Authority and scope

- Source-code author: ChatGPT only.
- Antigravity role: build, test, package, install, execute device checks, and report evidence.
- Worktree: `Part 7`; branch: `main`; package: `com.jarvis.assistant`; device target: Android 16 / API 36.
- Do not redesign, refactor, or edit application source. Return failures with exact diagnostics so ChatGPT can author patches.
- Do not run GitHub Actions. Do not commit or push Antigravity-side changes.

## 1. Fetch and establish exact baseline

```bash
git fetch origin main
git switch main
git pull --ff-only origin main
git status --short
git rev-parse HEAD
git rev-parse origin/main
```

The two SHAs must match. Record both in the report. Do not continue if the worktree contains uncommitted changes or the SHAs differ.

## 2. Capture the original Gradle startup failure

The previously observed GitHub Actions run ended in `startup_failure` with zero jobs created and no job logs. That evidence does **not** identify a Gradle or Kotlin root cause. Do not infer one.

Run locally from the repository root:

```bash
cd "Part 7"
java -version
./gradlew --version
./gradlew help --stacktrace --info --warning-mode all
```

On Windows use `gradlew.bat` instead of `./gradlew`.

If Gradle startup fails, return the full command, exit code, stdout/stderr, complete stack trace and first `Caused by:`, Java vendor/version, OS/architecture, available RAM/free disk, whether the Gradle distribution downloaded, and installed Android SDK platforms/build-tools. Redact local paths if they contain personal information. Do not independently edit build/source files.

## 3. Toolchain and SDK checks

This branch is configured for:
- Android Gradle Plugin 8.9.1.
- Gradle wrapper 8.11.1.
- Kotlin Gradle Plugin 2.2.20.
- Java 17 bytecode target.
- compileSdk 36 / targetSdk 36.
- CameraX 1.6.2.

Confirm SDK installation:

```bash
sdkmanager --list
```

Install missing Android SDK Platform 36, compatible Build-Tools, Platform-Tools and Command-line Tools through Android Studio SDK Manager or the approved local SDK manager. Do not change versions during verification without returning the exact resolution error first.

## 4. Build and test commands

Run one command at a time and preserve each exit code:

```bash
./gradlew clean
./gradlew :app:assembleDebug --stacktrace --warning-mode all
./gradlew :app:testDebugUnitTest --stacktrace --warning-mode all
./gradlew :app:lintDebug --stacktrace --warning-mode all
./gradlew :app:connectedDebugAndroidTest --stacktrace --warning-mode all
./gradlew :app:compileReleaseKotlin --stacktrace --warning-mode all
./gradlew :app:lintRelease --stacktrace --warning-mode all
```

Release packaging requires configured signing material. Do not invent or share credentials. If the owner has configured these locally, run:

```bash
./gradlew :app:verifyReleaseSecurityInputs
./gradlew :app:assembleRelease --stacktrace --warning-mode all
```

Required local release variables: `JARVIS_RELEASE_STORE_FILE`, `JARVIS_RELEASE_STORE_PASSWORD`, `JARVIS_RELEASE_KEY_ALIAS`, `JARVIS_RELEASE_KEY_PASSWORD`, `JARVIS_RELEASE_CERT_SHA256`, `JARVIS_DEVELOPER_NAME`, and `JARVIS_COPYRIGHT_OWNER`. Never paste passwords, keystore bytes, API keys, or signing secrets into the report.

Expected debug APK: `Part 7/app/build/outputs/apk/debug/app-debug.apk`.
Expected release APK after signing verification: `Part 7/app/build/outputs/apk/release/app-release.apk`.

Record actual file existence, size, SHA-256, variant, and signing certificate fingerprint. A generated file is not a successful build unless its Gradle task exited 0.

## 5. Android 16 device verification

```bash
adb devices -l
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.jarvis.assistant
adb shell monkey -p com.jarvis.assistant 1
```

Capture logs while reproducing each failure:

```bash
adb logcat -c
adb logcat -v threadtime
```

### Required runtime cases

1. **Voice / foreground service:** grant microphone and notification permissions; start voice while visible; verify the microphone foreground-service notification and audio; background, lock/unlock, interrupt playback, return to standby and stop. Deny microphone permission and confirm capture fails safely without false success. Force-stop/relaunch and check for phantom active sessions.
2. **Accessibility:** enable the service manually in Android Settings. For sideloaded installs, follow Android's restricted-settings user confirmation; never bypass it. Verify connect, screen events, node lookup, permitted action, interrupt, disable/re-enable and process restart.
3. **Vision / CameraX:** open Neural Lens and grant camera permission. Confirm embedded live preview (not an external camera app), capture a photo, verify still preview and camera release. Deny camera permission and verify gallery still works. Test large gallery images, Gemini analysis with a configured provider credential (never include it in logs), OCR, activity rotation and background/return.
4. **Navigation:** test precise and approximate location, denied permission, disabled GPS/network provider and no recent fix. Search a known destination; verify marker and route. Trigger rerouting, clear route during an in-flight search/route, and confirm stale callbacks cannot restore it. Record network/provider errors.
5. **Database security:** run `DatabaseKeyStoreInstrumentedTest` and `SqlCipherRuntimeInstrumentedTest`. Verify existing user data survives upgrade. Verify migrations v1→v2→v3→v4→v5 with isolated test databases, wrong-key rejection, AES-GCM tamper rejection and corruption snapshot preservation in app-private no-backup storage. Never delete/reset the production database to make a test pass.
6. **Wallpaper / avatar / UI:** select a user-owned video, preview, invoke Android live-wallpaper confirmation and confirm the system actually applies it. Lock/unlock and switch wallpaper visibility; verify playback stops while hidden and restarts when visible. Enable avatar with OS overlay permission, revoke permission and verify truthful disabled state. Test avatar mode, working-only mode, edge placement, drag position, process death and service restart. Change every Aura preset, custom color and haptics; kill/relaunch and confirm persistence. Recreate applicable activities and verify selected video URI and chat/history survive.
7. **Regression:** run the full unit suite. Exercise main chat, model/provider selection, mission execution and voice. Check cold/warm start, back navigation, permission denial and process recreation.

## 6. Evidence collection

For every command capture exact command and working directory, start/end time, exit code, full output or log path, Gradle task summary, failed test class/method, stack trace, relevant logcat, device model and Android API. Redact serial/IMEI. For APKs record path, size and SHA-256.

Do not report PASS from source inspection alone. Use the schema in `docs/v4-verification-report.schema.json`. Allowed statuses:
- `IMPLEMENTED`: source implementation reviewed.
- `BUILD_VERIFIED`: actual successful Gradle compilation/build.
- `TEST_VERIFIED`: actual successful test execution.
- `DEVICE_VERIFIED`: actual Android 16 device evidence.
- `BLOCKED`: external dependency prevented the check.
- `FAILED`: an executed check failed.

## 7. Return handoff

Return the completed JSON report, full build/test logs, logcat extracts and APK metadata. Do not patch source code. ChatGPT will inspect the evidence, author required fixes, commit them to `main`, and issue a new verification handoff.
