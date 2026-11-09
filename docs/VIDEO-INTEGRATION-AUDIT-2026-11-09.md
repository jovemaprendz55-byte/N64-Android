# Video integration audit — 2026-11-09

## Confirmed package contents

The debug APK from workflow run 42562262626 was downloaded and inspected.
Its `lib/arm64-v8a` directory contains:

- `libmupen64plus-video-GLideN64.so` — the renderer loaded by MainActivity.
- `libmupen64plus-video-android.so` — a separate initial ABI/stub module.
- `libmupen64plus.so` — the Mupen64Plus Core.
- `libn64bridge.so` — JNI frontend bridge.
- `libSDL2.so` — SDL2 runtime.

MainActivity explicitly constructs the GLideN64 path
`libmupen64plus-video-GLideN64.so` and passes it to
`nativeLoadGfxPlugin()`. The small `android_gfx_plugin.cpp` module is a
separate ABI experiment and is not the renderer selected by that path.

## Video extension and presentation

The frontend registers 17 video-extension callbacks through
`CoreOverrideVidExt`. The `androidVideoSwapBuffers()` callback checks that
the current EGL context/surface are valid, then calls `eglSwapBuffers()`.
It logs the first presentation callback and every 120th callback, as well
as EGL failures.

The status text was corrected in commit `08ff495f094d`: presentation is
performed by the video-extension callback on the GL thread, not by an
unspecified automatic GLSurfaceView swap.

## Current validated state

- GL thread has a valid EGL/GLES context in the device test.
- User confirmed the app can enter and leave the graphics integration
  without unexpectedly closing.
- Previous device output reported successful GLideN64 ABI validation and
  PluginStartup.
- Previous device output reported ROM open and GFX attach successful.
- The Core execution loop remains deliberately blocked after an earlier
  native crash.
- No game display-list processing or first rendered game frame has been
  confirmed.

## Next work

Continue static auditing of the Core/GLideN64 attach and frame callbacks.
Do not enable `M64CMD_EXECUTE` merely because the APK builds or the EGL
lifecycle test passes. A separate controlled device test is required before
the execution gate can be reconsidered.


## GLES context version alignment (2026-11-09)

A source audit found that the app requested an OpenGL ES 2 context with
`GLSurfaceView.setEGLContextClientVersion(2)`, while the pinned GLideN64
Android adaptation requests an OpenGL ES 3.0 context. The target Poco C85
reports GLES 3.2, but relying on the driver's context selection to exceed
the requested client version is unnecessary and fragile.

The frontend now explicitly requests an ES 3 context. Before calling
`PluginStartup`, the JNI bridge also parses the current `GL_VERSION` and
refuses startup if the actual context is below GLES 3.0. This is a defensive
compatibility check; it does not enable ROM execution or claim that the
renderer has produced a frame.


## GLES 3 build verification (2026-11-09)

The combined frontend/native change was built successfully by GitHub Actions
run 42563783008 (commit `fce176803836fcfd821c057781605fa2b329dd2e`).
The uploaded debug APK artifact is `n64-android-core-isolated-diagnostic`
(artifact ID 13650293874).

The APK was downloaded and inspected: it contains the ARM64
`libmupen64plus-video-GLideN64.so`, Mupen64Plus Core, SDL2, and
`libn64bridge.so`. The JNI bridge binary contains the new explicit
GLES 3.0 startup guard message. This confirms packaging and compilation
of the guard, but does not replace on-device testing of PluginStartup.

## Poco C85 GLES 3.2 plugin startup test (2026-11-09)

The user tested the GLES 3 build on-device and reported:

- Android video extension registered in the Core.
- EGL context valid on the GL thread; device reports OpenGL ES 3.2.
- EGL/GLES video callbacks active on the GL thread.
- GLideN64 GFX plugin loaded and validated; the status confirms PluginStartup.
- No ROM was loaded and the plugin was not yet attached to the Core.
- InitiateGFX has not yet been called; this is expected before the ROM-open/attach step.

This confirms the GLES 3.2 context and GLideN64 PluginStartup stage on the Poco C85. It does not confirm CoreAttachPlugin, InitiateGFX, display-list processing, or a rendered game frame. The next device test is limited to selecting a legally obtained N64 ROM and using “Abrir ROM no Core e anexar vídeo”. Do not press “Iniciar emulação” while the M64CMD_EXECUTE safety gate remains active.

## On-device ROM open and GFX attach (2026-11-09)

The user tested the next integration step on the Poco C85 and reported:

- ROM opened successfully in the Mupen64Plus Core.
- Graphics plugin attached to the Core.
- CoreAttachPlugin executed on the GL thread with the current EGL context.
- Core emulation was not started.

This advances the device-verified state through ROM open and GFX attachment. It does not establish that M64CMD_EXECUTE ran, that display lists were processed, or that a game frame was rendered. Keep the M64CMD_EXECUTE safety gate closed. Next, audit the pinned Core/GLideN64 InitiateGFX and ROM lifecycle callbacks and prepare a separate guarded execution test; do not ask the user to press the emulation button yet.

## CoreAttachPlugin / InitiateGFX API audit (2026-11-09)

The upstream Mupen64Plus v2.0 Plugin API documents that the video plugin's
`InitiateGFX(GFX_INFO)` callback is called during `CoreAttachPlugin`. The
Front-End API also requires the Core and plugin to have completed startup and
a ROM to be open before `CoreAttachPlugin`; the video plugin is attached
first in the plugin ordering.

On the Poco C85, the user reported successful ROM open and successful
`CoreAttachPlugin(GFX)` on the GL thread with the current EGL context. Since
the native bridge only sets `g_gfxAttached` after `CoreAttachPlugin` returns
`M64ERR_SUCCESS`, this is strong API-level confirmation that the Core accepted
the `InitiateGFX` initialization path. The bridge status/log now states this
explicitly (commit `9eb06cedb5b109584ce15f4b19cc02520181a3ca`). There is not yet
a separate instrumented callback trace from inside GLideN64.

This still does not confirm `M64CMD_EXECUTE`, RSP task execution,
`ProcessDList`/`ProcessRDPList`, buffer swaps during gameplay, or a rendered
frame. Keep the execution safety gate closed pending the next isolated audit.

## RSP execution dependency audit (2026-11-09)

A source-manifest audit found that the current Android Core build compiles
upstream `src/plugin/dummy_rsp.c` as part of the Core. The pinned dummy RSP
implementation identifies itself as `Mupen64Plus-NoRSP`; its
`dummyrsp_DoRspCycles` only returns the requested cycle count and its
`dummyrsp_InitiateRSP` is empty. No separate HLE RSP plugin is currently
checked out, built, packaged, or attached by the Android workflow.

This is a concrete blocker for meaningful N64 game execution: successful
GFX initialization alone does not provide the RSP microcode processing needed
to generate display-list work for GLideN64. Do not unlock `M64CMD_EXECUTE`
for a gameplay test yet. The next engineering task is to integrate and validate
a compatible ARM64 Mupen64Plus HLE RSP plugin, then verify its attachment and
RSP task callbacks before a guarded execution test. Audio and input can remain
stubbed for the first video-only proof, but RSP cannot.


## HLE RSP ARM64 build integration (2026-11-09)

The official `mupen64plus-rsp-hle` source was selected as the first RSP
implementation candidate. Its source identifies the plugin API exports
`PluginGetVersion`, `PluginStartup`, `PluginShutdown`, `InitiateRSP`,
`DoRspCycles`, and `RomClosed`. Its upstream Unix Makefile supports
AArch64 and builds a shared library using the Mupen64Plus Core API headers.

The Android workflow now checks out the immutable upstream revision
`dd09c7dcdc6d2cff584aacb9d519600d47d61039`, builds it with the Android NDK
ARM64 API 23 compiler, verifies the required exported symbols, and packages
`libmupen64plus-rsp-hle.so` in the APK. Workflow change commit:
`70f478c4b9bbdc19ff0394d44f42a6ef8dffe242`.

This is build/package integration only. The JNI frontend does not yet load
the RSP library, call its `PluginStartup`, or attach it through
`CoreAttachPlugin(M64PLUGIN_RSP)`. No RSP task execution or game frame is
claimed. Keep `M64CMD_EXECUTE` blocked until runtime attachment, correct plugin
ordering, task callback behavior, and a safe stop/shutdown path are implemented
and verified.


## HLE RSP build verified and Core attach-path audit (2026-11-09)

GitHub Actions run `42565359838` completed successfully. The job verified
the Android ARM64 build of the pinned upstream HLE RSP plugin, checked its
required exported symbols, packaged
`lib/arm64-v8a/libmupen64plus-rsp-hle.so`, and uploaded artifact
`13651605758` (SHA-256
`374d373f14570305dfe1412ebbed9619c82b1d15a45004a08ec3b30f90db58e8`).

Static audit of the exact pinned Core revision
`a9ca8ec0b7decd17390c76c6341cf423d5d79321` confirms:

- `CoreAttachPlugin` calls `plugin_connect` and then `plugin_start`.
- For `M64PLUGIN_RSP`, `plugin_start_rsp()` calls the RSP plugin's
  `InitiateRSP` with Core memory/register pointers.
- The RSP plugin's `DoRspCycles` is the entry used to process pending RSP
  tasks. HLE RSP dispatches graphics/audio tasks through the callbacks
  supplied by the Core.
- The Core's `plugin_check()` explicitly warns when no RSP plugin is
  attached; its warning says video output will be corrupted.
- The Core source emits a warning (not an immediate error) if plugin attach
  order is violated. Intended order is Video, Audio, Input, RSP. Current app
  has only attached Video; Audio and Input remain Core dummy plugins.

Official API references:
`src/api/frontend.c` and `src/plugin/plugin.c` in the pinned Core revision;
upstream RSP implementation `src/plugin.c` at
`dd09c7dcdc6d2cff584aacb9d519600d47d61039`.

Runtime RSP integration is NOT yet implemented. The APK only contains the
library. Before calling `CoreAttachPlugin(M64PLUGIN_RSP)`, the frontend must
`dlopen` the RSP library, validate `PluginGetVersion` (type RSP, API major
0x0002), call `PluginStartup`, and retain the handle. The attach must happen
only after ROM open and GFX attach. On successful attach, the Core calls
`InitiateRSP`. Execution remains blocked.

Shutdown is also a required prerequisite: attached plugins must be detached
while the Core is initialized and not executing, before their
`PluginShutdown` and `dlclose`. The current shutdown path has not yet been
audited end-to-end for both GFX and RSP detach ordering. Do not enable
`M64CMD_EXECUTE` until attach failure rollback, stop, ROM close, detach,
plugin shutdown, and EGL-thread ownership are made consistent and verified.
