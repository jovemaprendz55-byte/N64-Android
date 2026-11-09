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
