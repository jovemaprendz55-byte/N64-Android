package com.jovemaprendz55byte.n64android

/**
 * Small, testable MIPS III/R4300 interpreter foundation.
 *
 * This remains a partial CPU core: exceptions, TLB, CP0/FPU, caches, and N64
 * peripherals are not implemented. Memory is a simple big-endian byte array,
 * not the Nintendo 64 memory map.
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

    fun readByte(address: Int): Byte {
        checkRange(address, 1)
        return memory[address]
    }

    fun writeByte(address: Int, value: Byte) {
        checkRange(address, 1)
        memory[address] = value
    }

    /** Reads a signed, big-endian 16-bit value. */
    fun readHalf(address: Int): Short {
        requireHalfAligned(address)
        checkRange(address, 2)
        return ((((memory[address].toInt() and 0xFF) shl 8) or
            (memory[address + 1].toInt() and 0xFF))).toShort()
    }

    fun writeHalf(address: Int, value: Short) {
        requireHalfAligned(address)
        checkRange(address, 2)
        memory[address] = (value.toInt() ushr 8).toByte()
        memory[address + 1] = value.toByte()
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

    fun readLong(address: Int): Long {
        requireDoubleAligned(address)
        checkRange(address, 8)
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (memory[address + i].toLong() and 0xFFL)
        }
        return value
    }

    fun writeLong(address: Int, value: Long) {
        requireDoubleAligned(address)
        checkRange(address, 8)
        for (i in 0 until 8) {
            memory[address + i] = (value ushr (56 - i * 8)).toByte()
        }
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
        val address = (a + signedImmediate).toInt()

        when (opcode) {
            0 -> when (funct) {
                0x00 -> writeRegister(rd, (b.toInt() shl shift).toLong()) // SLL
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
                0x2D -> writeRegister(rd, a + b) // DADDU
                0x38 -> writeRegister(rd, b shl shift) // DSLL
                0x3A -> writeRegister(rd, b ushr shift) // DSRL
                0x3B -> writeRegister(rd, b shr shift) // DSRA
                0x3C -> writeRegister(rd, b shl (shift + 32)) // DSLL32
                0x3E -> writeRegister(rd, b ushr (shift + 32)) // DSRL32
                0x3F -> writeRegister(rd, b shr (shift + 32)) // DSRA32
                else -> unsupported(instruction, instructionAddress)
            }
            0x09 -> writeRegister(rt, (a.toInt() + signedImmediate.toInt()).toLong()) // ADDIU
            0x0C -> writeRegister(rt, a and immediate.toLong()) // ANDI
            0x0D -> writeRegister(rt, a or immediate.toLong()) // ORI
            0x0E -> writeRegister(rt, a xor immediate.toLong()) // XORI
            0x0F -> writeRegister(rt, (immediate shl 16).toInt().toLong()) // LUI
            0x04 -> if (a == b) pendingBranchTarget = pc + (signedImmediate shl 2) // BEQ
            0x05 -> if (a != b) pendingBranchTarget = pc + (signedImmediate shl 2) // BNE
            0x19 -> writeRegister(rt, a + signedImmediate) // DADDIU
            0x20 -> writeRegister(rt, readByte(address).toLong()) // LB
            0x21 -> writeRegister(rt, readHalf(address).toLong()) // LH
            0x23 -> writeRegister(rt, readWord(address).toLong()) // LW
            0x24 -> writeRegister(rt, readByte(address).toLong() and 0xFFL) // LBU
            0x25 -> writeRegister(rt, readHalf(address).toLong() and 0xFFFFL) // LHU
            0x28 -> writeByte(address, b.toByte()) // SB
            0x29 -> writeHalf(address, b.toShort()) // SH
            0x2B -> writeWord(address, b.toInt()) // SW
            0x37 -> writeRegister(rt, readLong(address)) // LD
            0x3F -> writeLong(address, b) // SD
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

    private fun requireHalfAligned(address: Int) {
        require(address >= 0 && address % 2 == 0) { "Halfword access must be 2-byte aligned" }
    }

    private fun requireAligned(address: Int) {
        require(address >= 0 && address % 4 == 0) { "Word access must be 4-byte aligned" }
    }

    private fun requireDoubleAligned(address: Int) {
        require(address >= 0 && address % 8 == 0) { "Doubleword access must be 8-byte aligned" }
    }

    private fun checkRange(address: Int, length: Int) {
        require(address >= 0 && length >= 0 && address.toLong() + length <= memory.size) {
            "Memory access outside configured RAM"
        }
    }
}
