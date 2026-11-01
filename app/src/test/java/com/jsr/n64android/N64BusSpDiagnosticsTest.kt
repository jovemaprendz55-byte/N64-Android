package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64BusSpDiagnosticsTest {
    @Test
    fun pteWatchpointRecordsCpuWriterPcAndByteValues() {
        val bus = N64Bus()
        bus.setDiagnosticCpuPc(0xFFFFFFFF80001874UL.toLong())

        bus.write32(0x5DBF0, 0x00000001)

        assertEquals(4, bus.diagnosticPteWriteHistory.size)
        assertEquals(4L, bus.diagnosticPteWriteCount)
        assertTrue(bus.diagnosticPteWriteHistory.all { it.contains("PC=0xFFFFFFFF80001874") })
        assertTrue(bus.diagnosticPteWriteHistory[0].contains("PADDR=0x0005DBF0"))
        assertTrue(bus.diagnosticPteWriteHistory[0].contains("VALUE=0x00"))
        assertTrue(bus.diagnosticPteWriteHistory[3].contains("PADDR=0x0005DBF3"))
        assertTrue(bus.diagnosticPteWriteHistory[3].contains("VALUE=0x01"))
    }

    @Test
    fun pteWatchpointIncludesDecodedStoreInstructionAndSourceRegisters() {
        val bus = N64Bus()
        bus.setDiagnosticCpuInstructionContext(
            pc = 0xFFFFFFFF80001874UL.toLong(),
            instruction = 0xAC620000.toInt(), // SW $2, 0($3)
            rs = 3,
            rt = 2,
            rsValue = 0x5DBF0L,
            rtValue = 1L,
            effectiveAddress = 0x5DBF0L
        )

        bus.write32(0x5DBF0, 0x00000001)

        val first = bus.diagnosticPteWriteHistory.first()
        assertTrue(first.contains("INST=0xAC620000"))
        assertTrue(first.contains("OP=0x2B"))
        assertTrue(first.contains("RS=3 RT=2"))
        assertTrue(first.contains("BASE=0x5DBF0"))
        assertTrue(first.contains("RTVAL=0x1"))
        assertTrue(first.contains("EA=0x5DBF0"))
    }

    @Test
    fun dedicatedSpHistoryRetainsCpuReadsAndWritesAfterOtherMmioTraffic() {
        val bus = N64Bus()
        bus.setDiagnosticCpuPc(0xFFFFFFFF80001234UL.toLong())

        bus.read32(N64Bus.SP_STATUS_REG)
        bus.setDiagnosticCpuPc(0xFFFFFFFF80001238UL.toLong())
        bus.write32(N64Bus.SP_PC_REG, 0x20)

        // More recent MI accesses would evict both SP entries from the shared
        // 256-entry MMIO ring, but must not evict the dedicated SP history.
        repeat(300) { bus.read32(N64Bus.MI_VERSION_REG) }

        val history = bus.diagnosticSpMmioHistory.toList()
        assertEquals(2, history.size)
        assertTrue(history[0].contains("SP_STATUS R SP"))
        assertTrue(history[0].contains("PC=0xFFFFFFFF80001234"))
        assertTrue(history[1].contains("SP_PC W SP"))
        assertTrue(history[1].contains("PC=0xFFFFFFFF80001238"))
        assertTrue(history[1].contains("VALUE=0x00000020"))
        assertEquals(1L, bus.diagnosticSpMmioCounts["SP_STATUS"])
        assertEquals(1L, bus.diagnosticSpMmioCounts["SP_PC"])
    }
    @Test
    fun diagnosticMemoryAndScratchpadDumpsAreReadOnly() {
        val rom = ByteArray(0x1000)
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        val bus = N64Bus(ram = N64Memory(0x4000), cartridgeRom = rom)
        bus.write8(0x100, 0x5A)
        val mmioBefore = bus.diagnosticMmioHistory.size

        val memory = bus.diagnosticMemoryDump(listOf(0x100..0x100))
        val scratchpad = bus.diagnosticSpMemoryDump()
        val cartridge = bus.diagnosticCartridgeSummary()

        assertTrue(memory.contains("5A"))
        assertTrue(scratchpad.contains("SP DMEM"))
        assertTrue(scratchpad.contains("SP IMEM"))
        assertTrue(cartridge.contains("ROM_BYTES=4096"))
        assertTrue(cartridge.contains("MAGIC=0x80371240"))
        assertEquals(mmioBefore, bus.diagnosticMmioHistory.size)
    }

}
