package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for branch delay slots and jump link semantics. */
class R4300CpuBranchDelaySlotTest {

    @Test
    fun takenBeqExecutesDelaySlotBeforeApplyingTarget() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x04, 0, 0, 2),       // BEQ r0, r0, +2 -> address 12
            iType(0x09, 0, 2, 5),       // delay slot: ADDIU r2, r0, 5
            iType(0x09, 0, 3, 9),       // should be skipped
            iType(0x09, 0, 4, 7)        // branch target
        ))

        cpu.step()
        assertEquals(4L, cpu.pc)
        cpu.step()
        assertEquals(12L, cpu.pc)
        cpu.step()

        assertEquals(5L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(7L, cpu.register(4))
    }

    @Test
    fun untakenBneStillExecutesItsDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x05, 0, 0, 2),       // BNE r0, r0, +2: not taken
            iType(0x09, 0, 2, 5),       // delay slot must execute
            iType(0x09, 0, 3, 9)
        ))

        repeat(2) { cpu.step() }

        assertEquals(8L, cpu.pc)
        assertEquals(5L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
    }

    @Test
    fun jalWritesReturnAddressAndExecutesDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            jType(0x03, 2),             // JAL address 8
            iType(0x09, 0, 2, 5),       // delay slot
            iType(0x09, 0, 3, 9)        // target
        ))

        cpu.step()
        assertEquals(8L, cpu.register(31))
        cpu.step()
        assertEquals(8L, cpu.pc)
        cpu.step()

        assertEquals(5L, cpu.register(2))
        assertEquals(9L, cpu.register(3))
    }

    @Test
    fun notTakenBeqlAnnulsDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 1),      // r1 = 1
            iType(0x14, 1, 0, 2),      // BEQL r1, r0, +2: not taken
            iType(0x09, 0, 2, 5),      // annulled delay slot
            iType(0x09, 0, 3, 9)       // next instruction
        ))

        repeat(3) { cpu.step() }

        assertEquals(0L, cpu.register(2))
        assertEquals(9L, cpu.register(3))
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun jType(op: Int, targetWordAddress: Int): Int =
        (op shl 26) or (targetWordAddress and 0x03FF_FFFF)

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
