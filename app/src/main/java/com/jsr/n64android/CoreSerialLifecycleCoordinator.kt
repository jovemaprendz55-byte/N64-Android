package com.jsr.n64android

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Process-scoped single-flight coordinator for Core STOP/drain requests.
 *
 * It coalesces callers onto one worker operation. It never performs shutdown,
 * plugin teardown, Activity access, or EGL work.
 */
internal class CoreSerialLifecycleCoordinator(
    private val bridge: CoreLifecycleBridge,
    private val worker: Executor,
    private val stateMachine: CoreLifecycleStateMachine = CoreLifecycleStateMachine()
) {
    data class OperationResult(
        val generation: Long,
        val request: CoreLifecycleStateMachine.Request?,
        val stopResult: String,
        val waitResult: String?,
        val stopAccepted: Boolean
    )

    private val lock = Any()
    private var active: CompletableFuture<OperationResult>? = null

    fun requestStopAndAwait(
        request: CoreLifecycleStateMachine.Request,
        timeoutMs: Int
    ): CompletableFuture<OperationResult> {
        val operation: CompletableFuture<OperationResult>
        val snapshot: CoreLifecycleStateMachine.Snapshot
        val shouldSchedule: Boolean

        synchronized(lock) {
            snapshot = stateMachine.begin(request)
            val existing = active
            if (existing != null) return existing

            operation = CompletableFuture()
            active = operation
            shouldSchedule = true
        }

        if (shouldSchedule) {
            try {
                worker.execute {
                    try {
                        val stop = bridge.requestStop()
                        val accepted = stop.startsWith("Pedido de parada enviado ao Core") ||
                            stop.startsWith("Pedido M64CMD_STOP ja foi enviado")
                        val wait = if (accepted) {
                            bridge.awaitExecuteReturned(timeoutMs.coerceAtLeast(0))
                        } else {
                            null
                        }

                        if (!accepted) {
                            stateMachine.markStopRejected(snapshot.generation)
                        } else if (wait?.startsWith("M64CMD_EXECUTE retornou.") == true) {
                            stateMachine.markExecuteReturned(snapshot.generation)
                        } else {
                            stateMachine.markExecuteReturnTimeout(snapshot.generation)
                        }

                        operation.complete(
                            OperationResult(
                                generation = snapshot.generation,
                                request = stateMachine.snapshot().request,
                                stopResult = stop,
                                waitResult = wait,
                                stopAccepted = accepted
                            )
                        )
                    } catch (failure: Throwable) {
                        stateMachine.markExecuteReturnTimeout(snapshot.generation)
                        operation.completeExceptionally(failure)
                    } finally {
                        synchronized(lock) {
                            if (active === operation) active = null
                        }
                    }
                }
            } catch (failure: Throwable) {
                stateMachine.markExecuteReturnTimeout(snapshot.generation)
                synchronized(lock) {
                    if (active === operation) active = null
                }
                operation.completeExceptionally(failure)
            }
        }
        return operation
    }

    fun snapshot(): CoreLifecycleStateMachine.Snapshot = stateMachine.snapshot()
}
