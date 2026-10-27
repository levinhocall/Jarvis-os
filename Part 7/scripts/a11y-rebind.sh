#!/bin/bash
# JARVIS OS — Post-Install Accessibility Rebind Script
# Resolves Android stale AccessibilityService binder after debug APK reinstalls.

set -e

C="com.jarvis.assistant/com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService"

echo "[*] Checking ADB connection..."
if ! adb get-state >/dev/null 2>&1; then
    echo "[!] No ADB device connected. Please pair or connect via wireless ADB first."
    exit 1
fi

echo "[*] Reading current enabled accessibility services..."
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$cur" = "null" ] && cur=""

others=$(echo "$cur" | tr ':' '\n' | grep -v "^$C$" | grep -v '^$' | paste -sd: -)

echo "[*] Unbinding JarvisAccessibilityService..."
adb shell settings put secure enabled_accessibility_services "$others"
sleep 2

echo "[*] Rebinding JarvisAccessibilityService..."
adb shell settings put secure enabled_accessibility_services "${others:+$others:}$C"
adb shell settings put secure accessibility_enabled 1

echo "[✓] Accessibility service rebind triggered successfully."
