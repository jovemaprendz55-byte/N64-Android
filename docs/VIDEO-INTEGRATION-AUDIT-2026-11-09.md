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
