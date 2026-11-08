# Source manifest for the pinned Mupen64Plus Core revision.
#
# Provenance: mupen64plus/mupen64plus-core
# Revision: a9ca8ec0b7decd17390c76c6341cf423d5d79321
# The list mirrors projects/unix/Makefile's required Core sources, with the
# ARM64 NEW_DYNAREC sources selected explicitly for Android arm64-v8a.
#
# This file intentionally does not create a target yet. Android still needs a
# validated SDL2/dependency configuration. files_unix.c is the POSIX OSAL
# backend; the Android frontend must set HOME to app-private storage first.

include_guard(GLOBAL)

if(NOT DEFINED MUPEN64PLUS_CORE_SOURCE_DIR)
    message(FATAL_ERROR "Set MUPEN64PLUS_CORE_SOURCE_DIR to the pinned Core source tree")
endif()

set(M64P_CORE_UPSTREAM_SOURCES
    src/api/callbacks.c
    src/api/common.c
    src/api/config.c
    src/api/debugger.c
    src/api/frontend.c
    src/api/vidext.c
    src/backends/api/video_capture_backend.c
    src/backends/plugins_compat/audio_plugin_compat.c
    src/backends/plugins_compat/input_plugin_compat.c
    src/backends/clock_ctime_plus_delta.c
    src/backends/dummy_video_capture.c
    src/backends/file_storage.c
    src/device/cart/cart.c
    src/device/cart/af_rtc.c
    src/device/cart/cart_rom.c
    src/device/cart/eeprom.c
    src/device/cart/flashram.c
    src/device/cart/is_viewer.c
    src/device/cart/sram.c
    src/device/controllers/game_controller.c
    src/device/controllers/vru_controller.c
    src/device/controllers/paks/biopak.c
    src/device/controllers/paks/mempak.c
    src/device/controllers/paks/rumblepak.c
    src/device/controllers/paks/transferpak.c
    src/device/dd/dd_controller.c
    src/device/dd/disk.c
    src/device/device.c
    src/device/gb/gb_cart.c
    src/device/gb/mbc3_rtc.c
    src/device/gb/m64282fp.c
    src/device/memory/memory.c
    src/device/pif/bootrom_hle.c
    src/device/pif/cic.c
    src/device/pif/n64_cic_nus_6105.c
    src/device/pif/pif.c
    src/device/r4300/cached_interp.c
    src/device/r4300/cp0.c
    src/device/r4300/cp1.c
    src/device/r4300/cp2.c
    src/device/r4300/idec.c
    src/device/r4300/interrupt.c
    src/device/r4300/pure_interp.c
    src/device/r4300/r4300_core.c
    src/device/r4300/tlb.c
    src/device/rcp/ai/ai_controller.c
    src/device/rcp/mi/mi_controller.c
    src/device/rcp/pi/pi_controller.c
    src/device/rcp/rdp/fb.c
    src/device/rcp/rdp/rdp_core.c
    src/device/rcp/ri/ri_controller.c
    src/device/rcp/rsp/rsp_core.c
    src/device/rcp/si/si_controller.c
    src/device/rcp/vi/vi_controller.c
    src/device/rdram/rdram.c
    src/main/main.c
    src/main/util.c
    src/main/cheat.c
    src/main/eventloop.c
    src/main/rom.c
    src/main/savestates.c
    src/main/sdl_key_converter.c
    src/main/workqueue.c
    src/plugin/plugin.c
    src/plugin/dummy_video.c
    src/plugin/dummy_audio.c
    src/plugin/dummy_input.c
    src/plugin/dummy_rsp.c
    src/osal/dynamiclib_unix.c
    src/osal/files_unix.c
    src/device/r4300/new_dynarec/new_dynarec.c
    src/device/r4300/new_dynarec/arm64/linkage_arm64.S
)

# Third-party files that the upstream Makefile compiles into the Core.
# Keep their original license and copyright files in the fetched source tree.
set(M64P_CORE_BUNDLED_SOURCES
    subprojects/md5/md5.c
    subprojects/minizip/ioapi.c
    subprojects/minizip/zip.c
    subprojects/minizip/unzip.c
)

set(M64P_CORE_INCLUDE_DIRS
    "${MUPEN64PLUS_CORE_SOURCE_DIR}/src"
    "${MUPEN64PLUS_CORE_SOURCE_DIR}/src/asm_defines"
    "${MUPEN64PLUS_CORE_SOURCE_DIR}/subprojects/md5"
    "${MUPEN64PLUS_CORE_SOURCE_DIR}/subprojects"
    "${MUPEN64PLUS_CORE_SOURCE_DIR}/subprojects/minizip"
    "${MUPEN64PLUS_CORE_SOURCE_DIR}/subprojects/xxhash"
)

# Resolve paths without globbing so missing or renamed upstream files fail
# visibly instead of silently changing the source set.
foreach(_source IN LISTS M64P_CORE_UPSTREAM_SOURCES M64P_CORE_BUNDLED_SOURCES)
    if(NOT EXISTS "${MUPEN64PLUS_CORE_SOURCE_DIR}/${_source}")
        message(FATAL_ERROR "Pinned Mupen64Plus source is missing: ${_source}")
    endif()
endforeach()

list(TRANSFORM M64P_CORE_UPSTREAM_SOURCES
    PREPEND "${MUPEN64PLUS_CORE_SOURCE_DIR}/")
list(TRANSFORM M64P_CORE_BUNDLED_SOURCES
    PREPEND "${MUPEN64PLUS_CORE_SOURCE_DIR}/")
