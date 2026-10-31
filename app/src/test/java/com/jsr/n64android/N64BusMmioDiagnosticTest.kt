package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BusMmioDiagnosticTest {
    @Test
    fun recordsCpuPcDeviceAddressAndValueForMmioReadsAndWrites() {
        val bus = N64Bus()
        bus.setDiagnosticCpuPc(0xFFFFFFFF80001234UL.toLong())

        assertEquals(N64Bus.MI_VERSION_VALUE, bus.read32(N64Bus.MI_VERSION_REG))
        bus.write32(N64Bus.VI_REG_BASE, 0x00000320)

        assertEquals(2, bus.diagnosticMmioHistory.size)
        val events = bus.diagnosticMmioHistory.toList()
        assertTrue(events[0].contains("R MI PC=0xFFFFFFFF80001234"))
        assertTrue(events[0].contains("PADDR=0x04300004"))
        assertTrue(events[0].contains("VALUE=0x02020102"))
        assertTrue(events[1].contains("W VI PC=0xFFFFFFFF80001234"))
        assertTrue(events[1].contains("PADDR=0x04400000"))
        assertTrue(events[1].contains("VALUE=0x00000320"))
        assertEquals(1L, bus.diagnosticMmioCounts["MI"])
        assertEquals(1L, bus.diagnosticMmioCounts["VI"])
    }

    @Test
    fun keepsOnlyLast256MmioEventsButPreservesExactDeviceCounts() {
        val bus = N64Bus()
        repeat(300) { index ->
            bus.setDiagnosticCpuPc(index.toLong())
            bus.read32(N64Bus.MI_VERSION_REG)
        }

        assertEquals(256, bus.diagnosticMmioHistory.size)
        assertEquals(300L, bus.diagnosticMmioCounts["MI"])
        assertTrue(bus.diagnosticMmioHistory.first().contains("PC=0x2C"))
        assertTrue(bus.diagnosticMmioHistory.last().contains("PC=0x12B"))
    }
}
