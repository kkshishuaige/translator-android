cmake_minimum_required(VERSION 3.10)
project("whisper_translator")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 指向 whisper.cpp 源码根目录
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# 定义 Whisper 版本
set(WHISPER_VERSION "1.9.1")

# 构建 whisper 库（只编译 whisper.cpp + JNI，不手动编译 ggml）
add_library(
    whisper
    SHARED
    whisper.cpp
    whisper-jni.cpp
)

# 让 GGML 用自己的 CMake 编译（官方推荐方式）
# 注：ggml/ 是项目子目录，直接用 add_subdirectory 而不是 FetchContent
set(GGML_CPU_EXTRA_DISABLE ON)
set(GGML_USE_CPU ON)
set(GGML_BUILD_EXAMPLES OFF)
set(GGML_BUILD_TESTS OFF)
set(GGML_BUILD_CUDA OFF)
set(GGML_BUILD_METAL OFF)
set(GGML_BUILD_VULKAN OFF)
set(GGML_BUILD_OPENCL OFF)
set(GGML_BUILD_HEXAGON OFF)
set(GGML_BUILD_VIRTGPU OFF)
set(GGML_BUILD_OPENVINO OFF)
# 禁用所有 CPU 优化（NEON/SIMD），解决部分设备上的指令崩溃
add_definitions(-DGGML_FORCE_SCALAR_DOT_F16=1)
add_subdirectory(ggml)

# 链接
target_link_libraries(whisper
    log
    android
    ggml
)

# 编译选项
target_include_directories(whisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/src
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu
)

target_compile_definitions(whisper PRIVATE
    GGML_USE_CPU=1
    GGML_CPU_EXTRA_DISABLE=1
    GGML_NO_CPU_OPTIMIZATIONS=1
    WHISPER_VERSION="${WHISPER_VERSION}"
)

if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3)
    target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(whisper PRIVATE -Wl,--gc-sections)
    target_link_options(whisper PRIVATE -Wl,--exclude-libs,ALL)
endif()
