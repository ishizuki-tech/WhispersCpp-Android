cmake_minimum_required(VERSION 3.10)
project(whisper_jni LANGUAGES C CXX)

# ============================================================
# ✅ whisper.cpp — Android JNI Build Script (Final Working Edition)
# ------------------------------------------------------------
# • Builds JNI shared libraries for whisper.cpp + WhisperLib.c
# • Supports multiple ABI-optimized builds (arm64-v8a / armeabi-v7a)
# • Automatically switches between bundled and external GGML
# • Enables aggressive compiler and linker optimizations
# • Safe for Android Studio + NDK 28 (Clang 19)
# ============================================================

# ------------------------------------------------------------
# Compiler configuration
# ------------------------------------------------------------
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ------------------------------------------------------------
# Directory setup (use current JNI folder for reliable relative paths)
# ------------------------------------------------------------
# Base directory of whisper.cpp (relative to this JNI folder)
set(WHISPER_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper_core"
        CACHE PATH "Path to whisper.cpp root directory")

# Optional external GGML source override (OFF by default)
option(GGML_HOME "Path to external GGML source. Leave empty to use bundled GGML." OFF)

# ------------------------------------------------------------
# Source list
# ------------------------------------------------------------
set(SOURCE_FILES
        "${WHISPER_LIB_DIR}/src/whisper.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/whisperLib.c"
)

# ------------------------------------------------------------
# Internal GGML fallback (bundled source)
# ------------------------------------------------------------
if (NOT GGML_HOME)
    list(APPEND SOURCE_FILES
            "${WHISPER_LIB_DIR}/ggml/src/ggml.c"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-alloc.c"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-backend.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-backend-reg.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-quants.c"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-threading.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu.c"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/unary-ops.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/binary-ops.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/vec.cpp"
            "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ops.cpp"
    )
endif()

# Verify that all sources exist before proceeding
foreach(src IN LISTS SOURCE_FILES)
    if (NOT EXISTS "${src}")
        message(FATAL_ERROR "❌ Missing source file: ${src}")
    endif()
endforeach()

# ------------------------------------------------------------
# Include paths
# ------------------------------------------------------------
add_library(ggml_interface INTERFACE)
target_include_directories(ggml_interface INTERFACE
        "${WHISPER_LIB_DIR}"
        "${WHISPER_LIB_DIR}/src"
        "${WHISPER_LIB_DIR}/include"
        "${WHISPER_LIB_DIR}/ggml/include"
        "${WHISPER_LIB_DIR}/ggml/src"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu"
)

# ------------------------------------------------------------
# Android system libraries
# ------------------------------------------------------------
find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)

# ------------------------------------------------------------
# Function: build_library(<target_name>)
# ------------------------------------------------------------
# Builds one JNI .so library for whisper.cpp.
# Includes:
#   • CPU backend definition (GGML_USE_CPU)
#   • Architecture tuning flags
#   • Aggressive optimization (O3 + LTO + gc-sections)
#   • Android log + ggml linkage
# ------------------------------------------------------------
function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)

    # ABI-specific tuning
    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4 -mfloat-abi=softfp)
    endif()

    # Release optimizations
    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE
                -O3 -fvisibility=hidden -fvisibility-inlines-hidden
                -ffunction-sections -fdata-sections
        )
        target_link_options(${target_name} PRIVATE
                -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto
        )
    endif()

    # Link libraries
    target_link_libraries(${target_name} PRIVATE ${LOG_LIB} ${ANDROID_LIB} ggml_interface)

    # External GGML integration (optional)
    if (GGML_HOME)
        target_include_directories(${target_name} PRIVATE
                "${GGML_HOME}" "${GGML_HOME}/include" "${GGML_HOME}/src"
        )
        # Uncomment if linking external ggml build
        # target_link_libraries(${target_name} PRIVATE ggml)
    endif()
endfunction()

# ------------------------------------------------------------
# Build per ABI + fallback target
# ------------------------------------------------------------
if (DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif()

# Always build a generic fallback library
build_library("whisper")

message(STATUS "✅ whisper.cpp JNI build configuration completed successfully.")
