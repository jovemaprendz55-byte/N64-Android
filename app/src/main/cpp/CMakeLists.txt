cmake_minimum_required(VERSION 3.22.1)
project(n64bridge LANGUAGES C CXX)

# Stage 2 diagnostic: link only the pinned SDL2 shared library.
# Mupen64Plus Core remains excluded until SDL2 is validated on-device.
add_library(n64bridge SHARED native-lib.cpp)

target_compile_features(n64bridge PRIVATE cxx_std_17)
target_compile_options(n64bridge PRIVATE -Wall -Wextra -Werror)

get_filename_component(N64_PROJECT_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(N64_SDL2_LIBRARY "${N64_PROJECT_ROOT}/build/sdl2-arm64/libSDL2.so")
set(N64_SDL2_INCLUDE "${N64_PROJECT_ROOT}/third_party/SDL/include")

if(NOT EXISTS "${N64_SDL2_LIBRARY}" OR NOT EXISTS "${N64_SDL2_INCLUDE}/SDL.h")
    message(FATAL_ERROR "Pinned SDL2 ARM64 build or headers are missing")
endif()

add_library(SDL2 SHARED IMPORTED GLOBAL)
set_target_properties(SDL2 PROPERTIES
    IMPORTED_LOCATION "${N64_SDL2_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${N64_SDL2_INCLUDE}")

target_link_libraries(n64bridge PRIVATE SDL2 log)
target_compile_definitions(n64bridge PRIVATE N64_HAS_SDL2=1)

message(STATUS "N64 diagnostic mode: SDL2 linked; Mupen64Plus Core excluded")
