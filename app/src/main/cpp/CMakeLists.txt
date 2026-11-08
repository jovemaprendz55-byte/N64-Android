cmake_minimum_required(VERSION 3.22.1)
project(n64bridge LANGUAGES C CXX)

add_library(n64bridge SHARED native-lib.cpp)

target_compile_features(n64bridge PRIVATE cxx_std_17)
target_compile_options(n64bridge PRIVATE -Wall -Wextra -Werror)

# The CI builds the pinned SDL2 shared library before Gradle configures this
# project. Keep local bridge-only builds available when SDL2 is not present.
get_filename_component(N64_PROJECT_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(N64_SDL2_LIBRARY "${N64_PROJECT_ROOT}/build/sdl2-arm64/libSDL2.so")
set(N64_SDL2_INCLUDE "${N64_PROJECT_ROOT}/third_party/SDL/include")

if(EXISTS "${N64_SDL2_LIBRARY}" AND EXISTS "${N64_SDL2_INCLUDE}/SDL.h")
    add_library(SDL2 SHARED IMPORTED GLOBAL)
    set_target_properties(SDL2 PROPERTIES
        IMPORTED_LOCATION "${N64_SDL2_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${N64_SDL2_INCLUDE}")
    target_link_libraries(n64bridge PRIVATE SDL2)
    target_compile_definitions(n64bridge PRIVATE N64_HAS_SDL2=1)
    message(STATUS "Pinned SDL2 shared library linked into n64bridge")
else()
    message(STATUS "Pinned SDL2 build not found; building JNI bridge only")
endif()

# The Android workflow checks out the pinned Core beside the app project.
# Local builds without that checkout keep the JNI bridge buildable.
set(MUPEN64PLUS_CORE_SOURCE_DIR
    "${N64_PROJECT_ROOT}/third_party/mupen64plus-core")

if(EXISTS "${MUPEN64PLUS_CORE_SOURCE_DIR}/src/asm_defines/asm_defines.c")
    include("${N64_PROJECT_ROOT}/cmake/Mupen64PlusAsmDefines.cmake")
    m64p_add_arm64_asm_defines(
        "${MUPEN64PLUS_CORE_SOURCE_DIR}"
        n64bridge)
else()
    message(STATUS
        "Pinned Mupen64Plus Core checkout not found; building JNI bridge only")
endif()
