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

    @Test
    fun deviceSnapshotReportsImplementedStateWithoutAddingMmioEvents() {
        val bus = N64Bus()
        bus.write32(N64Bus.SP_STATUS_REG, N64Bus.SP_STATUS_CMD_CLR_HALT)
        bus.write32(N64Bus.MI_INTR_MASK_REG, 1 shl 1)
        bus.write32(N64Bus.AI_DACRATE_REG, 0x000003FF)
        bus.write32(N64Bus.SI_DRAM_ADDR_REG, 0x00001230)
        bus.write32(N64Bus.VI_REG_BASE, 0x00000320)

        val eventCountBeforeSnapshot = bus.diagnosticMmioHistory.size
        val snapshot = bus.diagnosticDeviceSnapshot()

        assertTrue(snapshot.any { it.startsWith("SP:") && it.contains("STATUS=0x0") })
        assertTrue(snapshot.any { it.startsWith("MI:") && it.contains("INTR_MASK=0x2") })
        assertTrue(snapshot.any { it.startsWith("AI:") && it.contains("DACRATE=0x3FF") })
        assertTrue(snapshot.any { it.startsWith("SI:") && it.contains("DRAM_ADDR=0x1230") })
        assertTrue(snapshot.any { it.startsWith("VI:") && it.contains("R0=0x00000320") })
        assertTrue(snapshot.any { it.startsWith("RI:") && it.contains("R3=0x00000014") })
        assertEquals(eventCountBeforeSnapshot, bus.diagnosticMmioHistory.size)
    }
}
