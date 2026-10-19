package com.jovemaprendz55byte.n64android

/**
 * Minimal scalar RSP interpreter foundation.
 *
 * Implements a deliberately small MIPS-I integer subset for smoke tests.
 * Vector instructions, COP0, DMA scheduling, interrupts, and cycle timing are
 * not implemented. Branches use a one-instruction delay slot.
 */
internal class N64RspCpu(private val bus: N64MemoryBus) {
    private val gpr = IntArray(32)
    var pc: Int = 0
        private set
    private var pendingTarget: Int? = null

    fun register(index: Int): Int {
        require(index in 0..31)
        return if (index == 0) 0 else gpr[index]
    }

    fun setPc(address: Int) {
        require(address in 0 until N64SpMemory.MEMORY_SIZE && address % 4 == 0)
        pc = address
        pendingTarget = null
    }

    /** Fetches and executes one instruction, then advances through delay-slot semantics. */
    fun step() {
        require(pc in 0 until N64SpMemory.MEMORY_SIZE && pc % 4 == 0) {
            "RSP PC is outside IMEM or unaligned: 0x${pc.toString(16)}"
        }
        val instruction = bus.readWord(N64SpMemory.IMEM_BASE + pc)
        val oldTarget = pendingTarget
        pendingTarget = null
        val sequentialPc = (pc + 4) and 0xFFC
        pc = sequentialPc
        execute(instruction, sequentialPc)
        if (oldTarget != null) pc = oldTarget and 0xFFC
        gpr[0] = 0
    }

    private fun execute(instruction: Int, pcPlus4: Int) {
        val opcode = instruction ushr 26
        val rs = (instruction ushr 21) and 31
        val rt = (instruction ushr 16) and 31
        val rd = (instruction ushr 11) and 31
        val shamt = (instruction ushr 6) and 31
        val immediate = instruction.toShort().toInt()
        val unsignedImmediate = instruction and 0xFFFF
        val a = register(rs)
        val b = register(rt)

        when (opcode) {
            0 -> when (instruction and 63) {
                0 -> writeRegister(rd, b shl shamt) // SLL / NOP
                8 -> pendingTarget = a // JR
                33 -> writeRegister(rd, a + b) // ADDU
                35 -> writeRegister(rd, a - b) // SUBU
                36 -> writeRegister(rd, a and b) // AND
                37 -> writeRegister(rd, a or b) // OR
                38 -> writeRegister(rd, a xor b) // XOR
                42 -> writeRegister(rd, if (a < b) 1 else 0) // SLT
                else -> unsupported(instruction)
            }
            2 -> pendingTarget = (pcPlus4 and 0xF0000000.toInt()) or
                ((instruction and 0x03FFFFFF) shl 2) // J
            3 -> {
                writeRegister(31, (pcPlus4 + 4) and 0xFFC)
                pendingTarget = (pcPlus4 and 0xF0000000.toInt()) or
                    ((instruction and 0x03FFFFFF) shl 2) // JAL
            }
            4 -> if (a == b) pendingTarget = pcPlus4 + (immediate shl 2)
            5 -> if (a != b) pendingTarget = pcPlus4 + (immediate shl 2)
            9 -> writeRegister(rt, a + immediate) // ADDIU
            12 -> writeRegister(rt, a and unsignedImmediate) // ANDI
            13 -> writeRegister(rt, a or unsignedImmediate) // ORI
            14 -> writeRegister(rt, a xor unsignedImmediate) // XORI
            15 -> writeRegister(rt, unsignedImmediate shl 16) // LUI
            35 -> writeRegister(rt, bus.readWord(dmemAddress(a + immediate))) // LW
            43 -> bus.writeWord(dmemAddress(a + immediate), b) // SW
            else -> unsupported(instruction)
        }
    }

    private fun dmemAddress(address: Int): Long {
        require(address in 0 until N64SpMemory.MEMORY_SIZE && address % 4 == 0) {
            "RSP data address is outside DMEM or unaligned: 0x${address.toString(16)}"
        }
        return N64SpMemory.DMEM_BASE + address
    }

    private fun writeRegister(index: Int, value: Int) {
        if (index != 0) gpr[index] = value
    }

    private fun unsupported(instruction: Int): Nothing =
        throw IllegalArgumentException("Unsupported scalar RSP instruction 0x${instruction.toString(16)}")
}
