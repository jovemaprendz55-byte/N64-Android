# GLideN64 Android patch set

## Upstream source

- Repository: `gonetz/GLideN64`
- Pinned revision: `a04081ad6a966a9413bea63a55f502e0dc98bb18`
- Upstream license: GNU GPL version 2 (see upstream `LICENSE` and `licenses/`).
- The CI checks out the original upstream tree at the immutable revision and applies the documented context change through a guarded exact-block replacement. The unified diff in this directory is the reviewable patch record; the workflow fails if the original block is missing or appears more than once. It does not rewrite or vendor the upstream source tree into this repository.

## android-gles-profile.patch

Changes the Mupen64Plus display backend's requested context from desktop OpenGL 3.3 CORE to OpenGL ES 3.0. The target Poco C85 reports an OpenGL ES 3.2 context; requesting a desktop CORE profile is incompatible with the Android EGL/GLES surface.

The patch intentionally does not claim that rendering works. It only makes the requested API profile consistent with the Android context. The frontend's video callbacks must still validate the current context, and the GLideN64 execution/thread model must be integrated before attaching the plugin or opening a ROM.

## Build and packaging contract

The Android workflow must:
1. Check out the exact upstream revision above.
2. Apply the equivalent context-attribute change using a guarded exact-block replacement (the original upstream block must occur exactly once), then run `git diff --check`.
3. Build the plugin for `arm64-v8a` using the Android NDK and EGL backend.
4. Package the resulting `libmupen64plus-video-GLideN64.so` in the APK without linking it as a startup dependency of `libn64bridge.so`.
5. Verify its required Mupen64Plus GFX ABI exports.

The app is configured to load the plugin only for `PluginStartup`/ABI validation in this milestone; that runtime test still needs to be performed on the Poco C85. `InitiateGFX`, `CoreAttachPlugin`, `M64CMD_ROM_OPEN`, and `M64CMD_EXECUTE` remain blocked until EGL context ownership and the GLideN64 threaded-video behavior are resolved and tested on the device.

## Android GL thread ownership

The patch also changes the Mupen64Plus display backend's FunctionWrapper
setup on Android to call setThreadedMode(0). Upstream GLideN64 defaults
ThreadedVideo=1 under OS_ANDROID, which creates a private command
execution thread. That thread does not own the EGL context created by the
app's GLSurfaceView; sending GL calls there would violate EGL context
thread affinity.

This is intentionally Android-only. Other platforms continue to use the
configured ThreadedVideo value. The CI applies the change as a guarded
exact-once replacement and compiles the ARM64 plugin. This proves the
adaptation compiles, not that GLideN64 has been initialized on the device.
The frontend must still schedule every Core/plugin graphics operation on
the GLSurfaceView render thread and handle EGL context recreation.


## Android surface resolution

The Android display backend now reads the active GLES viewport from the current GLSurfaceView EGL context during GLideN64 display initialization. This replaces the desktop windowed 640x480 defaults with the actual landscape surface dimensions before GLideN64 calculates its internal game viewport and scaling. The change is guarded by OS_ANDROID and leaves desktop/other platform sizing unchanged. It is a resolution integration fix; the final on-device result must still be checked for the selected aspect-ratio mode.
