package com.jsr.n64android

import java.util.concurrent.atomic.AtomicLong

/**
 * Continuous CPU-side ROM execution with bounded VI polling.
 *
 * The caller must prepare the CPU/bus (currently via N64HleBoot.prepare) before
 * constructing this session. It runs on a dedicated worker through the
 * N64ExecutionWorker and publishes only changed VI frames. It polls SP cooperatively and runs a bounded RSP quantum only after
 * software clears SP HALT. It does not discover, queue, or schedule OSTasks on behalf
 * of libultra; device timing and audio remain approximate or unimplemented.
 */
class N64RomVideoSession(
    private val bus: N64Bus,
    cpu: R4300Cpu,
    instructionLimit: Long = DEFAULT_INSTRUCTION_LIMIT,
    batchSize: Int = N64ExecutionSession.DEFAULT_BATCH_SIZE,
    private val framePollInterval: Long = DEFAULT_FRAME_POLL_INTERVAL,
    private val rspPollInterval: Long = DEFAULT_RSP_POLL_INTERVAL,
    private val rspInstructionQuantum: Int = DEFAULT_RSP_INSTRUCTION_QUANTUM,
    private val progressPollInterval: Long = DEFAULT_PROGRESS_POLL_INTERVAL,
    private val onProgress: (Long) -> Unit = {},
    private val onFrame: (N64VideoFrame) -> Unit
) : N64ExecutionController {
    private val instructionCount = AtomicLong(0L)
    private val cpuCycleClock = N64CpuCycleClock()
    private val publishedFrames = AtomicLong(0L)
    private val rspInstructions = AtomicLong(0L)
    private val instructionTrace = N64InstructionTrace()
    @Volatile private var previousFrameWidth: Int = 0
    @Volatile private var previousFrameHeight: Int = 0
    @Volatile private var previousPixels: IntArray? = null
    @Volatile private var rspCore: N64RspCore? = null
    @Volatile private var rspFailure: String? = null
    @Volatile private var lastRspInstructionPc: Int? = null
    @Volatile private var lastRspInstructionWord: Int? = null
    @Volatile private var lastFrameCaptureFailure: String? = null

    private val delegate: N64ExecutionSession

    init {
        require(framePollInterval > 0L) { "Frame polling interval must be positive." }
        require(rspPollInterval > 0L) { "RSP polling interval must be positive." }
        require(rspInstructionQuantum > 0) { "RSP instruction quantum must be positive." }
        require(progressPollInterval > 0L) { "Progress polling interval must be positive." }
        delegate = N64ExecutionSession(
            instructionLimit = instructionLimit,
            batchSize = batchSize,
            beforeInstruction = { bus.setDiagnosticCpuPc(cpu.pc) },
            afterInstruction = {
                val count = instructionCount.incrementAndGet()
                instructionTrace.record(cpu.diagnosticInstructionAddress, cpu.diagnosticInstructionWord)
                bus.advanceViInstructions()
                if (count % rspPollInterval == 0L) pumpRsp()
                if (count % progressPollInterval == 0L) onProgress(count)
                if (count % framePollInterval == 0L) {
                    // VI registers are guest-controlled and may be transiently invalid
                    // while a ROM is programming its display mode. A bad frame capture
                    // must not escape the after-instruction callback and fail the CPU worker.
                    val frame = try {
                        bus.captureViFrameFromRegisters().also {
                            if (it != null) lastFrameCaptureFailure = null
                        }
                    } catch (error: IllegalArgumentException) {
                        lastFrameCaptureFailure =
                            "${error.javaClass.simpleName}: ${error.message ?: "configuração VI inválida"}"
                        null
                    }
                    // A resolution change is a visible frame change even when the
                    // flattened pixel array happens to contain identical values.
                    if (frame != null && (
                            frame.width != previousFrameWidth ||
                            frame.height != previousFrameHeight ||
                            !frame.argbPixels.contentEquals(previousPixels)
                        )) {
                        previousFrameWidth = frame.width
                        previousFrameHeight = frame.height
                        previousPixels = frame.argbPixels.copyOf()
                        publishedFrames.incrementAndGet()
                        onFrame(frame)
                    }
                }
                false
            },
            stepInstruction = cpu::step
        )
    }

    override fun execute(): N64ExecutionSession.Snapshot {
        cpu.setCycleAdvanceListener(cpuCycleClock::advance)
        return try {
            delegate.execute()
        } finally {
            cpu.setCycleAdvanceListener(null)
        }
    }

    override fun requestStop() = delegate.requestStop()

    override fun snapshot(): N64ExecutionSession.Snapshot = delegate.snapshot()

    fun publishedFrameCount(): Long = publishedFrames.get()

    fun executedInstructionCount(): Long = instructionCount.get()

    /** Total modeled CPU cycles emitted by the CPU during this session (not calibrated hardware cycles). */
    fun executedCpuCycles(): Long = cpuCycleClock.totalCycles

    fun executedRspInstructionCount(): Long = rspInstructions.get()

    /** Recent successfully fetched CPU instructions, bounded to a small ring. */
    fun diagnosticInstructionTraceSnapshot(): List<N64InstructionTrace.Entry> = instructionTrace.snapshot()

    /** Returns a short exact repeated instruction cycle, if one is present. */
    fun diagnosticRepeatedInstructionLoop(): N64InstructionTrace.Loop? = instructionTrace.detectLoop()

    fun rspFailureMessage(): String? = rspFailure

    fun lastRspInstructionAddress(): Int? = lastRspInstructionPc

    fun lastRspInstructionWord(): Int? = lastRspInstructionWord

    /** Last invalid guest VI configuration; cleared after a successful capture. */
    fun frameCaptureFailureMessage(): String? = lastFrameCaptureFailure

    /** Returns all current RSP scalar/vector state without advancing emulation. */
    fun diagnosticRspSnapshot(): String =
        rspCore?.diagnosticSnapshot() ?: "RSP core ainda não foi criado nesta sessão (SP HALT ou sem quantum executado)."


    /**
     * Gives the RSP a bounded execution quantum whenever the CPU has released SP.
     * The core is recreated after HALT so it reloads the architectural SP_PC for
     * the next task. This is an approximate cooperative scheduler, not cycle timing.
     */
    private fun pumpRsp() {
        // Scheduler inspection is not a CPU bus transaction and must not pollute the CPU MMIO trace.
        val status = bus.diagnosticSpStatus()
        if ((status and N64Bus.SP_STATUS_HALT) != 0) {
            rspCore = null
            return
        }

        val core = rspCore?.takeUnless { it.halted } ?: N64RspCore(bus).also {
            it.setInstructionListener { pc, instruction ->
                lastRspInstructionPc = pc
                lastRspInstructionWord = instruction
            }
            rspCore = it
        }
        val before = core.executedInstructions
        try {
            core.run(rspInstructionQuantum)
            rspInstructions.addAndGet(core.executedInstructions - before)
            if (core.halted) rspCore = null
        } catch (error: Throwable) {
            rspInstructions.addAndGet(core.executedInstructions - before)
            rspFailure = "${error.javaClass.simpleName}: ${error.message ?: "falha sem detalhes"}"
            // Preserve the guest-visible SP state. A failed host-side RSP step
            // must not fabricate an SP HALT command or let CPU execution continue.
            rspCore = null
            throw error
        }
    }

    companion object {
        const val DEFAULT_INSTRUCTION_LIMIT = Long.MAX_VALUE
        const val DEFAULT_FRAME_POLL_INTERVAL = 100_000L
        const val DEFAULT_RSP_POLL_INTERVAL = 4_096L
        const val DEFAULT_RSP_INSTRUCTION_QUANTUM = 512
        const val DEFAULT_PROGRESS_POLL_INTERVAL = 50_000L
    }
}
