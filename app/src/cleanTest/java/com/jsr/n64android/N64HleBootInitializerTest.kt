package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleBootInitializerTest {
    private fun cartridge(): N64HleCartridgeImage {
        val bytes = ByteArray(0x80)
        bytes[0] = 0x80.toByte()
        bytes[1] = 0x37
        bytes[2] = 0x12
        bytes[3] = 0x40
        for (i in 0x40 until bytes.size) bytes[i] = i.toByte()
        return N64HleCartridgeImage.parse(bytes)
    }

    @Test
    fun appliesOnlyExplicitProfileCopiesAndRegisterValues() {
        val memory = N64HleGuestAddressSpace()
        val profile = N64HleBootProfile(
            entryPc = 0x80000400L,
            registerValues = mapOf(4 to 0x1234L, 29 to 0x80001000L, 31 to 0x80002000L),
            romCopies = listOf(N64HleBootProfile.RomCopy(0x40, 0x80000400L, 0x20)),
            memoryWrites = listOf(
                N64HleBootProfile.MemoryWrite(0x80000010L, 4, 0x3FL)
            ),
            evidence = "test fixture: explicit synthetic profile"
        )

        val state = N64HleBootInitializer().initialize(cartridge(), memory, profile)

        assertEquals(0x80000400L, state.programCounter)
        assertEquals(0x1234L, state.copyRegisters()[4])
        assertEquals(0x80001000L, state.copyRegisters()[29])
        assertEquals(0L, state.copyRegisters()[0])
        assertEquals(0x3FL, memory.read32(0x80000010L))
        assertEquals(0x40414243L, memory.read32(0x80000400L))
    }

    @Test
    fun rejectsInvalidProfileBeforeTouchingMemory() {
        val memory = N64HleGuestAddressSpace()
        val profile = N64HleBootProfile(
            entryPc = 0x80000400L,
            registerValues = emptyMap(),
            romCopies = listOf(N64HleBootProfile.RomCopy(0x7F, 0x80000400L, 4)),
            memoryWrites = emptyList(),
            evidence = "synthetic invalid range"
        )

        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootInitializer().initialize(cartridge(), memory, profile)
        }
        assertEquals(0L, memory.read32(0x80000400L))
    }

    @Test
    fun rejectsUnknownOrUnalignedEntryAndImmutableZeroRegister() {
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfile(0x80000402L, emptyMap(), emptyList(), emptyList(), "test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfile(0xA0000400L, emptyMap(), emptyList(), emptyList(), "test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfile(0x80000400L, mapOf(0 to 99L), emptyList(), emptyList(), "test")
        }
    }

    @Test
    fun rejectsInvalidMemoryWriteWidthsAndValues() {
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfile(
                0x80000400L, emptyMap(), emptyList(),
                listOf(N64HleBootProfile.MemoryWrite(0x80000000L, 3, 1L)), "test"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfile(
                0x80000400L, emptyMap(), emptyList(),
                listOf(N64HleBootProfile.MemoryWrite(0x80000000L, 1, 256L)), "test"
            )
        }
    }

    @Test
    fun failedPreflightDoesNotPartiallyCopyEarlierRomRanges() {
        val memory = N64HleGuestAddressSpace()
        val profile = N64HleBootProfile(
            0x80000400L,
            emptyMap(),
            listOf(
                N64HleBootProfile.RomCopy(0x40, 0x80000400L, 4),
                N64HleBootProfile.RomCopy(0x7F, 0x80000500L, 4)
            ),
            emptyList(),
            "synthetic invalid second range"
        )
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootInitializer().initialize(cartridge(), memory, profile)
        }
        assertEquals(0L, memory.read32(0x80000400L))
    }
    @Test
    fun bootHandoffCarriesOnlyEvidenceBackedCop0Values() {
        val profile = N64HleBootProfile(
            entryPc = 0x80000400L,
            registerValues = emptyMap(),
            romCopies = emptyList(),
            memoryWrites = emptyList(),
            evidence = "synthetic GPR handoff",
            cop0RegisterValues = mapOf(12 to 0x34000000L, 14 to 0x80001234L),
            cop0Evidence = "synthetic COP0 fixture"
        )
        val state = N64HleBootInitializer().initialize(
            cartridge(),
            N64HleGuestAddressSpace(),
            profile
        )

        assertEquals(0x34000000L, state.cop0State.requireValue(12))
        assertEquals(0x80001234L, state.cop0State.requireValue(14))
        assertEquals(null, state.cop0State.valueOrNull(13))
    }

    @Test
    fun bootHandoffDoesNotInventCop0ValuesWhenProfileOmitsThem() {
        val profile = N64HleBootProfile(
            entryPc = 0x80000400L,
            registerValues = emptyMap(),
            romCopies = emptyList(),
            memoryWrites = emptyList(),
            evidence = "synthetic GPR-only handoff"
        )
        val state = N64HleBootInitializer().initialize(
            cartridge(),
            N64HleGuestAddressSpace(),
            profile
        )

        assertEquals(false, state.cop0State.isKnown(12))
        assertEquals(null, state.cop0State.valueOrNull(12))
    }

    @Test
    fun explicitCop0ValuesRequireEvidenceAndValidIndices() {
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfile(
                0x80000400L, emptyMap(), emptyList(), emptyList(), "test",
                cop0RegisterValues = mapOf(12 to 1L)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleBootProfile(
                0x80000400L, emptyMap(), emptyList(), emptyList(), "test",
                cop0RegisterValues = mapOf(32 to 1L),
                cop0Evidence = "test evidence"
            )
        }
    }

}
