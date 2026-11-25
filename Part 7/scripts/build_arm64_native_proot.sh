#!/usr/bin/env bash
set -euo pipefail

# Builds native 16 KB page-aligned ARM64 Android shared libraries in Termux/Ubuntu PRoot
# where Google's x86_64 NDK clang binary cannot execute directly on an ARM64 host.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PART7_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CPP_DIR="$PART7_DIR/app/src/main/cpp"
OUT_DIR="$PART7_DIR/app/prebuiltJniLibs/arm64-v8a"
BUILD_TMP="${1:-/tmp/jarvis_arm64_android_build}"

SYSROOT="/usr/lib/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
NDK_CLANG_LIB="/usr/lib/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/19/lib/linux/aarch64"
TERMUX_CLANG="/data/data/com.termux/files/usr/bin/clang-21"
TERMUX_CLANGXX="/data/data/com.termux/files/usr/bin/clang++-21"
TERMUX_AR="/data/data/com.termux/files/usr/bin/llvm-ar"
TAR_ARCHIVE="$CPP_DIR/third_party/webrtc-aec3-2cec2f52.tar.gz"
WEBRTC_SRC_BASE="$BUILD_TMP/webrtc_src"

if [[ ! -x "$TERMUX_CLANGXX" ]]; then
  echo "ERROR: Expected Termux ARM64 clang++-21 at $TERMUX_CLANGXX" >&2
  exit 1
fi
if [[ ! -d "$SYSROOT" ]]; then
  echo "ERROR: Expected Android NDK r28c sysroot at $SYSROOT" >&2
  exit 1
fi

mkdir -p "$OUT_DIR" "$BUILD_TMP/objs" "$WEBRTC_SRC_BASE"

WEBRTC_ROOT="$WEBRTC_SRC_BASE/webrtc-aec3-2cec2f52e26646f93bd2d5498bbabf59cba18da9/src"
if [[ ! -d "$WEBRTC_ROOT" ]]; then
  tar -xzf "$TAR_ARCHIVE" -C "$WEBRTC_SRC_BASE"
fi

TARGET_FLAGS=(
  --target=aarch64-linux-android26
  "--sysroot=$SYSROOT"
  -fPIC
  -O2
  -DANDROID
  -DWEBRTC_LINUX
  -DWEBRTC_POSIX
  -DWEBRTC_ANDROID
  -DWEBRTC_HAS_NEON
)

INC_FLAGS=(
  "-I$CPP_DIR"
  "-I$WEBRTC_ROOT"
  "-I$WEBRTC_ROOT/api"
  "-I$WEBRTC_ROOT/base"
  "-I$WEBRTC_ROOT/base/rtc_base"
  "-I$WEBRTC_ROOT/base/system_wrappers/include"
  "-I$WEBRTC_ROOT/audio_processing"
  "-I$WEBRTC_ROOT/absl"
  "-I$WEBRTC_ROOT/base/jsoncpp/include"
  "-I$WEBRTC_ROOT/audio_processing/aec3"
  "-I$WEBRTC_ROOT/audio_processing/resampler"
  "-I$WEBRTC_ROOT/audio_processing/logging"
  "-I$WEBRTC_ROOT/audio_processing/include"
  "-I$WEBRTC_ROOT/audio_processing/utility"
  "-I$WEBRTC_ROOT/rtc_base/experiments"
  "-I$WEBRTC_ROOT/common_audio"
  "-I$WEBRTC_ROOT/common_audio/third_party/ooura/fft_size_128"
  "-I/usr/lib/android-sdk/ndk/28.2.13676358/sources/android/cpufeatures"
)

echo "Compiling WebRTC AEC3 ARM64 static archive..."
OBJS=()
while IFS= read -r -d '' src; do
  rel="${src#$WEBRTC_ROOT/}"
  obj="$BUILD_TMP/objs/${rel//\//_}.o"
  OBJS+=("$obj")
  if [[ ! -f "$obj" || "$src" -nt "$obj" ]]; then
    if [[ "$src" == *.c ]]; then
      "$TERMUX_CLANG" "${TARGET_FLAGS[@]}" "${INC_FLAGS[@]}" -c "$src" -o "$obj"
    else
      "$TERMUX_CLANGXX" -std=c++20 -Wno-deprecated-declarations -Wno-deprecated-volatile "${TARGET_FLAGS[@]}" "${INC_FLAGS[@]}" -c "$src" -o "$obj"
    fi
  fi
done < <(find "$WEBRTC_ROOT" -type f \( -name '*.cc' -o -name '*.c' -o -name '*.cpp' \) ! -name '*_avx2.cc' ! -name '*_sse2.cc' ! -name '*_sse.cc' -print0)

# Include cpu-features.c from NDK when present
CPU_FEAT="/usr/lib/android-sdk/ndk/28.2.13676358/sources/android/cpufeatures/cpu-features.c"
if [[ -f "$CPU_FEAT" ]]; then
  obj="$BUILD_TMP/objs/ndk_cpu_features.o"
  OBJS+=("$obj")
  if [[ ! -f "$obj" || "$CPU_FEAT" -nt "$obj" ]]; then
    "$TERMUX_CLANG" "${TARGET_FLAGS[@]}" "-I/usr/lib/android-sdk/ndk/28.2.13676358/sources/android/cpufeatures" -c "$CPU_FEAT" -o "$obj"
  fi
fi

"$TERMUX_AR" rcs "$BUILD_TMP/libjarvis_webrtc_android.a" "${OBJS[@]}"

echo "Linking 16 KB aligned arm64-v8a/libjarvis_aec3.so (static C++ runtime)..."
"$TERMUX_CLANGXX" -std=c++20 -nostdlib++ "${TARGET_FLAGS[@]}" "${INC_FLAGS[@]}" \
  "$CPP_DIR/aec_core.cpp" "$CPP_DIR/aec_jni.cpp" \
  "$BUILD_TMP/libjarvis_webrtc_android.a" \
  -shared -fuse-ld=lld \
  "-L$SYSROOT/usr/lib/aarch64-linux-android/26" \
  "-L$SYSROOT/usr/lib/aarch64-linux-android" \
  "-L$NDK_CLANG_LIB" \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  -Wl,--exclude-libs,ALL \
  -Wl,--gc-sections -Wl,-soname,libjarvis_aec3.so \
  -lc++_static -lc++abi -lunwind -llog -lm -ldl \
  -o "$OUT_DIR/libjarvis_aec3.so"

readelf -h -l "$OUT_DIR/libjarvis_aec3.so" | grep -E "Machine|LOAD" -A1 || true
echo "Built: $OUT_DIR/libjarvis_aec3.so ($(stat -c%s "$OUT_DIR/libjarvis_aec3.so") bytes)"
