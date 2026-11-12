# SurfaceView + EGL renderer experiment

## Goal

Evaluate replacing Android `GLSurfaceView` with a dedicated `SurfaceView` and an app-owned EGL render thread, while retaining OpenGL ES 3 and the current Mupen64Plus/GLideN64 plugin.

This is an experiment. It must not replace the known-good renderer until device testing demonstrates equivalent correctness and a measurable benefit.

## Baseline

- Reference commit: `dbf1aa52c69f0cc2613122fc7672a18cedfd1f67` (Build 495 source).
- Existing view: `GLSurfaceView`, GLES 3 context, preserved EGL context on pause.
- The Core's synchronous `M64CMD_EXECUTE` runs on the GL thread.
- The Android video extension presents each completed frame through `eglSwapBuffers()` on that same thread.
- Core shutdown and GLideN64 teardown are deliberately serialized on the GL thread while its EGL context is current.

## Required compatibility contract

A replacement view must preserve all of the following before it can be connected to the game:

1. Own exactly one EGL display, config, context, and window surface for the active Android `Surface`.
2. Make that EGL context current on one dedicated render thread before any GLideN64/Core graphics operation.
3. Run queued Core and JNI work on that same thread; never execute graphics plugin calls on the UI thread.
4. Keep the EGL context current throughout synchronous `M64CMD_EXECUTE`; the native video extension calls `eglSwapBuffers` for each presented frame.
5. Handle `surfaceCreated`, `surfaceChanged`, and `surfaceDestroyed` without destroying EGL resources while the Core/plugin still owns them.
6. Preserve the existing STOP handshake: request STOP off the render thread, wait for `M64CMD_EXECUTE` to return, then tear down GL resources on the render thread.
7. Preserve pause/resume and ROM-picker behavior, including the existing timeout safety rules.
8. Provide equivalents for the current call sites: `queueEvent`, `requestRender`, `onPause`, and `onResume`. Do not silently change their threading semantics.

## Implementation stages

### Stage 0 — baseline

Build 495 is the comparison baseline. Record the same ROM, scene, device refresh rate, resolution, and measurement interval before and after the experiment. Capture FPS plus frame-work and swap timing; do not compare different game scenes.

### Stage 1 — isolated EGL surface prototype

Implement a separate `SurfaceView`-based component with a dedicated EGL thread. Initially test only:
- EGL display/config/context/window-surface creation and destruction.
- GLES version and renderer identification.
- Surface resize and rotation.
- A simple test frame and repeated swap.
- Clean pause/resume and surface recreation.

Do not load the Core or GLideN64 in this stage.

### Stage 2 — Core thread bridge

Add a serialized task queue on the EGL thread and prove that native EGL checks and Core diagnostics execute with the expected current context. Verify shutdown and timeout behavior before attaching the graphics plugin.

Current implementation: `SurfaceEglTestView.runQueueDiagnostic()` posts one ordered diagnostic batch to the dedicated EGL `HandlerThread`. It verifies that the context is current, reads the GLES version/renderer, calls the existing JNI `nativeVerifyEglContext()` check on that same thread, and confirms that the current EGL context is unchanged afterward. The diagnostic UI exposes this as button `13 - Testar fila EGL + JNI`. This does not start or shut down Mupen64Plus and does not load GLideN64; Core lifecycle integration remains gated on successful device testing and a separate shutdown/timeout design review.

Device acceptance for this stage: run button 13 repeatedly; confirm the reported thread is `N64-Surface-EGL`, JNI reports a valid current EGL context, and `context preserved after JNI` is `SIM`. Then leave/re-enter the screen and rotate the device, repeating the diagnostic after each lifecycle transition. Any timeout, crash, missing context, or changed context blocks Stage 3.

### Stage 3 — plugin integration

Only after Stage 2 passes, connect the Core and GLideN64. Keep the existing GLSurfaceView implementation available as a fallback until device tests pass.

### Stage 4 — performance comparison

Compare Build 495 and the experimental renderer using identical conditions. Keep the replacement only if it improves sustained frame pacing or reduces overhead without regressions in audio, controls, lifecycle, or shutdown.

## Performance expectation

A custom SurfaceView/EGL layer gives more lifecycle and thread control, but it does not inherently make OpenGL faster. It may produce no FPS gain because GLideN64 rendering, the emulator CPU workload, GPU limits, and swap/VSync pacing may dominate. The experiment must be justified by measurements, not assumptions.

## Vulkan

This experiment remains OpenGL ES only. Vulkan is a separate renderer/backend project and must not be mixed into this migration.
