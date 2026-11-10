# Native shutdown journal integration audit

Date: 2026-11-10

## Result

The retry-safe teardown journal and executor are covered by host tests and the
Android workflow, but they are not yet connected to
`Java_com_jsr_n64android_CoreNativeBridge_nativeShutdown`.

This is intentional. The current JNI shutdown path still performs the native
teardown directly while holding `g_coreMutex`. Replacing it with the executor
without first defining persistent session ownership would weaken the existing
handle-lifetime guarantees.

## Verified current behavior

- The runtime coordinator closes normal Core-call admission and waits for
  active leases to drain before teardown.
- The JNI shutdown path rejects teardown if Execute remains active.
- Detach and plugin shutdown callbacks are only attempted with the required
  EGL context current when the graphics plugin is attached or started.
- Successful detach/shutdown operations update their runtime flags.
- A failed `dlclose` preserves the corresponding handle for a later retry.
- The journal reserves one step at a time, executes callbacks outside its own
  mutex, and leaves a failed step pending.
- The executor serializes its own calls and rejects stale generations.
- CI now tests the EGL requirement for all four attached/started flag pairs.

## Integration blockers

1. **Persistent generation ownership.** The journal must live across repeated
   JNI shutdown calls. A stack-local journal would lose completed-step history
   after a failure and could repeat non-idempotent callbacks.
2. **Generation creation.** The runtime coordinator currently exposes phases,
   but not a shutdown-generation token that can be safely reused by retries.
   A generation must be created exactly once for a shutdown lifecycle.
3. **Single-flight across JNI calls.** The executor's mutex protects one
   executor instance. The integration must guarantee one process-lifetime
   session and must not allow a second JNI call to create another executor.
4. **Handle synchronization.** Current teardown holds `g_coreMutex` across
   every callback. The executor deliberately calls callbacks without its
   journal mutex, but callbacks still need a clear handle-locking contract.
   Do not simply release `g_coreMutex` between steps until every competing
   JNI path is audited for coordinator leases and handle access.
5. **Step resolution from live state.** Missing resources must be marked
   `Skipped`, while present resources require the exact native operation.
   The decision must be made under the handle lock and must not mark a step
   complete before the operation succeeds.
6. **EGL affinity.** The graphics-context guard must run on the GL thread at
   the point of the graphics callback, not merely before dispatching work.
7. **Final state commit.** `ShutdownComplete` may be published only after
   every journal step is Completed or Skipped, every owned handle is null,
   and the runtime coordinator accepts the final transition.

## Required integration sequence

1. Add a process-lifetime shutdown-session owner with a monotonic generation
   and explicit states (inactive, active, complete).
2. Make session creation and executor acquisition single-flight across JNI
   callers; retries must reuse the same generation.
3. Implement one callback per journal step. Each callback must acquire the
   appropriate handle protection, inspect the live resource flag, perform
   exactly one operation, and update its flag only after success.
4. Keep callbacks outside the journal mutex. Do not hold a journal lock while
   calling Core, plugin, EGL, or dynamic-loader functions.
5. Ensure the graphics callback is dispatched to the GL thread and validates
   EGL inside that dispatched callback.
6. On callback failure, preserve all remaining handles and keep that journal
   step pending. A retry must not repeat already completed operations.
7. Publish runtime `ShutdownComplete` only when the journal is complete and
   all native handles and started/attached/open flags are resolved.
8. Add host tests for session retry, concurrent JNI-style callers, stale
   generation, callback failure at every step, and final-state rejection.
9. Only then connect the session to the JNI method and exercise it on device.

## Explicitly not changed

- No change to `M64CMD_EXECUTE` admission or execution.
- No change to Activity pause/destroy behavior or EGL dispatch.
- No ROM execution or renderer claim is made.
- No plugin or Core unload order is changed.
- The current direct JNI teardown remains the active implementation.
