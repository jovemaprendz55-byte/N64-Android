cmake_minimum_required(VERSION 3.22.1)
project(n64bridge LANGUAGES C CXX)

add_library(n64bridge SHARED native-lib.cpp)

target_compile_features(n64bridge PRIVATE cxx_std_17)
target_compile_options(n64bridge PRIVATE -Wall -Wextra -Werror)

# The Android workflow checks out the pinned Core beside the app project.
# Local builds without that checkout keep the JNI bridge buildable.
set(MUPEN64PLUS_CORE_SOURCE_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/mupen64plus-core")

if(EXISTS "${MUPEN64PLUS_CORE_SOURCE_DIR}/src/asm_defines/asm_defines.c")
    include("${CMAKE_CURRENT_LIST_DIR}/../../../../cmake/Mupen64PlusAsmDefines.cmake")
    m64p_add_arm64_asm_defines(
        "${MUPEN64PLUS_CORE_SOURCE_DIR}"
        n64bridge)
else()
    message(STATUS
        "Pinned Mupen64Plus Core checkout not found; building JNI bridge only")
endif()
