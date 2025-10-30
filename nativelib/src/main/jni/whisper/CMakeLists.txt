cmake_minimum_required(VERSION 3.10)

project(whisper.cpp)

# C++17を使用
set(CMAKE_CXX_STANDARD 17)

# whisper_core のパス（JNIからの相対パス）
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../whisper_core/)

# 外部GGMLの使用をオプション化（指定がなければ内部GGMLを使う）
option(GGML_HOME "whisper: Path to external GGML source" OFF)

# whisperのソースコードとJNIブリッジを設定
set(SOURCE_FILES
        ${WHISPER_LIB_DIR}/src/whisper.cpp
        ${CMAKE_SOURCE_DIR}/jni.c
)

# 内部GGML使用時のソースを追加
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

# Androidログ用ライブラリを探す
find_library(LOG_LIB log)

# INTERFACEライブラリでGGMLのインクルードパスをまとめる
add_library(ggml_interface INTERFACE)
target_include_directories(ggml_interface INTERFACE
        ${WHISPER_LIB_DIR}
        ${WHISPER_LIB_DIR}/src
        ${WHISPER_LIB_DIR}/include
        ${WHISPER_LIB_DIR}/ggml/include
        ${WHISPER_LIB_DIR}/ggml/src
        ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

# whisper ライブラリをビルドする関数
function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})

    # CPUバックエンドを使用する定義
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)

    # アーキテクチャごとの最適化オプション
    set(GGML_COMPILE_OPTIONS "")
    if (${target_name} STREQUAL "whisper_v8fp16_va")
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        set(GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
    endif()

    if (GGML_COMPILE_OPTIONS)
        target_compile_options(${target_name} PRIVATE ${GGML_COMPILE_OPTIONS})
    endif()

    # Releaseビルド時の最適化設定
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

    # 外部GGMLを使用する場合はFetchContentで取得
    if (GGML_HOME)
        include(FetchContent)
        FetchContent_Declare(ggml SOURCE_DIR ${GGML_HOME})
        FetchContent_MakeAvailable(ggml)

        target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
        target_link_libraries(${target_name} PRIVATE ggml ${LOG_LIB} android ggml_interface)
    else()
        target_link_libraries(${target_name} PRIVATE ${LOG_LIB} android ggml_interface)
    endif()
endfunction()

# AndroidのABIごとに異なるターゲットをビルド
if (DEFINED ANDROID_ABI AND ${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (DEFINED ANDROID_ABI AND ${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif()

# 汎用（非最適化）ターゲットもビルド
build_library("whisper")
