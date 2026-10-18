package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300CpuTest {
    @Test fun executesImmediateArithmeticAndKeepsZeroRegisterConstant() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x09, 0, 1, 10), iType(0x0D, 1, 2, 0x20), iType(0x09, 0, 0, 99)))
        repeat(3) { cpu.step() }
        assertEquals(10L, cpu.register(1))
        assertEquals(42L, cpu.register(2))
        assertEquals(0L, cpu.register(0))
        assertEquals(12L, cpu.pc)
    }

    @Test fun executesLuiAndBigEndianLoadStore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x0F, 0, 1, 0x1234), iType(0x2B, 0, 1, 0x100), iType(0x23, 0, 2, 0x100)))
        repeat(3) { cpu.step() }
        assertEquals(0x12340000L, cpu.register(1))
        assertEquals(0x12340000L, cpu.register(2))
        assertEquals(0x12340000, cpu.readWord(0x100))
    }

    @Test fun branchAppliesAfterOneDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x09, 0, 1, 1), iType(0x04, 1, 1, 2), iType(0x09, 0, 2, 7), iType(0x09, 0, 3, 99), iType(0x09, 0, 4, 5)))
        cpu.step(); cpu.step()
        assertEquals(8L, cpu.pc)
        cpu.step()
        assertEquals(0x10L, cpu.pc)
        cpu.step()
        assertEquals(7L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(5L, cpu.register(4))
    }

    @Test fun supportsSignedAndUnsignedByteAndHalfwordLoads() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x20, 0, 1, 0x100), iType(0x24, 0, 2, 0x100), iType(0x21, 0, 3, 0x102), iType(0x25, 0, 4, 0x102)))
        cpu.loadBytes(0x100, byteArrayOf(0x80.toByte(), 0x00, 0xFF.toByte(), 0xFE.toByte()))
        repeat(4) { cpu.step() }
        assertEquals(-128L, cpu.register(1))
        assertEquals(128L, cpu.register(2))
        assertEquals(-2L, cpu.register(3))
        assertEquals(65534L, cpu.register(4))
    }

    @Test fun supportsDoublewordArithmeticShiftsAndLoadStore() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x19, 0, 1, 0x1234), iType(0x3F, 0, 1, 0x108), iType(0x37, 0, 2, 0x108), rType(0, 2, 3, 1, 0x38)))
        repeat(4) { cpu.step() }
        assertEquals(0x1234L, cpu.register(1))
        assertEquals(0x1234L, cpu.register(2))
        assertEquals(0x2468L, cpu.register(3))
        assertEquals(0x1234L, cpu.readLong(0x108))
    }

    @Test fun doublewordMemoryUsesBigEndianOrder() {
        val cpu = R4300Cpu()
        cpu.writeLong(0x80, 0x0123456789ABCDEFL)
        assertEquals(0x01234567, cpu.readWord(0x80))
        assertEquals(0x0123456789ABCDEFL, cpu.readLong(0x80))
    }

    @Test fun sltiAndDsubuProduceExpectedResults() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -5),
            iType(0x0A, 1, 2, -2),
            rType(0, 1, 3, 0, 0x2F)
        ))
        repeat(3) { cpu.step() }
        assertEquals(1L, cpu.register(2))
        assertEquals(5L, cpu.register(3))
    }

    @Test fun jalWritesReturnAddressAndTakesEffectAfterDelaySlot() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            jType(0x03, 4),                 // JAL target 0x10
            iType(0x09, 0, 1, 7),          // delay slot
            iType(0x09, 0, 2, 99),         // skipped
            iType(0x09, 0, 3, 99),         // skipped
            iType(0x09, 0, 4, 5)           // target
        ))
        cpu.step()
        assertEquals(8L, cpu.register(31))
        assertEquals(4L, cpu.pc)
        cpu.step()
        assertEquals(0x10L, cpu.pc)
        cpu.step()
        assertEquals(7L, cpu.register(1))
        assertEquals(0L, cpu.register(2))
        assertEquals(5L, cpu.register(4))
    }

    @Test fun bgtzBranchesOnlyWhenRegisterIsPositive() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -1),
            iType(0x07, 1, 0, 2),
            iType(0x09, 0, 2, 3),
            iType(0x09, 0, 3, 4)
        ))
        repeat(4) { cpu.step() }
        assertEquals(3L, cpu.register(2))
        assertEquals(4L, cpu.register(3))
    }

    @Test fun multiplyAndMoveHiLoInstructionsWork() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, -7),
            iType(0x09, 0, 2, 3),
            rType(1, 2, 0, 0, 0x18), // MULT: -7 * 3
            rType(0, 0, 3, 0, 0x12), // MFLO
            rType(0, 0, 4, 0, 0x10), // MFHI
            rType(1, 2, 0, 0, 0x1A), // DIV: -7 / 3
            rType(0, 0, 5, 0, 0x12), // MFLO
            rType(0, 0, 6, 0, 0x10)  // MFHI
        ))
        repeat(8) { cpu.step() }
        assertEquals(-21L, cpu.register(3))
        assertEquals(-1L, cpu.register(4))
        assertEquals(-2L, cpu.register(5))
        assertEquals(-1L, cpu.register(6))
    }

    @Test fun unsignedDivideUsesUnsigned32BitOperands() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x0F, 0, 1, 0xFFFF),
            iType(0x0D, 1, 1, 0xFFFF),
            iType(0x09, 0, 2, 2),
            rType(1, 2, 0, 0, 0x1B),
            rType(0, 0, 3, 0, 0x12),
            rType(0, 0, 4, 0, 0x10)
        ))
        repeat(6) { cpu.step() }
        assertEquals(2147483647L, cpu.register(3))
        assertEquals(1L, cpu.register(4))
    }

    @Test fun jalrWithZeroDestinationDoesNotOverwriteReturnRegister() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 16),
            rType(1, 0, 0, 0, 0x09), // JALR rd=$zero, target in r1
            iType(0x09, 0, 2, 7),    // delay slot
            iType(0x09, 0, 3, 99),   // skipped
            iType(0x09, 0, 4, 5)     // target
        ))
        repeat(5) { cpu.step() }
        assertEquals(0L, cpu.register(31))
        assertEquals(7L, cpu.register(2))
        assertEquals(0L, cpu.register(3))
        assertEquals(5L, cpu.register(4))
    }

    @Test fun addAndSubUseSigned32BitResults() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 12),
            iType(0x09, 0, 2, -5),
            rType(1, 2, 3, 0, 0x20),
            rType(1, 2, 4, 0, 0x22)
        ))
        repeat(4) { cpu.step() }
        assertEquals(7L, cpu.register(3))
        assertEquals(17L, cpu.register(4))
    }

    @Test fun addi64PerformsSignedAddition() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x18, 0, 1, -9)))
        cpu.step()
        assertEquals(-9L, cpu.register(1))
    }

    @Test fun luiSignExtendsIts32BitResultOnR4300() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(iType(0x0F, 0, 1, 0xFFFF)))
        cpu.step()
        assertEquals(-65536L, cpu.register(1))
    }

    @Test fun sltiuComparesAgainstSignExtendedImmediateAsUnsigned() {
        val cpu = R4300Cpu()
        cpu.loadBytes(0, words(
            iType(0x09, 0, 1, 1),
            iType(0x0B, 1, 2, -1),
            iType(0x0B, 0, 3, -1)
        ))
        repeat(3) { cpu.step() }
        assertEquals(1L, cpu.register(2))
        assertEquals(1L, cpu.register(3))
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun jType(op: Int, targetWordAddress: Int): Int =
        (op shl 26) or (targetWordAddress and 0x03FFFFFF)

    private fun rType(rs: Int, rt: Int, rd: Int, shamt: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or (shamt shl 6) or funct

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
