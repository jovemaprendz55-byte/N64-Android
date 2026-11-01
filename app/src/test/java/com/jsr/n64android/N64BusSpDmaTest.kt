package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusSpDmaTest {
    @Test
    fun spReadLengthCopiesRdramIntoDmemAndAdvancesAddresses() {
        val ram = N64Memory(8 * 1024)
        ram.write32(0x100, 0x12345678)
        val bus = N64Bus(ram = ram)

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x20)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.SP_RD_LEN_REG, 3)

        assertEquals(0x12345678, bus.read32(N64Bus.SP_DMEM_BASE + 0x20))
        assertEquals(0x24, bus.read32(N64Bus.SP_MEM_ADDR_REG))
        assertEquals(0x104, bus.read32(N64Bus.SP_DRAM_ADDR_REG))
        assertEquals(0, bus.read32(N64Bus.SP_DMA_BUSY_REG))
        assertEquals(0, bus.read32(N64Bus.SP_DMA_FULL_REG))
    }

    @Test
    fun spWriteLengthCopiesImemIntoRdram() {
        val ram = N64Memory(8 * 1024)
        val bus = N64Bus(ram = ram)
        bus.write32(N64Bus.SP_IMEM_BASE + 0x40, 0x89ABCDEF.toInt())

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x1040)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0x200)
        bus.write32(N64Bus.SP_WR_LEN_REG, 3)

        assertEquals(0x89ABCDEF.toInt(), ram.read32(0x200))
        assertEquals(0x1044, bus.read32(N64Bus.SP_MEM_ADDR_REG))
        assertEquals(0x204, bus.read32(N64Bus.SP_DRAM_ADDR_REG))
    }

    @Test
    fun spReadLengthHonorsBlockCountAndDramSkip() {
        val ram = N64Memory(8 * 1024)
        ram.write32(0x100, 0x11223344)
        ram.write32(0x104, 0x55667788)
        ram.write32(0x108, 0x99AABBCC.toInt())
        val bus = N64Bus(ram = ram)

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x20)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0x100)
        // Two 4-byte blocks; skip 4 bytes in RDRAM between blocks.
        bus.write32(N64Bus.SP_RD_LEN_REG, (4 shl 20) or (1 shl 12) or 3)

        assertEquals(0x11223344, bus.read32(N64Bus.SP_DMEM_BASE + 0x20))
        assertEquals(0x99AABBCC.toInt(), bus.read32(N64Bus.SP_DMEM_BASE + 0x24))
        assertEquals(0x28, bus.read32(N64Bus.SP_MEM_ADDR_REG))
        assertEquals(0x110, bus.read32(N64Bus.SP_DRAM_ADDR_REG))
    }

    @Test
    fun spWriteLengthHonorsBlockCountAndDramSkip() {
        val ram = N64Memory(8 * 1024)
        val bus = N64Bus(ram = ram)
        bus.write32(N64Bus.SP_IMEM_BASE + 0x40, 0x11223344)
        bus.write32(N64Bus.SP_IMEM_BASE + 0x44, 0x55667788)

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x1040)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0x100)
        // Two 4-byte blocks; skip 4 bytes in RDRAM between blocks.
        bus.write32(N64Bus.SP_WR_LEN_REG, (4 shl 20) or (1 shl 12) or 3)

        assertEquals(0x11223344, ram.read32(0x100))
        assertEquals(0, ram.read32(0x104))
        assertEquals(0x55667788, ram.read32(0x108))
        assertEquals(0x1048, bus.read32(N64Bus.SP_MEM_ADDR_REG))
        assertEquals(0x110, bus.read32(N64Bus.SP_DRAM_ADDR_REG))
    }

    @Test
    fun spDmaWrapsWithinSelectedFourKiBMemory() {
        val ram = N64Memory(8 * 1024)
        ram.write32(0xFFC, 0xCAFEBABE.toInt())
        val bus = N64Bus(ram = ram)

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0xFFC)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0xFFC)
        bus.write32(N64Bus.SP_RD_LEN_REG, 7)

        assertEquals(0xCAFEBABE.toInt(), bus.read32(N64Bus.SP_DMEM_BASE + 0xFFC))
        assertEquals(0, bus.read32(N64Bus.SP_DMEM_BASE))
        assertEquals(0x004, bus.read32(N64Bus.SP_MEM_ADDR_REG))
    }

    @Test
    fun spReadLengthCanTargetImemAndWrapAtFourKiBBoundary() {
        val ram = N64Memory(8 * 1024)
        ram.write8(0x200, 0x11)
        ram.write8(0x201, 0x22)
        ram.write8(0x202, 0x33)
        ram.write8(0x203, 0x44)
        val bus = N64Bus(ram = ram)

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x1FFE)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0x200)
        bus.write32(N64Bus.SP_RD_LEN_REG, 3)

        assertEquals(0x11, bus.read8(N64Bus.SP_IMEM_BASE + 0xFFE))
        assertEquals(0x22, bus.read8(N64Bus.SP_IMEM_BASE + 0xFFF))
        assertEquals(0x33, bus.read8(N64Bus.SP_IMEM_BASE))
        assertEquals(0x44, bus.read8(N64Bus.SP_IMEM_BASE + 1))
        assertEquals(0x1002, bus.read32(N64Bus.SP_MEM_ADDR_REG))
        assertEquals(0x204, bus.read32(N64Bus.SP_DRAM_ADDR_REG))
    }

    @Test
    fun reportsCompletedSpDmaWithStructuredAddressesAndTransferShape() {
        val ram = N64Memory(8 * 1024)
        ram.write32(0x300, 0x12345678)
        ram.write32(0x308, 0x90ABCDEF.toInt())
        val bus = N64Bus(ram = ram)
        var observed: N64Bus.SpDmaTransfer? = null
        bus.setSpDmaListener { observed = it }

        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x1000)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, 0x300)
        // Two 4-byte IMEM blocks with a 4-byte RDRAM skip.
        bus.write32(N64Bus.SP_RD_LEN_REG, (4 shl 20) or (1 shl 12) or 3)

        val transfer = observed ?: throw AssertionError("SP DMA completion was not reported")
        assertEquals(true, transfer.toSp)
        assertEquals(0x300, transfer.dramStartAddress)
        assertEquals(0x1000, transfer.spStartAddress)
        assertEquals(4, transfer.blockLength)
        assertEquals(2, transfer.blockCount)
        assertEquals(4, transfer.dramSkip)
        assertEquals(8, transfer.transferredBytes)
        assertEquals(0x12345678, bus.read32(N64Bus.SP_IMEM_BASE))
        assertEquals(0x90ABCDEF.toInt(), bus.read32(N64Bus.SP_IMEM_BASE + 4))
    }

}
