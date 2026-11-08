cmake_minimum_required(VERSION 3.22.1)
project(n64bridge LANGUAGES CXX)

add_library(n64bridge SHARED native-lib.cpp)

target_compile_features(n64bridge PRIVATE cxx_std_17)
target_compile_options(n64bridge PRIVATE -Wall -Wextra -Werror)
