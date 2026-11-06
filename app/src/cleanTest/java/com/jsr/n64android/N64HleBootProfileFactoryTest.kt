package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleBootProfileFactoryTest {
    private fun validCic6102Cartridge(): N64HleCartridgeImage {
        val rom = ByteArray(0x1000 + 0x100000)
        rom[0] = 0x80.toByte()
        rom[1] = 0x37
        rom[2] = 0x12
        rom[3] = 0x40
        putWord(rom, 0x08, 0x80000400.toInt())
        rom[0x3E] = 'E'.code.toByte()

        // Synthetic IPL3 checksum accepted by the project's conservative detector.
        for (word in 0 until 208) putWord(rom, 0x40 + word * 4, 0xFFFFFFFF.toInt())
        putWord(rom, 0x40 + 208 * 4, 0x57C85314)

        for (i in 0 until 0x100000) {
            rom[0x1000 + i] = ((i * 13 + 7) and 0xFF).toByte()
        }
        // Fixed vector generated with the independent n64dev.org reference algorithm.
        putWord(rom, 0x10, 0xF6CC53D6.toInt())
        putWord(rom, 0x14, 0x5198A84B)
        return N64HleCartridgeImage.parse(rom)
    }

    @Test
    fun createsValidatedCic6102PostIpl3ProfileAndPreparesHandoff() {
        val cartridge = validCic6102Cartridge()
        val profile = N64HleBootProfileFactory.createCic6102Profile(cartridge)

        assertEquals(0x80000400L, profile.entryPc)
        assertEquals(0x3FL, profile.registerValues[22])
        assertEquals(0xFFFFFFFFA4001FF0UL.toLong(), profile.registerValues[29])
        assertEquals(0x34000000L, profile.cop0RegisterValues[12])
        assertEquals(0x0005E463L, profile.cop0RegisterValues[16])
        assertEquals(1, profile.romCopies.size)
        assertEquals(0x1000, profile.romCopies[0].romOffset)
        assertEquals(0x100000, profile.romCopies[0].length)

        val session = N64HleCartridgeSession.fromValidatedImage(cartridge)
        val handoff = session.prepareHleHandoff(profile)
        assertEquals(0x80000400L, handoff.cpuEntryState.programCounter)
        assertEquals(0x0714212EL, handoff.guestMemory.read32(0x80000400L))
        assertEquals(1L, handoff.guestMemory.read32(0x80000300L)) // NTSC
        assertEquals(0xB0000000L, handoff.guestMemory.read32(0x80000308L))
        assertEquals(0L, handoff.guestMemory.read32(0x80000310L)) // osCicId != CIC seed
        assertEquals(0x3FL, handoff.cpuEntryState.copyRegisters()[22])
    }

    @Test
    fun acceptsIndependentCic6102VectorWhenAccumulatorEqualsCurrentWord() {
        val rom = validCic6102Cartridge().copyCanonicalBytes()
        for (i in 0 until 0x100000) rom[0x1000 + i] = 0
        // First d equals the initial t2 seed (0xF8CA4DDC), exercising t2 == d.
        putWord(rom, 0x1000, 0xF8CA4DDC.toInt())
        // Fixed CRC1/CRC2 calculated with the independent n64dev.org reference.
        putWord(rom, 0x10, 0x095ED665)
        putWord(rom, 0x14, 0x12367389)

        val cartridge = N64HleCartridgeImage.parse(rom)
        val profile = N64HleBootProfileFactory.createCic6102Profile(cartridge)
        assertEquals(0x80000400L, profile.entryPc)
    }

    @Test
    fun rejectsGameImageChecksumMismatch() {
        val rom = validCic6102Cartridge().copyCanonicalBytes()
        rom[0x1000] = (rom[0x1000].toInt() xor 1).toByte()
        val changed = N64HleCartridgeImage.parse(rom)
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfileFactory.createCic6102Profile(changed)
        }
    }

    @Test
    fun rejectsUnknownCicAndInvalidEntryPoint() {
        val rom = validCic6102Cartridge().copyCanonicalBytes()
        rom[0x40] = (rom[0x40].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfileFactory.createCic6102Profile(N64HleCartridgeImage.parse(rom))
        }

        val invalidEntry = validCic6102Cartridge().copyCanonicalBytes()
        putWord(invalidEntry, 0x08, 0xA0000400.toInt())
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfileFactory.createCic6102Profile(N64HleCartridgeImage.parse(invalidEntry))
        }
    }

    private fun putWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }


}
