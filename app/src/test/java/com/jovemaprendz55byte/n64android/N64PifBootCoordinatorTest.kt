package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N64PifBootCoordinatorTest {
    @Test
    fun coordinatorVerifiesBootCopyBeforeCic6105Handoff() {
        val bus = N64MemoryBus()
        val cpu = R4300Cpu(bus)
        val rom = validRom('P')

        val handoff = N64PifBootCoordinator(bus, cpu).startCic6105(rom)

        assertEquals(N64PifBootSequence.CicFamily.CIC_6105, handoff.cic)
        assertEquals(N64BootSequence.Region.PAL, handoff.region)
        assertEquals(0xFC0, handoff.ipl3BytesLoaded)
        assertEquals(N64BootImageLoader.IPL3_ENTRY_POINT, cpu.pc)
        for (offset in rom.indices) {
            assertEquals("DMEM copy at 0x%X".format(offset), rom[offset],
                bus.readByte(N64SpMemory.DMEM_BASE + offset))
        }
    }

    @Test
    fun coordinatorUsesCic6102ResetState() {
        val bus = N64MemoryBus()
        val cpu = R4300Cpu(bus)

        val handoff = N64PifBootCoordinator(bus, cpu).startCic6102(validRom('E'))

        assertEquals(N64PifBootSequence.CicFamily.CIC_6102, handoff.cic)
        assertEquals(0x3FL, cpu.register(22))
        assertTrue(handoff.entryPoint == cpu.pc)
    }

    private fun validRom(country: Char): ByteArray = ByteArray(0x1000).also { rom ->
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
        rom[0x3E] = country.code.toByte()
        for (offset in 0x40 until rom.size) rom[offset] = (offset and 0xFF).toByte()
    }
}
