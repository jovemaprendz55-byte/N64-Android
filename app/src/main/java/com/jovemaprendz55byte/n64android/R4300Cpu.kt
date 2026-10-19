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
                0x08 -> {
                    if (a % 4L != 0L) throw IllegalArgumentException("JR target must be 4-byte aligned")
                    pendingBranchTarget = a
                } // JR
                0x09 -> {
                    if (a % 4L != 0L) throw IllegalArgumentException("JALR target must be 4-byte aligned")
                    writeRegister(rd, instructionAddress + 8L)
                    pendingBranchTarget = a
                } // JALR
                0x10 -> writeRegister(rd, hi) // MFHI
                0x11 -> hi = a // MTHI
                0x12 -> writeRegister(rd, lo) // MFLO
                0x13 -> lo = a // MTLO
                0x18 -> {
                    val product = a.toInt().toLong() * b.toInt().toLong()
                    hi = (product shr 32).toInt().toLong()
                    lo = product.toInt().toLong()
                } // MULT
                0x19 -> {
                    val product = Integer.toUnsignedLong(a.toInt()) * Integer.toUnsignedLong(b.toInt())
                    hi = (product ushr 32).toInt().toLong()
                    lo = product.toInt().toLong()
                } // MULTU
                0x1A -> {
                    val dividend = a.toInt()
                    val divisor = b.toInt()
                    if (divisor != 0) {
                        if (dividend == Int.MIN_VALUE && divisor == -1) {
                            lo = Int.MIN_VALUE.toLong()
                            hi = 0L
                        } else {
                            lo = (dividend / divisor).toLong()
                            hi = (dividend % divisor).toLong()
                        }
                    }
                } // DIV; division by zero leaves HI/LO unchanged in this simplified core
                0x1B -> {
                    val dividend = a.toInt()
                    val divisor = b.toInt()
                    if (divisor != 0) {
                        val unsignedDividend = Integer.toUnsignedLong(dividend)
                        val unsignedDivisor = Integer.toUnsignedLong(divisor)
                        lo = (unsignedDividend / unsignedDivisor).toLong()
                        hi = (unsignedDividend % unsignedDivisor).toLong()
                    }
                } // DIVU
                0x20 -> writeRegister(rd, Math.addExact(a.toInt(), b.toInt()).toLong()) // ADD
                0x21 -> writeRegister(rd, (a.toInt() + b.toInt()).toLong()) // ADDU
                0x22 -> writeRegister(rd, Math.subtractExact(a.toInt(), b.toInt()).toLong()) // SUB
                0x24 -> writeRegister(rd, a and b) // AND
                0x25 -> writeRegister(rd, a or b) // OR
                0x26 -> writeRegister(rd, a xor b) // XOR
                0x27 -> writeRegister(rd, (a or b).inv()) // NOR
                0x2A -> writeRegister(rd, if (a < b) 1L else 0L) // SLT
                0x2B -> writeRegister(rd, if (java.lang.Long.compareUnsigned(a, b) < 0) 1L else 0L) // SLTU
                0x2D -> writeRegister(rd, a + b) // DADDU
                0x2F -> writeRegister(rd, a - b) // DSUBU
                0x38 -> writeRegister(rd, b shl shift) // DSLL
                0x3A -> writeRegister(rd, b ushr shift) // DSRL
                0x3B -> writeRegister(rd, b shr shift) // DSRA
                0x3C -> writeRegister(rd, b shl (shift + 32)) // DSLL32
                0x3E -> writeRegister(rd, b ushr (shift + 32)) // DSRL32
                0x3F -> writeRegister(rd, b shr (shift + 32)) // DSRA32
                else -> unsupported(instruction, instructionAddress)
            }
            0x01 -> when (rt) {
                0x00 -> if (a < 0L) pendingBranchTarget = pc + (signedImmediate shl 2) // BLTZ
                0x01 -> if (a >= 0L) pendingBranchTarget = pc + (signedImmediate shl 2) // BGEZ
                0x10 -> if (a < 0L) {
                    writeRegister(31, instructionAddress + 8L)
                    pendingBranchTarget = pc + (signedImmediate shl 2)
                } // BLTZAL
                0x11 -> if (a >= 0L) {
                    writeRegister(31, instructionAddress + 8L)
                    pendingBranchTarget = pc + (signedImmediate shl 2)
                } // BGEZAL
                else -> unsupported(instruction, instructionAddress)
            }
            0x03 -> {
                writeRegister(31, instructionAddress + 8L)
                pendingBranchTarget = (pc and -0x10000000L) or
                    ((instruction.toLong() and 0x03FFFFFFL) shl 2)
            } // JAL
            0x06 -> if (a <= 0L) pendingBranchTarget = pc + (signedImmediate shl 2) // BLEZ
            0x07 -> if (a > 0L) pendingBranchTarget = pc + (signedImmediate shl 2) // BGTZ
            0x09 -> writeRegister(rt, (a.toInt() + signedImmediate.toInt()).toLong()) // ADDIU
            0x0A -> writeRegister(rt, if (a < signedImmediate) 1L else 0L) // SLTI
            0x0B -> writeRegister(rt, if (java.lang.Long.compareUnsigned(a, signedImmediate) < 0) 1L else 0L) // SLTIU
            0x0C -> writeRegister(rt, a and immediate.toLong()) // ANDI
            0x0D -> writeRegister(rt, a or immediate.toLong()) // ORI
            0x0E -> writeRegister(rt, a xor immediate.toLong()) // XORI
            0x0F -> writeRegister(rt, (immediate shl 16).toLong()) // LUI (sign-extended 32-bit result)
            0x04 -> if (a == b) pendingBranchTarget = pc + (signedImmediate shl 2) // BEQ
            0x05 -> if (a != b) pendingBranchTarget = pc + (signedImmediate shl 2) // BNE
            0x18 -> writeRegister(rt, Math.addExact(a, signedImmediate)) // DADDI
            0x19 -> writeRegister(rt, a + signedImmediate) // DADDIU
            0x22 -> writeRegister(rt, loadWordLeft(address, b.toInt()).toLong()) // LWL
            0x20 -> writeRegister(rt, readByte(address).toLong()) // LB
            0x21 -> writeRegister(rt, readHalf(address).toLong()) // LH
            0x23 -> writeRegister(rt, readWord(address).toLong()) // LW
            0x26 -> writeRegister(rt, loadWordRight(address, b.toInt()).toLong()) // LWR
            0x24 -> writeRegister(rt, readByte(address).toLong() and 0xFFL) // LBU
            0x25 -> writeRegister(rt, readHalf(address).toLong() and 0xFFFFL) // LHU
            0x2A -> storeWordLeft(address, b.toInt()) // SWL
            0x28 -> writeByte(address, b.toByte()) // SB
            0x29 -> writeHalf(address, b.toShort()) // SH
            0x2B -> writeWord(address, b.toInt()) // SW
            0x2E -> storeWordRight(address, b.toInt()) // SWR
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


    /** Big-endian MIPS LWL: merge memory's left bytes into the high end of rt. */
    private fun loadWordLeft(address: Int, oldValue: Int): Int {
        val offset = address and 3
        val word = readWord(address and -4)
        val preservedMask = if (offset == 0) 0 else (1 shl (offset * 8)) - 1
        return (word shl (offset * 8)) or (oldValue and preservedMask)
    }

    /** Big-endian MIPS LWR: merge memory's right bytes into the low end of rt. */
    private fun loadWordRight(address: Int, oldValue: Int): Int {
        val offset = address and 3
        val word = readWord(address and -4)
        val preservedMask = when (offset) {
            0 -> 0xFFFFFF00.toInt()
            1 -> 0xFFFF0000.toInt()
            2 -> 0xFF000000.toInt()
            else -> 0
        }
        return (oldValue and preservedMask) or (word ushr ((3 - offset) * 8))
    }

    /** Big-endian MIPS SWL: store rt's left bytes through the end of the aligned word. */
    private fun storeWordLeft(address: Int, value: Int) {
        val offset = address and 3
        val aligned = address and -4
        val preservedMask = when (offset) {
            0 -> 0
            1 -> 0xFF000000.toInt()
            2 -> 0xFFFF0000.toInt()
            else -> 0xFFFFFF00.toInt()
        }
        val shift = offset * 8
        writeWord(aligned, (readWord(aligned) and preservedMask) or (value ushr shift))
    }

    /** Big-endian MIPS SWR: store rt's right bytes from the start through the effective address. */
    private fun storeWordRight(address: Int, value: Int) {
        val offset = address and 3
        val aligned = address and -4
        val preservedMask = when (offset) {
            0 -> 0x00FFFFFF
            1 -> 0x0000FFFF
            2 -> 0x000000FF
            else -> 0
        }
        val shift = (3 - offset) * 8
        writeWord(aligned, (readWord(aligned) and preservedMask) or (value shl shift))
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
