# JARVIS OS V4 test APK

The verified [CI #292 APK release](https://github.com/levinhocall/Jarvis-os/releases/tag/debug-ci-292) is now available. For repeatable GitHub-hosted builds and permanent CI download links, follow [Build and download APKs without ChatGPT](../docs/CI-APK-DOWNLOAD.md). The ZIP below is the earlier local fallback, not a CI release.

[Download the debug APK ZIP](https://github.com/levinhocall/Jarvis-os/raw/refs/heads/main/downloads/JARVIS-OS-V4-debug.zip) (87.2 MB).

Extract the ZIP and open `JARVIS-OS-V4-debug.apk` on your Android phone. Android may ask you to allow installation from the app opening the file. This is a debug-signed test build, version 1.8.7 (143), package `com.jarvis.assistant`, minimum Android API 26, target Android 16 / API 36. It includes the application's universal native libraries and local voice assets.

If Android reports a signing conflict with an installed copy, do not uninstall a copy containing important memories before making a local memory backup. This build uses a development signing certificate and cannot update an app signed by a different key.

Configure provider API keys in the app; no API key is bundled. Memory remains local in Room/SQLCipher and remote memory synchronization remains disabled. Independent model reviews remain off by default.

The owner explicitly authorized APK/CI verification after the earlier no-build directive. [CI run #290](https://github.com/levinhocall/Jarvis-os/actions/runs/41584363088) failed at startup with zero jobs and artifacts. This download was instead built successfully in the local execution environment from source commit `7ce7e5bd9d34b3a1e031534f4cab5469820acd10`; it is **not a successful CI artifact**. APK signature verification, ZIP integrity and embedded APK checksum checks passed. No Android device was attached, so phone acceptance remains unverified.

See [build-evidence.json](build-evidence.json) for exact sizes and SHA-256 values. The ZIP contains a separate APK SHA-256 file; the adjacent `.zip.sha256` verifies the download archive.
