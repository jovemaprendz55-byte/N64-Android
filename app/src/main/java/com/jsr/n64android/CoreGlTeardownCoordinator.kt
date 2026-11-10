package com.jsr.n64android

import java.util.concurrent.CompletableFuture

/**
 * Coordinates the final native teardown after STOP/drain has completed.
 *
 * The dispatcher contract requires the action to run on the owning GL thread.
 * EGL is checked inside that action, not on the caller/worker thread.
 * This class is deliberately not wired to Activity callbacks yet.
 */
internal class CoreGlTeardownCoordinator(
    private val serial: CoreSerialLifecycleCoordinator,
    private val dispatcher: GlTeardownDispatcher,
    private val eglContextGuard: EglContextGuard,
    private val shutdownOnGlThread: () -> String
) {
    interface GlTeardownDispatcher {
        /** Returns false only when the GL surface can no longer accept work. */
        fun dispatch(action: () -> String, completion: (String) -> Unit): Boolean
    }

    fun interface EglContextGuard {
        /** Must be evaluated from inside the dispatched GL-thread action. */
        fun isCurrent(): Boolean
    }

    data class Result(
        val generation: Long,
        val stopAccepted: Boolean,
        val executeReturned: Boolean,
        val teardownQueued: Boolean,
        val shutdownComplete: Boolean,
        val message: String
    )

    fun stopDrainAndTeardown(
        request: CoreLifecycleStateMachine.Request,
        timeoutMs: Int
    ): CompletableFuture<Result> {
        val result = CompletableFuture<Result>()
        val stopFuture = serial.requestStopAndAwait(request, timeoutMs)

        stopFuture.whenComplete { stop, failure ->
            if (failure != null) {
                result.completeExceptionally(failure)
                return@whenComplete
            }

            val executeReturned = stop.waitResult
                ?.startsWith("M64CMD_EXECUTE retornou.") == true
            if (!stop.stopAccepted || !executeReturned) {
                result.complete(
                    Result(
                        generation = stop.generation,
                        stopAccepted = stop.stopAccepted,
                        executeReturned = executeReturned,
                        teardownQueued = false,
                        shutdownComplete = false,
                        message = stop.waitResult ?: stop.stopResult
                    )
                )
                return@whenComplete
            }

            if (!serial.markGlTeardownQueued(stop.generation)) {
                result.complete(
                    Result(
                        stop.generation, true, true, false, false,
                        "Teardown GL recusado: estado ou geração não permite enfileirar."
                    )
                )
                return@whenComplete
            }

            val accepted = try {
                dispatcher.dispatch(
                    action = {
                        if (!eglContextGuard.isCurrent()) {
                            "Encerramento gráfico recusado: contexto EGL não está atual na thread GL."
                        } else {
                            shutdownOnGlThread()
                        }
                    },
                    completion = { shutdownResult ->
                        val complete = shutdownResult.startsWith(
                            "Biblioteca do Core descarregada corretamente"
                        ) || shutdownResult.startsWith(
                            "Biblioteca do Core nao estava carregada"
                        ) || shutdownResult.startsWith(
                            "Core ja foi encerrado e descarregado"
                        )
                        if (complete) {
                            val marked = serial.markShutdownComplete(stop.generation)
                            result.complete(
                                Result(
                                    stop.generation, true, true, true, marked,
                                    if (marked) shutdownResult else
                                        "Core reportou descarga, mas o coordenador recusou confirmar a geração."
                                )
                            )
                        } else {
                            result.complete(
                                Result(
                                    stop.generation, true, true, true, false,
                                    shutdownResult
                                )
                            )
                        }
                    }
                )
            } catch (failureToDispatch: Throwable) {
                result.completeExceptionally(failureToDispatch)
                return@whenComplete
            }

            if (!accepted) {
                serial.markSurfaceUnavailable(stop.generation)
                result.complete(
                    Result(
                        stop.generation, true, true, false, false,
                        "Superfície GL indisponível: teardown não foi enfileirado; handles preservados."
                    )
                )
            }
        }
        return result
    }
}
