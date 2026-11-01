package com.jsr.n64android

/**
 * First independent scalar RSP interpreter.
 *
 * This is deliberately a small, explicit MIPS-I subset for SP IMEM/DMEM.
 * It is not the R4300 CPU and does not reuse its implementation. Vector
 * instructions, COP0, DMA scheduling and graphics microcode are not included.
 */
class N64RspCore(private val bus: N64Bus) {
    val gpr = IntArray(32)
    var pc: Int = 0
        private set
    var executedInstructions: Long = 0
        private set
    var lastInstruction: Int = 0
        private set
    val halted: Boolean
        get() = (bus.read32(N64Bus.SP_STATUS_REG) and N64Bus.SP_STATUS_HALT) != 0

    private var delayedTarget: Int? = null

    init {
        pc = bus.read32(N64Bus.SP_PC_REG) and PC_MASK
    }

    fun reset(programCounter: Int = 0) {
        require(programCounter and 3 == 0)
        pc = programCounter and PC_MASK
        gpr.fill(0)
        executedInstructions = 0
        lastInstruction = 0
        delayedTarget = null
        bus.write32(N64Bus.SP_PC_REG, pc)
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
                0x0D -> { bus.write32(N64Bus.SP_STATUS_REG, N64Bus.SP_STATUS_CMD_SET_HALT); delayedTarget = null }
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
            0x02, 0x03 -> {
                if (op == 0x03) gpr[31] = pc
                delayedTarget = ((pc and 0xF0000000.toInt()) or ((i and 0x03FFFFFF) shl 2)) and PC_MASK
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
