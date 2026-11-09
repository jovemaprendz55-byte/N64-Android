# Android GL Thread Lifecycle Risks — 2026-11-09

## Scope

Follow-up static audit of Activity pause/destroy behavior and the planned synchronous Mupen64Plus Core execution path. No emulation code is enabled by this document.

## Confirmed source behavior

- `GLSurfaceView` is configured with `RENDERMODE_WHEN_DIRTY`.
- `executeCoreOnGlThread()` queues `nativeExecuteCore()` on the GLSurfaceView GL thread.
- Mupen64Plus `M64CMD_EXECUTE` enters the Core's synchronous run loop. If called from the GL thread, that thread remains inside the JNI call until the Core exits.
- The visible Stop button calls `nativeStopCore()` directly from the Android UI thread, not through `queueEvent`. This is the correct thread direction for a stop request while the GL thread is occupied.
- `onPause()` currently calls `glView?.onPause()` immediately and does not first request Core stop or wait for the execute call to return.
- `onDestroy()` queues `nativeShutdown()` on the GL thread and waits at most 1500 ms. If that thread is still inside synchronous execute, the shutdown event cannot run before execute returns; the timeout is not proof that shutdown completed.

## Risk

Enabling synchronous Core execution without Activity lifecycle coordination can leave the GL thread occupied while Android pauses or destroys the Activity. A queued shutdown cannot preempt a synchronous call already running on that same thread. The app must not detach plugins, close the ROM, call PluginShutdown, or dlclose the Core/renderer while execute remains active.

## Required lifecycle state machine before enabling execute

Track at least these states distinctly:
- READY: ROM and required plugins attached; no execute call active.
- RUNNING: execute call entered and has not returned.
- STOP_REQUESTED: UI thread successfully issued M64CMD_STOP; execute may still be running.
- STOPPED: execute call returned and native active flag is false.
- SHUTTING_DOWN: only entered after STOPPED and while GL/EGL context is valid for graphics detach.

Required rules:
1. On Stop click, issue M64CMD_STOP directly from the UI thread. Do not queue Stop behind the blocked GL thread.
2. Do not interpret a successful M64CMD_STOP return as proof that execute has returned.
3. Observe execute return separately, clear the active flag only then, and publish STOPPED to the UI.
4. On Activity pause, if state is RUNNING or STOP_REQUESTED, request stop and wait asynchronously for STOPPED before calling GLSurfaceView.onPause(). Avoid blocking the UI thread indefinitely; define a bounded timeout and a safe retry/recovery state.
5. On Activity destroy, never queue shutdown and then assume a timeout means cleanup. If execute has not returned, retain native handles and avoid plugin detach/dlclose. Prefer completing cleanup on the GL thread only after STOPPED and with EGL current.
6. Handle EGL context loss/recreation explicitly. If graphics shutdown cannot run with the expected context, keep libraries loaded and report cleanup pending.
7. Add tests for pause while running, destroy while running, Stop during active execute, repeated Stop, Stop racing with execute return, and EGL loss after Stop.

## Current status

- This is a source-level lifecycle risk assessment, not an on-device test.
- Native `M64CMD_EXECUTE` remains safety-gated and the UI execute control remains disabled.
- The user's latest on-device confirmation covers ROM open, GFX/RSP plugin attachment, InitiateGFX/InitiateRSP and CoreAttachPlugin only.
- No RSP graphics task, GLideN64 display list, game-frame swap, or visible game frame has been confirmed.

## Next engineering step

Implement a single native execution state machine and a Kotlin lifecycle coordinator together. Do not enable the execute button until both are reviewed and tests/builds pass. Then perform a controlled on-device run with crash-log capture and a verified Stop/return sequence before claiming a first frame.
