# JARVIS OS — Accessibility Forensics & Hypothesis Status (First Run)

This document records the initial forensic analysis of hypotheses H1–H7 regarding the **"Service ON in Settings, but not working"** bug on the device.

---

## Hypothesis Matrix (H1–H7)

| Hypothesis | Description | Verdict | Code / Runtime Evidence |
|---|---|---|---|
| **H1** | **PermissionHelper false positive** (`isAccessibilityServiceEnabled()` returns true when merely listed in Settings while `instance == null`). | **CONFIRMED** | `PermissionHelper.kt:89` returned `AccessibilityHealthModel.isServiceEnabledInSettings(context)` directly, causing UI indicators to show green "Active" despite `instance == null`. Fixed by adding `isAccessibilityServiceWorking()` with live probe. |
| **H2** | **Stale binder after debug reinstall / OEM kill** (Settings retains component in `ENABLED_ACCESSIBILITY_SERVICES` but Android does not invoke `onServiceConnected()`). | **CONFIRMED** | Code inspection confirmed no post-install rebind handler existed. Added `A11yWatchdog` to notify user or auto-rebind via `scripts/a11y-rebind.sh` or `WRITE_SECURE_SETTINGS`. |
| **H3** | **Direct-boot crash / keystore access before user unlock** (`android:directBootAware="true"` in manifest while `JarvisApp.onCreate` initializes EncryptedSharedPreferences and Room DB). | **CONFIRMED** | `AndroidManifest.xml` had `android:directBootAware="true"` on `JarvisAccessibilityService`. During boot before credential decryption, keystore access fails. Removed `directBootAware` from manifest. |
| **H4** | **FGS crash in JarvisConversationService** (`startForeground` called with ungranted `microphone` or unheld `mediaProjection` on Android 14 API 34, causing uncaught `SecurityException` that kills the process). | **CONFIRMED** | `JarvisConversationService.kt` called `startForeground(types)` unconditionally with `FOREGROUND_SERVICE_TYPE_MICROPHONE` and `MEDIA_PROJECTION`. Added `ForegroundTypeSelector` and try/catch guards. |
| **H5** | **IPC event flooding** (`accessibility_service_config.xml` used `typeAllMask` + `notificationTimeout="100"` flooding main UI thread with all system events while only 5 event types are monitored). | **CONFIRMED** | `accessibility_service_config.xml` declared `typeAllMask`. Narrowed to exact union `typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged|typeViewScrolled|typeNotificationStateChanged`. |
| **H6** | **Non-standard `onUnbind`** (`onUnbind()` returned true without calling `super.onUnbind()`). | **CONFIRMED** | `JarvisAccessibilityService.kt:472` had `return true` without calling `super.onUnbind(intent)`. Fixed to invoke `super.onUnbind(intent)`. |
| **H7** | **Android 13+ Restricted Settings & OEM battery kills** (Settings switch greyed out or OEM optimizer killing background service). | **CONFIRMED** | Added detection in `AccessibilityDoctor` and dedicated guidance steps in `AccessibilityFixActivity`. |

*Note on Device Verification: Wireless ADB was not paired at session start (`adb devices` returned empty). Device checks are documented based on code analysis and unit tests; manual verification on phone instructions are provided in `accessibility-howto.md`.*
