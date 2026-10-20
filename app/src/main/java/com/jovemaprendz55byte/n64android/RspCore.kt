package com.jovemaprendz55byte.n64android

/**
 * Partial RSP interpreter supporting a subset of scalar instructions,
 * basic COP0 transfers to SP registers, byte-accurate MFC2/MTC2 transfers,
 * BREAK, and a limited lane-wise vector ALU subset (VADD, VSUB, and bitwise
 * logic). Full vector accumulator/flag behavior, accurate DMA timing,
 * interrupts, and complete task execution remain unimplemented.
 */
internal class RspCore(private val bus: N64MemoryBus) {
    private val gpr = IntArray(32)
    private val vector = Array(32) { ShortArray(8) }
    var pc: Int = 0
        private set
    /** IMEM offset of the most recently attempted instruction, useful for fault diagnostics. */
    var lastInstructionAddress: Int = 0
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
        vector.forEach { it.fill(0) }
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
        lastInstructionAddress = pc
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
        val cop0rd = (instruction ushr 11) and 31
        val element = (instruction ushr 7) and 0xF
        val vectorRegister = (instruction ushr 11) and 31
        val imm = instruction.toShort().toInt()
        val a = register(rs)
        val b = register(rt)

        when (op) {
            0 -> when (funct) {
                0x00 -> writeReg(rd, b shl shift) // SLL
                0x02 -> writeReg(rd, b ushr shift) // SRL
                0x03 -> writeReg(rd, b shr shift) // SRA
                0x04 -> writeReg(rd, b shl (a and 31)) // SLLV
                0x06 -> writeReg(rd, b ushr (a and 31)) // SRLV
                0x07 -> writeReg(rd, b shr (a and 31)) // SRAV
                0x08 -> pendingBranchTarget = a and 0xFFC // JR
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
            0x10 -> when (rs) {
                0x00 -> writeReg(rt, readSpCop0(cop0rd)) // MFC0
                0x04 -> writeSpCop0(cop0rd, b) // MTC0
                else -> unsupported(instruction, instructionPc)
            }
            0x12 -> when (rs) {
                0x00 -> {
                    val value = (getVectorByte(vectorRegister, element) shl 8) or
                        getVectorByte(vectorRegister, element + 1)
                    writeReg(rt, value.toShort().toInt()) // MFC2 reads two consecutive bytes.
                }
                0x04 -> {
                    setVectorByte(vectorRegister, element, b ushr 8)
                    if (element != 15) setVectorByte(vectorRegister, element + 1, b)
                } // MTC2 writes two bytes, except element 15 writes only the high byte.
                in 0x10..0x1F -> executeVectorAlu(instruction, instructionPc)
                else -> unsupported(instruction, instructionPc)
            }
            0x32 -> executeVectorByteMemory(instruction, a, true, instructionPc) // LWC2 LBV subset
            0x3A -> executeVectorByteMemory(instruction, a, false, instructionPc) // SWC2 SBV subset
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

    /**
     * Implements the byte forms LBV and SBV of the RSP vector load/store unit.
     * Other vector memory suboperations are deliberately rejected until their
     * lane grouping and alignment rules are implemented.
     */
    private fun executeVectorByteMemory(
        instruction: Int,
        base: Int,
        load: Boolean,
        address: Int
    ) {
        val subOperation = (instruction ushr 11) and 31
        if (subOperation !in 0..1) unsupported(instruction, address) // LBV/SBV and LSV/SSV
        val vt = (instruction ushr 16) and 31
        val element = (instruction ushr 7) and 15
        val offset7 = instruction and 0x7F
        val signedOffset = if (offset7 and 0x40 != 0) offset7 - 0x80 else offset7
        val byteCount = 1 shl subOperation // LBV/SBV: 1 byte; LSV/SSV: 2 bytes.
        val memoryStart = base + signedOffset * byteCount
        for (index in 0 until byteCount) {
            val addressForByte = dmemAddress(memoryStart + index)
            if (load) {
                setVectorByte(vt, element + index, bus.readByte(addressForByte).toInt() and 0xFF)
            } else {
                bus.writeByte(addressForByte, getVectorByte(vt, element + index).toByte())
            }
        }
    }

    /**
     * Lane-wise vector ALU subset. Element selection/swizzling is supported for
     * the implemented operations; VADD/VSUB saturate signed 16-bit results, but
     * VCO carry-in and the 48-bit accumulator/flags are not modeled yet.
     */
    private fun executeVectorAlu(instruction: Int, address: Int) {
        val vt = (instruction ushr 16) and 31
        val vs = (instruction ushr 11) and 31
        val vd = (instruction ushr 6) and 31
        val funct = instruction and 63
        val element = (instruction ushr 21) and 0xF
        // The vector unit reads both source registers before writing any destination
        // lane. Snapshot them first so vd may safely alias vs or vt when swizzling.
        val sourceVs = vector[vs].copyOf()
        val sourceVt = vector[vt].copyOf()
        for (lane in 0 until 8) {
            val left = sourceVs[lane].toInt()
            val right = sourceVt[selectedVectorLane(element, lane)].toInt()
            val result = when (funct) {
                0x10 -> (left + right).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) // VADD signed saturation; VCO carry-in not modeled
                0x11 -> (left - right).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) // VSUB signed saturation; VCO carry-in not modeled
                0x28 -> left and right // VAND
                0x29 -> (left and right).inv() // VNAND
                0x2A -> left or right // VOR
                0x2B -> (left or right).inv() // VNOR
                0x2C -> left xor right // VXOR
                0x2D -> (left xor right).inv() // VNXOR
                else -> unsupported(instruction, address)
            }
            vector[vd][lane] = result.toShort()
        }
    }

    /**
     * Selects the RSP vector source lane. Selectors 0..7 use the hardware's
     * halfword swizzles; selectors 8..15 broadcast one selected halfword.
     */
    private fun selectedVectorLane(element: Int, lane: Int): Int = when (element) {
        0 -> lane
        1 -> lane and 0b110
        2 -> lane and 0b100
        3 -> 0
        4 -> (lane and 0b110) or 1
        5 -> (lane and 0b100) or 1
        6 -> (lane and 0b100) or 2
        7 -> (lane and 0b100) or 3
        in 8..15 -> element and 0b111
        else -> throw IllegalArgumentException("Invalid RSP vector element selector $element")
    }

    private fun readSpCop0(index: Int): Int = when (index) {
        0 -> bus.readWord(N64MemoryBus.SP_MEM_ADDR)
        1 -> bus.readWord(N64MemoryBus.SP_DRAM_ADDR)
        2 -> bus.readWord(N64MemoryBus.SP_RD_LEN)
        3 -> bus.readWord(N64MemoryBus.SP_WR_LEN)
        4 -> bus.readWord(N64MemoryBus.SP_STATUS)
        5 -> bus.readWord(N64MemoryBus.SP_DMA_FULL)
        6 -> bus.readWord(N64MemoryBus.SP_DMA_BUSY)
        7 -> bus.readWord(N64MemoryBus.SP_SEMAPHORE)
        else -> throw IllegalArgumentException("Unsupported RSP COP0 register $index")
    }

    private fun writeSpCop0(index: Int, value: Int) {
        val address = when (index) {
            0 -> N64MemoryBus.SP_MEM_ADDR
            1 -> N64MemoryBus.SP_DRAM_ADDR
            2 -> N64MemoryBus.SP_RD_LEN
            3 -> N64MemoryBus.SP_WR_LEN
            4 -> N64MemoryBus.SP_STATUS
            7 -> N64MemoryBus.SP_SEMAPHORE
            else -> throw IllegalArgumentException("RSP cannot write COP0 register $index")
        }
        bus.writeWord(address, value)
    }

    private fun dmemAddress(offset: Int): Long =
        N64SpMemory.DMEM_BASE + (offset and 0xFFF).toLong()

    /** Vector byte 0 is the high byte of lane 0; byte indices wrap modulo 16. */
    private fun getVectorByte(register: Int, element: Int): Int {
        val lane = (element and 15) ushr 1
        val value = vector[register][lane].toInt() and 0xFFFF
        return if (element and 1 == 0) value ushr 8 else value and 0xFF
    }

    private fun setVectorByte(register: Int, element: Int, value: Int) {
        val lane = (element and 15) ushr 1
        val old = vector[register][lane].toInt() and 0xFFFF
        vector[register][lane] = (if (element and 1 == 0) {
            ((value and 0xFF) shl 8) or (old and 0x00FF)
        } else {
            (old and 0xFF00) or (value and 0xFF)
        }).toShort()
    }

    private fun unsupported(instruction: Int, address: Int): Nothing =
        throw IllegalArgumentException(
            "Unsupported scalar RSP instruction 0x${instruction.toString(16)} at IMEM+0x${address.toString(16)}"
        )
}
