# Merge / CI-only checkpoint

User authorized merging the accumulated local changes and running CI, explicitly deferring the final APK build. Local source verification already passed: 503 JVM tests, zero failures/errors, one existing skip; lint, debug assembly and Android-test compilation passed in the preceding storage task. No new local APK build is run for this merge request.

To honor the deferred APK instruction, push CI now performs unit tests, migration-preservation checks, lint and Android-test compilation. Debug APK/checksum/tag/release steps require workflow_dispatch with build_apk explicitly true; the default is false. Runtime emulator tests remain available manually because connectedDebugAndroidTest necessarily builds/installs app and test APKs. They are not being claimed executed in this CI-only checkpoint.

The local Living Eye, opt-in Compose Voice Core and storage/startup fixes are merged together. Existing ViewModels/navigation are preserved except the already-reviewed storage error handling. Parts 1–6 are untouched. The two root workflow edits implement the user's CI-only delivery instruction.

Remaining limitations in STORAGE_CRASH_AUDIT.md and the orb implementation reports still apply. This merge does not certify physical-device rendering, microphone latency, full startup ANR elimination, large-service decomposition or neural embeddings.
