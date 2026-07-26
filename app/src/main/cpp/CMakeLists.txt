cmake_minimum_required(VERSION 3.10)
project("whisper_translator")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# 优化选项：Android 平台不需要 AVX 等 x86 指令
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.4a+dotprod+fp16 -O3 -DGGML_USE_LLAMAFILE=0")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.4a+dotprod+fp16 -O3 -DGGML_USE_LLAMAFILE=0")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon -mfloat-abi=softfp -O3")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -mfloat-abi=softfp -O3")
else()
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
endif()

# 禁用不必要的加速模块
add_definitions(-DGGML_USE_CPU=1)
add_definitions(-DGGML_CPU_EXTRA_DISABLE=1)

# 源文件
set(WHISPER_SOURCES
    whisper.cpp
    ggml/src/ggml.c
    ggml/src/ggml.cpp
    ggml/src/ggml-alloc.c
    ggml/src/ggml-backend.cpp
    ggml/src/ggml-backend-reg.cpp
    ggml/src/ggml-backend-meta.cpp
    ggml/src/ggml-quants.c
    ggml/src/ggml-threading.cpp
    ggml/src/ggml-cpu/ggml-cpu.cpp
    ggml/src/ggml-cpu/ops.cpp
    ggml/src/ggml-cpu/binary-ops.cpp
    ggml/src/ggml-cpu/unary-ops.cpp
    ggml/src/ggml-cpu/traits.cpp
    ggml/src/ggml-cpu/vec.cpp
    ggml/src/ggml-cpu/repack.cpp
    ggml/src/ggml-cpu/hbm.cpp
    whisper-jni.cpp
)

# 头文件路径（含内部头文件目录）
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/src
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu
)

# 构建共享库
add_library(whisper SHARED ${WHISPER_SOURCES})

# 版本宏定义（必须在 add_library 之后）
target_compile_definitions(whisper PRIVATE
    GGML_VERSION="0.16.0"
    GGML_COMMIT="unknown"
    WHISPER_VERSION="1.9.1"
)

# 链接系统库
target_link_libraries(whisper
    log
    android
    c
    m
    z
)
