package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertTrue
import org.junit.Test

class N64AutoDiagnosticsTest {
    @Test
    fun identifiesIpl3LoopAsBootPathProblemWithoutBlamingRomByDefault() {
        val rom = ByteArray(0x101000)
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40

        val result = N64AutoDiagnostics.analyze(
            rom = rom,
            stopReason = "BOOT_CHECKSUM_FAILURE_LOOP",
            programCounter = 0xFFFFFFFF800001C8UL.toLong(),
            errorMessage = null,
            bootSummary = "ROM_CIC_DETECTED=UNKNOWN_OR_SHORT_ROM, SP_DMEM_IPL3_COPY_MATCH=true",
            videoSummary = "VI_WIDTH=0, VI_ORIGIN=0x00000000",
            executionSummary = "LAST_INSTR=0x0411FFFF"
        )

        assertTrue(result.contains("CONCLUSÃO PRINCIPAL"))
        assertTrue(result.contains("não emula o firmware PIF real"))
        assertTrue(result.contains("não culpe a ROM apenas"))
        assertTrue(result.contains("CIC não reconhecido"))
        assertTrue(result.contains("imagem de jogo válida"))
    }

    @Test
    fun flagsRomTooShortForIpl3() {
        val result = N64AutoDiagnostics.analyze(
            rom = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40),
            stopReason = "EXECUTION_ERROR",
            programCounter = 0L,
            errorMessage = "unsupported instruction",
            bootSummary = "",
            videoSummary = "",
            executionSummary = ""
        )

        assertTrue(result.contains("ROM curta demais"))
        assertTrue(result.contains("unsupported instruction"))
    }
}
