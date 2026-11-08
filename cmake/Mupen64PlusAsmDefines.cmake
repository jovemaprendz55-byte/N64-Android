# Build the pinned Core's ABI-matched offset helper and generate assembly headers.
#
# The helper must use the same structure-affecting defines and include paths as
# the ARM64 NEW_DYNAREC Core build. LTO is disabled exactly as upstream requires.
#
# Provenance: mupen64plus/mupen64plus-core
# Revision: a9ca8ec0b7decd17390c76c6341cf423d5d79321
# Upstream reference: projects/unix/Makefile and tools/gen_asm_script.sh

function(m64p_add_arm64_asm_defines core_source_dir consumer_target)
    if(NOT TARGET ${consumer_target})
        message(FATAL_ERROR "Unknown consumer target: ${consumer_target}")
    endif()

    set(_asm_defines_source "${core_source_dir}/src/asm_defines/asm_defines.c")
    foreach(_required
            "${_asm_defines_source}"
            "${core_source_dir}/src/device/device.h"
            "${core_source_dir}/src/device/r4300/new_dynarec/new_dynarec.h"
            "${core_source_dir}/src/device/r4300/r4300_core.h")
        if(NOT EXISTS "${_required}")
            message(FATAL_ERROR "Pinned Core file required for ARM64 offsets is missing: ${_required}")
        endif()
    endforeach()

    set(_object_target m64p_arm64_asm_defines)
    set(_generated_dir "${CMAKE_CURRENT_BINARY_DIR}/mupen64plus-generated")
    set(_gas_header "${_generated_dir}/asm_defines_gas.h")
    set(_nasm_header "${_generated_dir}/asm_defines_nasm.h")

    add_library(${_object_target} OBJECT "${_asm_defines_source}")
    target_include_directories(${_object_target} PRIVATE
        "${core_source_dir}/src"
        "${core_source_dir}/src/asm_defines"
        "${core_source_dir}/subprojects/xxhash"
        "${core_source_dir}/subprojects/md5"
        "${core_source_dir}/subprojects/minizip")
    target_compile_definitions(${_object_target} PRIVATE
        DYNAREC
        NEW_DYNAREC=4
        M64P_PARALLEL)
    target_compile_options(${_object_target} PRIVATE -fno-lto)

    add_custom_command(
        OUTPUT "${_gas_header}" "${_nasm_header}"
        COMMAND ${CMAKE_COMMAND}
            "-DINPUT_OBJECT=$<TARGET_OBJECTS:${_object_target}>"
            "-DOUTPUT_DIR=${_generated_dir}"
            -P "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/GenerateMupen64PlusAsmDefines.cmake"
        DEPENDS ${_object_target}
            "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/GenerateMupen64PlusAsmDefines.cmake"
        COMMENT "Generating ARM64 Mupen64Plus assembly offsets"
        VERBATIM)

    add_custom_target(m64p_generate_arm64_asm_defines
        DEPENDS "${_gas_header}" "${_nasm_header}")
    add_dependencies(${consumer_target} m64p_generate_arm64_asm_defines)
    target_include_directories(${consumer_target} PRIVATE "${_generated_dir}")
endfunction()
