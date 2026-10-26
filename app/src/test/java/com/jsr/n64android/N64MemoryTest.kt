package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64MemoryTest {
    @Test fun readsAndWritesBigEndianWords() {
        val memory = N64Memory(16)
        memory.write32(4, 0x81234567.toInt())
        assertEquals(0x81, memory.read8(4))
        assertEquals(0x8123, memory.read16(4))
        assertEquals(0x81234567.toInt(), memory.read32(4))
    }

    @Test fun clearResetsMemoryToZero() {
        val memory = N64Memory(8)
        memory.write32(0, -1)
        memory.clear()
        assertEquals(0, memory.read32(0))
    }

    @Test fun rejectsOutOfRangeAccess() {
        val memory = N64Memory(4)
        assertThrows(IllegalArgumentException::class.java) { memory.read8(4) }
        assertThrows(IllegalArgumentException::class.java) { memory.write16(3, 0x1234) }
    }
}
