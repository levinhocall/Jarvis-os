# WebRTC AEC3 source provenance

Public extraction: https://github.com/Enaium/webrtc-aec3
Commit: 2cec2f52e26646f93bd2d5498bbabf59cba18da9
Original unmodified source archive: https://codeload.github.com/Enaium/webrtc-aec3/tar.gz/2cec2f52e26646f93bd2d5498bbabf59cba18da9
Archive SHA-256: 3bc3423e71b7280cf96d87b2e429444ed3fee364b94648ce9875ee95b8f485f8
License: BSD 3-Clause, WebRTC project authors. License also bundled in APK assets/licenses.

The archive is source code, not a precompiled/MAYA library. CMake verifies its digest,
extracts it locally and applies the JARVIS platform build configuration. Upstream's
host-only -m64 flag is not used for Android. Network access is not needed for this
source dependency during builds. JNI and PCM/session adapters are original JARVIS code.
