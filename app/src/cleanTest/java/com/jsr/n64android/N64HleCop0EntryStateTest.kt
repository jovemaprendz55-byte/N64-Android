package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleCop0EntryStateTest {
    @Test
    fun unspecifiedRegistersRemainUnknownInsteadOfReceivingDefaults() {
        val state = N64HleCop0EntryState.unspecified()

        assertFalse(state.isKnown(12))
        assertNull(state.valueOrNull(12))
        assertThrows(IllegalStateException::class.java) {
            state.requireValue(12)
        }
        assertTrue(state.copyRegisterValues().isEmpty())
    }

    @Test
    fun preservesOnlyExplicitlySuppliedValues() {
        val state = N64HleCop0EntryState(
            mapOf(12 to 0x34000000L, 14 to 0xFFFFFFFF80001234L),
            "synthetic fixture: values supplied by test"
        )

        assertTrue(state.isKnown(12))
        assertEquals(0x34000000L, state.requireValue(12))
        assertEquals(0xFFFFFFFF80001234L, state.valueOrNull(14))
        assertFalse(state.isKnown(13))
    }

    @Test
    fun rejectsInvalidRegisterIndicesAndMissingEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            N64HleCop0EntryState(mapOf(32 to 1L), "test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleCop0EntryState(mapOf(-1 to 1L), "test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleCop0EntryState(mapOf(12 to 1L), " ")
        }
    }

    @Test
    fun returnedMapCannotMutateState() {
        val state = N64HleCop0EntryState(mapOf(12 to 0x1234L), "test")
        val copy = state.copyRegisterValues().toMutableMap()
        copy[12] = 0x9999L
        copy[13] = 0x8888L

        assertEquals(0x1234L, state.requireValue(12))
        assertFalse(state.isKnown(13))
    }
}
