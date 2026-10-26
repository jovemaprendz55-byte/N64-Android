package com.jsr.n64android

/**
 * First executable R4300 subset for bring-up and unit tests.
 * This is not a complete MIPS III/R4300 implementation: exceptions, COP0/TLB,
 * 64-bit instructions, cache operations and the full N64 bus remain unimplemented.
 */
class R4300Cpu(private val memory: N64Memory) {
    val gpr: LongArray = LongArray(32)
    var pc: Long = RESET_VECTOR
        private set
    private var delayedTarget: Long? = null

    fun reset() {
        gpr.fill(0L)
        pc = RESET_VECTOR
        delayedTarget = null
    }

    fun setProgramCounter(address: Long) {
        pc = address
        delayedTarget = null
    }

    /** Executes one instruction. Branches and jumps take effect after the delay slot. */
    fun step() {
        val instructionAddress = pc
        val instruction = memory.read32(physicalAddress(instructionAddress))
        val targetFromPreviousBranch = delayedTarget
        delayedTarget = null

        val opcode = instruction ushr 26
        val rs = (instruction ushr 21) and 0x1F
        val rt = (instruction ushr 16) and 0x1F
        val rd = (instruction ushr 11) and 0x1F
        val shamt = (instruction ushr 6) and 0x1F
        val funct = instruction and 0x3F
        val immediate = instruction.toShort().toInt()
        val unsignedImmediate = instruction and 0xFFFF
        val nextSequential = instructionAddress + 4L

        when (opcode) {
            0 -> when (funct) {
                0x00 -> if (instruction != 0) gpr[rd] = (gpr[rt].toInt() shl shamt).toLong() // SLL
                0x02 -> gpr[rd] = (gpr[rt].toInt() ushr shamt).toLong() // SRL
                0x03 -> gpr[rd] = (gpr[rt].toInt() shr shamt).toLong() // SRA
                0x08 -> delayedTarget = gpr[rs] // JR
                0x09 -> { gpr[rd] = nextSequential + 4L; delayedTarget = gpr[rs] } // JALR
                0x21 -> gpr[rd] = (gpr[rs].toInt() + gpr[rt].toInt()).toLong() // ADDU
                0x23 -> gpr[rd] = (gpr[rs].toInt() - gpr[rt].toInt()).toLong() // SUBU
                0x24 -> gpr[rd] = (gpr[rs].toInt() and gpr[rt].toInt()).toLong()
                0x25 -> gpr[rd] = (gpr[rs].toInt() or gpr[rt].toInt()).toLong()
                0x26 -> gpr[rd] = (gpr[rs].toInt() xor gpr[rt].toInt()).toLong()
                0x27 -> gpr[rd] = (gpr[rs].toInt() or gpr[rt].toInt()).inv().toLong()
                0x2A -> gpr[rd] = if (gpr[rs].toInt() < gpr[rt].toInt()) 1L else 0L
                0x2B -> gpr[rd] = if (gpr[rs].toInt().toUInt() < gpr[rt].toInt().toUInt()) 1L else 0L
                else -> throw UnsupportedOperationException("R4300 SPECIAL funct 0x${funct.toString(16)} at 0x${instructionAddress.toString(16)}")
            }
            0x02, 0x03 -> {
                val jumpAddress = (nextSequential and -0x10000000L) or
                    ((instruction.toLong() and 0x03FFFFFFL) shl 2)
                if (opcode == 0x03) gpr[31] = nextSequential + 4L
                delayedTarget = jumpAddress
            }
            0x04, 0x05 -> {
                val equal = gpr[rs] == gpr[rt]
                if ((opcode == 0x04 && equal) || (opcode == 0x05 && !equal)) {
                    delayedTarget = nextSequential + (immediate.toLong() shl 2)
                }
            }
            0x09 -> gpr[rt] = (gpr[rs].toInt() + immediate).toLong() // ADDIU
            0x0C -> gpr[rt] = (gpr[rs].toInt() and unsignedImmediate).toLong()
            0x0D -> gpr[rt] = (gpr[rs].toInt() or unsignedImmediate).toLong()
            0x0E -> gpr[rt] = (gpr[rs].toInt() xor unsignedImmediate).toLong()
            0x0F -> gpr[rt] = (unsignedImmediate shl 16).toLong() // LUI
            0x20 -> gpr[rt] = memory.read8(physicalAddress(gpr[rs] + immediate)).toByte().toLong()
            0x24 -> gpr[rt] = memory.read8(physicalAddress(gpr[rs] + immediate)).toLong()
            0x21 -> gpr[rt] = memory.read16(physicalAddress(gpr[rs] + immediate)).toShort().toLong()
            0x25 -> gpr[rt] = memory.read16(physicalAddress(gpr[rs] + immediate)).toLong()
            0x23 -> gpr[rt] = memory.read32(physicalAddress(gpr[rs] + immediate)).toLong()
            0x28 -> memory.write8(physicalAddress(gpr[rs] + immediate), gpr[rt].toInt())
            0x29 -> memory.write16(physicalAddress(gpr[rs] + immediate), gpr[rt].toInt())
            0x2B -> memory.write32(physicalAddress(gpr[rs] + immediate), gpr[rt].toInt())
            else -> throw UnsupportedOperationException("R4300 opcode 0x${opcode.toString(16)} at 0x${instructionAddress.toString(16)}")
        }

        pc = targetFromPreviousBranch ?: nextSequential
        gpr[0] = 0L
    }

    private fun physicalAddress(address: Long): Int {
        // KSEG0/KSEG1 are directly mapped to physical addresses on the N64.
        val physical = if ((address ushr 29) and 0x7L in 4L..5L) {
            (address and 0x1FFFFFFFL)
        } else address
        require(physical >= 0L && physical <= Int.MAX_VALUE.toLong()) {
            "Address cannot be represented by this initial RAM bus: 0x${address.toString(16)}"
        }
        return physical.toInt()
    }

    companion object {
        const val RESET_VECTOR: Long = -0x40400000L
    }
}
