package com.jsr.n64android

/**
 * Non-UI preparation stage for the bounded HLE boot diagnostic.
 *
 * This class owns the handoff-to-report boundary; CPU tracing and device
 * snapshots are moved here in subsequent extraction steps. It deliberately
 * does not execute IPL3 or claim a complete game boot.
 */
class N64HleDiagnosticRunner {
    data class Preparation(
        val handoff: N64HleBoot.Result,
        val summaryLines: List<String>
    )

    fun executeCpu(
        cpu: R4300Cpu,
        instructionLimit: Long,
        batchSize: Int = N64ExecutionSession.DEFAULT_BATCH_SIZE,
        beforeInstruction: ((R4300Cpu) -> Unit)? = null,
        afterInstruction: ((R4300Cpu) -> Boolean)? = null
    ): N64ExecutionSession.Snapshot = N64CpuExecutionSession(
        cpu = cpu,
        instructionLimit = instructionLimit,
        batchSize = batchSize,
        beforeInstruction = beforeInstruction,
        afterInstruction = afterInstruction
    ).execute()

    fun prepare(bus: N64Bus, cpu: R4300Cpu): Preparation {
        val handoff = N64HleBoot.prepare(bus, cpu)
        val lines = listOf(
            "BOOT HLE — IPL3 NÃO EXECUTADO",
            "PIF ROM utilizada: ${handoff.pifRomUsed}",
            "CIC seed: 0x${handoff.cicSeed.toString(16).uppercase()}",
            "Payload: ${handoff.copiedBytes} bytes | ROM 0x${handoff.romOffset.toString(16).uppercase()} -> RDRAM 0x${handoff.ramAddress.toString(16).uppercase()}",
            "PC inicial: 0x${cpu.pc.toULong().toString(16).uppercase()}",
            "SP inicial: 0x${cpu.gpr[29].toULong().toString(16).uppercase()}"
        )
        return Preparation(handoff, lines)
    }
}
