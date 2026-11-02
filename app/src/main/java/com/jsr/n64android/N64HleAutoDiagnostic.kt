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
        appendLine("REGISTRADORES CPU (snapshot no fim da sessão):")
        cpu.gpr.forEachIndexed { index, value ->
            if (value != 0L) appendLine("  GPR[$index]=0x${hex64(value)}")
        }
        appendLine("  HI=0x${hex64(cpu.hi)} LO=0x${hex64(cpu.lo)}")
        appendLine("  COP0_STATUS=0x${hex64(cpu.cop0[12])} CAUSE=0x${hex64(cpu.cop0[13])} EPC=0x${hex64(cpu.cop0[14])}")

        if (loop != null) {
            appendLine("PADRÃO REPETIDO: mesmos PCs e opcodes por ${loop.repetitions} repetições; período de ${loop.period} instrução(ões). Isso não prova, sozinho, que o estado da CPU ficou preso.")
            loop.pattern.forEachIndexed { index, entry ->
                appendLine("  PADRÃO[$index] PC=0x${hex64(entry.pc)} INST=0x${hex32(entry.instruction)}")
            }
        } else {
            appendLine("PADRÃO: nenhuma repetição curta de PC+opcode detectada na janela recente.")
        }

        val spHalted = (bus.diagnosticSpStatus() and N64Bus.SP_STATUS_HALT) != 0
        val spWrites = bus.diagnosticMmioWriteCounts["SP"] ?: 0L
        val dpcWrites = bus.diagnosticMmioWriteCounts["DPC"] ?: 0L
        if (spHalted) {
            appendLine("OBSERVAÇÃO: SP está em HALT.")
            if (spWrites == 0L) appendLine("EVIDÊNCIA: nenhuma escrita CPU nos registradores SP foi registrada nesta sessão.")
        }
        appendLine("MMIO ACUMULADO: escritas SP=$spWrites | escritas DPC=$dpcWrites (contadores totais; histórico visual permanece limitado)")
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
                appendLine("Há repetição de PC+opcode enquanto SP permanece em HALT; RSP e frames ainda não avançaram. Isso é um indício, não prova de estado idêntico. Investigar progresso dos registradores, condição de saída e entrega de interrupções.")
            spHalted && spWrites == 0L ->
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
