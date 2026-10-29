package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusVideoInterfaceTest {
    @Test fun viCurrentRegisterIsReadableInsteadOfThrowingUnmappedRead() {
        val bus = N64Bus()

        assertEquals(0, bus.read32(N64Bus.VI_CURRENT_REG))
    }

    @Test fun viConfigurationRegistersCanBeWrittenAndReadBack() {
        val bus = N64Bus()
        val viStatus = N64Bus.VI_REG_BASE
        val viWidth = N64Bus.VI_REG_BASE + 0x08

        bus.write32(viStatus, 0x00000003)
        bus.write32(viWidth, 320)

        assertEquals(0x00000003, bus.read32(viStatus))
        assertEquals(320, bus.read32(viWidth))
    }

    @Test fun writingViCurrentRegisterAcknowledgesCurrentLineState() {
        val bus = N64Bus()

        bus.write32(N64Bus.VI_CURRENT_REG, 1)

        assertEquals(0, bus.read32(N64Bus.VI_CURRENT_REG))
    }
}
