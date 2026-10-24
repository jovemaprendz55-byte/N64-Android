package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300TlbExceptionTest {
    @Test fun invalidLoadPageUsesGeneralTlbExceptionVector() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = virtual address 0x40000000
            iType(0x09, 0, 2, 0x0058), // PFN 1, cacheable, V=0, D=0
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (10 shl 11), // MTC0 EntryHi
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (2 shl 11), // MTC0 EntryLo0
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (3 shl 11), // MTC0 EntryLo1
            0x42000002, // TLBWI
            iType(0x23, 1, 3, 0) // LW from an invalid page
        ))

        repeat(7) { cpu.step() }

        assertEquals(2L, (cpu.readCp0ForTest(13) ushr 2) and 0x1FL) // TLBL
        assertEquals(0x40000000L, cpu.readCp0ForTest(8)) // BadVAddr
        assertEquals(24L, cpu.readCp0ForTest(14)) // faulting LW
        assertEquals(0xFFFF_FFFF_8000_0180UL.toLong(), cpu.pc) // invalid is not a refill miss
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun words(vararg instructions: Int): ByteArray {
        val bytes = ByteArray(instructions.size * 4)
        instructions.forEachIndexed { index, instruction ->
            val offset = index * 4
            bytes[offset] = (instruction ushr 24).toByte()
            bytes[offset + 1] = (instruction ushr 16).toByte()
            bytes[offset + 2] = (instruction ushr 8).toByte()
            bytes[offset + 3] = instruction.toByte()
        }
        return bytes
    }
}
