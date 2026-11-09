# Execute/Stop UI Wiring Audit — 2026-11-09

## Scope

Static source audit of the current `main` branch after the Core execute/stop lifecycle review. This document records what is actually wired in Android UI and JNI; it does not enable emulation.

## Findings

### Execution remains intentionally unreachable

- `nativeExecuteCore()` in `app/src/main/cpp/native-lib.cpp` returns immediately behind the safety gate. It does not call `CoreDoCommand(M64CMD_EXECUTE)`.
- `executeCoreOnGlThread()` exists in `MainActivity.kt` and would enqueue the JNI call using `GLSurfaceView.queueEvent`.
- A source-wide inspection found no call site for `executeCoreOnGlThread()`. It is currently an unused private method.
- The visible integration UI presents a disabled button labelled `Emulação bloqueada por segurança`; therefore the UI does not currently offer a route to the execute helper.

### Stop control is present but cannot stop a running Core yet

- The UI's `Parar emulação` button calls `nativeStopCore()` directly from its click listener on the Android UI thread.
- `nativeStopCore()` checks `g_coreExecuting` under `g_coreMutex`. Since the execute gate prevents any active execution and no call path sets the flag true, the current expected result is `Nenhuma execucao do Core esta ativa.`
- This is safe as a dormant control, but it is not evidence that asynchronous stop works on-device. The actual `M64CMD_STOP` call remains unexercised in the current app state.

### Shutdown protections

- `nativeShutdown()` refuses shutdown while `g_coreExecuting` is true.
- When GLideN64 has started, shutdown also refuses to detach/release graphics resources unless the expected EGL context is current.
- The Kotlin `onDestroy()` path attempts shutdown through `GLSurfaceView.queueEvent` and waits up to 1500 ms. This is a static code observation, not a device-tested shutdown result.

## Required implementation before enabling execution

1. Keep the execute UI disabled until the native lifecycle is implemented and reviewed.
2. Under `g_coreMutex`, validate Core/ROM/plugins and atomically mark execution active before releasing the mutex.
3. Call synchronous `M64CMD_EXECUTE` on the GL thread without holding `g_coreMutex`.
4. Clear the active flag only after `M64CMD_EXECUTE` returns; report this separately from a STOP request.
5. Let the Android UI thread call `M64CMD_STOP` without waiting for the GL queue, while preserving the Core/plugin/ROM handles until execute returns.
6. Make shutdown, plugin detach, ROM close and `dlclose` reject while execution is active.
7. Add native lifecycle instrumentation and only then perform a controlled on-device test.

## Current device evidence

The user confirmed on-device that ROM open, GLideN64 attach, RSP-HLE attach, `InitiateGFX`, `InitiateRSP`, and `CoreAttachPlugin` completed on the GL thread with the EGL context current.

No RSP task, graphics display list, GLideN64 `ProcessDList`, successful game-frame swap, or visible non-clear game frame has been confirmed. Emulation has not been started.

## Validation status

- Source audit only for UI/JNI wiring.
- The Android ARM64 APK workflows associated with commits `b2f025751f840f30bd784bc64954f1ccf7379174` and `ff9368d881f63dd2fb384597dd1a51068ceff193` completed successfully.
- Build success does not validate execution, STOP, shutdown, or rendering on the device.
