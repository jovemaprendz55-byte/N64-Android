package com.jsr.n64android

import java.math.BigInteger

/**
 * First executable R4300 subset for bring-up and unit tests.
 * This is not a complete MIPS III/R4300 implementation: remaining instructions,
 * full delay-slot exception semantics, complete COP0/TLB exception delivery, cache operations and full N64 devices remain unimplemented.
 */
class R4300Cpu(private val memory: N64AddressSpace) {
    val gpr: LongArray = LongArray(32)
    var hi: Long = 0L
        private set
    var lo: Long = 0L
        private set
    /** Basic COP0 storage with initial syscall/break exception and ERET handling. */
    val cop0: LongArray = LongArray(32)
    /** Floating-point register storage (raw 32-bit words; full paired-double semantics remain incomplete). */
    val fpr: LongArray = LongArray(32)
    /** Floating-point control registers; FCR31 holds the FPU condition/status flags. */
    val fcr: LongArray = LongArray(32)
    /** Initial 32-entry TLB model; opt-in until architectural TLB exceptions are wired. */
    val tlb: R4300Tlb = R4300Tlb()
    var tlbEnabled: Boolean = false
    var pc: Long = RESET_VECTOR
        private set
    /** Metadata for the most recently delivered architectural exception, used by ROM diagnostics. */
    var lastExceptionInstructionAddress: Long? = null
        private set
    var lastExceptionCode: Int? = null
        private set
    var lastExceptionBranchAddress: Long? = null
        private set
    /** Instruction word fetched at the most recently delivered exception, if fetch succeeded. */
    var lastExceptionInstructionWord: Int? = null
        private set
    /** Access that caused the most recent TLB exception (FETCH, READ, or WRITE). */
    var lastExceptionAccessType: String? = null
        private set
    /** Immutable snapshot of the last TLB fault, unaffected by later diagnostic memory accesses. */
    var lastTlbFaultVirtualAddress: Long? = null
        private set
    var lastTlbFaultAccessType: String? = null
        private set
    var lastTlbFaultKind: R4300Tlb.FaultKind? = null
        private set
    private var instructionWordForCurrentStep: Int? = null
    private var instructionAddressForCurrentStep: Long? = null
    /** Exact instruction context retained when a bus access fails during execution. */
    val diagnosticInstructionAddress: Long? get() = instructionAddressForCurrentStep
    val diagnosticInstructionWord: Int? get() = instructionWordForCurrentStep
    /** Most recent translated memory access, retained even if the physical bus throws. */
    var diagnosticVirtualAddress: Long? = null
        private set
    var diagnosticPhysicalAddress: Int? = null
        private set
    var diagnosticAccessType: String? = null
        private set
    private var delayedTarget: Long? = null
    private var pendingDelaySlotBranchAddress: Long? = null
    private var randomResetByWiredWrite = false
    /** Bounded record of actual TLBWI/TLBWR operations for ROM diagnostics. */
    val diagnosticTlbWriteHistory: MutableList<String> = mutableListOf()
    /** TLB contents frozen when the first architectural TLB fault is delivered. */
    var lastTlbEntrySnapshot: String = "(nenhuma entrada capturada)"
        private set

    fun reset() {
        gpr.fill(0L)
        hi = 0L
        lo = 0L
        cop0.fill(0L)
        fpr.fill(0L)
        fcr.fill(0L)
        tlb.clear()
        tlbEnabled = false
        pc = RESET_VECTOR
        lastExceptionInstructionAddress = null
        lastExceptionCode = null
        lastExceptionBranchAddress = null
        lastExceptionInstructionWord = null
        lastExceptionAccessType = null
        lastTlbFaultVirtualAddress = null
        lastTlbFaultAccessType = null
        lastTlbFaultKind = null
        instructionWordForCurrentStep = null
        instructionAddressForCurrentStep = null
        diagnosticVirtualAddress = null
        diagnosticPhysicalAddress = null
        diagnosticAccessType = null
        delayedTarget = null
        pendingDelaySlotBranchAddress = null
    }

    fun setProgramCounter(address: Long) {
        require(address and 0x3L == 0L) { "Instruction address must be 4-byte aligned." }
        pc = address
        delayedTarget = null
        pendingDelaySlotBranchAddress = null
    }

    /** Executes one instruction and converts address-translation faults into architectural COP0 exceptions. */
    fun step() {
        instructionWordForCurrentStep = null
        instructionAddressForCurrentStep = pc
        val branchAddressForFault = pendingDelaySlotBranchAddress
        randomResetByWiredWrite = false
        try {
            stepInstruction()
            if (!randomResetByWiredWrite) advanceRandomRegister()
        } catch (fault: R4300Tlb.TlbFault) {
            enterTlbException(fault, branchAddressForFault)
        } catch (fault: AddressFault) {
            enterAddressException(fault, branchAddressForFault)
        } catch (_: ReservedInstructionFault) {
            // Unsupported encodings raise the architectural Reserved Instruction exception (RI).
            enterException(instructionAddressForCurrentStep ?: pc, 10, branchAddressForFault)
            pc = exceptionVector()
            delayedTarget = null
            pendingDelaySlotBranchAddress = null
        }
    }

    /** Branches and jumps take effect after the delay slot. */
    private fun stepInstruction() {
        val instructionAddress = pc
        instructionAddressForCurrentStep = instructionAddress
        val instruction = memory.read32(physicalAddress(instructionAddress, R4300Tlb.Access.FETCH))
        instructionWordForCurrentStep = instruction
        val targetFromPreviousBranch = delayedTarget
        val branchAddressForCurrentInstruction = pendingDelaySlotBranchAddress
        delayedTarget = null
        pendingDelaySlotBranchAddress = null

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
                0x00 -> { // SLL; rs is reserved and must be zero.
                    if (rs != 0) throw ReservedInstructionFault("R4300 SLL has nonzero rs at 0x${instructionAddress.toString(16)}")
                    if (instruction != 0) gpr[rd] = (gpr[rt].toInt() shl shamt).toLong()
                }
                0x02 -> {
                    if (rs != 0) throw ReservedInstructionFault("R4300 SRL has nonzero rs at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = (gpr[rt].toInt() ushr shamt).toLong()
                }
                0x03 -> {
                    if (rs != 0) throw ReservedInstructionFault("R4300 SRA has nonzero rs at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = (gpr[rt].toInt() shr shamt).toLong()
                }
                0x04 -> {
                    if (shamt != 0) throw ReservedInstructionFault("R4300 SLLV has nonzero shamt at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = (gpr[rt].toInt() shl (gpr[rs].toInt() and 0x1F)).toLong()
                }
                0x06 -> {
                    if (shamt != 0) throw ReservedInstructionFault("R4300 SRLV has nonzero shamt at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = (gpr[rt].toInt() ushr (gpr[rs].toInt() and 0x1F)).toLong()
                }
                0x07 -> {
                    if (shamt != 0) throw ReservedInstructionFault("R4300 SRAV has nonzero shamt at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = (gpr[rt].toInt() shr (gpr[rs].toInt() and 0x1F)).toLong()
                }
                0x14 -> { // DSLLV
                    if (shamt != 0) throw ReservedInstructionFault("R4300 DSLLV has nonzero shamt at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] shl (gpr[rs].toInt() and 0x3F)
                }
                0x16 -> {
                    if (shamt != 0) throw ReservedInstructionFault("R4300 DSRLV has nonzero shamt at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] ushr (gpr[rs].toInt() and 0x3F)
                }
                0x17 -> {
                    if (shamt != 0) throw ReservedInstructionFault("R4300 DSRAV has nonzero shamt at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] shr (gpr[rs].toInt() and 0x3F)
                }
                0x2C -> { // DADD: signed 64-bit addition traps on overflow.
                    val left = gpr[rs]
                    val right = gpr[rt]
                    val result = left + right
                    if (((left xor result) and (right xor result)) < 0L) {
                        enterException(instructionAddress, 12, branchAddressForCurrentInstruction)
                        directPc = exceptionVector()
                    } else {
                        gpr[rd] = result
                    }
                }
                0x2D -> gpr[rd] = gpr[rs] + gpr[rt] // DADDU wraps without trapping.
                0x2E -> { // DSUB: signed 64-bit subtraction traps on overflow.
                    val left = gpr[rs]
                    val right = gpr[rt]
                    val result = left - right
                    if (((left xor right) and (left xor result)) < 0L) {
                        enterException(instructionAddress, 12, branchAddressForCurrentInstruction)
                        directPc = exceptionVector()
                    } else {
                        gpr[rd] = result
                    }
                }
                0x2F -> gpr[rd] = gpr[rs] - gpr[rt] // DSUBU wraps without trapping.
                0x38 -> {
                    if (rs != 0) throw ReservedInstructionFault("R4300 DSLL has nonzero rs at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] shl shamt
                }
                0x3A -> {
                    if (rs != 0) throw ReservedInstructionFault("R4300 DSRL has nonzero rs at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] ushr shamt
                }
                0x3B -> {
                    if (rs != 0) throw ReservedInstructionFault("R4300 DSRA has nonzero rs at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] shr shamt
                }
                0x3C -> {
                    if (rs != 0) throw ReservedInstructionFault("R4300 DSLL32 has nonzero rs at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] shl (shamt + 32)
                }
                0x3E -> {
                    if (rs != 0) throw ReservedInstructionFault("R4300 DSRL32 has nonzero rs at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] ushr (shamt + 32)
                }
                0x3F -> {
                    if (rs != 0) throw ReservedInstructionFault("R4300 DSRA32 has nonzero rs at 0x${instructionAddress.toString(16)}")
                    gpr[rd] = gpr[rt] shr (shamt + 32)
                }
                0x0A -> { // MOVZ
                    if (shamt != 0) throw ReservedInstructionFault("Malformed MOVZ reserved fields")
                    if (gpr[rt] == 0L) gpr[rd] = gpr[rs]
                }
                0x0B -> { // MOVN
                    if (shamt != 0) throw ReservedInstructionFault("Malformed MOVN reserved fields")
                    if (gpr[rt] != 0L) gpr[rd] = gpr[rs]
                }
                0x10 -> { // MFHI
                    if (rs != 0 || rt != 0 || shamt != 0) throw ReservedInstructionFault("Malformed MFHI reserved fields")
                    gpr[rd] = hi
                }
                0x11 -> { // MTHI
                    if (rt != 0 || rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed MTHI reserved fields")
                    hi = gpr[rs]
                }
                0x12 -> { // MFLO
                    if (rs != 0 || rt != 0 || shamt != 0) throw ReservedInstructionFault("Malformed MFLO reserved fields")
                    gpr[rd] = lo
                }
                0x13 -> { // MTLO
                    if (rt != 0 || rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed MTLO reserved fields")
                    lo = gpr[rs]
                }
                0x18 -> {
                    if (rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed MULT reserved fields")
                    val product = gpr[rs].toInt().toLong() * gpr[rt].toInt().toLong()
                    lo = product.toInt().toLong()
                    hi = (product shr 32).toInt().toLong()
                }
                0x19 -> {
                    if (rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed MULTU reserved fields")
                    val left = gpr[rs].toInt().toLong() and 0xFFFFFFFFL
                    val right = gpr[rt].toInt().toLong() and 0xFFFFFFFFL
                    val product = left * right
                    lo = product.toInt().toLong() and 0xFFFFFFFFL
                    hi = (product ushr 32) and 0xFFFFFFFFL
                }
                0x1A -> {
                    if (rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed DIV reserved fields")
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
                    if (rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed DIVU reserved fields")
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
                    if (rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed DMULT reserved fields")
                    val product = BigInteger.valueOf(gpr[rs]).multiply(BigInteger.valueOf(gpr[rt]))
                    lo = product.toLong()
                    hi = product.shiftRight(64).toLong()
                }
                0x1D -> { // DMULTU: unsigned 64-bit product, split into HI/LO
                    if (rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed DMULTU reserved fields")
                    val product = unsignedBigInteger(gpr[rs]).multiply(unsignedBigInteger(gpr[rt]))
                    lo = product.toLong()
                    hi = product.shiftRight(64).toLong()
                }
                0x1E -> { // DDIV: signed 64-bit quotient/remainder
                    if (rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed DDIV reserved fields")
                    val dividend = gpr[rs]
                    val divisor = gpr[rt]
                    if (divisor != 0L) {
                        lo = dividend / divisor
                        hi = dividend % divisor
                    } // Division by zero has architecturally unpredictable results.
                }
                0x1F -> { // DDIVU: unsigned 64-bit quotient/remainder
                    if (rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed DDIVU reserved fields")
                    val dividend = unsignedBigInteger(gpr[rs])
                    val divisor = unsignedBigInteger(gpr[rt])
                    if (divisor != BigInteger.ZERO) {
                        val result = dividend.divideAndRemainder(divisor)
                        lo = result[0].toLong()
                        hi = result[1].toLong()
                    } // Division by zero has architecturally unpredictable results.
                }
                0x0C -> { // SYSCALL: initial synchronous exception handling
                    enterException(instructionAddress, 8, branchAddressForCurrentInstruction)
                    directPc = exceptionVector()
                }
                0x0D -> { // BREAK: initial synchronous exception handling
                    enterException(instructionAddress, 9, branchAddressForCurrentInstruction)
                    directPc = exceptionVector()
                }
                0x08 -> { // JR: rt, rd and shamt are reserved.
                    if (rt != 0 || rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed JR reserved fields")
                    pendingDelaySlotBranchAddress = instructionAddress
                    delayedTarget = gpr[rs]
                }
                0x09 -> { // JALR: rt and shamt are reserved; rd selects the link register.
                    if (rt != 0 || shamt != 0) throw ReservedInstructionFault("Malformed JALR reserved fields")
                    gpr[rd] = nextSequential + 4L
                    pendingDelaySlotBranchAddress = instructionAddress
                    delayedTarget = gpr[rs]
                }
                0x20 -> { // ADD: signed 32-bit addition traps on overflow.
                    val result = gpr[rs].toInt().toLong() + gpr[rt].toInt().toLong()
                    if (result !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        enterException(instructionAddress, 12, branchAddressForCurrentInstruction)
                        directPc = exceptionVector()
                    } else {
                        gpr[rd] = result.toInt().toLong()
                    }
                }
                0x21 -> gpr[rd] = (gpr[rs].toInt() + gpr[rt].toInt()).toLong() // ADDU wraps without trapping.
                0x22 -> { // SUB: signed 32-bit subtraction traps on overflow.
                    val result = gpr[rs].toInt().toLong() - gpr[rt].toInt().toLong()
                    if (result !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        enterException(instructionAddress, 12, branchAddressForCurrentInstruction)
                        directPc = exceptionVector()
                    } else {
                        gpr[rd] = result.toInt().toLong()
                    }
                }
                0x23 -> gpr[rd] = (gpr[rs].toInt() - gpr[rt].toInt()).toLong() // SUBU wraps without trapping.
                0x24 -> gpr[rd] = (gpr[rs].toInt() and gpr[rt].toInt()).toLong()
                0x25 -> gpr[rd] = (gpr[rs].toInt() or gpr[rt].toInt()).toLong()
                0x26 -> gpr[rd] = (gpr[rs].toInt() xor gpr[rt].toInt()).toLong()
                0x27 -> gpr[rd] = (gpr[rs].toInt() or gpr[rt].toInt()).inv().toLong()
                0x2A -> gpr[rd] = if (gpr[rs] < gpr[rt]) 1L else 0L
                0x2B -> gpr[rd] = if (java.lang.Long.compareUnsigned(gpr[rs], gpr[rt]) < 0) 1L else 0L
                else -> throw ReservedInstructionFault("R4300 SPECIAL funct 0x${funct.toString(16)} at 0x${instructionAddress.toString(16)}")
            }
            0x10 -> { // COP0: minimal MFC0/MTC0 register transfers
                val copRs = rs
                val copRd = (instruction ushr 11) and 0x1F
                when (copRs) {
                    0x00 -> gpr[rt] = cop0[copRd].toInt().toLong() // MFC0 sign-extends 32-bit value
                    0x04 -> { // MTC0 writes low 32 bits.
                        val value = gpr[rt].toInt().toLong()
                        when (copRd) {
                            0 -> { // Index.P is read-only; only the low five index bits are writable.
                                cop0[0] = (cop0[0] and (1L shl 31)) or (value and 0x1FL)
                            }
                            1, 8, 15 -> { // Random, BadVAddr and PRId are read-only.
                                // MTC0 writes to these registers have no effect.
                            }
                            4 -> { // Context: PTEBase is writable; BadVPN2 is hardware-maintained.
                                cop0[4] = (cop0[4] and 0x007FFFF0L) or (value and 0xFF800000L)
                            }
                            6 -> { // Wired: five-bit value; writing it resets Random to 31.
                                cop0[6] = value and 0x1FL
                                cop0[1] = 31L
                                randomResetByWiredWrite = true
                            }
                            2, 3 -> { // EntryLo0/1: PFN [25:6] and flags [5:0] are writable.
                                cop0[copRd] = value and 0x03FFFFFFL
                            }
                            5 -> { // PageMask: supported paired-page size encoding.
                                cop0[5] = R4300Tlb.normalizePageMask(value)
                            }
                            10 -> { // EntryHi: VPN2 [31:13] and ASID [7:0] are writable.
                                cop0[10] = value and 0xFFFFE0FFL
                            }
                            13 -> { // Cause: only software interrupt pending bits IP[1:0] are writable.
                                cop0[13] = (cop0[13] and 0x300L.inv()) or (value and 0x300L)
                            }
                            else -> cop0[copRd] = value
                        }
                    }
                    0x10 -> { // COP0 CO-format operations
                        when (funct) {
                            0x01 -> { // TLBR: copy indexed TLB entry into COP0 registers
                                val index = (cop0[0].toInt() and 0x1F)
                                val entry = try {
                                    tlb.read(index)
                                } catch (_: IllegalStateException) {
                                    null // An uninitialized model entry reads as invalid/zero.
                                }
                                if (entry == null) {
                                    cop0[5] = 0L
                                    cop0[10] = 0L
                                    cop0[2] = 0L
                                    cop0[3] = 0L
                                } else {
                                    cop0[5] = entry.pageMask
                                    cop0[10] = (entry.virtualPagePairBase or entry.asid.toLong()) and 0xFFFFFFFFL
                                    cop0[2] = encodeEntryLo(entry.evenPage)
                                    cop0[3] = encodeEntryLo(entry.oddPage)
                                }
                            }
                            0x02 -> writeTlbEntry((cop0[0].toInt() and 0x1F)) // TLBWI
                            0x06 -> writeTlbEntry((cop0[1].toInt() and 0x1F)) // TLBWR
                            0x08 -> { // TLBP: probe VPN2 and ASID
                                val hit = tlb.probe(cop0[10], (cop0[10].toInt() and 0xFF))
                                cop0[0] = if (hit == null) {
                                    1L shl 31
                                } else {
                                    hit.toLong()
                                }
                            }
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
                            else -> throw ReservedInstructionFault(
                                "R4300 COP0 CO funct=0x${funct.toString(16)} at 0x${instructionAddress.toString(16)}"
                            )
                        }
                    }
                    else -> throw ReservedInstructionFault(
                        "R4300 COP0 rs=0x${copRs.toString(16)} at 0x${instructionAddress.toString(16)}"
                    )
                }
            }
            0x11 -> { // COP1 register/control transfers (initial subset)
                when (rs) {
                    0x00 -> { // MFC1 rt, fs
                        if ((instruction and 0x7FF) != 0) throw ReservedInstructionFault("Malformed MFC1 reserved fields")
                        gpr[rt] = fpr[rd].toInt().toLong()
                    }
                    0x02 -> { // CFC1 rt, fs
                        if ((instruction and 0x7FF) != 0) throw ReservedInstructionFault("Malformed CFC1 reserved fields")
                        gpr[rt] = fcr[rd].toInt().toLong()
                    }
                    0x04 -> { // MTC1 rt, fs
                        if ((instruction and 0x7FF) != 0) throw ReservedInstructionFault("Malformed MTC1 reserved fields")
                        fpr[rd] = gpr[rt].toInt().toLong() and 0xFFFFFFFFL
                    }
                    0x06 -> { // CTC1 rt, fs; FCR0 is read-only.
                        if ((instruction and 0x7FF) != 0) throw ReservedInstructionFault("Malformed CTC1 reserved fields")
                        if (rd != 0) fcr[rd] = gpr[rt].toInt().toLong()
                    }
                    else -> throw ReservedInstructionFault(
                        "R4300 COP1 transfer/arithmetic rs=0x${rs.toString(16)} at 0x${instructionAddress.toString(16)}; FPU arithmetic is not implemented yet"
                    )
                }
            }
            0x01 -> {
                val takeBranch = when (rt) {
                    0x00, 0x10 -> gpr[rs] < 0L // BLTZ / BLTZAL
                    0x01, 0x11 -> gpr[rs] >= 0L // BGEZ / BGEZAL
                    else -> throw ReservedInstructionFault(
                        "R4300 REGIMM rt=0x${rt.toString(16)} at 0x${instructionAddress.toString(16)}"
                    )
                }
                pendingDelaySlotBranchAddress = instructionAddress
                if (takeBranch) {
                    if (rt == 0x10 || rt == 0x11) gpr[31] = nextSequential + 4L
                    delayedTarget = nextSequential + (immediate.toLong() shl 2)
                }
            }
            0x02, 0x03 -> {
                val jumpAddress = (nextSequential and -0x10000000L) or
                    ((instruction.toLong() and 0x03FFFFFFL) shl 2)
                if (opcode == 0x03) gpr[31] = nextSequential + 4L
                pendingDelaySlotBranchAddress = instructionAddress
                delayedTarget = jumpAddress
            }
            0x04, 0x05, 0x06, 0x07, 0x14, 0x15, 0x16, 0x17 -> {
                val branchLikely = opcode >= 0x14
                val baseOpcode = if (branchLikely) opcode - 0x10 else opcode
                if ((baseOpcode == 0x06 || baseOpcode == 0x07) && rt != 0) {
                    throw ReservedInstructionFault("Malformed BLEZ/BGTZ reserved rt field")
                }
                val takeBranch = when (baseOpcode) {
                    0x04 -> gpr[rs] == gpr[rt] // BEQ / BEQL
                    0x05 -> gpr[rs] != gpr[rt] // BNE / BNEL
                    0x06 -> gpr[rs] <= 0L // BLEZ / BLEZL
                    else -> gpr[rs] > 0L // BGTZ / BGTZL
                }
                if (branchLikely && !takeBranch) {
                    // A not-taken branch-likely annuls its delay-slot instruction.
                    directPc = instructionAddress + 8L
                } else {
                    pendingDelaySlotBranchAddress = instructionAddress
                    if (takeBranch) delayedTarget = nextSequential + (immediate.toLong() shl 2)
                }
            }
            0x08 -> { // ADDI: signed 32-bit addition traps on overflow.
                val result = gpr[rs].toInt().toLong() + immediate.toLong()
                if (result !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    enterException(instructionAddress, 12, branchAddressForCurrentInstruction)
                    directPc = exceptionVector()
                } else {
                    gpr[rt] = result.toInt().toLong()
                }
            }
            0x09 -> gpr[rt] = (gpr[rs].toInt() + immediate).toLong() // ADDIU wraps without trapping.
            0x18 -> { // DADDI: signed 64-bit addition traps on overflow.
                val left = gpr[rs]
                val right = immediate.toLong()
                val result = left + right
                if (((left xor result) and (right xor result)) < 0L) {
                    enterException(instructionAddress, 12, branchAddressForCurrentInstruction)
                    directPc = exceptionVector()
                } else {
                    gpr[rt] = result
                }
            }
            0x19 -> gpr[rt] = gpr[rs] + immediate.toLong() // DADDIU wraps without trapping.
            0x0A -> gpr[rt] = if (gpr[rs] < immediate.toLong()) 1L else 0L // SLTI
            0x0B -> gpr[rt] = if (java.lang.Long.compareUnsigned(gpr[rs], immediate.toLong()) < 0) 1L else 0L // SLTIU
            0x0C -> gpr[rt] = gpr[rs] and unsignedImmediate.toLong()
            0x0D -> gpr[rt] = gpr[rs] or unsignedImmediate.toLong()
            0x0E -> gpr[rt] = gpr[rs] xor unsignedImmediate.toLong()
            0x0F -> gpr[rt] = (unsignedImmediate shl 16).toInt().toLong()
            0x1A -> { // LDL: merge left-side bytes from a big-endian aligned doubleword.
                val address = gpr[rs] + immediate
                val aligned = address and -8L
                val offset = (address and 7L).toInt()
                val physical = physicalAddress(aligned, R4300Tlb.Access.READ)
                val high = memory.read32(physical).toLong() shl 32
                val low = memory.read32(physical + 4).toLong() and 0xFFFFFFFFL
                val doubleword = high or low
                val shift = offset * 8
                val preserveMask = if (shift == 0) 0L else (-1L ushr (64 - shift))
                gpr[rt] = (gpr[rt] and preserveMask) or (doubleword shl shift)
            }
            0x1B -> { // LDR: merge right-side bytes from a big-endian aligned doubleword.
                val address = gpr[rs] + immediate
                val aligned = address and -8L
                val offset = (address and 7L).toInt()
                val physical = physicalAddress(aligned, R4300Tlb.Access.READ)
                val high = memory.read32(physical).toLong() shl 32
                val low = memory.read32(physical + 4).toLong() and 0xFFFFFFFFL
                val doubleword = high or low
                val shift = (7 - offset) * 8
                val preserveMask = if (shift == 0) 0L else (-1L shl (64 - shift))
                gpr[rt] = (gpr[rt] and preserveMask) or (doubleword ushr shift)
            }
            0x22 -> { // LWL: merge left-side bytes from a big-endian aligned word.
                val address = gpr[rs] + immediate
                val aligned = address and -4L
                val offset = (address and 3L).toInt()
                val word = memory.read32(physicalAddress(aligned, R4300Tlb.Access.READ))
                val shift = offset * 8
                val preserveMask = if (shift == 0) 0 else (0xFFFFFFFF.toInt() ushr (32 - shift))
                gpr[rt] = (((gpr[rt].toInt() and preserveMask) or (word shl shift)).toLong()).toInt().toLong()
            }
            0x26 -> { // LWR: merge right-side bytes from a big-endian aligned word.
                val address = gpr[rs] + immediate
                val aligned = address and -4L
                val offset = (address and 3L).toInt()
                val word = memory.read32(physicalAddress(aligned, R4300Tlb.Access.READ))
                val shift = (3 - offset) * 8
                val preserveMask = if (shift == 0) 0 else (-1 shl (32 - shift))
                gpr[rt] = (((gpr[rt].toInt() and preserveMask) or (word ushr shift)).toLong()).toInt().toLong()
            }
            0x20 -> gpr[rt] = memory.read8(physicalAddress(gpr[rs] + immediate, R4300Tlb.Access.READ)).toByte().toLong()
            0x24 -> gpr[rt] = memory.read8(physicalAddress(gpr[rs] + immediate, R4300Tlb.Access.READ)).toLong()
            0x21 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 2)
                gpr[rt] = memory.read16(physicalAddress(address, R4300Tlb.Access.READ)).toShort().toLong()
            }
            0x25 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 2)
                gpr[rt] = memory.read16(physicalAddress(address, R4300Tlb.Access.READ)).toLong()
            }
            0x23 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 4)
                gpr[rt] = memory.read32(physicalAddress(address, R4300Tlb.Access.READ)).toLong()
            }
            0x27 -> { // LWU: load a word and zero-extend to 64 bits
                val address = gpr[rs] + immediate
                requireAligned(address, 4)
                gpr[rt] = memory.read32(physicalAddress(address, R4300Tlb.Access.READ)).toLong() and 0xFFFFFFFFL
            }
            0x37 -> { // LD: load a big-endian doubleword
                val address = gpr[rs] + immediate
                requireAligned(address, 8)
                val high = memory.read32(physicalAddress(address, R4300Tlb.Access.READ)).toLong()
                val low = memory.read32(physicalAddress(address + 4L, R4300Tlb.Access.READ)).toLong() and 0xFFFFFFFFL
                gpr[rt] = (high shl 32) or low
            }
            0x2C -> { // SDL: store left-side bytes into a big-endian aligned doubleword.
                val address = gpr[rs] + immediate
                val aligned = address and -8L
                val offset = (address and 7L).toInt()
                val physical = physicalAddress(aligned, R4300Tlb.Access.WRITE)
                val oldHigh = memory.read32(physical)
                val oldLow = memory.read32(physical + 4)
                val oldDoubleword = (oldHigh.toLong() shl 32) or (oldLow.toLong() and 0xFFFFFFFFL)
                val shift = (7 - offset) * 8
                val valueMask = -1L shl shift
                val merged = (oldDoubleword and valueMask.inv()) or (gpr[rt] and valueMask)
                memory.write32(physical, (merged ushr 32).toInt())
                memory.write32(physical + 4, merged.toInt())
            }
            0x2D -> { // SDR: store right-side bytes into a big-endian aligned doubleword.
                val address = gpr[rs] + immediate
                val aligned = address and -8L
                val offset = (address and 7L).toInt()
                val physical = physicalAddress(aligned, R4300Tlb.Access.WRITE)
                val oldHigh = memory.read32(physical)
                val oldLow = memory.read32(physical + 4)
                val oldDoubleword = (oldHigh.toLong() shl 32) or (oldLow.toLong() and 0xFFFFFFFFL)
                val shift = offset * 8
                val preserveMask = if (shift == 0) 0L else (-1L shl (64 - shift))
                val valueMask = if (shift == 0) -1L else (-1L ushr shift)
                val merged = (oldDoubleword and preserveMask) or (gpr[rt] and valueMask)
                memory.write32(physical, (merged ushr 32).toInt())
                memory.write32(physical + 4, merged.toInt())
            }
            0x2A -> { // SWL: store left-side bytes into a big-endian aligned word.
                val address = gpr[rs] + immediate
                val aligned = address and -4L
                val offset = (address and 3L).toInt()
                val physical = physicalAddress(aligned, R4300Tlb.Access.WRITE)
                val oldWord = memory.read32(physical)
                val shift = (3 - offset) * 8
                val valueMask = -1 shl (24 - offset * 8)
                val preserveMask = valueMask.inv()
                memory.write32(physical, (oldWord and preserveMask) or (gpr[rt].toInt() and valueMask))
            }
            0x2E -> { // SWR: store right-side bytes into a big-endian aligned word.
                val address = gpr[rs] + immediate
                val aligned = address and -4L
                val offset = (address and 3L).toInt()
                val physical = physicalAddress(aligned, R4300Tlb.Access.WRITE)
                val oldWord = memory.read32(physical)
                val shift = offset * 8
                val preserveMask = if (shift == 0) 0 else (-1 shl (32 - shift))
                val valueMask = if (shift == 0) -1 else (0xFFFFFFFF.toInt() ushr shift)
                memory.write32(physical, (oldWord and preserveMask) or (gpr[rt].toInt() and valueMask))
            }
            0x2F -> {
                // CACHE: this early core has no modeled cache hierarchy.
                // Treat cache maintenance as a no-op for bring-up, not as accurate cache emulation.
            }
            0x28 -> memory.write8(physicalAddress(gpr[rs] + immediate, R4300Tlb.Access.WRITE), gpr[rt].toInt())
            0x29 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 2, store = true)
                memory.write16(physicalAddress(address, R4300Tlb.Access.WRITE), gpr[rt].toInt())
            }
            0x2B -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 4, store = true)
                memory.write32(physicalAddress(address, R4300Tlb.Access.WRITE), gpr[rt].toInt())
            }
            0x3F -> { // SD: store a big-endian doubleword
                val address = gpr[rs] + immediate
                requireAligned(address, 8)
                memory.write32(physicalAddress(address, R4300Tlb.Access.WRITE), (gpr[rt] ushr 32).toInt())
                memory.write32(physicalAddress(address + 4L, R4300Tlb.Access.WRITE), gpr[rt].toInt())
            }
            else -> throw ReservedInstructionFault("R4300 opcode 0x${opcode.toString(16)} at 0x${instructionAddress.toString(16)}")
        }

        pc = directPc ?: targetFromPreviousBranch ?: nextSequential
        gpr[0] = 0L
    }

    /** Random cycles down through the replaceable TLB indices [Wired, 31]. */
    private fun advanceRandomRegister() {
        val wired = (cop0[6] and 0x1FL).toInt()
        val random = (cop0[1] and 0x1FL).toInt()
        cop0[1] = if (random <= wired) 31L else (random - 1).toLong()
    }

    private fun encodeEntryLo(page: R4300Tlb.Page): Long {
        return (page.physicalFrameNumber.toLong() shl 6) or
            (if (page.dirty) 0x4L else 0L) or
            (if (page.valid) 0x2L else 0L) or
            (if (page.global) 0x1L else 0L)
    }

    private fun decodeEntryLo(value: Long): R4300Tlb.Page {
        val raw = value and 0xFFFFFFFFL
        return R4300Tlb.Page(
            physicalFrameNumber = ((raw ushr 6) and 0xFFFFFL).toInt(),
            valid = (raw and 0x2L) != 0L,
            dirty = (raw and 0x4L) != 0L,
            global = (raw and 0x1L) != 0L
        )
    }

    private fun writeTlbEntry(index: Int) {
        val entryHi = cop0[10] and 0xFFFFFFFFL
        val pageMask = R4300Tlb.normalizePageMask(cop0[5])
        val pairMask = R4300Tlb.pairAddressMask(pageMask)
        val entry = R4300Tlb.Entry(
            virtualPagePairBase = entryHi and pairMask,
            asid = (entryHi and 0xFFL).toInt(),
            evenPage = decodeEntryLo(cop0[2]),
            oddPage = decodeEntryLo(cop0[3]),
            pageMask = pageMask
        )
        tlb.write(index, entry)
        val operation = if ((instructionWordForCurrentStep?.and(0x3F)) == 0x06) "TLBWR" else "TLBWI"
        val line = operation + " PC=0x" + (instructionAddressForCurrentStep ?: pc).toULong().toString(16).uppercase() +
            " index=$index PageMask=0x" + pageMask.toString(16).uppercase() +
            " EntryHi=0x" + entryHi.toString(16).uppercase() +
            " EntryLo0=0x" + cop0[2].toUInt().toString(16).uppercase().padStart(8, '0') +
            " EntryLo1=0x" + cop0[3].toUInt().toString(16).uppercase().padStart(8, '0')
        diagnosticTlbWriteHistory.add(line)
        if (diagnosticTlbWriteHistory.size > 64) diagnosticTlbWriteHistory.removeAt(0)
    }

    private fun snapshotTlbEntriesForDiagnostic(): String {
        val lines = mutableListOf<String>()
        for (index in 0 until R4300Tlb.ENTRY_COUNT) {
            val entry = try { tlb.read(index) } catch (_: IllegalStateException) { null }
            if (entry != null) {
                lines.add("TLB[$index] VPN2=0x" + entry.virtualPagePairBase.toString(16).uppercase() +
                    " ASID=0x" + entry.asid.toString(16).uppercase() +
                    " PageMask=0x" + entry.pageMask.toString(16).uppercase() +
                    " Lo0=0x" + encodeEntryLo(entry.evenPage).toString(16).uppercase().padStart(8, '0') +
                    " Lo1=0x" + encodeEntryLo(entry.oddPage).toString(16).uppercase().padStart(8, '0'))
            }
        }
        return if (lines.isEmpty()) "(nenhuma entrada TLB inicializada)" else lines.joinToString("\\n")
    }

    private fun enterException(instructionAddress: Long, exceptionCode: Int, branchAddress: Long?) {
        lastExceptionInstructionAddress = instructionAddress
        lastExceptionCode = exceptionCode
        lastExceptionBranchAddress = branchAddress
        lastExceptionInstructionWord = instructionWordForCurrentStep
        val status = cop0[12]
        if ((status and 0x2L) == 0L) {
            cop0[14] = branchAddress ?: instructionAddress
        }
        val branchDelayBit = 1L shl 31
        val exceptionCodeMask = 0x1FL shl 2
        var cause = cop0[13] and branchDelayBit.inv() and exceptionCodeMask.inv()
        cause = cause or (exceptionCode.toLong() shl 2)
        if (branchAddress != null) cause = cause or branchDelayBit
        cop0[13] = cause
        cop0[12] = status or 0x2L // Set EXL.
    }

    private fun enterTlbException(fault: R4300Tlb.TlbFault, branchAddress: Long?) {
        lastTlbFaultVirtualAddress = fault.virtualAddress and 0xFFFFFFFFL
        lastTlbFaultAccessType = when (fault.access) {
            R4300Tlb.Access.FETCH -> "FETCH"
            R4300Tlb.Access.READ -> "READ"
            R4300Tlb.Access.WRITE -> "WRITE"
        }
        lastTlbFaultKind = fault.kind
        lastTlbEntrySnapshot = snapshotTlbEntriesForDiagnostic()
        lastExceptionAccessType = when (fault.access) {
            R4300Tlb.Access.FETCH -> "FETCH"
            R4300Tlb.Access.READ -> "READ"
            R4300Tlb.Access.WRITE -> "WRITE"
        }
        val status = cop0[12]
        val wasExl = (status and 0x2L) != 0L
        val badAddress = fault.virtualAddress and 0xFFFFFFFFL
        cop0[8] = badAddress // BadVAddr
        cop0[4] = (cop0[4] and 0xFF800000L) or ((badAddress ushr 9) and 0x007FFFF0L)
        cop0[10] = (badAddress and R4300Tlb.PAGE_PAIR_MASK) or (cop0[10] and 0xFFL)

        val code = when (fault.kind) {
            R4300Tlb.FaultKind.MODIFIED -> 1
            R4300Tlb.FaultKind.MISS, R4300Tlb.FaultKind.INVALID ->
                if (fault.access == R4300Tlb.Access.WRITE) 3 else 2
        }
        enterException(instructionAddressForCurrentStep ?: pc, code, branchAddress)

        val boot = (cop0[12] and (1L shl 22)) != 0L
        pc = if (fault.kind == R4300Tlb.FaultKind.MISS && !wasExl) {
            if (boot) 0xFFFFFFFFBFC00200UL.toLong() else 0xFFFFFFFF80000000UL.toLong()
        } else {
            if (boot) 0xFFFFFFFFBFC00380UL.toLong() else 0xFFFFFFFF80000180UL.toLong()
        }
        delayedTarget = null
        pendingDelaySlotBranchAddress = null
    }

    private fun exceptionVector(): Long {
        // Status.BEV (bit 22) selects the boot exception vector.
        return if ((cop0[12] and (1L shl 22)) != 0L) {
            0xFFFFFFFFBFC00380UL.toLong()
        } else {
            0xFFFFFFFF80000180UL.toLong()
        }
    }

    private fun unsignedBigInteger(value: Long): BigInteger {
        val signed = BigInteger.valueOf(value)
        return if (value >= 0L) signed else signed.add(BigInteger.ONE.shiftLeft(64))
    }

    private class ReservedInstructionFault(message: String) : RuntimeException(message)

    private class AddressFault(val virtualAddress: Long, val store: Boolean) : RuntimeException()

    private fun enterAddressException(fault: AddressFault, branchAddress: Long?) {
        cop0[8] = fault.virtualAddress and 0xFFFFFFFFL // BadVAddr
        enterException(instructionAddressForCurrentStep ?: pc, if (fault.store) 5 else 4, branchAddress) // AdES / AdEL
        pc = exceptionVector()
        delayedTarget = null
        pendingDelaySlotBranchAddress = null
    }

    private fun requireAligned(address: Long, alignment: Int, store: Boolean = false) {
        if ((address and (alignment - 1).toLong()) != 0L) {
            throw AddressFault(address, store)
        }
    }

    /** Read-only summary of how the diagnostic address would be handled by the current MMU state. */
    fun describeTlbAddressForDiagnostic(address: Long): String {
        val virtual32 = address and 0xFFFFFFFFL
        val segment = (virtual32 ushr 29) and 0x7L
        if (segment == 4L || segment == 5L) {
            return "KSEG bypass: VADDR=0x${virtual32.toString(16).uppercase()} -> PADDR=0x${(virtual32 and 0x1FFFFFFFL).toString(16).uppercase()}"
        }
        if (!tlbEnabled) {
            return "TLB disabled; compatibility translation is active"
        }
        val asid = (cop0[10].toInt() and 0xFF)
        val index = tlb.probe(virtual32, asid)
            ?: return "TLB MISS: VADDR=0x${virtual32.toString(16).uppercase()}, ASID=0x${asid.toString(16).uppercase()}; no matching VPN2/ASID entry"
        val entry = tlb.read(index)
        val pageSize = R4300Tlb.pageSize(entry.pageMask)
        val odd = (virtual32 and pageSize) != 0L
        val page = if (odd) entry.oddPage else entry.evenPage
        return "TLB entry=$index VPN2=0x${entry.virtualPagePairBase.toString(16).uppercase()} ASID=0x${entry.asid.toString(16).uppercase()} PageMask=0x${entry.pageMask.toString(16).uppercase()} page=${if (odd) "odd" else "even"} PFN=0x${page.physicalFrameNumber.toString(16).uppercase()} valid=${page.valid} dirty=${page.dirty} global=${page.global}"
    }

    /** Translate a virtual instruction address for diagnostics using the same TLB rules as CPU fetch. */
    fun readInstructionForDiagnostic(address: Long): Int =
        memory.read32(physicalAddress(address, R4300Tlb.Access.FETCH))

    private fun physicalAddress(
        address: Long,
        access: R4300Tlb.Access = R4300Tlb.Access.READ
    ): Int {
        // Record the attempted access before translation, because a TLB fault throws.
        diagnosticVirtualAddress = address
        diagnosticPhysicalAddress = null
        diagnosticAccessType = when (access) {
            R4300Tlb.Access.FETCH -> "FETCH"
            R4300Tlb.Access.READ -> "READ"
            R4300Tlb.Access.WRITE -> "WRITE"
        }
        // Treat sign-extended 32-bit virtual addresses as 32-bit N64 addresses.
        val virtual32 = address and 0xFFFFFFFFL
        val segment = (virtual32 ushr 29) and 0x7L
        val physical = if (segment == 4L || segment == 5L) {
            virtual32 and 0x1FFFFFFFL // KSEG0/KSEG1 bypass the TLB.
        } else if (tlbEnabled) {
            tlb.translate(virtual32, (cop0[10].toInt() and 0xFF), access)
                .toLong() and 0xFFFFFFFFL
        } else {
            virtual32 // Compatibility mode for current unit tests and early bring-up.
        }
        require(physical <= Int.MAX_VALUE.toLong()) {
            "Address cannot be represented by this initial RAM bus: 0x${address.toString(16)}"
        }
        diagnosticPhysicalAddress = physical.toInt()
        return physical.toInt()
    }

    companion object {
        const val RESET_VECTOR: Long = -0x40400000L
    }
}
