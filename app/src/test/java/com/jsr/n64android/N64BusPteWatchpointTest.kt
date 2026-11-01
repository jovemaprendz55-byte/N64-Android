package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BusPteWatchpointTest {
    @Test
    fun watchesEntirePteTableRangeAndExcludesNextByte() {
        val bus = N64Bus()
        bus.setDiagnosticCpuPc(0x700017FCL)

        bus.write8(0x5DBF0, 0x01)
        bus.write8(0x5E3EF, 0x7F)
        bus.write8(0x5E3F0, 0x55)

        assertEquals(2L, bus.diagnosticPteWriteCount)
        assertEquals(2, bus.diagnosticPteWriteHistory.size)
        assertTrue(bus.diagnosticPteWriteHistory.first().contains("PADDR=0x0005DBF0"))
        assertTrue(bus.diagnosticPteWriteHistory.last().contains("PADDR=0x0005E3EF"))
        assertTrue(bus.diagnosticPteWriteHistory.last().contains("VALUE=0x7F"))
    }

    @Test
    fun retainsEveryByteWriteInTheFullPteTableWindow() {
        val bus = N64Bus()
        repeat(0x800) { offset ->
            bus.write8(0x5DBF0 + offset, offset)
        }

        assertEquals(0x800L, bus.diagnosticPteWriteCount)
        assertEquals(0x800, bus.diagnosticPteWriteHistory.size)
        assertTrue(bus.diagnosticPteWriteHistory.first().contains("PADDR=0x0005DBF0"))
        assertTrue(bus.diagnosticPteWriteHistory.last().contains("PADDR=0x0005E3EF"))
    }
}
