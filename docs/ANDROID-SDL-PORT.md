# Android porting notes: SDL and Core lifecycle

## Source baseline

All upstream observations below refer to the Mupen64Plus Core source pinned for this project:

- Repository: https://github.com/mupen64plus/mupen64plus-core
- Commit: `a9ca8ec0b7decd17390c76c6341cf423d5d79321`
- License declared by the Core: GPL-2.0; preserve upstream copyright and license notices when source is imported or modified.

This document records source inspection only. It does not claim that the Core has been imported or built for Android.

## SDL calls found in the inspected files

The following inventory is deliberately scoped to the files listed; it is not yet a complete repository-wide SDL dependency audit.

| Upstream file | SDL symbol(s) | Observed role |
|---|---|---|
| `src/main/main.c` | `SDL_PumpEvents` | Poll/pump platform events in the Core input check |
| `src/main/main.c` | `SDL_Delay` | Timing and frame pacing delays |
| `src/main/main.c` | `SDL_GetTicks` | Millisecond tick timing for frame pacing |
| `src/api/frontend.c` | `SDL_WasInit` | Detect whether the frontend initialized SDL before Core startup |
| `src/api/frontend.c` | `SDL_Quit` | Shut SDL down only when it was not already owned by the frontend |
| `src/plugin/plugin.c` | `SDL_KeyDown`, `SDL_KeyUp` | Required input-plugin ABI entry points; these are plugin symbol names, not SDL2 event functions |
| `src/api/m64p_vidext.h` | `SDL_InitSubSystem`, `SDL_QuitSubSystem` | Documented video-extension lifecycle calls for the SDL video subsystem |

The Core's public video-extension override can replace its video-extension function table, but it does not by itself remove the SDL references above. In particular, event pumping and timing need an explicit Android strategy.

## Core lifecycle constraints confirmed in `src/api/frontend.c`

1. `CoreStartup` checks API-version compatibility and initializes Core subsystems. It also initializes plugin connection state to disconnected.
2. `CoreAttachPlugin` is only valid after startup and after a ROM or disk is open; it connects the plugin and then calls the plugin start routine.
3. `CoreDoCommand(M64CMD_ROM_OPEN)` rejects an already-running/open state and validates the ROM buffer (minimum 4096 bytes, maximum cartridge size) before opening it.
4. `CoreDoCommand(M64CMD_ROM_CLOSE)` is rejected while emulation is running or when no ROM is open.
5. `CoreDoCommand(M64CMD_EXECUTE)` requires an open ROM/disk and runs the emulation loop. It must not be invoked on Android's UI thread.
6. `CoreShutdown` releases Core subsystems and only calls `SDL_Quit` if the frontend had not already initialized SDL.

The Android bridge must therefore own a clear state machine and serialize Core calls. It must not attach plugins before ROM-open under this pinned API implementation.

## Plugin loading implications

The inspected `src/plugin/plugin.c` resolves required plugin entry points dynamically, including `PluginGetVersion`, `RomOpen`, `RomClosed`, and the plugin-specific functions. The input plugin additionally exposes the legacy ABI names `SDL_KeyDown` and `SDL_KeyUp`. A native Android input implementation must honor those exact exported names and signatures if it is used through this compatibility ABI.

The Unix dynamic-library backend uses `dlopen`/ `dlsym`. Android's Bionic provides the dynamic-loader API, but the NDK link, library search paths, and loading of app-private plugin libraries still require an actual ARM64 build/runtime test.

## Porting decisions

- Keep the upstream API headers and enum values authoritative; do not duplicate numeric command values in Kotlin.
- Keep all Core calls on a dedicated native/emulation worker, not the UI thread.
- Treat SDL event pumping, clock/timing, and video subsystem ownership as separate porting concerns.
- Do not stub a function with a success return merely to make compilation pass. Any Android adaptation must preserve behavior or fail explicitly.
- Do not add a plugin binary or source until its exact upstream revision, license, and exported ABI have been audited.
- Do not mark ROM execution as supported until a real ROM-open/execute/stop test has succeeded.

## Next implementation sequence

1. Finish a repository-wide SDL symbol/call-site inventory, including headers and all Core source files.
2. Choose and pin an Android SDL2 dependency or implement a narrowly scoped Android platform adapter where the upstream architecture permits it. Audit the dependency license and version first.
3. Import the pinned Core source with its GPL-2.0 notices and build files preserved; add the minimum NDK/CMake integration and required system libraries.
4. Produce `libmupen64plus.so` for `arm64-v8a` in CI before attempting plugin integration.
5. Add ABI-verified plugins and only then implement the ROM lifecycle in JNI.

## Current verified project status

- Android skeleton/diagnostic JNI library: builds in GitHub Actions.
- Latest verified run at the time of this note: run `42429199197`, commit `32dd0896e81f92ea69a62d1b616d59de47f9afb5`, conclusion `success`.
- Debug APK artifact: `n64-android-debug`.
- Mupen64Plus Core source: analyzed at the pinned commit, not yet vendored, linked, or executed.
- GoldenEye 007: not yet tested; no claim of game compatibility.
