package com.jsr.n64android

/**
 * Converts existing emulator telemetry into a concise, evidence-labelled report.
 * This class is observational only: it never patches CPU, SP, interrupts or tasks.
 */
object N64HleAutoDiagnostic {
    fun build(
        cpu: R4300Cpu,
        bus: N64Bus,
        instructionCount: Long,
        rspInstructionCount: Long,
        frameCount: Long,
        loop: N64InstructionTrace.Loop?
    ): String = buildString {
        appendLine("=== DIAGNÓSTICO AUTOMÁTICO HLE ===")
        appendLine("CPU: $instructionCount instruções | PC=0x${hex64(cpu.pc)}")
        appendLine("RSP: $rspInstructionCount instruções | VI: $frameCount frames publicados")
        appendLine("SP_STATUS=0x${bus.diagnosticSpStatus().toUInt().toString(16).uppercase().padStart(8, '0')}")

        if (loop != null) {
            appendLine("CONFIRMADO: sequência de instruções repetida; ciclo de ${loop.period} instrução(ões), ${loop.repetitions} repetições consecutivas.")
            loop.pattern.forEachIndexed { index, entry ->
                appendLine("  CICLO[$index] PC=0x${hex64(entry.pc)} INST=0x${hex32(entry.instruction)}")
            }
        } else {
            appendLine("LOOP: nenhum ciclo curto confirmado na janela recente.")
        }

        val spHalted = (bus.diagnosticSpStatus() and N64Bus.SP_STATUS_HALT) != 0
        val spWrites = bus.diagnosticSpWriteHistory.size
        val dpcWrites = bus.diagnosticMmioHistory.count { it.startsWith("W DPC ") }
        if (spHalted) {
            appendLine("OBSERVAÇÃO: SP está em HALT.")
            if (spWrites == 0) appendLine("EVIDÊNCIA: nenhuma escrita CPU nos registradores SP foi registrada nesta sessão.")
        }
        appendLine("MMIO RECENTE: escritas SP=$spWrites | escritas DPC=$dpcWrites (buffers limitados)")
        appendLine("ESTADO DOS DISPOSITIVOS:")
        bus.diagnosticDeviceSnapshot().forEach { appendLine("  $it") }
        appendLine("ÚLTIMOS EVENTOS SP:")
        bus.diagnosticSpMmioHistory.toList().takeLast(12).forEach { appendLine("  $it") }
        if (bus.diagnosticSpMmioHistory.isEmpty()) appendLine("  Nenhum acesso SP registrado.")

        if (cpu.lastTlbFaultKind != null) {
            appendLine("EVIDÊNCIA: última falha TLB observada: ${cpu.lastTlbFaultKind} / ${cpu.lastTlbFaultAccessType} em VA=0x${cpu.lastTlbFaultVirtualAddress?.let(::hex64) ?: "N/A"}.")
        }
        if (cpu.lastExceptionCode != null) {
            appendLine("EVIDÊNCIA: última exceção CPU ExcCode=${cpu.lastExceptionCode} em PC=0x${cpu.lastExceptionInstructionAddress?.let(::hex64) ?: "N/A"}.")
        }

        appendLine("INTERPRETAÇÃO:")
        when {
            loop != null && spHalted && rspInstructionCount == 0L && frameCount == 0L ->
                appendLine("A CPU repete um ciclo enquanto SP permanece em HALT; RSP e frames ainda não avançaram. Investigar a condição de saída do loop e o estado/entrega de interrupções.")
            spHalted && spWrites == 0 ->
                appendLine("Não há evidência de submissão/liberação do SP nesta sessão. Investigar o fluxo de inicialização/scheduler antes de atribuir falha ao RSP.")
            cpu.lastTlbFaultKind != null ->
                appendLine("Há uma falha TLB registrada. Reconstruir o refill e o retorno antes de alterar a MMU.")
            else ->
                appendLine("Os dados atuais não isolam uma causa raiz. Usar o rastro recente e os eventos de hardware para escolher o próximo ponto.")
        }
        appendLine("CAUSA RAIZ: NÃO DETERMINADA AUTOMATICAMENTE.")
        appendLine("Este relatório não executa IPL3, não usa PIF ROM e não força tarefas ou registradores.")
    }

    private fun hex64(value: Long): String = value.toULong().toString(16).uppercase().padStart(16, '0')
    private fun hex32(value: Int): String = value.toUInt().toString(16).uppercase().padStart(8, '0')
}
