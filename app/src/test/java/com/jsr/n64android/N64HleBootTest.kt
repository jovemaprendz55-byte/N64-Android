package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleBootTest {
    @Test
    fun preparesFirstMegabyteAndBootParametersWithoutExecutingIpl3OrUsingPifRom() {
        val rom = ByteArray(N64HleBoot.BOOT_IMAGE_ROM_OFFSET + N64HleBoot.BOOT_IMAGE_SIZE)
        // Synthetic IPL3 checksum for CIC 6102/7101: 0xD057C85244.
        for (word in 0 until 208) putWord(rom, 0x40 + word * 4, 0xFFFFFFFF.toInt())
        putWord(rom, 0x40 + 208 * 4, 0x57C85314)
        // Header entry point and NTSC-U region code.
        putWord(rom, N64HleBoot.ROM_HEADER_ENTRY_OFFSET, 0x80000400.toInt())
        rom[N64HleBoot.ROM_HEADER_REGION_OFFSET] = 'E'.code.toByte()

        for (i in 0 until N64HleBoot.BOOT_IMAGE_SIZE) {
            rom[N64HleBoot.BOOT_IMAGE_ROM_OFFSET + i] = ((i * 13 + 7) and 0xFF).toByte()
        }
        // A NOP at the entry makes the first CPU fetch deterministic.
        for (i in 0 until 4) rom[N64HleBoot.BOOT_IMAGE_ROM_OFFSET + i] = 0

        val bus = N64Bus(ram = N64Memory(), pifRom = null, cartridgeRom = rom)
        val cpu = R4300Cpu(bus)
        val result = N64HleBoot.prepare(bus, cpu)

        assertEquals(0x3F, result.cicSeed)
        assertEquals(N64HleBoot.BOOT_IMAGE_SIZE, result.copiedBytes)
        assertEquals(N64HleBoot.BOOT_IMAGE_ROM_OFFSET, result.romOffset)
        assertEquals(N64HleBoot.BOOT_IMAGE_RAM_ADDRESS, result.ramAddress)
        assertEquals(N64HleBoot.ENTRY_PC, result.entryPc)
        assertEquals(N64HleBoot.ENTRY_PC, cpu.pc)
        assertEquals(N64HleBoot.INITIAL_STACK, cpu.gpr[29])
        assertEquals(0x3FL, cpu.gpr[22])
        assertEquals(0L, cpu.gpr[19])
        assertEquals(1L, cpu.gpr[20])
        assertEquals(0L, cpu.gpr[21])
        assertEquals(0L, cpu.gpr[23])
        assertTrue("HLE must use architectural TLB translation outside KSEG0/KSEG1", cpu.tlbEnabled)
        assertFalse(result.pifRomUsed)
        assertEquals(1, result.tvType)

        for (i in listOf(0, 4, 0x33F2F - N64HleBoot.BOOT_IMAGE_RAM_ADDRESS, N64HleBoot.BOOT_IMAGE_SIZE - 1)) {
            assertEquals(
                rom[N64HleBoot.BOOT_IMAGE_ROM_OFFSET + i].toInt() and 0xFF,
                bus.peek8ForDiagnostic(N64HleBoot.BOOT_IMAGE_RAM_ADDRESS + i)
            )
        }
        // The byte read by the previous failing copy loop must now be present:
        // physical 0x33F2F maps to ROM offset 0x34B2F.
        assertEquals(
            rom[0x34B2F].toInt() and 0xFF,
            bus.peek8ForDiagnostic(0x33F2F)
        )

        assertEquals(1, bus.read32(N64HleBoot.BOOT_FLAG_TV_TYPE))
        assertEquals(0, bus.read32(N64HleBoot.BOOT_FLAG_ROM_TYPE))
        assertEquals(0xB0000000.toInt(), bus.read32(N64HleBoot.BOOT_FLAG_ROM_BASE))
        assertEquals(0, bus.read32(N64HleBoot.BOOT_FLAG_RESET_TYPE))
        assertEquals(N64Memory.DEFAULT_RAM_SIZE, bus.read32(N64HleBoot.BOOT_FLAG_RDRAM_SIZE))

        // The CPU must fetch the staged first instruction through KSEG0 -> RDRAM.
        cpu.step()
        assertEquals(0, cpu.diagnosticInstructionWord)
        assertEquals(N64HleBoot.ENTRY_PC + 4L, cpu.pc)

        // IPL3 remains untouched in SP DMEM: HLE did not stage or execute it.
        assertEquals(0, bus.read8(0x04000040))
    }

    @Test
    fun rejectsUnknownCicBeforeChangingCpuState() {
        val rom = ByteArray(N64HleBoot.BOOT_IMAGE_ROM_OFFSET + N64HleBoot.BOOT_IMAGE_SIZE)
        putWord(rom, N64HleBoot.ROM_HEADER_ENTRY_OFFSET, 0x80000400.toInt())
        val bus = N64Bus(cartridgeRom = rom)
        val cpu = R4300Cpu(bus)
        cpu.gpr[8] = 123L

        try {
            N64HleBoot.prepare(bus, cpu)
            throw AssertionError("Unknown CIC should be rejected.")
        } catch (error: IllegalArgumentException) {
            assertEquals(123L, cpu.gpr[8])
            assertNotEquals(N64HleBoot.ENTRY_PC, cpu.pc)
        }
    }

    private fun putWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
