# SurfaceView + EGL gameplay integration

This branch now routes the gameplay renderer through `SurfaceEglGameView`.

## Thread ownership

- `N64-Game-EGL` owns the EGL display, window surface and GLES 3 context.
- Renderer callbacks (`onSurfaceCreated`, `onSurfaceChanged`, `onDrawFrame`) execute on that thread.
- `queueEvent` posts JNI/Core/plugin operations to the same EGL owner thread.
- The Android video extension's `eglSwapBuffers` callback therefore presents Core/GLideN64 frames on the context-owning thread.
- The Core's synchronous `M64CMD_EXECUTE` occupies the EGL thread while emulation runs; STOP remains requested from a worker, and teardown is queued only after EXECUTE returns.

## Renderer

The CI workflow checks out the pinned upstream GLideN64 source and builds
`libmupen64plus-video-GLideN64.so` for ARM64. The old `android_gfx_plugin.cpp`
is an ABI placeholder and is not the game renderer. The app loads the separately
built GLideN64 library from `nativeLibraryDir`.

## Validation status

The code path is integrated but is not yet considered device-validated. The
SurfaceView/EGL context must survive Core startup, GLideN64 PluginStartup,
ROM/plugin attachment, M64CMD_EXECUTE, STOP and shutdown on the target device.
Do not merge this branch until the APK builds and a real ROM is tested.

The EGL owner view uses a dirty render mode between emulation sessions; while
M64CMD_EXECUTE runs, GLideN64 uses the registered Android video extension to
swap frames directly. No FPS improvement is assumed merely from changing the
surface implementation.
