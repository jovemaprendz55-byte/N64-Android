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


    @Test fun capturesFramebufferFromProgrammedViRegistersWithoutAddingMmioReads() {
        val memory = N64Memory(1024)
        val bus = N64Bus(ram = memory)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET, 0x00000002)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, 0x00000020)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, 2)
        memory.write16(0x20, 0xF801)
        memory.write16(0x22, 0x07C1)
        val eventCountBeforeCapture = bus.diagnosticMmioHistory.size

        val frame = bus.captureViFrame(height = 1)

        assertEquals(2, frame?.width)
        assertEquals(1, frame?.height)
        assertEquals(0x20, frame?.origin)
        assertEquals(0xFFFF0000.toInt(), frame?.argbPixels?.get(0))
        assertEquals(0xFF00FF00.toInt(), frame?.argbPixels?.get(1))
        assertEquals(eventCountBeforeCapture, bus.diagnosticMmioHistory.size)
    }

    @Test fun returnsNoFrameWhenViWidthIsZero() {
        val bus = N64Bus()
        assertEquals(null, bus.captureViFrame(height = 240))
    }

    @Test fun writingViCurrentRegisterAcknowledgesCurrentLineState() {
        val bus = N64Bus()

        bus.write32(N64Bus.VI_CURRENT_REG, 1)

        assertEquals(0, bus.read32(N64Bus.VI_CURRENT_REG))
    }
}
