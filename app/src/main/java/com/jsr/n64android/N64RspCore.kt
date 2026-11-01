package com.jsr.n64android

/**
 * First independent scalar RSP interpreter.
 *
 * This is deliberately a small, explicit MIPS-I subset for SP IMEM/DMEM.
 * It is not the R4300 CPU and does not reuse its implementation. COP2 support
 * is an explicitly limited initial vector subset; DMA scheduling and graphics
 * microcode are not included.
 */
class N64RspCore(private val bus: N64Bus) {
    val gpr = IntArray(32)
    val vectorUnit = N64RspVectorUnit()
    var pc: Int = 0
        private set
    var executedInstructions: Long = 0
        private set
    var lastInstruction: Int = 0
        private set
    var hi: Int = 0
        private set
    var lo: Int = 0
        private set
    val halted: Boolean
        get() = (bus.read32(N64Bus.SP_STATUS_REG) and N64Bus.SP_STATUS_HALT) != 0

    private var delayedTarget: Int? = null
    private var instructionListener: ((pc: Int, instruction: Int) -> Unit)? = null

    /** Observes completed instruction execution; does not alter RSP timing or state. */
    fun setInstructionListener(listener: ((pc: Int, instruction: Int) -> Unit)?) {
        instructionListener = listener
    }

    init {
        pc = bus.read32(N64Bus.SP_PC_REG) and PC_MASK
    }

    fun reset(programCounter: Int = 0) {
        require(programCounter and 3 == 0)
        pc = programCounter and PC_MASK
        gpr.fill(0)
        executedInstructions = 0
        lastInstruction = 0
        hi = 0
        lo = 0
        delayedTarget = null
        bus.write32(N64Bus.SP_PC_REG, pc)
    }

    /** Full scalar and vector state for a single diagnostic export. */
    fun diagnosticSnapshot(): String = buildString {
        appendLine("RSP PC=0x${pc.toUInt().toString(16).uppercase().padStart(4, '0')} LAST=0x${lastInstruction.toUInt().toString(16).uppercase().padStart(8, '0')} COUNT=$executedInstructions HI=0x${hi.toUInt().toString(16).uppercase()} LO=0x${lo.toUInt().toString(16).uppercase()} HALTED=${(bus.diagnosticSpStatus() and N64Bus.SP_STATUS_HALT) != 0}")
        appendLine("RSP GPR:")
        for (index in 0 until 32) appendLine("RSP GPR[$index]=0x${gpr[index].toUInt().toString(16).uppercase().padStart(8, '0')}")
        appendLine("RSP VECTOR UNIT:")
        append(vectorUnit.diagnosticSnapshot())
    }

    /** Executes one instruction; returns false when SP is halted. */
    fun step(): Boolean {
        if (halted) return false
        val instructionPc = pc
        val instruction = bus.read32(N64Bus.SP_IMEM_BASE + (instructionPc and PC_MASK))
        lastInstruction = instruction
        val priorDelay = delayedTarget
        delayedTarget = null
        pc = (instructionPc + 4) and PC_MASK
        execute(instruction)
        if (priorDelay != null) pc = priorDelay and PC_MASK
        gpr[0] = 0
        executedInstructions++
        bus.write32(N64Bus.SP_PC_REG, pc)
        instructionListener?.invoke(instructionPc, instruction)
        return !halted
    }

    /** Runs no more than [maxInstructions], stopping on HALT or BREAK. */
    fun run(maxInstructions: Int): Int {
        require(maxInstructions >= 0)
        var count = 0
        while (count < maxInstructions && !halted) { step(); count++ }
        return count
    }

    private fun execute(i: Int) {
        val op = i ushr 26
        val rs = (i ushr 21) and 31
        val rt = (i ushr 16) and 31
        val rd = (i ushr 11) and 31
        val shamt = (i ushr 6) and 31
        val imm = i.toShort().toInt()
        val uimm = i and 0xFFFF
        val a = gpr[rs]
        val b = gpr[rt]
        when (op) {
            0x00 -> when (i and 63) {
                0x00 -> gpr[rd] = b shl shamt
                0x02 -> gpr[rd] = b ushr shamt
                0x03 -> gpr[rd] = b shr shamt
                0x04 -> gpr[rd] = b shl (a and 31)
                0x06 -> gpr[rd] = b ushr (a and 31)
                0x07 -> gpr[rd] = b shr (a and 31)
                0x08 -> delayedTarget = a and PC_MASK
                0x09 -> { gpr[rd] = pc; delayedTarget = a and PC_MASK }
                0x0D -> { bus.signalSpBreak(); delayedTarget = null }
                0x10 -> gpr[rd] = hi // MFHI
                0x11 -> hi = a // MTHI
                0x12 -> gpr[rd] = lo // MFLO
                0x13 -> lo = a // MTLO
                0x18 -> { // MULT signed
                    val product = a.toLong() * b.toLong()
                    lo = product.toInt()
                    hi = (product shr 32).toInt()
                }
                0x19 -> { // MULTU unsigned
                    val product = a.toUInt().toLong() * b.toUInt().toLong()
                    lo = product.toInt()
                    hi = (product ushr 32).toInt()
                }
                0x21 -> gpr[rd] = a + b
                0x23 -> gpr[rd] = a - b
                0x24 -> gpr[rd] = a and b
                0x25 -> gpr[rd] = a or b
                0x26 -> gpr[rd] = a xor b
                0x27 -> gpr[rd] = a.inv() and b.inv()
                0x2A -> gpr[rd] = if (a < b) 1 else 0
                0x2B -> gpr[rd] = if (a.toUInt() < b.toUInt()) 1 else 0
                else -> unsupported(i)
            }
            0x12 -> executeCop2(i, rs)
            0x32 -> executeVectorMemory(i, load = true)
            0x3A -> executeVectorMemory(i, load = false)
            0x10 -> when (rs) {
                0x00 -> gpr[rt] = readSpCop0(rd) // MFC0
                0x04 -> writeSpCop0(rd, b) // MTC0
                else -> unsupported(i)
            }
            0x02, 0x03 -> {
                if (op == 0x03) gpr[31] = pc
                delayedTarget = ((pc and 0xF0000000.toInt()) or ((i and 0x03FFFFFF) shl 2)) and PC_MASK
            }
            0x01 -> { // REGIMM: BLTZ / BGEZ
                val takeBranch = when (rt) {
                    0x00 -> a < 0 // BLTZ
                    0x01 -> a >= 0 // BGEZ
                    else -> unsupported(i)
                }
                if (takeBranch) delayedTarget = (pc + (imm shl 2)) and PC_MASK
            }
            0x04 -> if (a == b) delayedTarget = (pc + (imm shl 2)) and PC_MASK
            0x05 -> if (a != b) delayedTarget = (pc + (imm shl 2)) and PC_MASK
            0x06 -> if (a <= 0) delayedTarget = (pc + (imm shl 2)) and PC_MASK
            0x07 -> if (a > 0) delayedTarget = (pc + (imm shl 2)) and PC_MASK
            0x08, 0x09 -> gpr[rt] = a + imm
            0x0A -> gpr[rt] = if (a < imm) 1 else 0
            0x0B -> gpr[rt] = if (a.toUInt() < imm.toUInt()) 1 else 0
            0x0C -> gpr[rt] = a and uimm
            0x0D -> gpr[rt] = a or uimm
            0x0E -> gpr[rt] = a xor uimm
            0x0F -> gpr[rt] = uimm shl 16
            0x20 -> gpr[rt] = load8(a + imm).toByte().toInt()
            0x21 -> gpr[rt] = load16(a + imm).toShort().toInt()
            0x23 -> gpr[rt] = load32(a + imm)
            0x24 -> gpr[rt] = load8(a + imm)
            0x25 -> gpr[rt] = load16(a + imm)
            0x28 -> store8(a + imm, b)
            0x29 -> store16(a + imm, b)
            0x2B -> store32(a + imm, b)
            else -> unsupported(i)
        }
    }



    /**
     * Executes the initial COP2 vector arithmetic subset. RSP vector-format
     * instructions use rs=0x10..0x1F; the low four rs bits select element.
     * This first integration intentionally accepts element=0 only.
     */
    private fun executeCop2(instruction: Int, rs: Int) {
        val rt = (instruction ushr 16) and 31
        val vectorRegister = (instruction ushr 11) and 31
        when (rs) {
            0x00 -> { // MFC2: byte element offset, sign-extended halfword
                val element = (instruction ushr 7) and 0x0F
                val high = vectorUnit.readByte(vectorRegister, element)
                val low = vectorUnit.readByte(vectorRegister, (element + 1) and 0x0F)
                val value = (high shl 8) or low
                gpr[rt] = value.toShort().toInt()
                return
            }
            0x02 -> { // CFC2: VCO, VCC or VCE
                val control = vectorUnit.readControl(vectorRegister)
                gpr[rt] = if (vectorRegister == 2) control.toByte().toInt() else control.toShort().toInt()
                return
            }
            0x04 -> { // MTC2: byte element offset; final byte does not wrap
                val element = (instruction ushr 7) and 0x0F
                vectorUnit.writeByte(vectorRegister, element, (gpr[rt] ushr 8) and 0xFF)
                if (element != 15) {
                    vectorUnit.writeByte(vectorRegister, element + 1, gpr[rt] and 0xFF)
                }
                return
            }
            0x06 -> { // CTC2: write vector control register
                vectorUnit.writeControl(vectorRegister, gpr[rt] and 0xFFFF)
                return
            }
        }
        if (rs !in 0x10..0x1F) unsupported(instruction)
        val element = rs and 0x0F
        val vt = (instruction ushr 16) and 31
        val vs = (instruction ushr 11) and 31
        val vd = (instruction ushr 6) and 31
        when (instruction and 63) {
            0x10 -> vectorUnit.add(vd, vs, vt, element) // VADD
            0x11 -> vectorUnit.subtract(vd, vs, vt, element) // VSUB
            0x28 -> vectorUnit.and(vd, vs, vt, element) // VAND
            0x2A -> vectorUnit.or(vd, vs, vt, element) // VOR
            0x2B -> vectorUnit.nor(vd, vs, vt, element) // VNOR
            0x2C -> vectorUnit.xor(vd, vs, vt, element) // VXOR
            else -> unsupported(instruction)
        }
    }


    /**
     * Initial scalar vector-memory subset: LBV/LSV/LLV/LDV and stores.
     * Loads stop at the end of the vector register; stores wrap within it.
     * The signed 7-bit displacement is scaled by the transfer width.
     */
    private fun executeVectorMemory(instruction: Int, load: Boolean) {
        val base = (instruction ushr 21) and 31
        val vectorRegister = (instruction ushr 16) and 31
        val operation = (instruction ushr 11) and 31
        val element = (instruction ushr 7) and 0x0F
        val rawOffset = instruction and 0x7F
        val offset = if ((rawOffset and 0x40) != 0) rawOffset - 0x80 else rawOffset
        if (operation == 8) {
            // LHV/SHV use a 16-byte-scaled displacement and alternate DMEM bytes.
            // SGI specifies element=0 for LHV; non-zero element behavior remains unsupported.
            if (element != 0) {
                throw UnsupportedOperationException("RSP LHV/SHV currently supports element=0 only.")
            }
            val address = (gpr[base] + offset * 16) and 0xFFF
            if (load) {
                // LHV reads from the effective address, advancing two DMEM bytes per lane.
                // The effective address itself selects the alternating byte parity.
                for (lane in 0 until 8) {
                    val value = bus.read8(dmemAddress(address + lane * 2))
                    vectorUnit.writeLane(vectorRegister, lane, value shl 7)
                }
            } else {
                // SHV remains limited to the initial element=0 fixture. Its exact
                // rotated source mapping is intentionally isolated for later validation.
                val alignedStart = address and 0xFF0
                for (lane in 0 until 8) {
                    val value = (vectorUnit.readLane(vectorRegister, lane) ushr 7) and 0xFF
                    bus.write8(dmemAddress(alignedStart + lane * 2 + 1), value)
                }
            }
            return
        }
        if (operation == 6 || operation == 7) {
            // Packed transfers use an 8-byte-scaled signed displacement.
            val address = (gpr[base] + offset * 8) and 0xFFF
            if (load) {
                // LPV/LUV gather eight bytes from a 16-byte window rotated by element.
                for (lane in 0 until 8) {
                    val memoryIndex = ((16 - element + lane) and 0x0F)
                    val value = bus.read8(dmemAddress(address + memoryIndex))
                    val laneValue = if (operation == 6) value shl 8 else value shl 7
                    vectorUnit.writeLane(vectorRegister, lane, laneValue)
                }
            } else {
                // SPV/SUV emit eight consecutive bytes. The element cursor wraps
                // over 16 byte positions; positions 8..15 use the opposite packing.
                for (index in 0 until 8) {
                    val vectorElement = (element + index) and 0x0F
                    val lane = vectorElement and 0x07
                    val upperHalf = vectorElement < 8
                    val useHighByte = if (operation == 6) upperHalf else !upperHalf
                    val value = if (useHighByte) {
                        vectorUnit.readByte(vectorRegister, lane * 2)
                    } else {
                        (vectorUnit.readLane(vectorRegister, lane) ushr 7) and 0xFF
                    }
                    bus.write8(dmemAddress(address + index), value)
                }
            }
            return
        }
        if (operation == 4 || operation == 5) {
            val address = (gpr[base] + offset * 16) and 0xFFF
            val misalignment = address and 0x0F
            if (operation == 4) {
                // LQV/SQV transfer from EA through the end of its 16-byte block.
                val count = 16 - misalignment
                if (load) {
                    val transfer = minOf(count, 16 - element)
                    for (index in 0 until transfer) {
                        vectorUnit.writeByte(vectorRegister, element + index, bus.read8(dmemAddress(address + index)))
                    }
                } else {
                    for (index in 0 until count) {
                        val vectorByte = (element + index) and 0x0F
                        bus.write8(dmemAddress(address + index), vectorUnit.readByte(vectorRegister, vectorByte))
                    }
                }
            } else {
                // LRV/SRV transfer the bytes before EA, from the preceding aligned block.
                val count = minOf(misalignment, 16 - element)
                val memoryStart = (address - misalignment) and 0xFFF
                val vectorStart = 16 - count
                if (load) {
                    for (index in 0 until count) {
                        vectorUnit.writeByte(vectorRegister, vectorStart + index, bus.read8(dmemAddress(memoryStart + index)))
                    }
                } else {
                    val storeCount = misalignment
                    val sourceStart = (element + 16 - storeCount) and 0x0F
                    for (index in 0 until storeCount) {
                        val vectorByte = (sourceStart + index) and 0x0F
                        bus.write8(dmemAddress(memoryStart + index), vectorUnit.readByte(vectorRegister, vectorByte))
                    }
                }
            }
            return
        }
        val size = when (operation) {
            0 -> 1 // LBV / SBV
            1 -> 2 // LSV / SSV
            2 -> 4 // LLV / SLV
            3 -> 8 // LDV / SDV
            else -> unsupported(instruction)
        }
        val address = (gpr[base] + offset * size) and 0xFFF
        val transferBytes = if (load) minOf(size, 16 - element) else size
        for (index in 0 until transferBytes) {
            val vectorByte = if (load) element + index else (element + index) and 0x0F
            if (load) {
                vectorUnit.writeByte(vectorRegister, vectorByte, bus.read8(dmemAddress(address + index)))
            } else {
                bus.write8(dmemAddress(address + index), vectorUnit.readByte(vectorRegister, vectorByte))
            }
        }
    }

    /** SP COP0 register subset: DMA address/length, status and semaphore. */
    private fun readSpCop0(register: Int): Int {
        val address = when (register) {
            0 -> N64Bus.SP_MEM_ADDR_REG
            1 -> N64Bus.SP_DRAM_ADDR_REG
            2 -> N64Bus.SP_RD_LEN_REG
            3 -> N64Bus.SP_WR_LEN_REG
            4 -> N64Bus.SP_STATUS_REG
            5 -> N64Bus.SP_DMA_FULL_REG
            6 -> N64Bus.SP_DMA_BUSY_REG
            7 -> N64Bus.SP_SEMAPHORE_REG
            else -> unsupportedCop0(register)
        }
        return bus.read32(address)
    }

    private fun writeSpCop0(register: Int, value: Int) {
        val address = when (register) {
            0 -> N64Bus.SP_MEM_ADDR_REG
            1 -> N64Bus.SP_DRAM_ADDR_REG
            2 -> N64Bus.SP_RD_LEN_REG
            3 -> N64Bus.SP_WR_LEN_REG
            4 -> N64Bus.SP_STATUS_REG
            7 -> N64Bus.SP_SEMAPHORE_REG
            5, 6 -> return // DMA_FULL and DMA_BUSY are read-only.
            else -> unsupportedCop0(register)
        }
        bus.write32(address, value)
    }

    private fun unsupportedCop0(register: Int): Nothing =
        throw UnsupportedOperationException("Unsupported SP COP0 register $register.")

    private fun load8(address: Int): Int = bus.read8(dmemAddress(address))
    private fun load16(address: Int): Int = bus.read16(dmemAddress(address) and -2)
    private fun load32(address: Int): Int = bus.read32(dmemAddress(address) and -4)
    private fun store8(address: Int, value: Int) = bus.write8(dmemAddress(address), value)
    private fun store16(address: Int, value: Int) = bus.write16(dmemAddress(address) and -2, value)
    private fun store32(address: Int, value: Int) = bus.write32(dmemAddress(address) and -4, value)

    private fun dmemAddress(address: Int): Int =
        N64Bus.SP_DMEM_BASE + (address and 0xFFF)

    private fun unsupported(instruction: Int): Nothing =
        throw UnsupportedOperationException(
            "RSP instruction 0x${instruction.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "unsupported at PC=0x${pc.toString(16).uppercase().padStart(3, '0')}."
        )

    companion object {
        private const val PC_MASK = 0xFFC
    }
}
