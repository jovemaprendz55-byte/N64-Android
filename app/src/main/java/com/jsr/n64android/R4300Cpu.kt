package com.jsr.n64android

/**
 * First executable R4300 subset for bring-up and unit tests.
 * This is not a complete MIPS III/R4300 implementation: remaining instructions,
 * exceptions, COP0/TLB, cache operations and full N64 devices remain unimplemented.
 */
class R4300Cpu(private val memory: N64AddressSpace) {
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
        require(address and 0x3L == 0L) { "Instruction address must be 4-byte aligned." }
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
                0x00 -> if (instruction != 0) gpr[rd] = (gpr[rt].toInt() shl shamt).toLong()
                0x02 -> gpr[rd] = (gpr[rt].toInt() ushr shamt).toLong()
                0x03 -> gpr[rd] = (gpr[rt].toInt() shr shamt).toLong()
                0x14 -> gpr[rd] = gpr[rt] shl (gpr[rs].toInt() and 0x3F) // DSLLV
                0x16 -> gpr[rd] = gpr[rt] ushr (gpr[rs].toInt() and 0x3F) // DSRLV
                0x17 -> gpr[rd] = gpr[rt] shr (gpr[rs].toInt() and 0x3F) // DSRAV
                0x2C -> gpr[rd] = gpr[rs] + gpr[rt] // DADD
                0x2D -> gpr[rd] = gpr[rs] + gpr[rt] // DADDU
                0x2E -> gpr[rd] = gpr[rs] - gpr[rt] // DSUB
                0x2F -> gpr[rd] = gpr[rs] - gpr[rt] // DSUBU
                0x38 -> gpr[rd] = gpr[rt] shl shamt // DSLL
                0x3A -> gpr[rd] = gpr[rt] ushr shamt // DSRL
                0x3B -> gpr[rd] = gpr[rt] shr shamt // DSRA
                0x3C -> gpr[rd] = gpr[rt] shl (shamt + 32) // DSLL32
                0x3E -> gpr[rd] = gpr[rt] ushr (shamt + 32) // DSRL32
                0x3F -> gpr[rd] = gpr[rt] shr (shamt + 32) // DSRA32
                0x0A -> if (gpr[rt] == 0L) gpr[rd] = gpr[rs] // MOVZ
                0x0B -> if (gpr[rt] != 0L) gpr[rd] = gpr[rs] // MOVN
                0x08 -> delayedTarget = gpr[rs]
                0x09 -> { gpr[rd] = nextSequential + 4L; delayedTarget = gpr[rs] }
                0x21 -> gpr[rd] = (gpr[rs].toInt() + gpr[rt].toInt()).toLong()
                0x23 -> gpr[rd] = (gpr[rs].toInt() - gpr[rt].toInt()).toLong()
                0x24 -> gpr[rd] = (gpr[rs].toInt() and gpr[rt].toInt()).toLong()
                0x25 -> gpr[rd] = (gpr[rs].toInt() or gpr[rt].toInt()).toLong()
                0x26 -> gpr[rd] = (gpr[rs].toInt() xor gpr[rt].toInt()).toLong()
                0x27 -> gpr[rd] = (gpr[rs].toInt() or gpr[rt].toInt()).inv().toLong()
                0x2A -> gpr[rd] = if (gpr[rs] < gpr[rt]) 1L else 0L
                0x2B -> gpr[rd] = if (java.lang.Long.compareUnsigned(gpr[rs], gpr[rt]) < 0) 1L else 0L
                else -> throw UnsupportedOperationException("R4300 SPECIAL funct 0x${funct.toString(16)} at 0x${instructionAddress.toString(16)}")
            }
            0x01 -> {
                val takeBranch = when (rt) {
                    0x00, 0x10 -> gpr[rs] < 0L // BLTZ / BLTZAL
                    0x01, 0x11 -> gpr[rs] >= 0L // BGEZ / BGEZAL
                    else -> throw UnsupportedOperationException(
                        "R4300 REGIMM rt=0x${rt.toString(16)} at 0x${instructionAddress.toString(16)}"
                    )
                }
                if (takeBranch) {
                    if (rt == 0x10 || rt == 0x11) gpr[31] = nextSequential + 4L
                    delayedTarget = nextSequential + (immediate.toLong() shl 2)
                }
            }
            0x02, 0x03 -> {
                val jumpAddress = (nextSequential and -0x10000000L) or
                    ((instruction.toLong() and 0x03FFFFFFL) shl 2)
                if (opcode == 0x03) gpr[31] = nextSequential + 4L
                delayedTarget = jumpAddress
            }
            0x04, 0x05, 0x06, 0x07 -> {
                val takeBranch = when (opcode) {
                    0x04 -> gpr[rs] == gpr[rt] // BEQ
                    0x05 -> gpr[rs] != gpr[rt] // BNE
                    0x06 -> rt == 0 && gpr[rs] <= 0L // BLEZ
                    else -> rt == 0 && gpr[rs] > 0L // BGTZ
                }
                if (takeBranch) delayedTarget = nextSequential + (immediate.toLong() shl 2)
            }
            0x08 -> gpr[rt] = (gpr[rs].toInt() + immediate).toLong() // ADDI (overflow exceptions not modeled)
            0x09 -> gpr[rt] = (gpr[rs].toInt() + immediate).toLong()
            0x18 -> gpr[rt] = gpr[rs] + immediate.toLong() // DADDI (overflow exceptions not modeled)
            0x19 -> gpr[rt] = gpr[rs] + immediate.toLong() // DADDIU
            0x0A -> gpr[rt] = if (gpr[rs] < immediate.toLong()) 1L else 0L // SLTI
            0x0B -> gpr[rt] = if (java.lang.Long.compareUnsigned(gpr[rs], immediate.toLong()) < 0) 1L else 0L // SLTIU
            0x0C -> gpr[rt] = gpr[rs] and unsignedImmediate.toLong()
            0x0D -> gpr[rt] = gpr[rs] or unsignedImmediate.toLong()
            0x0E -> gpr[rt] = gpr[rs] xor unsignedImmediate.toLong()
            0x0F -> gpr[rt] = (unsignedImmediate shl 16).toInt().toLong()
            0x20 -> gpr[rt] = memory.read8(physicalAddress(gpr[rs] + immediate)).toByte().toLong()
            0x24 -> gpr[rt] = memory.read8(physicalAddress(gpr[rs] + immediate)).toLong()
            0x21 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 2)
                gpr[rt] = memory.read16(physicalAddress(address)).toShort().toLong()
            }
            0x25 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 2)
                gpr[rt] = memory.read16(physicalAddress(address)).toLong()
            }
            0x23 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 4)
                gpr[rt] = memory.read32(physicalAddress(address)).toLong()
            }
            0x27 -> { // LWU: load a word and zero-extend to 64 bits
                val address = gpr[rs] + immediate
                requireAligned(address, 4)
                gpr[rt] = memory.read32(physicalAddress(address)).toLong() and 0xFFFFFFFFL
            }
            0x37 -> { // LD: load a big-endian doubleword
                val address = gpr[rs] + immediate
                requireAligned(address, 8)
                val high = memory.read32(physicalAddress(address)).toLong()
                val low = memory.read32(physicalAddress(address + 4L)).toLong() and 0xFFFFFFFFL
                gpr[rt] = (high shl 32) or low
            }
            0x28 -> memory.write8(physicalAddress(gpr[rs] + immediate), gpr[rt].toInt())
            0x29 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 2)
                memory.write16(physicalAddress(address), gpr[rt].toInt())
            }
            0x2B -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 4)
                memory.write32(physicalAddress(address), gpr[rt].toInt())
            }
            0x3F -> { // SD: store a big-endian doubleword
                val address = gpr[rs] + immediate
                requireAligned(address, 8)
                memory.write32(physicalAddress(address), (gpr[rt] ushr 32).toInt())
                memory.write32(physicalAddress(address + 4L), gpr[rt].toInt())
            }
            else -> throw UnsupportedOperationException("R4300 opcode 0x${opcode.toString(16)} at 0x${instructionAddress.toString(16)}")
        }

        pc = targetFromPreviousBranch ?: nextSequential
        gpr[0] = 0L
    }

    private fun requireAligned(address: Long, alignment: Int) {
        require((address and (alignment - 1).toLong()) == 0L) {
            "Unaligned $alignment-byte memory access at 0x${address.toString(16)}."
        }
    }

    private fun physicalAddress(address: Long): Int {
        // Treat sign-extended 32-bit virtual addresses as 32-bit N64 addresses.
        val virtual32 = address and 0xFFFFFFFFL
        val segment = (virtual32 ushr 29) and 0x7L
        val physical = if (segment == 4L || segment == 5L) {
            virtual32 and 0x1FFFFFFFL // KSEG0/KSEG1 direct-mapped segments
        } else {
            virtual32
        }
        require(physical <= Int.MAX_VALUE.toLong()) {
            "Address cannot be represented by this initial RAM bus: 0x${address.toString(16)}"
        }
        return physical.toInt()
    }

    companion object {
        const val RESET_VECTOR: Long = -0x40400000L
    }
}
