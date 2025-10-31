cmake_minimum_required(VERSION 3.10)
project(whisper_jni LANGUAGES C CXX)

# ============================================================
# ✅ whisper.cpp — Android JNI Build Script (Ultimate Stable Edition)
# ------------------------------------------------------------
# • Works in both local + CI (GitHub Actions)
# • Auto-detects whisper.cpp source even if nested or missing
# • Auto-clones whisper.cpp if not found
# • Supports arm64-v8a / armeabi-v7a builds
# • Optimized for NDK 28 (Clang 19)
# ============================================================

# ------------------------------------------------------------
# Compiler setup
# ------------------------------------------------------------
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ------------------------------------------------------------
# Path detection (CI-safe)
# ------------------------------------------------------------
set(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../..")
set(CANDIDATE_PATHS
    "${ROOT_DIR}/whisper.cpp"
    "${ROOT_DIR}/nativelib/../../whisper.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../whisper.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper_core"
)

set(WHISPER_LIB_DIR "")
foreach(path IN LISTS CANDIDATE_PATHS)
    if (EXISTS "${path}/src/whisper.cpp")
        set(WHISPER_LIB_DIR "${path}")
        break()
    endif()
endforeach()

# ------------------------------------------------------------
# Auto-clone fallback (for CI)
# ------------------------------------------------------------
if (NOT WHISPER_LIB_DIR)
    message(WARNING "⚠️ whisper.cpp not found, attempting to clone...")
    execute_process(
        COMMAND git clone --depth=1 https://github.com/ggerganov/whisper.cpp.git
        WORKING_DIRECTORY ${ROOT_DIR}
    )
    if (EXISTS "${ROOT_DIR}/whisper.cpp/src/whisper.cpp")
        set(WHISPER_LIB_DIR "${ROOT_DIR}/whisper.cpp")
        message(STATUS "✅ whisper.cpp restored via auto-clone.")
    else()
        message(FATAL_ERROR "❌ whisper.cpp source not found.
Checked:
  ${CANDIDATE_PATHS}
Please ensure submodules or network clone access are configured.")
    endif()
endif()

message(STATUS "📂 Using whisper.cpp directory: ${WHISPER_LIB_DIR}")

# ------------------------------------------------------------
# Optional external GGML path
# ------------------------------------------------------------
option(GGML_HOME "Path to external GGML source (optional)." OFF)

# ------------------------------------------------------------
# Source list
# ------------------------------------------------------------
set(SOURCE_FILES
    "${WHISPER_LIB_DIR}/src/whisper.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/WhisperLib.c"
)

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

# ------------------------------------------------------------
# Verify all sources exist
# ------------------------------------------------------------
set(MISSING_FILES "")
foreach(src IN LISTS SOURCE_FILES)
    if (NOT EXISTS "${src}")
        list(APPEND MISSING_FILES "${src}")
    endif()
endforeach()

if (MISSING_FILES)
    message(FATAL_ERROR "❌ Missing source files:\n${MISSING_FILES}")
endif()

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
# Function to build per-ABI targets
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

    # Release optimization
    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE
            -O3 -fvisibility=hidden -fvisibility-inlines-hidden
            -ffunction-sections -fdata-sections
        )
        target_link_options(${target_name} PRIVATE
            -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto
        )
    else()
        target_compile_options(${target_name} PRIVATE -O0 -g)
    endif()

    # Link libs
    target_link_libraries(${target_name} PRIVATE ${LOG_LIB} ${ANDROID_LIB} ggml_interface)

    # External GGML
    if (GGML_HOME)
        target_include_directories(${target_name} PRIVATE
            "${GGML_HOME}" "${GGML_HOME}/include" "${GGML_HOME}/src"
        )
    endif()
endfunction()

# ------------------------------------------------------------
# Build targets per ABI
# ------------------------------------------------------------
if (DEFINED ANDROID_ABI)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        build_library("whisper_v8fp16_va")
    elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
        build_library("whisper_vfpv4")
    else()
        message(WARNING "⚠️ Unknown ABI: ${ANDROID_ABI}, using generic target.")
        build_library("whisper_generic")
    endif()
else()
    # Fallback for local builds
    build_library("whisper_generic")
endif()

# ------------------------------------------------------------
# Summary
# ------------------------------------------------------------
message(STATUS "✅ whisper.cpp JNI configuration complete.")
message(STATUS "🧱 Build type      : ${CMAKE_BUILD_TYPE}")
message(STATUS "🧩 ABI target      : ${ANDROID_ABI}")
message(STATUS "📦 whisper.cpp dir : ${WHISPER_LIB_DIR}")
message(STATUS "📁 Source count    : ${SOURCE_FILES}")
