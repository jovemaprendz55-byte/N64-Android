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

    @Test
    fun retainsAllWritesFromTheObservedPteInitializationSequence() {
        val bus = N64Bus()
        // The observed boot clears two 0x800-byte fields, then writes two
        // 32-bit words in each of the 128 16-byte PTE records.
        repeat(0x800) { offset -> bus.write8(0x5DBF0 + offset, 0) }
        repeat(0x800) { offset -> bus.write8(0x5DBF0 + offset, 0) }
        repeat(128) { index ->
            val record = 0x5DBF0 + index * 0x10
            repeat(4) { byte -> bus.write8(record + byte, if (byte == 3) 1 else 0) }
            repeat(4) { byte -> bus.write8(record + 4 + byte, 0) }
        }

        assertEquals(5120L, bus.diagnosticPteWriteCount)
        assertEquals(5120, bus.diagnosticPteWriteHistory.size)
        assertTrue(bus.diagnosticPteWriteHistory.first().contains("PADDR=0x0005DBF0"))
        assertTrue(bus.diagnosticPteWriteHistory.last().contains("PADDR=0x0005E3E7"))
    }

}
