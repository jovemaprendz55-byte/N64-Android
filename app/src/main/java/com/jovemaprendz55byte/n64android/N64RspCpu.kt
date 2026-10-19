package com.jovemaprendz55byte.n64android

/**
 * Minimal scalar RSP interpreter foundation.
 *
 * Implements a deliberately small MIPS-I integer subset for smoke tests.
 * Implements a limited vector subset (VMULF, VMULU, VMACF, VMACU, VSAR, VADD, VSUB, VABS, VADDC, VSUBC, VLT, VEQ, VNE, VGE, and logic operations) with
 * element selector e=0. Other vector operations, DMA scheduling, interrupts,
 * and cycle timing are not implemented. COP0 access is limited to SP registers.
 * Branches use a one-instruction delay slot.
 */
internal class N64RspCpu(private val bus: N64MemoryBus) {
    private val gpr = IntArray(32)
    private var hi = 0
    private var lo = 0
    private val vector = Array(32) { ShortArray(8) }
    // One signed 48-bit accumulator value per vector lane (kept in Long for safe intermediates).
    private val accumulator = LongArray(8)
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
                32 -> writeRegister(rd, a + b) // ADD (overflow exceptions are not modeled)
                34 -> writeRegister(rd, a - b) // SUB (overflow exceptions are not modeled)
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
            18 -> when {
                rs == 0 -> { // MFC2: move a selected vector halfword to a GPR
                    val element = (instruction ushr 7) and 15
                    val value = (getVectorByte(rd, element) shl 8) or
                        getVectorByte(rd, element + 1)
                    writeRegister(rt, value.toShort().toInt())
                }
                rs == 4 -> { // MTC2: move the low halfword of a GPR into vector bytes
                    val element = (instruction ushr 7) and 15
                    setVectorByte(rd, element, b ushr 8)
                    setVectorByte(rd, element + 1, b)
                }
                rs >= 16 -> executeVector(instruction, rs and 15, rt, rd, shamt)
                else -> unsupported(instruction)
            }
            50 -> when (rd) {
                0 -> { // LBV: load one byte into a vector register
                    val element = (instruction ushr 7) and 15
                    val offset = signExtend7(instruction and 0x7F)
                    val value = bus.readByte(dmemAddress(a + offset)).toInt() and 0xFF
                    setVectorByte(rt, element, value)
                }
                1 -> { // LSV: load two consecutive bytes; offset is scaled by two
                    val element = (instruction ushr 7) and 15
                    val offset = signExtend7(instruction and 0x7F) * 2
                    repeat(2) { index ->
                        val value = bus.readByte(dmemAddress(a + offset + index)).toInt() and 0xFF
                        setVectorByte(rt, element + index, value)
                    }
                }
                2 -> { // LLV: load four consecutive bytes; offset is scaled by four
                    val element = (instruction ushr 7) and 15
                    val offset = signExtend7(instruction and 0x7F) * 4
                    repeat(4) { index ->
                        val value = bus.readByte(dmemAddress(a + offset + index)).toInt() and 0xFF
                        setVectorByte(rt, element + index, value)
                    }
                }
                3 -> { // LDV: load eight consecutive bytes; offset is scaled by eight
                    val element = (instruction ushr 7) and 15
                    val offset = signExtend7(instruction and 0x7F) * 8
                    repeat(8) { index ->
                        val value = bus.readByte(dmemAddress(a + offset + index)).toInt() and 0xFF
                        setVectorByte(rt, element + index, value)
                    }
                }
                4 -> { // LQV: load up to the next 16-byte DMEM boundary
                    val element = (instruction ushr 7) and 15
                    val address = a + signExtend7(instruction and 0x7F) * 16
                    val count = minOf(16 - element, 16 - (address and 15))
                    repeat(count) { index ->
                        val value = bus.readByte(dmemAddress(address + index)).toInt() and 0xFF
                        setVectorByte(rt, element + index, value)
                    }
                }
                else -> unsupported(instruction)
            }
            58 -> when (rd) {
                0 -> { // SBV: store one byte from a vector register
                    val element = (instruction ushr 7) and 15
                    val offset = signExtend7(instruction and 0x7F)
                    bus.writeByte(dmemAddress(a + offset), getVectorByte(rt, element).toByte())
                }
                1 -> { // SSV: store two consecutive bytes; offset is scaled by two
                    val element = (instruction ushr 7) and 15
                    val offset = signExtend7(instruction and 0x7F) * 2
                    repeat(2) { index ->
                        bus.writeByte(
                            dmemAddress(a + offset + index),
                            getVectorByte(rt, element + index).toByte()
                        )
                    }
                }
                2 -> { // SLV: store four consecutive bytes; offset is scaled by four
                    val element = (instruction ushr 7) and 15
                    val offset = signExtend7(instruction and 0x7F) * 4
                    repeat(4) { index ->
                        bus.writeByte(
                            dmemAddress(a + offset + index),
                            getVectorByte(rt, element + index).toByte()
                        )
                    }
                }
                3 -> { // SDV: store eight consecutive bytes; offset is scaled by eight
                    val element = (instruction ushr 7) and 15
                    val offset = signExtend7(instruction and 0x7F) * 8
                    repeat(8) { index ->
                        bus.writeByte(
                            dmemAddress(a + offset + index),
                            getVectorByte(rt, element + index).toByte()
                        )
                    }
                }
                4 -> { // SQV: store through the next 16-byte DMEM boundary
                    val element = (instruction ushr 7) and 15
                    val address = a + signExtend7(instruction and 0x7F) * 16
                    val count = 16 - (address and 15)
                    repeat(count) { index ->
                        bus.writeByte(
                            dmemAddress(address + index),
                            getVectorByte(rt, element + index).toByte()
                        )
                    }
                }
                else -> unsupported(instruction)
            }
            16 -> when (rs) {
                0 -> writeRegister(rt, readSpCop0(rd)) // MFC0
                4 -> writeSpCop0(rd, b) // MTC0
                else -> unsupported(instruction)
            }
            1 -> when (rt) {
                0 -> if (a < 0) pendingTarget = pcPlus4 + (immediate shl 2) // BLTZ
                1 -> if (a >= 0) pendingTarget = pcPlus4 + (immediate shl 2) // BGEZ
                16 -> if (a < 0) { // BLTZAL
                    writeRegister(31, (pcPlus4 + 4) and 0xFFC)
                    pendingTarget = pcPlus4 + (immediate shl 2)
                }
                17 -> if (a >= 0) { // BGEZAL
                    writeRegister(31, (pcPlus4 + 4) and 0xFFC)
                    pendingTarget = pcPlus4 + (immediate shl 2)
                }
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
            8 -> writeRegister(rt, a + immediate) // ADDI (overflow exceptions are not modeled)
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
        val function = instruction and 63
        // VSAR uses the element field as a selector for accumulator slices.
        if (function == 0x1D) {
            val sliceShift = when (element) {
                8 -> 32 // accumulator high slice
                9 -> 16 // accumulator middle slice
                10 -> 0 // accumulator low slice
                else -> unsupported(instruction)
            }
            for (lane in 0..7) {
                vector[vd][lane] = ((accumulator[lane] shr sliceShift) and 0xFFFFL).toShort()
            }
            return
        }
        // Other operations currently implement only element selector zero.
        if (element != 0) unsupported(instruction)
        if (function !in setOf(0x00, 0x01, 0x07, 0x08, 0x09, 0x10, 0x11, 0x13, 0x14, 0x15, 0x20, 0x21, 0x22, 0x23, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F)) {
            unsupported(instruction)
        }
        var nextVco = 0
        for (lane in 0..7) {
            val a = vector[vs][lane].toInt()
            val b = vector[vt][lane].toInt()
            val carryIn = if ((vco and (1 shl lane)) != 0) 1 else 0
            val result = when (function) {
                0x00 -> {
                    // VMULF: signed fractional product, doubled with the RSP rounding constant.
                    val acc = a.toLong() * b.toLong() * 2L + 0x8000L
                    accumulator[lane] = signExtend48(acc)
                    (acc shr 16).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
                }
                0x01 -> {
                    // VMULU: fractional product with rounding and unsigned destination saturation.
                    val acc = a.toLong() * b.toLong() * 2L + 0x8000L
                    accumulator[lane] = signExtend48(acc)
                    saturateAccumulatorUnsigned(accumulator[lane])
                }
                0x07 -> {
                    // VMUDH: signed high-part multiply; place the 16x16 product in accumulator bits 47..16.
                    val acc = signExtend48((a.toLong() * b.toLong()) shl 16)
                    accumulator[lane] = acc
                    ((acc shr 32) and 0xFFFFL).toInt()
                }
                0x08 -> {
                    // VMACF: add a signed fractional product to the existing 48-bit accumulator.
                    val acc = signExtend48(accumulator[lane] + a.toLong() * b.toLong() * 2L)
                    accumulator[lane] = acc
                    (acc shr 16).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
                }
                0x09 -> {
                    // VMACU: accumulate a fractional product and apply unsigned destination saturation.
                    val acc = signExtend48(accumulator[lane] + a.toLong() * b.toLong() * 2L)
                    accumulator[lane] = acc
                    saturateAccumulatorUnsigned(acc)
                }
                0x10 -> (a + b + carryIn)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) // VADD
                0x11 -> (a - b - carryIn)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) // VSUB
                0x13 -> when {
                    // VABS operates on 16-bit lanes; negating -32768 wraps back to 0x8000.
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
                0x20 -> if (a < b || (a == b && (vco and (1 shl lane)) != 0)) a else b // VLT
                0x21 -> if (a == b && (vco and (1 shl lane)) == 0) a else b // VEQ
                0x22 -> if (a != b || (vco and (1 shl lane)) != 0) a else b // VNE
                0x23 -> if (a > b || (a == b && (vco and (1 shl lane)) == 0)) a else b // VGE
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
            0x20, 0x21, 0x22, 0x23 -> vco = 0 // Comparisons consume and clear VCO
        }
    }

    private fun saturateAccumulatorUnsigned(value: Long): Int {
        val high = (value shr 32) and 0xFFFFL
        val middle = (value shr 16) and 0xFFFFL
        return when {
            high == 0L && (middle and 0x8000L) != 0L -> 0xFFFF
            high == 0xFFFFL && (middle and 0x8000L) == 0L -> 0
            else -> middle.toInt()
        }
    }

    private fun signExtend48(value: Long): Long {
        val masked = value and 0x0000_FFFF_FFFF_FFFFL
        return if ((masked and (1L shl 47)) != 0L) masked or -0x0001_0000_0000_0000L else masked
    }

    private fun readSpCop0(index: Int): Int {
        require(index in 0..7) { "Unsupported RSP COP0 register: $index" }
        return bus.readWord(N64MemoryBus.SP_MEM_ADDR + index * 4L)
    }

    private fun writeSpCop0(index: Int, value: Int) {
        require(index in 0..7) { "Unsupported RSP COP0 register: $index" }
        bus.writeWord(N64MemoryBus.SP_MEM_ADDR + index * 4L, value)
    }

    private fun signExtend7(value: Int): Int =
        if (value and 0x40 != 0) value or -0x80 else value

    /** Vector byte element 0 is the high byte of lane 0, element 1 its low byte. */
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
