package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64PifBootFetchTest {
    @Test
    fun cpuStartsAtResetVectorAndExecutesBigEndianInstructionFromPifRom() {
        // addiu $t0, $zero, 42, encoded in the PIF ROM's big-endian byte order.
        val instruction = (0x09 shl 26) or (8 shl 16) or 42
        val pifRom = ByteArray(N64Bus.PIF_ROM_SIZE)
        pifRom[0] = (instruction ushr 24).toByte()
        pifRom[1] = (instruction ushr 16).toByte()
        pifRom[2] = (instruction ushr 8).toByte()
        pifRom[3] = instruction.toByte()

        val bus = N64Bus(ram = N64Memory(8 * 1024), pifRom = pifRom)
        val cpu = R4300Cpu(bus)

        assertEquals(0xFFFFFFFFBFC00000UL.toLong(), cpu.pc)
        cpu.step()

        assertEquals(42L, cpu.gpr[8])
        assertEquals(0xFFFFFFFFBFC00004UL.toLong(), cpu.pc)
    }

    @Test
    fun pifRomMustHaveExactHardwareWindowSize() {
        try {
            N64Bus(ram = N64Memory(8 * 1024), pifRom = ByteArray(N64Bus.PIF_ROM_SIZE - 1))
            throw AssertionError("Expected an invalid PIF ROM size to be rejected.")
        } catch (error: IllegalArgumentException) {
            assertEquals("PIF ROM must be exactly ${N64Bus.PIF_ROM_SIZE} bytes.", error.message)
        }
    }
}
