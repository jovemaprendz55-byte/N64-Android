cmake_minimum_required(VERSION 3.22.1)
project(n64bridge LANGUAGES C CXX ASM)

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
    include("${N64_PROJECT_ROOT}/cmake/Mupen64PlusCoreSources.cmake")
    include("${N64_PROJECT_ROOT}/cmake/Mupen64PlusAsmDefines.cmake")

    # Minizip's POSIX ftello/fseeko declarations are hidden by Android
    # Bionic's stdio headers. This target is ARM64-only, where long is 64-bit,
    # so use the equivalent ftell/fseek APIs in the pinned Minizip source.
    set(MINIZIP_IOAPI_C "${MUPEN64PLUS_CORE_SOURCE_DIR}/subprojects/minizip/ioapi.c")
    file(READ "${MINIZIP_IOAPI_C}" MINIZIP_IOAPI_CONTENT)
    string(REPLACE
        "#define FTELLO_FUNC(stream) ftello(stream)"
        "#define FTELLO_FUNC(stream) ftell(stream)"
        MINIZIP_IOAPI_CONTENT "${MINIZIP_IOAPI_CONTENT}")
    string(REPLACE
        "#define FSEEKO_FUNC(stream, offset, origin) fseeko(stream, offset, origin)"
        "#define FSEEKO_FUNC(stream, offset, origin) fseek(stream, (long)(offset), origin)"
        MINIZIP_IOAPI_CONTENT "${MINIZIP_IOAPI_CONTENT}")
    file(WRITE "${MINIZIP_IOAPI_C}" "${MINIZIP_IOAPI_CONTENT}")

    # Compile the pinned Core source manifest as an Android shared library.
    # Optional Unix-only features (OSD, netplay, debugger) remain disabled;
    # ARM64 uses the upstream NEW_DYNAREC=4 backend.
    add_library(mupen64plus SHARED
        ${M64P_CORE_UPSTREAM_SOURCES}
        ${M64P_CORE_BUNDLED_SOURCES}
        android_screenshot_stub.c)

    target_include_directories(mupen64plus PRIVATE
        ${M64P_CORE_INCLUDE_DIRS}
        "${N64_SDL2_INCLUDE}"
        "${CMAKE_CURRENT_BINARY_DIR}/mupen64plus-generated")

    target_compile_definitions(mupen64plus PRIVATE
        DYNAREC
        NEW_DYNAREC=4
        M64P_PARALLEL
        NOCRYPT
        NOUNCRYPT
        MINIZIP_FOPEN_NO_64)

    target_compile_options(mupen64plus PRIVATE
        -fvisibility=hidden
        -fPIC
        -Wall)

    target_link_libraries(mupen64plus PRIVATE
        SDL2
        android
        log
        ${CMAKE_DL_LIBS}
        m
        z)

    m64p_add_arm64_asm_defines(
        "${MUPEN64PLUS_CORE_SOURCE_DIR}"
        mupen64plus)

    # Keep the Core in the APK as a dependency of the JNI bridge.
    target_include_directories(n64bridge PRIVATE
        "${MUPEN64PLUS_CORE_SOURCE_DIR}/src/api")

    target_link_libraries(n64bridge PRIVATE mupen64plus)
    target_compile_definitions(n64bridge PRIVATE N64_HAS_MUPEN64PLUS=1)
    message(STATUS "Pinned Mupen64Plus Core ARM64 target enabled")
else()
    message(STATUS
        "Pinned Mupen64Plus Core checkout not found; building JNI bridge only")
endif()
