# Native shutdown JNI concurrency audit — 2026-11-10

## Scope

Audited `nativeShutdown()`, `nativeStopCore()`, `nativeAwaitExecuteReturned()`, `nativeExecuteCore()`, and the native lifecycle gates. This document records blockers; it does not change runtime behavior or claim two-phase shutdown is integrated.

## Verified behavior

- `nativeExecuteCore()` reserves the Execute lifecycle, resets the R4300 handshake, enters synchronous `M64CMD_EXECUTE`, then marks its return and releases the runtime lease.
- `nativeStopCore()` waits for the post-reset R4300-ready marker before issuing `M64CMD_STOP`; timeout sends no STOP.
- `nativeAwaitExecuteReturned()` only waits for Execute to return. It does not detach plugins, close the ROM, touch EGL, or unload libraries.
- `nativeShutdown()` still combines STOP coordination, bounded drain, EGL validation, plugin detach, ROM close, plugin shutdown/unload, Core shutdown/unload, and final lifecycle marking in one JNI call.
- `g_coreMutex` serializes handle-sensitive teardown after drain, but does not make the entire STOP/drain/finalize sequence a single-flight transaction.
- The teardown journal is not connected to `nativeShutdown()`; progress cannot be inferred from it yet.

## Integration blockers

1. Preparation and GL finalization are not separate JNI operations. Preparation must own STOP/drain only; finalization must run on the GL thread and validate EGL.
2. `CoreShutdownPhaseGate::beginPrepare()` returns the same token to concurrent callers while Preparing/Drained/Finalizing. This is idempotent token retrieval, not exclusive ownership. Integration needs a distinct owner/claim result.
3. Journal steps must be advanced only after each real operation succeeds. Missing resources must be explicitly skipped; failures must leave the current step pending.
4. Partial progress is spread across global flags. Migration must reconcile those flags with journal state before retrying, preventing repeated detach, ROM close, shutdown, or unload.
5. Activity has multiple legacy shutdown call sites. They must not be switched independently until a process-scoped lifecycle owner, GL dispatcher completion, timeout handling, and stale-generation rejection exist.

## Safe migration sequence

1. Add deterministic host tests for exclusive prepare/finalize ownership, generation invalidation, retry after a failed step, and duplicate concurrent requests.
2. Add native prepare that issues STOP at most once and drains Core calls without EGL or plugin teardown.
3. Add GL-thread finalize that validates token and EGL, then executes journal steps in order.
4. Preserve `nativeShutdown()` until every caller is migrated; never leave two independent teardown paths active.
5. Add Kotlin/JNI bridge and lifecycle callback tests before changing `onPause()`, `onDestroy()`, screen transitions, or ROM replacement.
6. Enable Execute/ROM launch only after the complete lifecycle is proven.

## Non-claims

No ROM execution, RSP HLE task, GLideN64 display-list processing, game frame, or real frame presentation is verified by this audit. No runtime shutdown behavior was changed.
