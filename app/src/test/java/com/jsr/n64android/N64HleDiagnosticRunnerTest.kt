package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleDiagnosticRunnerTest {
    @Test
    fun preparationReturnsHandoffAndHumanReadableSummaryWithoutExecutingCpu() {
        val rom = ByteArray(N64HleBoot.BOOT_IMAGE_ROM_OFFSET + N64HleBoot.BOOT_IMAGE_SIZE)
        for (word in 0 until 208) putWord(rom, 0x40 + word * 4, 0xFFFFFFFF.toInt())
        putWord(rom, 0x40 + 208 * 4, 0x57C85314)
        putWord(rom, N64HleBoot.ROM_HEADER_ENTRY_OFFSET, 0x80000400.toInt())
        rom[N64HleBoot.ROM_HEADER_REGION_OFFSET] = 'E'.code.toByte()

        val bus = N64Bus(cartridgeRom = rom)
        val cpu = R4300Cpu(bus)
        val preparation = N64HleDiagnosticRunner().prepare(bus, cpu)

        assertEquals(N64HleBoot.ENTRY_PC, preparation.handoff.entryPc)
        assertEquals(N64HleBoot.BOOT_IMAGE_SIZE, preparation.handoff.copiedBytes)
        assertEquals(N64HleBoot.ENTRY_PC, cpu.pc)
        assertEquals(6, preparation.summaryLines.size)
        assertTrue(preparation.summaryLines[0].contains("IPL3 NÃO EXECUTADO"))
        assertTrue(preparation.summaryLines[1].contains("false"))
        assertTrue(preparation.summaryLines[2].contains("3F"))
        assertTrue(preparation.summaryLines[4].contains("80000400"))
    }

    private fun putWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
