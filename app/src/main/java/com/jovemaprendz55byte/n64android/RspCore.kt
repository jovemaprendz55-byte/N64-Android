package com.jovemaprendz55byte.n64android

/**
 * Early scalar RSP interpreter foundation. Supports a small subset of the
 * scalar instruction set and BREAK; vector operations, COP0, DMA timing,
 * interrupts, and full RSP task execution remain unimplemented.
 */
internal class RspCore(private val bus: N64MemoryBus) {
    private val gpr = IntArray(32)
    var pc: Int = 0
        private set
    var halted: Boolean = true
        private set
    private var pendingBranchTarget: Int? = null

    fun register(index: Int): Int {
        require(index in 0..31) { "Register index must be 0..31" }
        return if (index == 0) 0 else gpr[index]
    }

    /** Starts at an aligned IMEM offset, clearing scalar registers and branch state. */
    fun start(entryPoint: Int = 0) {
        require(entryPoint in 0 until N64SpMemory.MEMORY_SIZE && entryPoint % 4 == 0) {
            "RSP entry point must be an aligned IMEM offset"
        }
        gpr.fill(0)
        pc = entryPoint
        pendingBranchTarget = null
        halted = false
        bus.writeWord(N64MemoryBus.SP_STATUS, (1 shl 0) or (1 shl 2)) // clear HALT and stale BROKE
    }

    /** Executes one scalar instruction; returns false when halted or BREAK executes. */
    fun step(): Boolean {
        if (halted || bus.isSpHalted()) {
            halted = true
            return false
        }
        val address = N64SpMemory.IMEM_BASE + pc.toLong()
        val instruction = bus.readWord(address)
        val instructionPc = pc
        pc = (pc + 4) and 0xFFF
        val branchToApply = pendingBranchTarget
        pendingBranchTarget = null

        val op = instruction ushr 26
        val rs = (instruction ushr 21) and 31
        val rt = (instruction ushr 16) and 31
        val rd = (instruction ushr 11) and 31
        val shift = (instruction ushr 6) and 31
        val funct = instruction and 63
        val imm = instruction.toShort().toInt()
        val a = register(rs)
        val b = register(rt)

        when (op) {
            0 -> when (funct) {
                0x00 -> writeReg(rd, b shl shift) // SLL
                0x02 -> writeReg(rd, b ushr shift) // SRL
                0x03 -> writeReg(rd, b shr shift) // SRA
                0x08 -> pendingBranchTarget = a and 0xFFF // JR
                0x0D -> {
                    pc = instructionPc
                    bus.markSpBroke()
                    halted = true
                    return false
                } // BREAK
                0x20, 0x21 -> writeReg(rd, a + b) // ADD/ADDU, simplified wraparound
                0x22, 0x23 -> writeReg(rd, a - b) // SUB/SUBU
                0x24 -> writeReg(rd, a and b)
                0x25 -> writeReg(rd, a or b)
                0x26 -> writeReg(rd, a xor b)
                0x27 -> writeReg(rd, (a or b).inv())
                0x2A -> writeReg(rd, if (a < b) 1 else 0)
                0x2B -> writeReg(rd, if (Integer.compareUnsigned(a, b) < 0) 1 else 0)
                else -> unsupported(instruction, instructionPc)
            }
            0x02 -> pendingBranchTarget = ((pc and 0xF0000000.toInt()) or
                ((instruction and 0x03FFFFFF) shl 2)) and 0xFFF // J
            0x03 -> {
                writeReg(31, (instructionPc + 8) and 0xFFF)
                pendingBranchTarget = ((pc and 0xF0000000.toInt()) or
                    ((instruction and 0x03FFFFFF) shl 2)) and 0xFFF // JAL
            }
            0x01 -> when (rt) {
                0x00 -> if (a < 0) pendingBranchTarget = (pc + (imm shl 2)) and 0xFFF // BLTZ
                0x01 -> if (a >= 0) pendingBranchTarget = (pc + (imm shl 2)) and 0xFFF // BGEZ
                else -> unsupported(instruction, instructionPc)
            }
            0x04 -> if (a == b) pendingBranchTarget = (pc + (imm shl 2)) and 0xFFF // BEQ
            0x05 -> if (a != b) pendingBranchTarget = (pc + (imm shl 2)) and 0xFFF // BNE
            0x06 -> if (a <= 0) pendingBranchTarget = (pc + (imm shl 2)) and 0xFFF // BLEZ
            0x07 -> if (a > 0) pendingBranchTarget = (pc + (imm shl 2)) and 0xFFF // BGTZ
            0x08, 0x09 -> writeReg(rt, a + imm) // ADDI(U), wrapping arithmetic
            0x0A -> writeReg(rt, if (a < imm) 1 else 0) // SLTI
            0x0B -> writeReg(rt, if (Integer.compareUnsigned(a, imm) < 0) 1 else 0) // SLTIU
            0x0C -> writeReg(rt, a and (instruction and 0xFFFF)) // ANDI
            0x0D -> writeReg(rt, a or (instruction and 0xFFFF)) // ORI
            0x0E -> writeReg(rt, a xor (instruction and 0xFFFF)) // XORI
            0x0F -> writeReg(rt, (instruction and 0xFFFF) shl 16) // LUI
            0x20 -> writeReg(rt, bus.readByte(dmemAddress(a + imm)).toInt()) // LB
            0x21 -> writeReg(rt, bus.readHalf(dmemAddress(a + imm)).toInt()) // LH
            0x23 -> writeReg(rt, bus.readWord(dmemAddress(a + imm))) // LW
            0x24 -> writeReg(rt, bus.readByte(dmemAddress(a + imm)).toInt() and 0xFF)
            0x25 -> writeReg(rt, bus.readHalf(dmemAddress(a + imm)).toInt() and 0xFFFF)
            0x28 -> bus.writeByte(dmemAddress(a + imm), b.toByte())
            0x29 -> bus.writeHalf(dmemAddress(a + imm), b.toShort())
            0x2B -> bus.writeWord(dmemAddress(a + imm), b) // SW
            else -> unsupported(instruction, instructionPc)
        }
        if (branchToApply != null) pc = branchToApply
        gpr[0] = 0
        return !halted
    }

    fun run(maxInstructions: Int): Int {
        require(maxInstructions > 0) { "Instruction budget must be positive" }
        var executed = 0
        while (executed < maxInstructions && !halted && !bus.isSpHalted()) {
            val continued = step()
            executed++ // Count BREAK as an executed instruction too.
            if (!continued) break
        }
        return executed
    }

    private fun writeReg(index: Int, value: Int) {
        if (index != 0) gpr[index] = value
    }

    private fun dmemAddress(offset: Int): Long =
        N64SpMemory.DMEM_BASE + (offset and 0xFFF).toLong()

    private fun unsupported(instruction: Int, address: Int): Nothing =
        throw IllegalArgumentException(
            "Unsupported scalar RSP instruction 0x${instruction.toString(16)} at IMEM+0x${address.toString(16)}"
        )
}
