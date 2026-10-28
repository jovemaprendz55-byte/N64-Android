package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusAiRegisterTest {
    @Test
    fun aiControlAndRateRegistersStoreHardwareWidthValues() {
        val bus = N64Bus()

        bus.write32(N64Bus.AI_DRAM_ADDR_REG, 0xFF123456.toInt())
        bus.write32(N64Bus.AI_CONTROL_REG, 0xFFFFFFFF.toInt())
        bus.write32(N64Bus.AI_DACRATE_REG, 0xFFFF)
        bus.write32(N64Bus.AI_BITRATE_REG, 0xFFFF)

        assertEquals(0x00123456, bus.read32(N64Bus.AI_DRAM_ADDR_REG))
        assertEquals(1, bus.read32(N64Bus.AI_CONTROL_REG))
        assertEquals(0x3FFF, bus.read32(N64Bus.AI_DACRATE_REG))
        assertEquals(0xF, bus.read32(N64Bus.AI_BITRATE_REG))
    }

    @Test
    fun aiStatusWriteIsAcceptedAndAudioQueueReadsIdle() {
        val bus = N64Bus()

        bus.write32(N64Bus.AI_LEN_REG, 0x1000)
        bus.write32(N64Bus.AI_STATUS_REG, 9)

        assertEquals(0, bus.read32(N64Bus.AI_LEN_REG))
        assertEquals(0, bus.read32(N64Bus.AI_STATUS_REG))
    }
}
