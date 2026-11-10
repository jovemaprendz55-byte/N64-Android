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
2. `CoreShutdownPhaseGate::beginPrepare()` returns the same token to concurrent callers in the same generation. A separate exclusive `tryClaimPrepare()` reservation is now available for the worker that performs STOP/drain; failed preparation must call `releasePrepareClaim()` before retry. This gate is still not wired to JNI.
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


## Follow-up inspection after the prepare-claim CI passed

The current JNI implementation confirms why the new gate must not be connected by a
small wrapper around the legacy method:

- `nativeStopCore()` obtains a `CoreRuntimeCoordinator::Operation::Stop` lease,
  waits for the R4300-ready-or-return handshake, and shares
  `g_stopCommandIssued` with the legacy shutdown path. Its call to
  `CoreDoCommand(M64CMD_STOP)` is made outside `g_coreMutex`, while the lease
  protects the Core lifetime.
- `nativeAwaitExecuteReturned()` waits on the handshake only when the runtime
  phase is `Executing` or `StopRequested`. It does not itself establish that
  the runtime call gate has drained; that remains a separate condition.
- `nativeShutdown()` independently calls `beginShutdown()`, may issue STOP,
  waits up to 1500 ms for the runtime call gate to drain, and then takes
  `g_coreMutex` for the entire GL/plugin/ROM/Core teardown sequence.
- The legacy method's STOP path and `nativeStopCore()` share the atomic
  `g_stopCommandIssued`, but neither currently claims
  `CoreShutdownPhaseGate::tryClaimPrepare()`. The new phase gate and teardown
  journal therefore remain test-only building blocks.
- The legacy method holds `g_coreMutex` while calling plugin detach/shutdown,
  ROM close, `CoreShutdown`, and `dlclose`. Moving those operations into a
  journal-driven finalizer must preserve the existing handle serialization and
  ensure that a failed step does not clear a handle or flag prematurely.

### Required JNI contract before implementation

1. A prepare request must obtain one generation token and one exclusive prepare
   claim before it can coordinate STOP/drain. Concurrent requests may join the
   same token, but only the claim owner may issue or coordinate the operation.
2. The prepare result must distinguish at least: drained, no active Execute,
   STOP rejected, readiness timeout, and drain timeout. A timeout/rejection must
   release the prepare claim only when no STOP/drain operation is still in flight;
   otherwise ownership must remain latched until that operation has a definitive
   result.
3. The finalizer must require the same token, a completed Execute return, zero
   active Core calls, and a current EGL context. It must acquire exclusive
   finalization before touching any plugin or library.
4. The progress journal must be reconciled with the existing native flags and
   handles at generation start. Each detach/close/shutdown/unload step advances
   only after the actual operation succeeds. A missing optional resource is
   marked skipped; an error leaves the current step pending.
5. A finalization failure must preserve enough state to retry the exact next
   step. In particular, never repeat a successful detach or shutdown, and never
   null a handle before its `dlclose` succeeds.
6. The legacy `nativeShutdown()` must remain the sole production teardown path
   until the new prepare/finalize pair and every Kotlin caller are migrated
   together. No parallel independent teardown implementations are acceptable.

No JNI source, Activity lifecycle, or runtime behavior was changed in this
follow-up. The prepare-claim CI passing validates the host-side ownership gate
only; it does not validate device shutdown or plugin teardown.
