cmake_minimum_required(VERSION 3.22.1)
project(n64bridge LANGUAGES C CXX)

# Diagnostic build: keep the JNI bridge independent from SDL2 and the
# Mupen64Plus Core. Android loads DT_NEEDED dependencies before JNI_OnLoad,
# so this build isolates whether either dependency causes the native abort.
add_library(n64bridge SHARED native-lib.cpp)

target_compile_features(n64bridge PRIVATE cxx_std_17)
target_compile_options(n64bridge PRIVATE -Wall -Wextra -Werror)
target_link_libraries(n64bridge PRIVATE log)

message(STATUS "N64 diagnostic mode: JNI bridge only; SDL2/Core are not linked")
