package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300Cop1MemoryTest {
    @Test fun lwc1LoadsWordAndSwc1StoresWord() {
        val memory = N64Memory(128)
        memory.write32(0, (0x31 shl 26) or (1 shl 21) or (3 shl 16))
        memory.write32(4, (0x39 shl 26) or (1 shl 21) or (3 shl 16) or 4)
        memory.write32(0x20, 0x89ABCDEF.toInt())
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.gpr[1] = 0x20L
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(0x89ABCDEFL, cpu.fpr[3])
        cpu.step()
        assertEquals(0x89ABCDEF.toInt(), memory.read32(0x24))
    }

    @Test fun ldc1LoadsBigEndianDoublewordAndSdc1StoresIt() {
        val memory = N64Memory(128)
        memory.write32(0, (0x35 shl 26) or (1 shl 21) or (6 shl 16))
        memory.write32(4, (0x3D shl 26) or (1 shl 21) or (6 shl 16) or 8)
        memory.write32(0x20, 0x01234567)
        memory.write32(0x24, 0x89ABCDEF.toInt())
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.gpr[1] = 0x20L
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(0x89ABCDEFL, cpu.fpr[6])
        assertEquals(0x01234567L, cpu.fpr[7])
        cpu.step()
        assertEquals(0x01234567, memory.read32(0x28))
        assertEquals(0x89ABCDEF.toInt(), memory.read32(0x2C))
    }

    @Test fun ldc1MisalignmentRaisesAddressLoadExceptionWithoutChangingFpr() {
        val memory = N64Memory(128)
        memory.write32(0, (0x35 shl 26) or (1 shl 21) or (2 shl 16))
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.gpr[1] = 0x24L
        cpu.fpr[2] = 0x1122334455667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(4L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(0x24L, cpu.cop0[8])
        assertEquals(0x1122334455667788L, cpu.fpr[2])
    }

    @Test fun sdc1MisalignmentRaisesAddressStoreExceptionWithoutWritingMemory() {
        val memory = N64Memory(128)
        memory.write32(0, (0x3D shl 26) or (1 shl 21) or (2 shl 16))
        memory.write32(0x20, 0x11223344)
        memory.write32(0x24, 0x55667788)
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.gpr[1] = 0x24L
        cpu.fpr[2] = 0xAABBCCDDEEFF0011UL.toLong()
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(5L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(0x24L, cpu.cop0[8])
        assertEquals(0x11223344, memory.read32(0x20))
        assertEquals(0x55667788, memory.read32(0x24))
    }

    @Test fun ldc1OddRegisterRaisesReservedInstructionInFr0WithoutChangingPair() {
        val memory = N64Memory(128)
        memory.write32(0, (0x35 shl 26) or (1 shl 21) or (3 shl 16))
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.gpr[1] = 0x20L
        cpu.fpr[2] = 0x11223344L
        cpu.fpr[3] = 0x55667788L
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(10L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(0x11223344L, cpu.fpr[2])
        assertEquals(0x55667788L, cpu.fpr[3])
    }

    @Test fun sdc1OddRegisterRaisesReservedInstructionInFr0WithoutWritingMemory() {
        val memory = N64Memory(128)
        memory.write32(0, (0x3D shl 26) or (1 shl 21) or (3 shl 16))
        memory.write32(0x20, 0x11223344)
        memory.write32(0x24, 0x55667788)
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.gpr[1] = 0x20L
        cpu.fpr[3] = 0xAABBCCDDL
        cpu.setProgramCounter(0)

        cpu.step()

        assertEquals(10L, (cpu.cop0[13] ushr 2) and 0x1FL)
        assertEquals(0x11223344, memory.read32(0x20))
        assertEquals(0x55667788, memory.read32(0x24))
    }

    @Test fun singleWordTransfersKeepOddAndEvenRegistersIndependentInFr0() {
        val memory = N64Memory(128)
        memory.write32(0, (0x11 shl 26) or (4 shl 21) or (1 shl 16) or (3 shl 11))
        memory.write32(4, (0x11 shl 26) or (0 shl 21) or (2 shl 16) or (3 shl 11))
        val cpu = R4300Cpu(memory)
        cpu.cop0[12] = 1L shl 29
        cpu.gpr[1] = 0x89ABCDEFL
        cpu.fpr[2] = 0x11223344L
        cpu.setProgramCounter(0)

        cpu.step()
        assertEquals(0x89ABCDEFL, cpu.fpr[3])
        cpu.step()
        assertEquals(0x89ABCDEFL.toInt().toLong(), cpu.gpr[2])
        assertEquals(0x11223344L, cpu.fpr[2])
    }

}
