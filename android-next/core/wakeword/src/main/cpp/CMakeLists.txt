# CMakeLists.txt — Vosk stderr polyfill for Lollipop (API 21–22) Bionic.
# Ported from android/app/src/main/cpp/ (A-07). See vosk_stderr_shim.c for the rationale.
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
