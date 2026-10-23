package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class N64MemoryMapTest {
    @Test fun classifiesRdramAndItsBoundary() {
        assertEquals(N64MemoryMap.Region.RDRAM, N64MemoryMap.regionOf(0L))
        assertEquals(N64MemoryMap.Region.RDRAM, N64MemoryMap.regionOf(0x007F_FFFFL))
        assertEquals(N64MemoryMap.Region.RDRAM_REGISTERS, N64MemoryMap.regionOf(0x03F0_0000L))
    }

    @Test fun separatesSpInstructionAndDataMemory() {
        assertEquals(N64MemoryMap.Region.SP_DMEM, N64MemoryMap.regionOf(0x0400_0000L))
        assertEquals(N64MemoryMap.Region.SP_DMEM, N64MemoryMap.regionOf(0x0400_0FFFL))
        assertEquals(N64MemoryMap.Region.SP_IMEM, N64MemoryMap.regionOf(0x0400_1000L))
        assertEquals(N64MemoryMap.Region.SP_IMEM, N64MemoryMap.regionOf(0x0400_1FFFL))
    }

    @Test fun identifiesPeripheralRegisterBlocks() {
        assertEquals(N64MemoryMap.Region.SP_REGISTERS, N64MemoryMap.regionOf(0x0404_0000L))
        assertEquals(N64MemoryMap.Region.DPC_REGISTERS, N64MemoryMap.regionOf(0x0410_0000L))
        assertEquals(N64MemoryMap.Region.DPS_REGISTERS, N64MemoryMap.regionOf(0x0420_0000L))
        assertEquals(N64MemoryMap.Region.MI_REGISTERS, N64MemoryMap.regionOf(0x0430_0000L))
        assertEquals(N64MemoryMap.Region.VI_REGISTERS, N64MemoryMap.regionOf(0x0440_0000L))
        assertEquals(N64MemoryMap.Region.AI_REGISTERS, N64MemoryMap.regionOf(0x0450_0000L))
        assertEquals(N64MemoryMap.Region.PI_REGISTERS, N64MemoryMap.regionOf(0x0460_0000L))
        assertEquals(N64MemoryMap.Region.RI_REGISTERS, N64MemoryMap.regionOf(0x0470_0000L))
        assertEquals(N64MemoryMap.Region.SI_REGISTERS, N64MemoryMap.regionOf(0x0480_0000L))
    }

    @Test fun identifiesCartridgeSramRomAndPifRegions() {
        assertEquals(N64MemoryMap.Region.CARTRIDGE_SRAM, N64MemoryMap.regionOf(0x0500_0000L))
        assertEquals(N64MemoryMap.Region.CARTRIDGE_SRAM, N64MemoryMap.regionOf(0x0500_0508L))
        assertEquals(N64MemoryMap.Region.CARTRIDGE_DOMAIN, N64MemoryMap.regionOf(0x1000_0000L))
        assertEquals(N64MemoryMap.Region.PIF, N64MemoryMap.regionOf(0x1FC0_0000L))
        assertEquals(N64MemoryMap.Region.PIF, N64MemoryMap.regionOf(0x1FC0_07FFL))
    }

    @Test fun returnsNullForUnknownOrOutOfRangeAddresses() {
        assertNull(N64MemoryMap.regionOf(0x0080_0000L))
        assertNull(N64MemoryMap.regionOf(0x0400_2000L))
        assertNull(N64MemoryMap.regionOf(-1L))
        assertNull(N64MemoryMap.regionOf(0x2000_0000L))
    }
}
