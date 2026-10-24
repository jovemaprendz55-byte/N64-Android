package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300TlbExceptionTest {
    @Test fun tlbModificationInBranchDelaySlotSetsCauseBdAndBranchEpc() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = virtual address 0x40000000
            iType(0x09, 0, 2, 0x005A), // valid, clean TLB page
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (10 shl 11), // MTC0 EntryHi
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (2 shl 11), // MTC0 EntryLo0
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (3 shl 11), // MTC0 EntryLo1
            0x42000002, // TLBWI
            (0x02 shl 26) or 8, // J 0x20
            iType(0x2B, 1, 2, 0) // SW in delay slot raises MOD
        ))

        repeat(8) { cpu.step() }

        val cause = cpu.readCp0ForTest(13)
        assertEquals(1L, (cause ushr 2) and 0x1FL) // MOD
        assertEquals(1L, (cause ushr 31) and 1L) // Cause.BD
        assertEquals(24L, cpu.readCp0ForTest(14)) // EPC points to the branch
        assertEquals(0x40000000L, cpu.readCp0ForTest(8)) // BadVAddr is faulting store address
        assertEquals(0xFFFF_FFFF_8000_0180UL.toLong(), cpu.pc) // general exception vector
    }

    @Test fun storeToCleanTlbPageRaisesModificationException() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = virtual address 0x40000000
            iType(0x09, 0, 2, 0x005A), // PFN 1, cacheable, V=1, D=0
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (10 shl 11), // MTC0 EntryHi
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (2 shl 11), // MTC0 EntryLo0
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (3 shl 11), // MTC0 EntryLo1
            0x42000002, // TLBWI
            iType(0x2B, 1, 2, 0) // SW to valid but read-only page
        ))

        repeat(7) { cpu.step() }

        assertEquals(1L, (cpu.readCp0ForTest(13) ushr 2) and 0x1FL) // MOD
        assertEquals(0x40000000L, cpu.readCp0ForTest(8)) // BadVAddr
        assertEquals(24L, cpu.readCp0ForTest(14)) // faulting SW
        assertEquals(0xFFFF_FFFF_8000_0180UL.toLong(), cpu.pc) // modification uses general vector
        assertEquals(0x2L, cpu.readCp0ForTest(12) and 0x2L) // Status.EXL
    }

    @Test fun invalidStorePageRaisesTlbsAndUsesGeneralTlbExceptionVector() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = virtual address 0x40000000
            iType(0x09, 0, 2, 0x0058), // PFN 1, cacheable, V=0, D=0
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (10 shl 11), // MTC0 EntryHi
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (2 shl 11), // MTC0 EntryLo0
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (3 shl 11), // MTC0 EntryLo1
            0x42000002, // TLBWI
            iType(0x2B, 1, 2, 0) // SW to an invalid page
        ))

        repeat(7) { cpu.step() }

        assertEquals(3L, (cpu.readCp0ForTest(13) ushr 2) and 0x1FL) // TLBS
        assertEquals(0x40000000L, cpu.readCp0ForTest(8)) // BadVAddr
        assertEquals(24L, cpu.readCp0ForTest(14)) // faulting SW
        assertEquals(0xFFFF_FFFF_8000_0180UL.toLong(), cpu.pc) // invalid is not a refill miss
        assertEquals(0x2L, cpu.readCp0ForTest(12) and 0x2L) // Status.EXL
    }

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

    @Test fun loadTlbMissRaisesTlblAndUsesRefillVector() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = unmapped virtual address 0x40000000
            iType(0x23, 1, 2, 0)       // LW must raise TLBL on TLB miss
        ))

        cpu.step()
        cpu.step()

        assertEquals(2L, (cpu.readCp0ForTest(13) ushr 2) and 0x1FL) // TLBL
        assertEquals(0x40000000L, cpu.readCp0ForTest(8)) // BadVAddr
        assertEquals(4L, cpu.readCp0ForTest(14)) // EPC is the faulting load
        assertEquals(0xFFFF_FFFF_8000_0000UL.toLong(), cpu.pc) // TLB refill vector
        assertEquals(0x2L, cpu.readCp0ForTest(12) and 0x2L) // Status.EXL
    }

    @Test fun storeTlbMissRaisesTlbsAndUsesRefillVector() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = unmapped virtual address 0x40000000
            iType(0x2B, 1, 2, 0)       // SW must raise TLBS on TLB miss
        ))

        cpu.step()
        cpu.step()

        assertEquals(3L, (cpu.readCp0ForTest(13) ushr 2) and 0x1FL) // TLBS
        assertEquals(0x40000000L, cpu.readCp0ForTest(8)) // BadVAddr
        assertEquals(4L, cpu.readCp0ForTest(14)) // EPC is the faulting store
        assertEquals(0xFFFF_FFFF_8000_0000UL.toLong(), cpu.pc) // TLB refill vector
        assertEquals(0x2L, cpu.readCp0ForTest(12) and 0x2L) // Status.EXL
    }

    @Test fun instructionFetchTlbMissUsesRefillVectorAndRecordsFaultingPc() {
        val cpu = R4300Cpu()
        cpu.resetExecutionState(0x40000000L)

        cpu.step()

        assertEquals(2L, (cpu.readCp0ForTest(13) ushr 2) and 0x1FL) // TLBL
        assertEquals(0x40000000L, cpu.readCp0ForTest(8)) // BadVAddr
        assertEquals(0x40000000L, cpu.readCp0ForTest(14)) // EPC is fetch PC
        assertEquals(0xFFFF_FFFF_8000_0000UL.toLong(), cpu.pc) // refill vector
        assertEquals(0x2L, cpu.readCp0ForTest(12) and 0x2L) // Status.EXL
    }


    @Test fun tlbMissInBranchDelaySlotSetsCauseBdAndBranchEpc() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = unmapped virtual address 0x40000000
            (0x02 shl 26) or 8,         // J 0x20; next instruction is its delay slot
            iType(0x23, 1, 2, 0),       // LW in delay slot raises TLBL
            0,                         // branch target delay-independent placeholder
            0, 0, 0, 0
        ))

        cpu.step() // LUI
        cpu.step() // J
        cpu.step() // delay-slot LW faults

        val cause = cpu.readCp0ForTest(13)
        assertEquals(2L, (cause ushr 2) and 0x1FL) // TLBL
        assertEquals(1L, (cause ushr 31) and 1L) // Cause.BD
        assertEquals(4L, cpu.readCp0ForTest(14)) // EPC points to branch, not delay slot
        assertEquals(0x40000000L, cpu.readCp0ForTest(8)) // BadVAddr is faulting load address
        assertEquals(0xFFFF_FFFF_8000_0000UL.toLong(), cpu.pc) // TLB refill vector
    }

    @Test fun tlbpFindsMatchingEntryAndTlbrRestoresEntryRegisters() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0x4000), // r1 = 0x40000000
            iType(0x0D, 1, 1, 3),      // r1 = EntryHi with ASID 3
            iType(0x09, 0, 2, 0x005A), // valid even page
            iType(0x09, 0, 3, 0x0062), // valid odd page
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (10 shl 11), // MTC0 EntryHi
            (0x10 shl 26) or (0x04 shl 21) or (2 shl 16) or (2 shl 11),  // MTC0 EntryLo0
            (0x10 shl 26) or (0x04 shl 21) or (3 shl 16) or (3 shl 11),  // MTC0 EntryLo1
            0x42000002, // TLBWI at Index 0
            iType(0x0F, 0, 4, 0x5000), // r4 = 0x50000000
            iType(0x0D, 4, 4, 4),      // different VPN2 and ASID
            (0x10 shl 26) or (0x04 shl 21) or (4 shl 16) or (10 shl 11), // MTC0 EntryHi
            0x42000008, // TLBP must miss
            (0x10 shl 26) or (0x04 shl 21) or (1 shl 16) or (10 shl 11), // restore matching EntryHi
            0x42000008, // TLBP must find index 0
            (0x10 shl 26) or 0x00000001 // TLBR
        ))

        repeat(15) { cpu.step() }

        assertEquals(0L, cpu.readCp0ForTest(0)) // Index = matching slot 0
        assertEquals(0x40000003L, cpu.readCp0ForTest(10)) // TLBR restored EntryHi + ASID
        assertEquals(0x5AL, cpu.readCp0ForTest(2)) // TLBR restored EntryLo0
        assertEquals(0x62L, cpu.readCp0ForTest(3)) // TLBR restored EntryLo1
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
