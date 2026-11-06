cmake_minimum_required(VERSION 3.22.1)
project(n64android_engine LANGUAGES CXX)

add_library(n64android_engine SHARED
    n64_engine_jni.cpp
)

target_compile_features(n64android_engine PRIVATE cxx_std_17)
target_compile_options(n64android_engine PRIVATE
    -Wall
    -Wextra
    -Werror
)
