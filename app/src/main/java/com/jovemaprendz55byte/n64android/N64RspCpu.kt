package com.jovemaprendz55byte.n64android

/**
 * Minimal scalar RSP interpreter foundation.
 *
 * Implements a deliberately small MIPS-I integer subset for smoke tests.
 * Implements a limited vector subset (VADD, VSUB, VABS, VADDC, VSUBC, VAND, VNAND, VOR, VNOR, VXOR, VNXOR, VANDC, VORC) with
 * element selector e=0. Other vector operations, DMA scheduling, interrupts,
 * and cycle timing are not implemented. COP0 access is limited to SP registers.
 * Branches use a one-instruction delay slot.
 */
internal class N64RspCpu(private val bus: N64MemoryBus) {
    private val gpr = IntArray(32)
    private var hi = 0
    private var lo = 0
    private val vector = Array(32) { ShortArray(8) }
    // VCO is stored as low-byte carry/borrow bits and high-byte nonzero bits.
    private var vco = 0
    var pc: Int = 0
        private set
    private var pendingTarget: Int? = null

    fun register(index: Int): Int {
        require(index in 0..31)
        return if (index == 0) 0 else gpr[index]
    }

    /** Reads one signed 16-bit lane from a vector register (test/debug API). */
    fun vectorLane(register: Int, lane: Int): Short {
        require(register in 0..31 && lane in 0..7)
        return vector[register][lane]
    }

    /** Sets one signed 16-bit lane in a vector register (test/debug API). */
    fun setVectorLane(register: Int, lane: Int, value: Short) {
        require(register in 0..31 && lane in 0..7)
        vector[register][lane] = value
    }

    fun setPc(address: Int) {
        require(address in 0 until N64SpMemory.MEMORY_SIZE && address % 4 == 0)
        pc = address
        pendingTarget = null
    }

    /**
     * Runs until the SP HALT flag is set again or the instruction budget is exhausted.
     * Returns the number of instructions executed. SP status command bit 0 must clear
     * HALT before this method starts executing.
     */
    fun run(maxInstructions: Int): Int {
        require(maxInstructions > 0) { "Instruction budget must be positive" }
        var executed = 0
        while (executed < maxInstructions && !bus.isSpHalted()) {
            step()
            executed++
        }
        return executed
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
                2 -> writeRegister(rd, b ushr shamt) // SRL
                3 -> writeRegister(rd, b shr shamt) // SRA
                4 -> writeRegister(rd, b shl (a and 31)) // SLLV
                6 -> writeRegister(rd, b ushr (a and 31)) // SRLV
                7 -> writeRegister(rd, b shr (a and 31)) // SRAV
                8 -> pendingTarget = a // JR
                9 -> {
                    writeRegister(rd, (pcPlus4 + 4) and 0xFFC)
                    pendingTarget = a
                } // JALR
                13 -> bus.markSpBroke() // BREAK
                16 -> writeRegister(rd, hi) // MFHI
                17 -> hi = a // MTHI
                18 -> writeRegister(rd, lo) // MFLO
                19 -> lo = a // MTLO
                24 -> {
                    val product = a.toLong() * b.toLong()
                    lo = product.toInt()
                    hi = (product shr 32).toInt()
                } // MULT
                25 -> {
                    val product = a.toUInt().toULong() * b.toUInt().toULong()
                    lo = product.toInt()
                    hi = (product shr 32).toInt()
                } // MULTU
                26 -> if (b != 0) {
                    if (a == Int.MIN_VALUE && b == -1) {
                        lo = Int.MIN_VALUE
                        hi = 0
                    } else {
                        lo = a / b
                        hi = a % b
                    }
                } // DIV; divide by zero leaves HI/LO unchanged
                27 -> if (b != 0) {
                    lo = (a.toUInt() / b.toUInt()).toInt()
                    hi = (a.toUInt() % b.toUInt()).toInt()
                } // DIVU; divide by zero leaves HI/LO unchanged
                33 -> writeRegister(rd, a + b) // ADDU
                35 -> writeRegister(rd, a - b) // SUBU
                36 -> writeRegister(rd, a and b) // AND
                37 -> writeRegister(rd, a or b) // OR
                38 -> writeRegister(rd, a xor b) // XOR
                39 -> writeRegister(rd, (a or b).inv()) // NOR
                42 -> writeRegister(rd, if (a < b) 1 else 0) // SLT
                43 -> writeRegister(rd, if (a.toUInt() < b.toUInt()) 1 else 0) // SLTU
                else -> unsupported(instruction)
            }
            18 -> if (rs >= 16) executeVector(instruction, rs and 15, rt, rd, shamt)
                else unsupported(instruction)
            16 -> when (rs) {
                0 -> writeRegister(rt, readSpCop0(rd)) // MFC0
                4 -> writeSpCop0(rd, b) // MTC0
                else -> unsupported(instruction)
            }
            1 -> when (rt) {
                0 -> if (a < 0) pendingTarget = pcPlus4 + (immediate shl 2) // BLTZ
                1 -> if (a >= 0) pendingTarget = pcPlus4 + (immediate shl 2) // BGEZ
                else -> unsupported(instruction)
            }
            2 -> pendingTarget = (pcPlus4 and 0xF0000000.toInt()) or
                ((instruction and 0x03FFFFFF) shl 2) // J
            3 -> {
                writeRegister(31, (pcPlus4 + 4) and 0xFFC)
                pendingTarget = (pcPlus4 and 0xF0000000.toInt()) or
                    ((instruction and 0x03FFFFFF) shl 2) // JAL
            }
            4 -> if (a == b) pendingTarget = pcPlus4 + (immediate shl 2) // BEQ
            5 -> if (a != b) pendingTarget = pcPlus4 + (immediate shl 2) // BNE
            6 -> if (a <= 0) pendingTarget = pcPlus4 + (immediate shl 2) // BLEZ
            7 -> if (a > 0) pendingTarget = pcPlus4 + (immediate shl 2) // BGTZ
            9 -> writeRegister(rt, a + immediate) // ADDIU
            10 -> writeRegister(rt, if (a < immediate) 1 else 0) // SLTI
            11 -> writeRegister(rt, if (a.toUInt() < immediate.toUInt()) 1 else 0) // SLTIU
            12 -> writeRegister(rt, a and unsignedImmediate) // ANDI
            13 -> writeRegister(rt, a or unsignedImmediate) // ORI
            14 -> writeRegister(rt, a xor unsignedImmediate) // XORI
            15 -> writeRegister(rt, unsignedImmediate shl 16) // LUI
            32 -> writeRegister(rt, bus.readByte(dmemAddress(a + immediate)).toInt()) // LB
            33 -> writeRegister(rt, bus.readHalf(dmemAddress(a + immediate)).toInt()) // LH
            35 -> writeRegister(rt, bus.readWord(dmemAddress(a + immediate))) // LW
            36 -> writeRegister(rt, bus.readByte(dmemAddress(a + immediate)).toInt() and 0xFF) // LBU
            37 -> writeRegister(rt, bus.readHalf(dmemAddress(a + immediate)).toInt() and 0xFFFF) // LHU
            40 -> bus.writeByte(dmemAddress(a + immediate), b.toByte()) // SB
            41 -> bus.writeHalf(dmemAddress(a + immediate), b.toShort()) // SH
            43 -> bus.writeWord(dmemAddress(a + immediate), b) // SW
            else -> unsupported(instruction)
        }
    }

    private fun executeVector(instruction: Int, element: Int, vt: Int, vs: Int, vd: Int) {
        // Start with the identity element mapping only. Other selectors need the
        // RSP's lane-broadcast/element-routing table and are rejected explicitly.
        if (element != 0) unsupported(instruction)
        val function = instruction and 63
        if (function !in setOf(0x10, 0x11, 0x13, 0x14, 0x15, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F)) {
            unsupported(instruction)
        }
        var nextVco = 0
        for (lane in 0..7) {
            val a = vector[vs][lane].toInt()
            val b = vector[vt][lane].toInt()
            val carryIn = if ((vco and (1 shl lane)) != 0) 1 else 0
            val result = when (function) {
                0x10 -> (a + b + carryIn)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) // VADD
                0x11 -> (a - b - carryIn)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) // VSUB
                0x13 -> when {
                    a < 0 -> -b
                    a > 0 -> b
                    else -> 0
                } // VABS: sign comes from vs; magnitude comes from vt
                0x14 -> {
                    val sum = (a and 0xFFFF) + (b and 0xFFFF)
                    if (sum > 0xFFFF) nextVco = nextVco or (1 shl lane)
                    sum and 0xFFFF // VADDC
                }
                0x15 -> {
                    val ua = a and 0xFFFF
                    val ub = b and 0xFFFF
                    val difference = (ua - ub) and 0xFFFF
                    if (ua < ub) nextVco = nextVco or (1 shl lane)
                    if (difference != 0) nextVco = nextVco or (1 shl (lane + 8))
                    difference // VSUBC
                }
                0x28 -> a and b // VAND
                0x29 -> (a and b).inv() // VNAND
                0x2A -> a or b // VOR
                0x2B -> (a or b).inv() // VNOR
                0x2C -> a xor b // VXOR
                0x2D -> (a xor b).inv() // VNXOR
                0x2E -> a and b.inv() // VANDC
                0x2F -> a or b.inv() // VORC
                else -> unsupported(instruction)
            }
            vector[vd][lane] = result.toShort()
        }
        when (function) {
            0x10, 0x11 -> vco = 0 // VADD and VSUB consume and clear VCO
            0x14, 0x15 -> vco = nextVco
        }
    }

    private fun readSpCop0(index: Int): Int {
        require(index in 0..7) { "Unsupported RSP COP0 register: $index" }
        return bus.readWord(N64MemoryBus.SP_MEM_ADDR + index * 4L)
    }

    private fun writeSpCop0(index: Int, value: Int) {
        require(index in 0..7) { "Unsupported RSP COP0 register: $index" }
        bus.writeWord(N64MemoryBus.SP_MEM_ADDR + index * 4L, value)
    }

    private fun dmemAddress(address: Int): Long {
        // SP DMEM is a 4 KiB local address space; effective addresses wrap
        // through the low 12 bits rather than escaping into another region.
        return N64SpMemory.DMEM_BASE + (address and (N64SpMemory.MEMORY_SIZE - 1))
    }

    private fun writeRegister(index: Int, value: Int) {
        if (index != 0) gpr[index] = value
    }

    private fun unsupported(instruction: Int): Nothing =
        throw IllegalArgumentException("Unsupported scalar RSP instruction 0x${instruction.toString(16)}")
}
