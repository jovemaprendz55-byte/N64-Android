# Generate the GAS/NASM offset headers from the target-compiled helper object.
#
# Provenance: mirrors the extraction contract in
# mupen64plus/mupen64plus-core/tools/gen_asm_script.sh at
# a9ca8ec0b7decd17390c76c6341cf423d5d79321.
# The object must be compiled for the target ABI with the same structure-
# affecting defines/include paths as the Core, and without LTO.
#
# Invocation:
# cmake -DINPUT_OBJECT=/path/to/asm_defines.o
#       -DOUTPUT_DIR=/path/to/generated -P GenerateMupen64PlusAsmDefines.cmake

if(NOT DEFINED INPUT_OBJECT OR NOT EXISTS "${INPUT_OBJECT}")
    message(FATAL_ERROR "INPUT_OBJECT must name an existing asm_defines object")
endif()
if(NOT DEFINED OUTPUT_DIR)
    message(FATAL_ERROR "OUTPUT_DIR must be set")
endif()

# The records are embedded in struct bytes, not ordinary text lines.
# Match the pinned upstream script's binary-safe grep -a extraction.
execute_process(
    COMMAND grep -a -o -E
        "@ASM_DEFINE[[:space:]]+offsetof_struct_[A-Za-z0-9_]+[[:space:]]+0x[0-9a-fA-F]+"
        "${INPUT_OBJECT}"
    RESULT_VARIABLE _grep_result
    OUTPUT_VARIABLE _asm_records
    ERROR_VARIABLE _grep_error
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
if(NOT _grep_result EQUAL 0 AND NOT _grep_result EQUAL 1)
    message(FATAL_ERROR "Could not scan binary object ${INPUT_OBJECT}: ${_grep_error}")
endif()

set(_definitions)
string(REPLACE "\n" ";" _asm_lines "${_asm_records}")
foreach(_line IN LISTS _asm_lines)
    string(REGEX MATCH
        "@ASM_DEFINE[ \t]+(offsetof_struct_[A-Za-z0-9_]+)[ \t]+(0x[0-9a-fA-F]+)"
        _match "${_line}")
    if(_match)
        list(APPEND _definitions "${CMAKE_MATCH_1}|${CMAKE_MATCH_2}")
    endif()
endforeach()

if(NOT _definitions)
    message(FATAL_ERROR "No @ASM_DEFINE records found in ${INPUT_OBJECT}")
endif()

list(REMOVE_DUPLICATES _definitions)
list(SORT _definitions)
file(MAKE_DIRECTORY "${OUTPUT_DIR}")

set(_gas "#pragma once\n")
set(_nasm "; Generated from target-compiled asm_defines object\n")
foreach(_definition IN LISTS _definitions)
    string(REPLACE "|" ";" _parts "${_definition}")
    list(GET _parts 0 _name)
    list(GET _parts 1 _value)
    string(APPEND _gas "#define ${_name} (${_value})\n")
    string(APPEND _nasm "%define ${_name} (${_value})\n")
endforeach()

file(WRITE "${OUTPUT_DIR}/asm_defines_gas.h" "${_gas}")
file(WRITE "${OUTPUT_DIR}/asm_defines_nasm.h" "${_nasm}")
message(STATUS "Generated ARM assembly offsets: ${OUTPUT_DIR}")
