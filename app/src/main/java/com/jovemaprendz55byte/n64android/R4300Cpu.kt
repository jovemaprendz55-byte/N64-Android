package com.jovemaprendz55byte.n64android

/**
 * Small, testable MIPS III/R4300 interpreter foundation.
 *
 * This is deliberately a partial CPU core: it does not yet implement the full
 * R4300 instruction set, exceptions, TLB, CP0/FPU, caches, or N64 peripherals.
 * Memory is a simple big-endian byte array, not the Nintendo 64 memory map.
 */
class R4300Cpu(memorySize: Int = 1024 * 1024) {
    private val memory = ByteArray(memorySize)
    private val gpr = LongArray(32)

    var pc: Long = 0L
    var hi: Long = 0L
        private set
    var lo: Long = 0L
        private set

    private var pendingBranchTarget: Long? = null

    fun register(index: Int): Long {
        require(index in 0..31) { "Register index must be 0..31" }
        return if (index == 0) 0L else gpr[index]
    }

    fun loadBytes(address: Int, bytes: ByteArray) {
        checkRange(address, bytes.size)
        bytes.copyInto(memory, address)
    }

    fun readWord(address: Int): Int {
        requireAligned(address)
        checkRange(address, 4)
        return ((memory[address].toInt() and 0xFF) shl 24) or
            ((memory[address + 1].toInt() and 0xFF) shl 16) or
            ((memory[address + 2].toInt() and 0xFF) shl 8) or
            (memory[address + 3].toInt() and 0xFF)
    }

    fun writeWord(address: Int, value: Int) {
        requireAligned(address)
        checkRange(address, 4)
        memory[address] = (value ushr 24).toByte()
        memory[address + 1] = (value ushr 16).toByte()
        memory[address + 2] = (value ushr 8).toByte()
        memory[address + 3] = value.toByte()
    }

    /** Executes one instruction; branches take effect after their delay slot. */
    fun step() {
        val instructionAddress = pc
        val instruction = readWord(pc.toInt())
        pc += 4L

        val branchToApply = pendingBranchTarget
        pendingBranchTarget = null

        val opcode = instruction ushr 26
        val rs = (instruction ushr 21) and 0x1F
        val rt = (instruction ushr 16) and 0x1F
        val rd = (instruction ushr 11) and 0x1F
        val shift = (instruction ushr 6) and 0x1F
        val funct = instruction and 0x3F
        val immediate = instruction and 0xFFFF
        val signedImmediate = immediate.toShort().toLong()
        val a = register(rs)
        val b = register(rt)

        when (opcode) {
            0 -> when (funct) {
                0x00 -> writeRegister(rd, (b.toInt() shl shift).toInt().toLong()) // SLL
                0x02 -> writeRegister(rd, (b.toInt() ushr shift).toLong()) // SRL
                0x03 -> writeRegister(rd, (b.toInt() shr shift).toLong()) // SRA
                0x08 -> pendingBranchTarget = a // JR
                0x21 -> writeRegister(rd, (a.toInt() + b.toInt()).toLong()) // ADDU
                0x24 -> writeRegister(rd, a and b) // AND
                0x25 -> writeRegister(rd, a or b) // OR
                0x26 -> writeRegister(rd, a xor b) // XOR
                0x27 -> writeRegister(rd, (a or b).inv()) // NOR
                0x2A -> writeRegister(rd, if (a < b) 1L else 0L) // SLT
                0x2B -> writeRegister(rd, if (java.lang.Long.compareUnsigned(a, b) < 0) 1L else 0L) // SLTU
                else -> unsupported(instruction, instructionAddress)
            }
            0x09 -> writeRegister(rt, a + signedImmediate) // ADDIU
            0x0C -> writeRegister(rt, a and immediate.toLong()) // ANDI
            0x0D -> writeRegister(rt, a or immediate.toLong()) // ORI
            0x0E -> writeRegister(rt, a xor immediate.toLong()) // XORI
            0x0F -> writeRegister(rt, ((immediate shl 16).toInt()).toLong()) // LUI
            0x04 -> if (a == b) pendingBranchTarget = pc + (signedImmediate shl 2) // BEQ
            0x05 -> if (a != b) pendingBranchTarget = pc + (signedImmediate shl 2) // BNE
            0x23 -> { // LW: sign-extend the loaded 32-bit word
                val address = (a + signedImmediate).toInt()
                writeRegister(rt, readWord(address).toLong())
            }
            0x2B -> { // SW
                val address = (a + signedImmediate).toInt()
                writeWord(address, b.toInt())
            }
            0x02 -> pendingBranchTarget =
                (pc and -0x10000000L) or ((instruction.toLong() and 0x03FFFFFFL) shl 2) // J
            else -> unsupported(instruction, instructionAddress)
        }

        // The instruction just executed was a delay-slot instruction.
        if (branchToApply != null) pc = branchToApply
        gpr[0] = 0L
    }

    private fun writeRegister(index: Int, value: Long) {
        if (index != 0) gpr[index] = value
    }

    private fun unsupported(instruction: Int, address: Long): Nothing =
        throw IllegalArgumentException(
            "Unsupported R4300 instruction 0x%08X at 0x%X".format(instruction, address)
        )

    private fun requireAligned(address: Int) {
        require(address >= 0 && address % 4 == 0) { "Word access must be 4-byte aligned" }
    }

    private fun checkRange(address: Int, length: Int) {
        require(address >= 0 && length >= 0 && address.toLong() + length <= memory.size) {
            "Memory access outside configured RAM"
        }
    }
}
