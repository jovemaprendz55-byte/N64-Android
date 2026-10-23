package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64PifBootSequenceTest {
    @Test
    fun cic6105PalHandoffLoadsIpl3AndSeedsCpu() {
        val bus = N64MemoryBus()
        val cpu = R4300Cpu(bus)
        val sequence = N64PifBootSequence(bus, cpu)
        val rom = validRom('P')

        val result = sequence.startCic6105(rom)

        assertEquals(N64PifBootSequence.CicFamily.CIC_6105, result.cic)
        assertEquals(N64BootSequence.Region.PAL, result.region)
        assertEquals(0xFC0, result.ipl3BytesLoaded)
        assertEquals(0xFFFF_FFFF_A400_0040UL.toLong(), result.entryPoint)
        assertEquals(result.entryPoint, cpu.pc)
        assertEquals(0L, cpu.register(20))
        assertEquals(0x3C0D_BFC0, bus.readWord(0x0400_1000L))
        assertEquals(rom[0x40], bus.readByte(0x0400_0040L))
    }

    @Test
    fun cic6102HandoffUsesItsOwnChecksumSeedAndDoesNotInstall6105Stub() {
        val bus = N64MemoryBus()
        val cpu = R4300Cpu(bus)
        // A new boot must not inherit volatile PIF RAM from the previous session.
        bus.writeByte(N64PifRam.PIF_RAM_BASE, 0x7F)

        val result = N64PifBootSequence(bus, cpu).startCic6102(validRom('E'))

        assertEquals(N64PifBootSequence.CicFamily.CIC_6102, result.cic)
        assertEquals(0x3FL, cpu.register(22))
        assertEquals(0xFFFF_FFFF_A400_0040UL.toLong(), cpu.pc)
        assertEquals(0, bus.readByte(N64PifRam.PIF_RAM_BASE).toInt())
        // The x105 IPL2 IMEM stub is specific to CIC-6105.
        assertEquals(0, bus.readWord(N64SpMemory.IMEM_BASE))
    }

    @Test
    fun cic6105NtscHandoffUsesNtscSeed() {
        val bus = N64MemoryBus()
        val cpu = R4300Cpu(bus)

        val result = N64PifBootSequence(bus, cpu).startCic6105(validRom('E'))

        assertEquals(N64BootSequence.Region.NTSC, result.region)
        assertEquals(1L, cpu.register(20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCountryIsRejectedInsteadOfGuessingRegionSeed() {
        val bus = N64MemoryBus()
        N64PifBootSequence(bus, R4300Cpu(bus)).startCic6105(validRom('Z'))
    }

    private fun validRom(country: Char): ByteArray = ByteArray(0x1000).also { rom ->
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
        rom[0x3E] = country.code.toByte()
        for (offset in 0x40 until 0x1000) rom[offset] = (offset and 0xFF).toByte()
    }
}
