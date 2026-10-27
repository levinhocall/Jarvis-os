# JARVIS OS — Accessibility Diagnostics & Recovery How-To

This guide provides Termux ADB commands and instructions for diagnosing and fixing the **"Service ON in Settings, but not working"** condition on Android devices.

---

## 1. Fast Recovery via ADB (Dev Loop)

Whenever you reinstall a debug APK, Android's `system_server` may fail to automatically re-bind the new process's `AccessibilityService` binder. Run our helper script:

```bash
cd "Part 7"
./scripts/a11y-rebind.sh
```

Or execute directly via ADB:

```bash
C="com.jarvis.assistant/com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService"
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$cur" = "null" ] && cur=""
others=$(echo "$cur" | tr ':' '\n' | grep -v "^$C$" | grep -v '^$' | paste -sd: -)
adb shell settings put secure enabled_accessibility_services "$others"; sleep 2
adb shell settings put secure enabled_accessibility_services "${others:+$others:}$C"
adb shell settings put secure accessibility_enabled 1
```

---

## 2. Wireless ADB Pairing (No PC Needed)

On Android 11+ phones connected to Wi-Fi:
1. Open phone **Settings → Developer Options → Wireless Debugging** (turn ON).
2. Tap **"Pair device with pairing code"**. Note the IP, pairing port, and 6-digit code.
3. In Termux, run:
   ```bash
   pkg install android-tools
   adb pair localhost:<PAIR_PORT>
   # (Enter the 6-digit code when prompted)
   ```
4. Look at the main Wireless Debugging screen for the **connection port** (often different from pair port):
   ```bash
   adb connect localhost:<CONNECT_PORT>
   adb devices
   ```

---

## 3. Useful ADB Diagnostic Commands

### A. Check Recent Crashes
```bash
adb logcat -b crash -d
```

### B. Filter Accessibility & System Errors
```bash
adb logcat -d | grep -i -E "JarvisAccessibility|A11yHealthModel|A11yBreadcrumbs|AndroidRuntime"
```

### C. System Dumpsys Accessibility Status
```bash
adb shell dumpsys accessibility | grep -A 10 -B 2 "com.jarvis.assistant"
```

### D. Historical Process Exit Info (Android 11+)
```bash
adb shell dumpsys activity exit-info com.jarvis.assistant
```

### E. Check Android 13+ Restricted Settings
```bash
adb shell cmd appops get com.jarvis.assistant ACCESS_RESTRICTED_SETTINGS
```

### F. Grant Optional Auto-Repair Permission
```bash
adb shell pm grant com.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS
```

---

## 4. How to Read the Accessibility Doctor Report

In the app, open **Settings → Voice Diagnostics & Telemetry HUD** (or tap **"Copy Diagnostic Report"** in the Guided Fix screen):

| Diagnostic Field | Expected Value | What Failure Means |
|---|---|---|
| `Build Stamp` | Current Git SHA + timestamp | Verifies which exact build is running on device |
| `Settings Component List` | `LISTED` | If `NOT_LISTED`, service is OFF in Settings |
| `Service Instance` | `PRESENT` | If `NULL`, service process is not bound by `system_server` |
| `Live Probe Result` | `SUCCESS` | If `NULL_ROOT` or `EXCEPTION`, IPC is broken |
| `TRUTHFUL VERDICT` | `WORKING` | If `ENABLED_NOT_CONNECTED`, toggle OFF then ON |
| `Historical Exit Reasons` | `REASON_EXIT_SELF` or empty | If `REASON_CRASH` or `REASON_LOW_MEMORY`, check breadcrumbs below |
| `Recent Breadcrumbs` | Shows lifecycle sequence | Inspect top 5 frames if an error was logged |
