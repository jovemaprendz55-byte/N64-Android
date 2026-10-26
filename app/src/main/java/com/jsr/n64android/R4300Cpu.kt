package com.jsr.n64android

import java.math.BigInteger

/**
 * First executable R4300 subset for bring-up and unit tests.
 * This is not a complete MIPS III/R4300 implementation: remaining instructions,
 * exceptions, COP0/TLB, cache operations and full N64 devices remain unimplemented.
 */
class R4300Cpu(private val memory: N64AddressSpace) {
    val gpr: LongArray = LongArray(32)
    var hi: Long = 0L
        private set
    var lo: Long = 0L
        private set
    /** Basic 32-bit COP0 register storage; exception and privilege semantics are not modeled yet. */
    val cop0: LongArray = LongArray(32)
    var pc: Long = RESET_VECTOR
        private set
    private var delayedTarget: Long? = null

    fun reset() {
        gpr.fill(0L)
        hi = 0L
        lo = 0L
        cop0.fill(0L)
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
        var directPc: Long? = null

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
                0x10 -> gpr[rd] = hi // MFHI
                0x11 -> hi = gpr[rs] // MTHI
                0x12 -> gpr[rd] = lo // MFLO
                0x13 -> lo = gpr[rs] // MTLO
                0x18 -> {
                    val product = gpr[rs].toInt().toLong() * gpr[rt].toInt().toLong()
                    lo = product.toInt().toLong()
                    hi = (product shr 32).toInt().toLong()
                }
                0x19 -> {
                    val left = gpr[rs].toInt().toLong() and 0xFFFFFFFFL
                    val right = gpr[rt].toInt().toLong() and 0xFFFFFFFFL
                    val product = left * right
                    lo = product.toInt().toLong() and 0xFFFFFFFFL
                    hi = (product ushr 32) and 0xFFFFFFFFL
                }
                0x1A -> {
                    val dividend = gpr[rs].toInt()
                    val divisor = gpr[rt].toInt()
                    if (divisor != 0) {
                        val quotient = if (dividend == Int.MIN_VALUE && divisor == -1) Int.MIN_VALUE else dividend / divisor
                        val remainder = if (dividend == Int.MIN_VALUE && divisor == -1) 0 else dividend % divisor
                        lo = quotient.toLong()
                        hi = remainder.toLong()
                    } else {
                        lo = if (dividend < 0) 1L else -1L
                        hi = dividend.toLong()
                    }
                }
                0x1B -> {
                    val dividend = gpr[rs].toInt().toLong() and 0xFFFFFFFFL
                    val divisor = gpr[rt].toInt().toLong() and 0xFFFFFFFFL
                    if (divisor != 0L) {
                        lo = (dividend / divisor) and 0xFFFFFFFFL
                        hi = (dividend % divisor) and 0xFFFFFFFFL
                    } else {
                        lo = 0xFFFFFFFFL
                        hi = dividend
                    }
                }
                0x1C -> { // DMULT: signed 64-bit product, split into HI/LO
                    val product = BigInteger.valueOf(gpr[rs]).multiply(BigInteger.valueOf(gpr[rt]))
                    lo = product.toLong()
                    hi = product.shiftRight(64).toLong()
                }
                0x1D -> { // DMULTU: unsigned 64-bit product, split into HI/LO
                    val product = unsignedBigInteger(gpr[rs]).multiply(unsignedBigInteger(gpr[rt]))
                    lo = product.toLong()
                    hi = product.shiftRight(64).toLong()
                }
                0x1E -> { // DDIV: signed 64-bit quotient/remainder
                    val dividend = gpr[rs]
                    val divisor = gpr[rt]
                    if (divisor != 0L) {
                        lo = dividend / divisor
                        hi = dividend % divisor
                    } // Division by zero has architecturally unpredictable results.
                }
                0x1F -> { // DDIVU: unsigned 64-bit quotient/remainder
                    val dividend = unsignedBigInteger(gpr[rs])
                    val divisor = unsignedBigInteger(gpr[rt])
                    if (divisor != BigInteger.ZERO) {
                        val result = dividend.divideAndRemainder(divisor)
                        lo = result[0].toLong()
                        hi = result[1].toLong()
                    } // Division by zero has architecturally unpredictable results.
                }
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
            0x10 -> { // COP0: minimal MFC0/MTC0 register transfers
                val copRs = rs
                val copRd = (instruction ushr 11) and 0x1F
                when (copRs) {
                    0x00 -> gpr[rt] = cop0[copRd].toInt().toLong() // MFC0 sign-extends 32-bit value
                    0x04 -> cop0[copRd] = gpr[rt].toInt().toLong() // MTC0 writes low 32 bits
                    0x10 -> { // COP0 CO-format operations
                        when (funct) {
                            0x18 -> { // ERET
                                val status = cop0[12]
                                if ((status and 0x4L) != 0L) {
                                    cop0[12] = status and 0x4L.inv() // clear ERL
                                    directPc = cop0[30] // ErrorEPC
                                } else {
                                    cop0[12] = status and 0x2L.inv() // clear EXL
                                    directPc = cop0[14] // EPC
                                }
                            }
                            else -> throw UnsupportedOperationException(
                                "R4300 COP0 CO funct=0x${funct.toString(16)} at 0x${instructionAddress.toString(16)}"
                            )
                        }
                    }
                    else -> throw UnsupportedOperationException(
                        "R4300 COP0 rs=0x${copRs.toString(16)} at 0x${instructionAddress.toString(16)}"
                    )
                }
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

        pc = targetFromPreviousBranch ?: directPc ?: nextSequential
        gpr[0] = 0L
    }

    private fun unsignedBigInteger(value: Long): BigInteger {
        val signed = BigInteger.valueOf(value)
        return if (value >= 0L) signed else signed.add(BigInteger.ONE.shiftLeft(64))
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
