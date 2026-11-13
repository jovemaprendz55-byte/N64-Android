cmake_minimum_required(VERSION 3.22.1)
project(n64vulkan LANGUAGES C CXX ASM)

# Vulkan-only Android surface bridge. No EGL or OpenGL libraries are linked
# into this branch's APK.
get_filename_component(N64_PROJECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
find_program(GLSLANG_VALIDATOR glslangValidator REQUIRED)
find_package(Python3 COMPONENTS Interpreter REQUIRED)
set(SHADER_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated-shaders")
set(VERTEX_SPV "${SHADER_OUTPUT_DIR}/triangle.vert.spv")
set(FRAGMENT_SPV "${SHADER_OUTPUT_DIR}/triangle.frag.spv")
set(SHADER_HEADER "${SHADER_OUTPUT_DIR}/triangle_shaders.h")
add_custom_command(OUTPUT "${SHADER_HEADER}"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${SHADER_OUTPUT_DIR}"
    COMMAND ${GLSLANG_VALIDATOR} -V "${CMAKE_CURRENT_SOURCE_DIR}/shaders/triangle.vert" -o "${VERTEX_SPV}"
    COMMAND ${GLSLANG_VALIDATOR} -V "${CMAKE_CURRENT_SOURCE_DIR}/shaders/triangle.frag" -o "${FRAGMENT_SPV}"
    COMMAND ${Python3_EXECUTABLE} "${N64_PROJECT_ROOT}/tools/embed_spirv.py" "${VERTEX_SPV}" "${FRAGMENT_SPV}" "${SHADER_HEADER}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/triangle.vert" "${CMAKE_CURRENT_SOURCE_DIR}/shaders/triangle.frag" "${N64_PROJECT_ROOT}/tools/embed_spirv.py"
    VERBATIM)
add_custom_target(n64vulkan_shaders DEPENDS "${SHADER_HEADER}")
add_library(n64vulkan SHARED native-vulkan.cpp "${SHADER_HEADER}")
add_dependencies(n64vulkan n64vulkan_shaders)
target_include_directories(n64vulkan PRIVATE "${SHADER_OUTPUT_DIR}")
target_compile_features(n64vulkan PRIVATE cxx_std_17)
target_compile_options(n64vulkan PRIVATE -Wall -Wextra -Werror)
find_library(ANDROID_LIB android REQUIRED)
find_library(LOG_LIB log REQUIRED)
find_library(VULKAN_LIB vulkan REQUIRED)
target_link_libraries(n64vulkan PRIVATE ${ANDROID_LIB} ${LOG_LIB} ${VULKAN_LIB})

set(N64_SDL2_LIBRARY "${N64_PROJECT_ROOT}/build/sdl2-arm64/libSDL2.so")
set(N64_SDL2_INCLUDE "${N64_PROJECT_ROOT}/third_party/SDL/include")

if(EXISTS "${N64_SDL2_LIBRARY}" AND EXISTS "${N64_SDL2_INCLUDE}/SDL.h")
    add_library(SDL2 SHARED IMPORTED GLOBAL)
    set_target_properties(SDL2 PROPERTIES
        IMPORTED_LOCATION "${N64_SDL2_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${N64_SDL2_INCLUDE}")
else()
    message(STATUS "Pinned SDL2 build not found; building Vulkan bridge only")
endif()

set(MUPEN64PLUS_CORE_SOURCE_DIR
    "${N64_PROJECT_ROOT}/third_party/mupen64plus-core")

if(EXISTS "${MUPEN64PLUS_CORE_SOURCE_DIR}/src/asm_defines/asm_defines.c")
    include("${N64_PROJECT_ROOT}/cmake/Mupen64PlusCoreSources.cmake")
    include("${N64_PROJECT_ROOT}/cmake/Mupen64PlusAsmDefines.cmake")

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

    # Vulkan GFX plugin: built into libn64vulkan so its frame callback resolves
    # directly to the Vulkan uploader without EGL/GLES or a cyclic shared-library link.
    target_sources(n64vulkan PRIVATE android_vulkan_gfx_plugin.cpp)
    target_include_directories(n64vulkan PRIVATE "${MUPEN64PLUS_CORE_SOURCE_DIR}/src/api")

    # Input plugin is independent of the graphics API.
    add_library(n64android_input SHARED android_input_plugin.cpp)
    target_include_directories(n64android_input PRIVATE
        "${MUPEN64PLUS_CORE_SOURCE_DIR}/src/api")
    target_compile_features(n64android_input PRIVATE cxx_std_17)
    target_compile_options(n64android_input PRIVATE -Wall -Wextra -Werror)
    target_link_libraries(n64android_input PRIVATE ${LOG_LIB})
    set_target_properties(n64android_input PROPERTIES
        OUTPUT_NAME "mupen64plus-input-android")

    add_library(mupen64plus SHARED
        ${M64P_CORE_UPSTREAM_SOURCES}
        ${M64P_CORE_BUNDLED_SOURCES}
        android_screenshot_stub.c)

    target_include_directories(mupen64plus PRIVATE
        ${M64P_CORE_INCLUDE_DIRS}
        "${N64_SDL2_INCLUDE}"
        "${CMAKE_CURRENT_BINARY_DIR}/mupen64plus-generated")
    target_compile_definitions(mupen64plus PRIVATE
        DYNAREC NEW_DYNAREC=4 M64P_PARALLEL NOCRYPT NOUNCRYPT MINIZIP_FOPEN_NO_64)
    target_compile_options(mupen64plus PRIVATE
        -O2 -fvisibility=hidden -fPIC -Wall)
    target_link_libraries(mupen64plus PRIVATE
        SDL2 ${ANDROID_LIB} ${LOG_LIB} ${CMAKE_DL_LIBS} m z)
    m64p_add_arm64_asm_defines("${MUPEN64PLUS_CORE_SOURCE_DIR}" mupen64plus)

    # Mupen64Plus HLE RSP plugin: interprets common RSP microcode tasks.
    set(MUPEN64PLUS_RSP_HLE_SOURCE_DIR
        "${N64_PROJECT_ROOT}/third_party/mupen64plus-rsp-hle")
    if(NOT EXISTS "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/plugin.c")
        message(FATAL_ERROR "Pinned Mupen64Plus HLE RSP source tree is missing")
    endif()
    add_library(mupen64plus_rsp_hle SHARED
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/alist.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/alist_audio.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/alist_naudio.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/alist_nead.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/audio.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/cicx105.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/hle.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/hvqm.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/jpeg.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/memory.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/mp3.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/musyx.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/re2.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/plugin.c"
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src/osal_dynamiclib_unix.c")
    target_include_directories(mupen64plus_rsp_hle PRIVATE
        "${MUPEN64PLUS_RSP_HLE_SOURCE_DIR}/src"
        "${MUPEN64PLUS_CORE_SOURCE_DIR}/src/api")
    target_compile_features(mupen64plus_rsp_hle PRIVATE c_std_99)
    target_compile_options(mupen64plus_rsp_hle PRIVATE -O2 -fvisibility=hidden -fPIC -Wall)
    target_link_libraries(mupen64plus_rsp_hle PRIVATE ${CMAKE_DL_LIBS} ${LOG_LIB})
    set_target_properties(mupen64plus_rsp_hle PROPERTIES OUTPUT_NAME "mupen64plus-rsp-hle")

    # Package Core and plugins as runtime dependencies of the Vulkan library.
    add_dependencies(n64vulkan mupen64plus n64android_input mupen64plus_rsp_hle)
    target_link_libraries(n64vulkan PRIVATE mupen64plus n64android_input mupen64plus_rsp_hle)
else()
    message(STATUS "Pinned Mupen64Plus Core checkout not found; building Vulkan bridge only")
endif()
