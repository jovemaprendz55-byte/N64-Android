cmake_minimum_required(VERSION 3.22.1)
project(n64bridge LANGUAGES C CXX)

# Stage 2b diagnostic: keep SDL2 packaged, but do not make it a DT_NEEDED
# dependency of n64bridge. The app will load SDL2 explicitly from JNI.
add_library(n64bridge SHARED native-lib.cpp)

target_compile_features(n64bridge PRIVATE cxx_std_17)
target_compile_options(n64bridge PRIVATE -Wall -Wextra -Werror)

target_link_libraries(n64bridge PRIVATE log dl)

message(STATUS "N64 diagnostic mode: SDL2 loaded dynamically; Mupen64Plus Core excluded")
