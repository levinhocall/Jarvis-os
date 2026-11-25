cmake_minimum_required(VERSION 3.22)
project(jarvis_aec3 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(FetchContent)
# Original WebRTC extraction, pinned archive + digest. No MAYA binaries are used.
FetchContent_Declare(webrtc_aec3
    URL ${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc-aec3-2cec2f52.tar.gz
    URL_HASH SHA256=3bc3423e71b7280cf96d87b2e429444ed3fee364b94648ce9875ee95b8f485f8
    SOURCE_SUBDIR jarvis_no_upstream_build)
FetchContent_MakeAvailable(webrtc_aec3)
set(WEBRTC_SRC ${webrtc_aec3_SOURCE_DIR}/src)
file(GLOB_RECURSE WEBRTC_FILES CONFIGURE_DEPENDS ${WEBRTC_SRC}/*.cc ${WEBRTC_SRC}/*.c ${WEBRTC_SRC}/*.cpp)
set(JARVIS_IS_ARM OFF)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64|armv7|arm"
   OR CMAKE_ANDROID_ARCH_ABI MATCHES "arm64-v8a|armeabi-v7a"
   OR ANDROID_ABI MATCHES "arm64-v8a|armeabi-v7a")
    set(JARVIS_IS_ARM ON)
endif()
if(JARVIS_IS_ARM)
    list(FILTER WEBRTC_FILES EXCLUDE REGEX ".*_(avx2|sse2|sse)\\.cc$")
else()
    list(FILTER WEBRTC_FILES EXCLUDE REGEX ".*_neon\\.cc$")
    foreach(SOURCE ${WEBRTC_FILES})
        if(SOURCE MATCHES "_avx2\\.cc$")
            set_source_files_properties(${SOURCE} PROPERTIES COMPILE_OPTIONS "-mavx2;-mfma")
        endif()
    endforeach()
endif()
add_library(jarvis_webrtc STATIC ${WEBRTC_FILES})
target_compile_definitions(jarvis_webrtc PUBLIC WEBRTC_LINUX WEBRTC_POSIX)
if(ANDROID)
    target_compile_definitions(jarvis_webrtc PUBLIC WEBRTC_ANDROID)
    target_sources(jarvis_webrtc PRIVATE ${ANDROID_NDK}/sources/android/cpufeatures/cpu-features.c)
    target_include_directories(jarvis_webrtc PRIVATE ${ANDROID_NDK}/sources/android/cpufeatures)
endif()
if(JARVIS_IS_ARM)
    target_compile_definitions(jarvis_webrtc PUBLIC WEBRTC_HAS_NEON)
endif()
target_include_directories(jarvis_webrtc PUBLIC ${WEBRTC_SRC} ${WEBRTC_SRC}/api
    ${WEBRTC_SRC}/base ${WEBRTC_SRC}/base/rtc_base ${WEBRTC_SRC}/base/system_wrappers/include
    ${WEBRTC_SRC}/audio_processing ${WEBRTC_SRC}/absl ${WEBRTC_SRC}/base/jsoncpp/include
    ${WEBRTC_SRC}/audio_processing/aec3 ${WEBRTC_SRC}/audio_processing/resampler
    ${WEBRTC_SRC}/audio_processing/logging ${WEBRTC_SRC}/audio_processing/include
    ${WEBRTC_SRC}/audio_processing/utility ${WEBRTC_SRC}/rtc_base/experiments
    ${WEBRTC_SRC}/common_audio ${WEBRTC_SRC}/common_audio/third_party/ooura/fft_size_128)
target_compile_options(jarvis_webrtc PRIVATE -Wno-deprecated-declarations)
if(ANDROID)
    add_library(jarvis_aec3 SHARED aec_core.cpp aec_jni.cpp)
    target_link_libraries(jarvis_aec3 PRIVATE jarvis_webrtc log)
    target_link_options(jarvis_aec3 PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
else()
    add_executable(jarvis_aec_test aec_core.cpp aec_test.cpp)
    target_link_libraries(jarvis_aec_test PRIVATE jarvis_webrtc)
    enable_testing()
    add_test(NAME synthetic_echo_and_near_end COMMAND jarvis_aec_test)
endif()
