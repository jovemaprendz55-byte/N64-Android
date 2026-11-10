# Native two-phase shutdown gate — integration status (2026-11-10)

## Current state

The platform-independent `CoreShutdownPhaseGate` is a tested contract only.
It is **not connected** to `native-lib.cpp`, JNI, `CoreRuntimeCoordinator`,
`CoreNativeBridge`, or Android lifecycle callbacks. It does not issue STOP,
wait for Execute, acquire the EGL context, or unload any library.

The existing JNI `nativeShutdown()` still performs both logical phases in one
call:

1. Begin shutdown, request STOP when required, and wait for the Core call gate
   to drain.
2. Under `g_coreMutex`, verify EGL when the graphics plugin is started, detach
   plugins, close the ROM, shut down and unload plugins, shut down the Core, and
   unload the Core.

The JNI method currently refuses graphics teardown when EGL is not current.
That safety check must remain until a GL-thread finalizer owns the operation.

## Gate contract

The gate models `Idle -> Preparing -> Drained -> Finalizing -> Complete`.
A generation token prevents stale callbacks from changing a later lifecycle.
Finalization requires three explicit facts: synchronous Execute returned,
there are no active Core calls, and the expected EGL context is current.
A failed finalization returns to `Drained` for a controlled retry. A new
generation can begin only after successful completion and explicit reset.

Host tests cover single-flight preparation, failed preconditions, retry,
single finalizer admission under concurrency, successful completion, explicit
reset, and rejection of a stale generation token.

## Required integration sequence

Do not merge the gate into JNI by simply wrapping the existing method. The
integration must preserve ownership and retry semantics at each partial step.

1. Introduce a prepare-only native operation. It closes normal Core-call
   admission, issues at most one STOP if Execute is active, waits for the
   synchronous Execute return and all leases to drain, and records the
   generation token. It must not detach plugins, close the ROM, call GLES, or
   unload libraries.
2. Introduce a finalize-only operation dispatched on the GL thread. It
   validates the matching generation, no active Core calls, and the current
   EGL context before any graphics-plugin detach/shutdown. It then performs
   the remaining teardown in the verified upstream-required order.
3. Make each teardown sub-step idempotent and retain handles/flags after any
   failure. In particular, a successful detach or ROM close followed by a
   later failure must be represented accurately so retry does not repeat an
   invalid operation.
4. Keep the old `nativeShutdown()` endpoint and all Activity callers intact
   until every caller is migrated to prepare + GL finalize. Do not run a
   fallback finalizer on the UI thread or a thread without the owning EGL
   context.
5. Add deterministic host tests for prepare/STOP races, timeout and retry,
   concurrent callers, stale generation, partial detach/ROM-close/plugin
   shutdown/CoreShutdown failures, and successful reset.
6. Only then migrate Activity pause/destroy/screen-change paths to one
   process-scoped lifecycle owner with a bounded completion callback.

## Current execution safety

`M64CMD_EXECUTE` remains disabled. Device tests have so far confirmed only
isolated Core library load/unload cycles; they have not exercised a real ROM
execution, STOP during Execute, Execute drain, GL-thread plugin teardown, or
frame presentation. Do not describe the renderer as operational until those
checks are recorded from the target device.
