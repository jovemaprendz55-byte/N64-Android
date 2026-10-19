package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64SpMemoryTest {
    @Test fun readsAndWritesBigEndianWordsInDmem() {
        val sp = N64SpMemory()
        sp.writeWord(N64SpMemory.DMEM_BASE, 0x12345678)
        assertEquals(0x12.toByte(), sp.readByte(N64SpMemory.DMEM_BASE))
        assertEquals(0x12345678, sp.readWord(N64SpMemory.DMEM_BASE))
    }

    @Test fun dmemAndImemAreSeparateStorage() {
        val sp = N64SpMemory()
        sp.writeWord(N64SpMemory.DMEM_BASE, 0x11223344)
        sp.writeWord(N64SpMemory.IMEM_BASE, 0xAABBCCDD.toInt())
        assertEquals(0x11223344, sp.readWord(N64SpMemory.DMEM_BASE))
        assertEquals(0xAABBCCDD.toInt(), sp.readWord(N64SpMemory.IMEM_BASE))
    }

    @Test fun allowsLastAlignedWordInEachMemory() {
        val sp = N64SpMemory()
        val lastDmemWord = N64SpMemory.DMEM_BASE + N64SpMemory.MEMORY_SIZE - 4
        val lastImemWord = N64SpMemory.IMEM_BASE + N64SpMemory.MEMORY_SIZE - 4
        sp.writeWord(lastDmemWord, 0xCAFEBABE.toInt())
        sp.writeWord(lastImemWord, 0xDEADBEEF.toInt())
        assertEquals(0xCAFEBABE.toInt(), sp.readWord(lastDmemWord))
        assertEquals(0xDEADBEEF.toInt(), sp.readWord(lastImemWord))
    }

    @Test fun rejectsUnalignedWords() {
        val sp = N64SpMemory()
        assertThrows(IllegalArgumentException::class.java) {
            sp.readWord(N64SpMemory.DMEM_BASE + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sp.writeWord(N64SpMemory.IMEM_BASE + 2, 0)
        }
    }

    @Test fun rejectsAddressesOutsideSpMemories() {
        val sp = N64SpMemory()
        assertThrows(IllegalArgumentException::class.java) { sp.readByte(0x04002000L) }
        assertThrows(IllegalArgumentException::class.java) { sp.writeByte(0x03FFFFFFL, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            sp.readWord(N64SpMemory.DMEM_BASE + N64SpMemory.MEMORY_SIZE)
        }
    }
}
