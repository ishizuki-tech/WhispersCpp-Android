cmake_minimum_required(VERSION 3.10)
project(whisper.cpp LANGUAGES C CXX)

# ============================================================
# ✅ whisper.cpp — Android JNI Build Script (Reviewed Version)
# ------------------------------------------------------------
# • Builds JNI shared libraries for whisper.cpp
# • Supports multiple ABI-specific optimized variants
# • Auto-switches between internal GGML and external GGML source
# • Enables aggressive compiler and linker optimization flags
# ============================================================

# Use C++17 standard for compatibility with whisper.cpp / ggml
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Base path for whisper.cpp core sources (relative to JNI folder)
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../whisper_core/)

# Optional external GGML directory (if not set, internal GGML will be used)
option(GGML_HOME "whisper: Path to external GGML source" OFF)

# ------------------------------------------------------------
# Source file list
# ------------------------------------------------------------
# Includes:
#   - whisper core implementation (whisper.cpp)
#   - JNI bridge entry point (jni.c)
set(SOURCE_FILES
        ${WHISPER_LIB_DIR}/src/whisper.cpp
        ${CMAKE_SOURCE_DIR}/whisperLib.c
)

# ------------------------------------------------------------
# Internal GGML source (if external not specified)
# ------------------------------------------------------------
if (NOT GGML_HOME)
    list(APPEND SOURCE_FILES
            ${WHISPER_LIB_DIR}/ggml/src/ggml.c
            ${WHISPER_LIB_DIR}/ggml/src/ggml-alloc.c
            ${WHISPER_LIB_DIR}/ggml/src/ggml-backend.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-backend-reg.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-quants.c
            ${WHISPER_LIB_DIR}/ggml/src/ggml-threading.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/vec.cpp
            ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ops.cpp
    )
endif()

# ------------------------------------------------------------
# Locate Android logging library
# ------------------------------------------------------------
find_library(LOG_LIB log)

# ------------------------------------------------------------
# Create an INTERFACE library for common include directories
# ------------------------------------------------------------
add_library(ggml_interface INTERFACE)
target_include_directories(ggml_interface INTERFACE
        ${WHISPER_LIB_DIR}
        ${WHISPER_LIB_DIR}/src
        ${WHISPER_LIB_DIR}/include
        ${WHISPER_LIB_DIR}/ggml/include
        ${WHISPER_LIB_DIR}/ggml/src
        ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

# ------------------------------------------------------------
# Function: build_library(<target_name>)
# ------------------------------------------------------------
# Builds one shared library (.so) for whisper JNI.
# Includes:
#   • CPU-optimized compiler flags
#   • Optional LTO & dead-code elimination
#   • Automatic linkage to ggml / android / log libs
# ------------------------------------------------------------
function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})

    # Define CPU backend usage for GGML
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)

    # Architecture-specific compile options
    set(GGML_COMPILE_OPTIONS "")
    if (${target_name} STREQUAL "whisper_v8fp16_va")
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        set(GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
    endif()

    if (GGML_COMPILE_OPTIONS)
        target_compile_options(${target_name} PRIVATE ${GGML_COMPILE_OPTIONS})
    endif()

    # Release build optimization flags
    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE
                -O3
                -fvisibility=hidden
                -fvisibility-inlines-hidden
                -ffunction-sections
                -fdata-sections
        )
        target_link_options(${target_name} PRIVATE
                -Wl,--gc-sections
                -Wl,--exclude-libs,ALL
                -flto
        )
    endif()

    # External GGML integration (if GGML_HOME is defined)
    if (GGML_HOME)
        include(FetchContent)
        FetchContent_Declare(ggml SOURCE_DIR ${GGML_HOME})
        FetchContent_MakeAvailable(ggml)

        target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
        target_link_libraries(${target_name} PRIVATE ggml ${LOG_LIB} android ggml_interface)
    else()
        # Internal GGML build (default)
        target_link_libraries(${target_name} PRIVATE ${LOG_LIB} android ggml_interface)
    endif()
endfunction()

# ------------------------------------------------------------
# ABI-specific builds
# ------------------------------------------------------------
# Each ABI variant gets its own optimized .so:
#   • arm64-v8a → whisper_v8fp16_va.so
#   • armeabi-v7a → whisper_vfpv4.so
#   • default (fallback) → whisper.so
# ------------------------------------------------------------
if (DEFINED ANDROID_ABI AND ${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (DEFINED ANDROID_ABI AND ${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif()

# Always build a generic fallback binary
build_library("whisper")
