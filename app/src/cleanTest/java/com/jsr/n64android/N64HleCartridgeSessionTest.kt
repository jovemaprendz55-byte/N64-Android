package com.jsr.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class N64HleCartridgeSessionTest {
    private fun cartridge(): N64HleCartridgeImage {
        val bytes = ByteArray(0x80).also {
            it[0] = 0x80.toByte()
            it[1] = 0x37
            it[2] = 0x12
            it[3] = 0x40
            it[8] = 0x80.toByte()
            it[10] = 0x04
            "SESSION TEST".toByteArray(Charsets.US_ASCII).copyInto(it, 0x20)
            for (index in 0x40 until it.size) it[index] = index.toByte()
        }
        return N64HleCartridgeImage.parse(bytes)
    }

    @Test
    fun retainsValidatedCartridgeAndReadsRequestedRomRange() {
        val image = cartridge()
        val session = N64HleCartridgeSession.fromValidatedImage(image)

        assertEquals(image.sizeBytes, session.romSizeBytes)
        assertEquals(image.title, session.cartridge.title)
        assertArrayEquals(image.readBytes(0x40, 8), session.readRomBytes(0x40, 8))
    }

    @Test
    fun returnedRomBytesAreDefensiveCopies() {
        val session = N64HleCartridgeSession.fromValidatedImage(cartridge())
        val first = session.readRomBytes(0x40, 4)
        val expected = first.copyOf()

        first[0] = (first[0].toInt() xor 0xFF).toByte()

        assertArrayEquals(expected, session.readRomBytes(0x40, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReadsOutsideCartridge() {
        val session = N64HleCartridgeSession.fromValidatedImage(cartridge())
        session.readRomBytes(0x7F, 2)
    }
    @Test
    fun preparesOnlyTheExplicitHleHandoffProfile() {
        val session = N64HleCartridgeSession.fromValidatedImage(cartridge())
        val profile = N64HleBootProfile(
            entryPc = 0x80000400L,
            registerValues = mapOf(29 to 0x80001000L),
            romCopies = listOf(N64HleBootProfile.RomCopy(0x40, 0x80000400L, 8)),
            memoryWrites = listOf(
                N64HleBootProfile.MemoryWrite(0x80000010L, 4, 0x12345678L)
            ),
            evidence = "synthetic unit-test fixture"
        )

        val handoff = session.prepareHleHandoff(profile)

        assertEquals(0x80000400L, handoff.cpuEntryState.programCounter)
        assertEquals(0x80001000L, handoff.cpuEntryState.copyRegisters()[29])
        assertEquals(0x40414243L, handoff.guestMemory.read32(0x80000400L))
        assertEquals(0x12345678L, handoff.guestMemory.read32(0x80000010L))
        assertEquals(false, handoff.cpuEntryState.cop0State.isKnown(12))
    }

    @Test
    fun failedHandoffPreparationDoesNotExposePartialGuestMemory() {
        val session = N64HleCartridgeSession.fromValidatedImage(cartridge())
        val invalidProfile = N64HleBootProfile(
            entryPc = 0x80000400L,
            registerValues = emptyMap(),
            romCopies = listOf(N64HleBootProfile.RomCopy(0x7F, 0x80000400L, 4)),
            memoryWrites = emptyList(),
            evidence = "invalid synthetic source range"
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            session.prepareHleHandoff(invalidProfile)
        }
    }

}
