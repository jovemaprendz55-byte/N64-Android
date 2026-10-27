package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusRiRegisterTest {
    @Test
    fun riSelectRegisterStartsWithResetValue() {
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = byteArrayOf(0x11))

        assertEquals(N64Bus.RI_SELECT_RESET, bus.read32(N64Bus.RI_REG_BASE + N64Bus.RI_SELECT_OFFSET))
    }

    @Test
    fun riRegistersCanBeReadBackAfterWrite() {
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = byteArrayOf(0x11))
        val register = N64Bus.RI_REG_BASE + 0x10

        bus.write32(register, 0x12345678)

        assertEquals(0x12345678, bus.read32(register))
    }

    @Test
    fun riRegisterWindowEndsAtTwentyBytes() {
        val bus = N64Bus(ram = N64Memory(8 * 1024), cartridgeRom = byteArrayOf(0x11))

        try {
            bus.read32(N64Bus.RI_REG_BASE + N64Bus.RI_REG_SIZE)
            throw AssertionError("Expected access outside RI register window to fail.")
        } catch (error: UnsupportedOperationException) {
            assertEquals(
                "Unmapped N64 physical read at 0x4700020.",
                error.message
            )
        }
    }
}
