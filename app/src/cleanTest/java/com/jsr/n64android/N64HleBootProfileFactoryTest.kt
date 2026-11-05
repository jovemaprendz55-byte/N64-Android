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
        val checksum = checksum6102(rom.copyOfRange(0x1000, 0x101000))
        putWord(rom, 0x10, checksum.first)
        putWord(rom, 0x14, checksum.second)
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

    private fun checksum6102(image: ByteArray): kotlin.Pair<Int, Int> {
        val mask = 0xFFFF_FFFFL
        val initial = (0x3FL * 0x5D588B65L + 1L) and mask
        var a3 = initial; var t2 = initial; var t3 = initial
        var s0 = initial; var a2 = initial; var t4 = initial
        var offset = 0
        while (offset < image.size) {
            val word = ((image[offset].toLong() and 0xFFL) shl 24) or
                ((image[offset + 1].toLong() and 0xFFL) shl 16) or
                ((image[offset + 2].toLong() and 0xFFL) shl 8) or
                (image[offset + 3].toLong() and 0xFFL)
            val sum = (a3 + word) and mask
            if (sum < a3) t2 = (t2 + 1L) and mask
            a3 = sum
            val rotate = Integer.rotateLeft(word.toInt(), (word and 0x1FL).toInt()).toLong() and mask
            t3 = (t3 xor word) and mask
            s0 = (s0 + rotate) and mask
            val mixed = if (a2 < word) (a3 xor word) else rotate
            a2 = (a2 xor mixed) and mask
            t4 = (t4 + (word xor s0)) and mask
            offset += 4
        }
        return kotlin.Pair(((a3 xor t2) xor t3).toInt(), ((s0 xor a2) xor t4).toInt())
    }
}
