package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleR4300CpuStateTest {
    @Test
    fun gprZeroIsAlwaysKnownZeroAndCannotBeChanged() {
        val state = N64HleR4300CpuState(0x80000400L)

        assertTrue(state.isGprKnown(0))
        assertEquals(0L, state.readGpr(0))
        state.writeGpr(0, 0x12345678L)
        assertEquals(0L, state.readGpr(0))
    }

    @Test
    fun unspecifiedRegistersStayUnknownUntilWritten() {
        val state = N64HleR4300CpuState(0x80000400L)

        assertFalse(state.isGprKnown(8))
        assertThrows(IllegalStateException::class.java) { state.readGpr(8) }

        state.writeGpr(8, 0x80001234L.toInt().toLong())
        assertTrue(state.isGprKnown(8))
        assertEquals(0x80001234L.toInt().toLong(), state.readGpr(8))
    }

    @Test
    fun constructorCopiesOnlyExplicitInitialRegisters() {
        val initial = mutableMapOf(4 to 0x1234L)
        val state = N64HleR4300CpuState(0x80000400L, initial)
        initial[4] = 0x9999L
        initial[5] = 0x8888L

        assertEquals(0x1234L, state.readGpr(4))
        assertFalse(state.isGprKnown(5))
    }

    @Test
    fun knownRegisterSnapshotCannotMutateCpuState() {
        val state = N64HleR4300CpuState(0x80000400L, mapOf(4 to 0x1234L))
        val snapshot = state.copyKnownGprs().toMutableMap()
        snapshot[4] = 0x9999L
        snapshot[5] = 0x8888L

        assertEquals(0x1234L, state.readGpr(4))
        assertFalse(state.isGprKnown(5))
    }

    @Test
    fun hiAndLoRemainUnknownUntilExplicitlyWritten() {
        val state = N64HleR4300CpuState(0x80000400L)

        assertFalse(state.isHiKnown())
        assertFalse(state.isLoKnown())
        assertThrows(IllegalStateException::class.java) { state.readHi() }
        assertThrows(IllegalStateException::class.java) { state.readLo() }

        state.writeHiLo(0x1122334455667788L, 0xFFEEDDCCBBAA0099UL.toLong())
        assertEquals(0x1122334455667788L, state.readHi())
        assertEquals(0xFFEEDDCCBBAA0099UL.toLong(), state.readLo())
    }

    @Test
    fun programCounterCanRepresentUnalignedFetchAddress() {
        val state = N64HleR4300CpuState(0x80000402L)
        assertEquals(0x80000402L, state.programCounter)

        state.setProgramCounter(0x80000404L)
        assertEquals(0x80000404L, state.programCounter)
    }

    @Test
    fun rejectsInvalidRegisterIndices() {
        val state = N64HleR4300CpuState(0x80000400L)
        assertThrows(IllegalArgumentException::class.java) { state.readGpr(32) }
        assertThrows(IllegalArgumentException::class.java) { state.writeGpr(-1, 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleR4300CpuState(0x80000400L, mapOf(0 to 1L))
        }
    }

    @Test
    fun preservesCop0SnapshotWithoutInventingValues() {
        val cop0 = N64HleCop0EntryState(mapOf(12 to 0x34000000L), "synthetic CPU fixture")
        val state = N64HleR4300CpuState(0x80000400L, cop0 = cop0)

        assertEquals(0x34000000L, state.cop0.requireValue(12))
        assertFalse(state.cop0.isKnown(13))
    }
}
