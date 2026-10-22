package com.jovemaprendz55byte.n64android

/**
 * Produces a human-readable diagnosis from ROM integrity checks and the core's stop state.
 * It distinguishes verified facts from hypotheses and never bypasses IPL3 checksum checks.
 */
internal object N64AutoDiagnostics {
    fun analyze(
        rom: ByteArray,
        stopReason: String,
        programCounter: Long,
        errorMessage: String?,
        bootSummary: String,
        videoSummary: String,
        executionSummary: String
    ): String = buildString {
        appendLine("=== AUTODIAGNÓSTICO N64 ===")
        appendLine("Parada: $stopReason | PC=0x" +
            programCounter.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))

        val magic = if (rom.size >= 4) rom.take(4).joinToString("") {
            (it.toInt() and 0xFF).toString(16).uppercase(java.util.Locale.ROOT).padStart(2, '0')
        } else ""
        val normalizedZ64 = magic == "80371240"
        appendLine("ROM: ${rom.size} bytes; magic=$magic; Z64 big-endian=$normalizedZ64")
        if (!normalizedZ64) {
            appendLine("ACHADO [ALTA]: cabeçalho não é Z64 normalizado (80 37 12 40). Verifique conversão/normalização da ROM.")
        } else {
            appendLine("ACHADO [OK]: ordem de bytes do cabeçalho normalizada para Z64.")
        }

        if (rom.size < 0x1000) {
            appendLine("ACHADO [ALTA]: ROM curta demais para conter o IPL3 completo (mínimo 0x1000 bytes).")
        } else {
            val checksum = N64RomChecksum.calculate(rom)
            if (checksum == null) {
                appendLine("ACHADO [MÉDIA]: CIC não reconhecido pelo CRC32 do IPL3. Pode ser ROM modificada/hack, dump não padrão ou CIC não suportado; isso não prova corrupção por si só.")
            } else {
                appendLine("CIC detectado=${checksum.cic.label}; CRC1 calculado=%08X; CRC2 calculado=%08X; CRC do cabeçalho confere=${checksum.matchesHeader}".format(
                    checksum.crc1.toLong(), checksum.crc2.toLong()
                ))
                if (rom.size < 0x101000) {
                    appendLine("ACHADO [ALTA]: ROM menor que 0x101000 bytes; não há dados suficientes para validar o checksum padrão.")
                } else if (!checksum.matchesHeader) {
                    appendLine("ACHADO [ALTA]: checksum calculado diferente do cabeçalho. Pode ser ROM modificada/dump ruim ou erro no algoritmo; compare com um dump conhecido antes de concluir.")
                } else {
                    appendLine("ACHADO [OK]: CIC conhecido e checksum do cabeçalho confere; isso não prova que o boot emulado esteja correto.")
                }
            }
        }

        when {
            stopReason.contains("BOOT_CHECKSUM_FAILURE_LOOP") ||
                stopReason.contains("loop de falha de checksum", ignoreCase = true) -> {
                appendLine("CONCLUSÃO PRINCIPAL: IPL3 repete o desvio de falha de checksum.")
                appendLine("LIMITAÇÃO: o projeto entra no IPL3 por um atalho diagnóstico e ainda não emula o firmware PIF real. O loop pode vir de estado inicial/seed CIC ou de CPU/hardware incompletos; não culpe a ROM apenas por esse loop.")
                when {
                    bootSummary.contains("SP_DMEM_IPL3_COPY_MATCH=false") ->
                        appendLine("ACHADO [ALTA]: a cópia do IPL3 para SP DMEM difere da ROM; investigar carregamento/cópia de memória.")
                    bootSummary.contains("SP_DMEM_IPL3_COPY_MATCH=true") ->
                        appendLine("ACHADO [OK]: a cópia do IPL3 confere; investigar estado de boot/CIC, execução da CPU e checksum.")
                }
            }
            stopReason.contains("EXECUTION_ERROR") ||
                stopReason.contains("Erro de execução", ignoreCase = true) -> {
                appendLine("CONCLUSÃO PRINCIPAL: CPU parou por erro de execução.")
                if (!errorMessage.isNullOrBlank()) appendLine("Erro: $errorMessage")
            }
            else -> appendLine("CONCLUSÃO: ainda não há evidência suficiente para escolher uma causa única.")
        }

        if (videoSummary.contains("VI_WIDTH=0") || videoSummary.contains("VI_ORIGIN=0x00000000")) {
            appendLine("VÍDEO: VI ainda não fornece imagem de jogo válida; quadros de diagnóstico não contam como jogo renderizado.")
        }
        appendLine("CPU: $executionSummary")
        appendLine("MEMÓRIA/ROM: $bootSummary")
        appendLine("VÍDEO: $videoSummary")
        appendLine("Próximo passo: corrigir achados [ALTA]. Se CIC/checksum estiverem OK e o loop continuar, implementar/testar boot PIF real e estado CIC correto, sem desativar o checksum.")
    }
}
