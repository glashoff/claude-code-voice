cmake_minimum_required(VERSION 3.22)
project(whisper_jni C CXX)

include(FetchContent)

# whisper.cpp, pinned to a release tarball + hash.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
# ggml's own thread pool instead of OpenMP: avoids shipping libomp.so.
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8.2 with dot-product and fp16 instructions: every phone CPU since roughly 2019.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif ()

FetchContent_Declare(
    whispercpp
    URL      https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
    URL_HASH SHA256=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
)
FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED whisper_jni.c)
target_link_libraries(whisper_jni PRIVATE whisper log)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections)
