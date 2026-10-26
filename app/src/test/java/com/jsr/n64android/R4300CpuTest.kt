package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class R4300CpuTest {
    @Test fun executesAddiuAndKeepsZeroRegisterImmutable() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 7))
        memory.write32(4, iType(0x09, 1, 2, -3))
        memory.write32(8, iType(0x09, 0, 0, 99))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        repeat(3) { cpu.step() }
        assertEquals(7L, cpu.gpr[1])
        assertEquals(4L, cpu.gpr[2])
        assertEquals(0L, cpu.gpr[0])
        assertEquals(12L, cpu.pc)
    }

    @Test fun branchExecutesDelaySlotBeforeTakingTarget() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 5))
        memory.write32(4, iType(0x04, 1, 1, 2))
        memory.write32(8, iType(0x09, 0, 2, 8))
        memory.write32(12, iType(0x09, 0, 3, 9))
        memory.write32(16, iType(0x09, 0, 4, 10))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        repeat(3) { cpu.step() }
        assertEquals(8L, cpu.gpr[2])
        assertEquals(0L, cpu.gpr[3])
        assertEquals(16L, cpu.pc)
        cpu.step()
        assertEquals(10L, cpu.gpr[4])
    }

    @Test fun loadsAndStoresBigEndianWords() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 32))
        memory.write32(4, iType(0x0F, 0, 2, 0x1234))
        memory.write32(8, iType(0x0D, 2, 2, 0x5678))
        memory.write32(12, iType(0x2B, 1, 2, 0))
        memory.write32(16, iType(0x23, 1, 3, 0))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        repeat(5) { cpu.step() }
        assertEquals(0x12345678L, cpu.gpr[3])
        assertEquals(0x12345678, memory.read32(32))
    }

    @Test fun mapsSignExtendedKseg0AndKseg1Addresses() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x09, 0, 1, 11))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(-0x80000000L)
        cpu.step()
        assertEquals(11L, cpu.gpr[1])
        assertEquals(-0x7FFFFFFCL, cpu.pc)
        cpu.setProgramCounter(-0x60000000L)
        cpu.step()
        assertEquals(11L, cpu.gpr[1])
    }

    @Test fun sltuComparesFullUnsigned64BitRegisters() {
        val memory = N64Memory(16)
        memory.write32(0, rType(1, 2, 3, 0x2B))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 0L
        cpu.gpr[2] = Long.MIN_VALUE
        cpu.step()
        assertEquals(1L, cpu.gpr[3])
    }

    @Test fun immediateComparisonsUseSignedAndUnsignedSemantics() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x0A, 1, 2, -1)) // slti $v0, $t0, -1
        memory.write32(4, iType(0x0B, 1, 3, -1)) // sltiu $v1, $t0, 0xffffffffffffffff
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = -2L
        cpu.step()
        cpu.step()
        assertEquals(1L, cpu.gpr[2])
        assertEquals(1L, cpu.gpr[3])
    }

    @Test fun blezAndBltzTakeBranchesAfterDelaySlot() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x06, 1, 0, 2)) // blez $t0, target at 12
        memory.write32(4, iType(0x09, 0, 2, 7)) // delay slot
        memory.write32(8, iType(0x09, 0, 3, 8)) // skipped
        memory.write32(12, iType(0x01, 1, 0, 1)) // bltz $t0, target at 20
        memory.write32(16, iType(0x09, 0, 4, 9)) // delay slot
        memory.write32(20, iType(0x09, 0, 5, 10))
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0)
        cpu.gpr[1] = -1L
        repeat(4) { cpu.step() }
        assertEquals(7L, cpu.gpr[2])
        assertEquals(0L, cpu.gpr[3])
        assertEquals(9L, cpu.gpr[4])
        assertEquals(20L, cpu.pc)
        cpu.step()
        assertEquals(10L, cpu.gpr[5])
    }

    @Test fun resetVectorReportsMissingPifRomInsteadOfOutOfBoundsRam() {
        val cpu = R4300Cpu(N64Bus())
        val error = assertThrows(UnsupportedOperationException::class.java) { cpu.step() }
        assertTrue(error.message!!.contains("PIF boot ROM is not loaded"))
    }

    @Test fun busReadsPifRomWhenExplicitlyProvided() {
        val pif = ByteArray(N64Bus.PIF_ROM_SIZE)
        pif[0] = 0x12
        pif[1] = 0x34
        pif[2] = 0x56
        pif[3] = 0x78
        val bus = N64Bus(pifRom = pif)
        assertEquals(0x12345678, bus.read32(N64Bus.PIF_ROM_BASE))
    }

    @Test fun busMapsCartridgeRomAtPhysicalBase() {
        val cart = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40, 0x11, 0x22, 0x33, 0x44)
        val bus = N64Bus(cartridgeRom = cart)
        assertEquals(0x80371240.toInt(), bus.read32(N64Bus.CARTRIDGE_ROM_BASE))
        assertEquals(0x11223344, bus.read32(N64Bus.CARTRIDGE_ROM_BASE + 4))
    }

    @Test fun cpuCanFetchAndExecuteInstructionFromMappedCartridgeWindow() {
        // addiu $t0, $zero, 42 — instruction bytes are stored big-endian in the ROM.
        val cartridge = byteArrayOf(0x24, 0x08, 0x00, 0x2A)
        val bus = N64Bus(cartridgeRom = cartridge)
        val cpu = R4300Cpu(bus)
        // KSEG1 virtual address B0000000 maps to physical cartridge address 10000000.
        cpu.setProgramCounter(0xFFFFFFFFB0000000UL.toLong())
        cpu.step()
        assertEquals(42L, cpu.gpr[8])
        assertEquals(0xFFFFFFFFB0000004UL.toLong(), cpu.pc)
    }

    @Test fun piDmaCopiesCartridgeBytesIntoRdram() {
        val bus = N64Bus(cartridgeRom = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40, 1, 2, 3, 4))
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0x100)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE)
        // PI_RD_LEN is length minus one, so 3 requests four bytes.
        bus.write32(N64Bus.PI_RD_LEN_REG, 3)
        assertEquals(0x80371240.toInt(), bus.read32(0x100))
        assertEquals(0x104, bus.read32(N64Bus.PI_DRAM_ADDR_REG))
        assertEquals(N64Bus.CARTRIDGE_ROM_BASE + 4, bus.read32(N64Bus.PI_CART_ADDR_REG))
    }

    @Test fun piDmaRejectsReadsOutsideCartridgeImage() {
        val bus = N64Bus(cartridgeRom = byteArrayOf(1, 2, 3, 4))
        bus.write32(N64Bus.PI_DRAM_ADDR_REG, 0)
        bus.write32(N64Bus.PI_CART_ADDR_REG, N64Bus.CARTRIDGE_ROM_BASE + 2)
        assertThrows(IllegalArgumentException::class.java) {
            bus.write32(N64Bus.PI_RD_LEN_REG, 2)
        }
    }

    @Test fun cartridgeRomIsReadOnlyAndOutOfRangeReadsFailClearly() {
        val bus = N64Bus(cartridgeRom = byteArrayOf(1, 2, 3, 4))
        assertThrows(UnsupportedOperationException::class.java) {
            bus.write8(N64Bus.CARTRIDGE_ROM_BASE, 0)
        }
        val error = assertThrows(UnsupportedOperationException::class.java) {
            bus.read8(N64Bus.CARTRIDGE_ROM_BASE + 4)
        }
        assertTrue(error.message!!.contains("beyond the loaded ROM size"))
    }

    @Test fun rejectsUnalignedInstructionAndWordAccesses() {
        val memory = N64Memory(64)
        memory.write32(0, iType(0x23, 1, 2, 0))
        val cpu = R4300Cpu(memory)
        assertThrows(IllegalArgumentException::class.java) { cpu.setProgramCounter(2) }
        cpu.setProgramCounter(0)
        cpu.gpr[1] = 1
        assertThrows(IllegalArgumentException::class.java) { cpu.step() }
    }

    private fun iType(op: Int, rs: Int, rt: Int, imm: Int): Int =
        (op shl 26) or (rs shl 21) or (rt shl 16) or (imm and 0xFFFF)

    private fun rType(rs: Int, rt: Int, rd: Int, funct: Int): Int =
        (rs shl 21) or (rt shl 16) or (rd shl 11) or funct
}
