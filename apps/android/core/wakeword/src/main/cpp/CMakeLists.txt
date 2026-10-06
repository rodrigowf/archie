# CMakeLists.txt — Vosk stderr polyfill for Lollipop (API 21–22) Bionic.
# Ported from the old app (legacy/android/app/src/main/cpp/, A-07). See vosk_stderr_shim.c for the rationale.
# Built for every ABI; only dlopen'd below API 23 (AndroidVoskModelSource.maybeLoadStderrShim).

cmake_minimum_required(VERSION 3.18.1)

project(vosk-stderr-shim C)

add_library(vosk-stderr-shim SHARED vosk_stderr_shim.c)

# dl for dlopen; log is cheap and kept from the original build.
find_library(log-lib log)
target_link_libraries(vosk-stderr-shim ${log-lib} dl)

set_target_properties(vosk-stderr-shim PROPERTIES
    LINK_FLAGS "-Wl,--no-undefined"
)

# 16 KB page-size devices (Android 15+): align the 64-bit builds' LOAD segments to 16 KB. The 32-bit
# (A300M) build keeps the default so its binary stays the one field-tested on API 21.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    target_link_options(vosk-stderr-shim PRIVATE "-Wl,-z,max-page-size=16384")
endif()
