package com.jsr.n64android

import java.math.BigInteger

/**
 * First executable R4300 subset for bring-up and unit tests.
 * This is not a complete MIPS III/R4300 implementation: remaining instructions,
 * full delay-slot exception semantics, complete COP0/TLB behavior, cache operations and full N64 devices remain unimplemented.
 */
class R4300Cpu(private val memory: N64AddressSpace) {
    /**
     * Architectural state needed to suspend and resume a guest thread at an
     * instruction boundary. Diagnostic histories are intentionally excluded.
     */
    data class ContextSnapshot(
        val gpr: List<Long>,
        val hi: Long,
        val lo: Long,
        val cop0: List<Long>,
        val fpr: List<Long>,
        val fcr: List<Long>,
        val tlb: R4300Tlb.Snapshot,
        val tlbEnabled: Boolean,
        val pc: Long,
        val delayedTarget: Long?,
        val pendingDelaySlotBranchAddress: Long?,
        val randomResetByWiredWrite: Boolean
    )

    fun snapshotContext(): ContextSnapshot = ContextSnapshot(
        gpr = gpr.toList(),
        hi = hi,
        lo = lo,
        cop0 = cop0.toList(),
        fpr = fpr.toList(),
        fcr = fcr.toList(),
        tlb = tlb.snapshot(),
        tlbEnabled = tlbEnabled,
        pc = pc,
        delayedTarget = delayedTarget,
        pendingDelaySlotBranchAddress = pendingDelaySlotBranchAddress,
        randomResetByWiredWrite = randomResetByWiredWrite
    )

    /**
     * Restores architectural state atomically after validating all register
     * banks and addresses. LL/SC reservation is cleared so it cannot leak
     * from a previously running guest thread into the resumed thread.
     */
    fun restoreContext(snapshot: ContextSnapshot) {
        require(snapshot.gpr.size == 32) { "GPR snapshot must contain 32 registers." }
        require(snapshot.cop0.size == 32) { "COP0 snapshot must contain 32 registers." }
        require(snapshot.fpr.size == 32) { "FPR snapshot must contain 32 registers." }
        require(snapshot.fcr.size == 32) { "FCR snapshot must contain 32 registers." }
        require(snapshot.pc and 3L == 0L) { "Context PC must be 4-byte aligned." }
        require(snapshot.delayedTarget == null || snapshot.delayedTarget and 3L == 0L) {
            "Delayed branch target must be 4-byte aligned."
        }
        require(snapshot.pendingDelaySlotBranchAddress == null ||
            snapshot.pendingDelaySlotBranchAddress and 3L == 0L) {
            "Delay-slot branch address must be 4-byte aligned."
        }

        // TLB restore validates every entry before mutating the live TLB.
        tlb.restore(snapshot.tlb)
        snapshot.gpr.forEachIndexed { index, value -> gpr[index] = value }
        gpr[0] = 0L
        hi = snapshot.hi
        lo = snapshot.lo
        snapshot.cop0.forEachIndexed { index, value -> cop0[index] = value }
        snapshot.fpr.forEachIndexed { index, value -> fpr[index] = value }
        snapshot.fcr.forEachIndexed { index, value -> fcr[index] = value }
        tlbEnabled = snapshot.tlbEnabled
        pc = snapshot.pc
        delayedTarget = snapshot.delayedTarget
        pendingDelaySlotBranchAddress = snapshot.pendingDelaySlotBranchAddress
        randomResetByWiredWrite = snapshot.randomResetByWiredWrite

        memory.clearLoadLinkedReservation()
        instructionWordForCurrentStep = null
        instructionAddressForCurrentStep = null
        diagnosticVirtualAddress = null
        diagnosticPhysicalAddress = null
        diagnosticAccessType = null
    }

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
    /** Basic 32-entry TLB model; HLE boot explicitly enables translation after CPU reset. */
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
    /** JR/JALR context frozen at the first TLB fault, before the handler changes it. */
    var firstTlbFaultIndirectJumpAddress: Long? = null
        private set
    var firstTlbFaultIndirectJumpInstruction: Int? = null
        private set
    var firstTlbFaultIndirectTarget: Long? = null
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
    /** Target captured when the most recently executed instruction was JR/JALR. */
    var diagnosticLastIndirectTarget: Long? = null
    /** Source instruction for the most recently executed JR/JALR, retained across later instructions. */
    var diagnosticLastIndirectJumpAddress: Long? = null
        private set
    var diagnosticLastIndirectJumpInstruction: Int? = null
        private set
    private var pendingDelaySlotBranchAddress: Long? = null
    private var randomResetByWiredWrite = false
    /** Bounded control-flow events for interrupt/exception/ERET diagnosis. */
    val diagnosticControlFlowHistory: MutableList<String> = mutableListOf()
    /** Bounded record of actual TLBWI/TLBWR operations for ROM diagnostics. */
    val diagnosticTlbWriteHistory: MutableList<String> = mutableListOf()
    /** Bounded architectural TLB exception trace, including the selected exception vector. */
    val diagnosticTlbExceptionHistory: MutableList<String> = mutableListOf()
    /** TLB contents frozen when the first architectural TLB fault is delivered. */
    var lastTlbEntrySnapshot: String = "(nenhuma entrada capturada)"
        private set
    /** Short instruction trace after TLB exceptions plus sparse snapshots during later loops. */
    val diagnosticTlbInstructionHistory: MutableList<String> = mutableListOf()
    /** Instructions executed immediately before the first TLB fault, frozen in a bounded ring. */
    val diagnosticTlbPreExceptionHistory: MutableList<String> = mutableListOf()
    /** Effective-address and value trace for data accesses while the TLB refill handler runs. */
    val diagnosticTlbMemoryAccessHistory: MutableList<String> = mutableListOf()
    /** Narrow trace of the ROM routine that initializes the suspected page-table area. */
    val diagnosticPteSetupHistory: MutableList<String> = mutableListOf()
    /** Bounded register snapshots around the observed JR RA -> JAL call path. */
    val diagnosticCallPathHistory: MutableList<String> = mutableListOf()
    private val preTlbFaultPc = LongArray(TLB_PRE_EXCEPTION_TRACE_CAPACITY)
    private val preTlbFaultInstruction = IntArray(TLB_PRE_EXCEPTION_TRACE_CAPACITY)
    private val preTlbFaultGprRs = LongArray(TLB_PRE_EXCEPTION_TRACE_CAPACITY)
    private val preTlbFaultGprRt = LongArray(TLB_PRE_EXCEPTION_TRACE_CAPACITY)
    private val preTlbFaultStatus = LongArray(TLB_PRE_EXCEPTION_TRACE_CAPACITY)
    private var preTlbFaultWriteIndex = 0
    private var preTlbFaultCount = 0
    private var preTlbFaultTraceCaptured = false
    private var diagnosticTlbTraceRemaining = 0
    private var diagnosticInstructionCount = 0L

    fun reset() {
        memory.clearLoadLinkedReservation()
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
        firstTlbFaultIndirectJumpAddress = null
        firstTlbFaultIndirectJumpInstruction = null
        firstTlbFaultIndirectTarget = null
        diagnosticTlbWriteHistory.clear()
        diagnosticControlFlowHistory.clear()
        diagnosticTlbExceptionHistory.clear()
        lastTlbEntrySnapshot = "(nenhuma entrada capturada)"
        diagnosticTlbInstructionHistory.clear()
        diagnosticTlbPreExceptionHistory.clear()
        diagnosticTlbMemoryAccessHistory.clear()
        diagnosticPteSetupHistory.clear()
        diagnosticCallPathHistory.clear()
        preTlbFaultPc.fill(0L)
        preTlbFaultInstruction.fill(0)
        preTlbFaultGprRs.fill(0L)
        preTlbFaultGprRt.fill(0L)
        preTlbFaultStatus.fill(0L)
        preTlbFaultWriteIndex = 0
        preTlbFaultCount = 0
        preTlbFaultTraceCaptured = false
        diagnosticTlbTraceRemaining = 0
        diagnosticInstructionCount = 0L
        instructionWordForCurrentStep = null
        instructionAddressForCurrentStep = null
        diagnosticVirtualAddress = null
        diagnosticPhysicalAddress = null
        diagnosticAccessType = null
        delayedTarget = null
        diagnosticLastIndirectTarget = null
        diagnosticLastIndirectJumpAddress = null
        diagnosticLastIndirectJumpInstruction = null
        pendingDelaySlotBranchAddress = null
    }

    fun setProgramCounter(address: Long) {
        require(address and 0x3L == 0L) { "Instruction address must be 4-byte aligned." }
        pc = address
        delayedTarget = null
        pendingDelaySlotBranchAddress = null
    }

    /**
     * Samples the N64 MI aggregate interrupt line as Cause.IP2 before each instruction.
     * The MI line is reflected in Cause even while Status masks or exception levels prevent service.
     */
    private fun serviceMiInterrupt(branchAddress: Long?): Boolean {
        val miPending = (memory as? N64Bus)?.isMiInterruptPending()
        val ip2Mask = 1L shl 10
        if (miPending != null) {
            cop0[13] = if (miPending) cop0[13] or ip2Mask else cop0[13] and ip2Mask.inv()
        }

        val status = cop0[12]
        val pendingAndEnabled = (cop0[13] and status and 0x0000FF00L) != 0L
        val globallyEnabled = (status and 0x1L) != 0L
        val handlingException = (status and 0x2L) != 0L
        val handlingError = (status and 0x4L) != 0L
        if (!pendingAndEnabled || !globallyEnabled || handlingException || handlingError) return false

        enterException(instructionAddressForCurrentStep ?: pc, 0, branchAddress)
        pc = exceptionVector()
        delayedTarget = null
        pendingDelaySlotBranchAddress = null
        return true
    }

    /** Executes one instruction and converts address-translation faults into architectural COP0 exceptions. */
    fun step() {
        instructionWordForCurrentStep = null
        instructionAddressForCurrentStep = pc
        val branchAddressForFault = pendingDelaySlotBranchAddress
        randomResetByWiredWrite = false
        try {
            if (serviceMiInterrupt(branchAddressForFault)) return
            stepInstruction()
            if (!randomResetByWiredWrite) advanceRandomRegister()
        } catch (fault: R4300Tlb.TlbFault) {
            enterTlbException(fault, branchAddressForFault)
        } catch (fault: AddressFault) {
            enterAddressException(fault, branchAddressForFault)
        } catch (fault: CoprocessorUnusableFault) {
            enterException(
                instructionAddressForCurrentStep ?: pc,
                11,
                branchAddressForFault,
                coprocessorNumber = fault.coprocessorNumber
            )
            pc = exceptionVector()
            delayedTarget = null
            pendingDelaySlotBranchAddress = null
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
        recordTlbDiagnosticInstruction(instructionAddress, instruction)
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

        // All COP1 encodings, including floating-point memory operations, trap
        // before address translation or memory side effects when CU1 is disabled.
        if ((opcode == 0x11 || opcode == 0x31 || opcode == 0x35 ||
                opcode == 0x39 || opcode == 0x3D) &&
            (cop0[12] and (1L shl 29)) == 0L
        ) {
            throw CoprocessorUnusableFault(1)
        }

        val diagnosticEffectiveAddress = if (
            opcode == 0x28 || opcode == 0x29 || opcode == 0x2A || opcode == 0x2B ||
            opcode == 0x2C || opcode == 0x2D || opcode == 0x2E || opcode == 0x38 ||
            opcode == 0x3C || opcode == 0x3F
        ) {
            gpr[rs] + immediate.toLong()
        } else null
        (memory as? N64Bus)?.setDiagnosticCpuInstructionContext(
            pc = instructionAddress,
            instruction = instruction,
            rs = rs,
            rt = rt,
            rsValue = gpr[rs],
            rtValue = gpr[rt],
            effectiveAddress = diagnosticEffectiveAddress
        )
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
                0x0F -> { // SYNC: all memory operations are already serialized by this interpreter.
                    if (rs != 0 || rt != 0 || rd != 0) {
                        throw ReservedInstructionFault("Malformed SYNC reserved fields")
                    }
                    // The single-threaded bus has no outstanding asynchronous CPU memory operations to drain.
                }
                0x30, 0x31, 0x32, 0x33, 0x34, 0x36 -> { // TGE/TGEU/TLT/TLTU/TEQ/TNE
                    val trap = when (funct) {
                        0x30 -> gpr[rs] >= gpr[rt]
                        0x31 -> java.lang.Long.compareUnsigned(gpr[rs], gpr[rt]) >= 0
                        0x32 -> gpr[rs] < gpr[rt]
                        0x33 -> java.lang.Long.compareUnsigned(gpr[rs], gpr[rt]) < 0
                        0x34 -> gpr[rs] == gpr[rt]
                        else -> gpr[rs] != gpr[rt]
                    }
                    if (trap) {
                        enterException(instructionAddress, 13, branchAddressForCurrentInstruction)
                        directPc = exceptionVector()
                    }
                }
                0x08 -> { // JR: rt, rd and shamt are reserved.
                    if (rt != 0 || rd != 0 || shamt != 0) throw ReservedInstructionFault("Malformed JR reserved fields")
                    pendingDelaySlotBranchAddress = instructionAddress
                    diagnosticLastIndirectTarget = gpr[rs]
                    diagnosticLastIndirectJumpAddress = instructionAddress
                    diagnosticLastIndirectJumpInstruction = instruction
                    delayedTarget = gpr[rs]
                }
                0x09 -> { // JALR: rt and shamt are reserved; rd selects the link register.
                    if (rt != 0 || shamt != 0) throw ReservedInstructionFault("Malformed JALR reserved fields")
                    val jumpTarget = gpr[rs]
                    gpr[rd] = nextSequential + 4L
                    pendingDelaySlotBranchAddress = instructionAddress
                    diagnosticLastIndirectTarget = jumpTarget
                    diagnosticLastIndirectJumpAddress = instructionAddress
                    diagnosticLastIndirectJumpInstruction = instruction
                    delayedTarget = jumpTarget
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
                0x24 -> gpr[rd] = gpr[rs] and gpr[rt]
                0x25 -> gpr[rd] = gpr[rs] or gpr[rt]
                0x26 -> gpr[rd] = gpr[rs] xor gpr[rt]
                0x27 -> gpr[rd] = (gpr[rs] or gpr[rt]).inv()
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
                            0x18 -> { // ERET clears the VR4300 LLbit before returning.
                                memory.clearLoadLinkedReservation()
                                val status = cop0[12]
                                if ((status and 0x4L) != 0L) {
                                    cop0[12] = status and 0x4L.inv() // clear ERL
                                    directPc = cop0[30] // ErrorEPC
                                } else {
                                    cop0[12] = status and 0x2L.inv() // clear EXL
                                    directPc = cop0[14] // EPC
                                }
                                recordControlFlowEvent("ERET pc=0x${instructionAddress.toULong().toString(16).uppercase()} " +
                                    "target=0x${directPc!!.toULong().toString(16).uppercase()} " +
                                    "status=0x${cop0[12].toULong().toString(16).uppercase()} " +
                                    "cause=0x${cop0[13].toULong().toString(16).uppercase()} " +
                                    "epc=0x${cop0[14].toULong().toString(16).uppercase()}")
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
            0x11 -> { // COP1 transfers and condition-code branches (partial FPU)
                when (rs) {
                    0x08 -> { // BC1F/T/FL/TL; this R4300 subset models FCC0 only.
                        if ((rt and 0x1C) != 0) {
                            throw ReservedInstructionFault("R4300 BC1 condition code $rt is not supported")
                        }
                        val conditionTrue = (fcr[31] and (1L shl 23)) != 0L
                        val branchKind = rt and 0x03
                        val takeBranch = if (branchKind == 0 || branchKind == 2) {
                            !conditionTrue // BC1F / BC1FL
                        } else {
                            conditionTrue // BC1T / BC1TL
                        }
                        val branchLikely = branchKind == 2 || branchKind == 3
                        if (branchLikely && !takeBranch) {
                            directPc = instructionAddress + 8L
                        } else {
                            pendingDelaySlotBranchAddress = instructionAddress
                            if (takeBranch) delayedTarget = nextSequential + (immediate.toLong() shl 2)
                        }
                    }
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
                if (rt == 0x08 || rt == 0x09 || rt == 0x0A || rt == 0x0B || rt == 0x0C || rt == 0x0E) {
                    val signedImmediate = immediate.toLong()
                    val trap = when (rt) {
                        0x08 -> gpr[rs] >= signedImmediate // TGEI
                        0x09 -> java.lang.Long.compareUnsigned(gpr[rs], signedImmediate) >= 0 // TGEIU
                        0x0A -> gpr[rs] < signedImmediate // TLTI
                        0x0B -> java.lang.Long.compareUnsigned(gpr[rs], signedImmediate) < 0 // TLTIU
                        0x0C -> gpr[rs] == signedImmediate // TEQI
                        else -> gpr[rs] != signedImmediate // TNEI
                    }
                    if (trap) {
                        enterException(instructionAddress, 13, branchAddressForCurrentInstruction)
                        directPc = exceptionVector()
                    }
                } else {
                    val branchLikely = rt == 0x02 || rt == 0x03 || rt == 0x12 || rt == 0x13
                    val link = rt == 0x10 || rt == 0x11 || rt == 0x12 || rt == 0x13
                    val takeBranch = when (rt) {
                        0x00, 0x10, 0x02, 0x12 -> gpr[rs] < 0L // BLTZ / BLTZAL / BLTZL / BLTZALL
                        0x01, 0x11, 0x03, 0x13 -> gpr[rs] >= 0L // BGEZ / BGEZAL / BGEZL / BGEZALL
                        else -> throw ReservedInstructionFault(
                            "R4300 REGIMM rt=0x${rt.toString(16)} at 0x${instructionAddress.toString(16)}"
                        )
                    }
                    if (branchLikely && !takeBranch) {
                        // Likely branches annul the delay-slot instruction when not taken.
                        directPc = instructionAddress + 8L
                    } else {
                        pendingDelaySlotBranchAddress = instructionAddress
                        if (takeBranch) {
                            if (link) gpr[31] = nextSequential + 4L
                            delayedTarget = nextSequential + (immediate.toLong() shl 2)
                        }
                    }
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
            0x20, 0x24 -> {
                val address = gpr[rs] + immediate
                val physical = physicalAddress(address, R4300Tlb.Access.READ)
                val value = memory.read8(physical)
                recordTlbMemoryAccess(if (opcode == 0x20) "LB" else "LBU", address, physical, value.toLong())
                gpr[rt] = if (opcode == 0x20) value.toByte().toLong() else value.toLong()
            }
            0x21 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 2)
                val physical = physicalAddress(address, R4300Tlb.Access.READ)
                val value = memory.read16(physical)
                recordTlbMemoryAccess("LH", address, physical, value.toLong())
                gpr[rt] = value.toShort().toLong()
            }
            0x25 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 2)
                val physical = physicalAddress(address, R4300Tlb.Access.READ)
                val value = memory.read16(physical)
                recordTlbMemoryAccess("LHU", address, physical, value.toLong())
                gpr[rt] = value.toLong()
            }
            0x23 -> {
                val address = gpr[rs] + immediate
                requireAligned(address, 4)
                val physical = physicalAddress(address, R4300Tlb.Access.READ)
                val value = memory.read32(physical)
                recordTlbMemoryAccess("LW", address, physical, value.toLong())
                gpr[rt] = value.toLong()
            }
            0x27 -> { // LWU: load a word and zero-extend to 64 bits
                val address = gpr[rs] + immediate
                requireAligned(address, 4)
                val physical = physicalAddress(address, R4300Tlb.Access.READ)
                val value = memory.read32(physical)
                recordTlbMemoryAccess("LWU", address, physical, value.toLong() and 0xFFFFFFFFL)
                gpr[rt] = value.toLong() and 0xFFFFFFFFL
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
                // Cache data is not modeled yet. Preserve the VR4300 LLbit side effect for
                // D-cache invalidation operations, which can invalidate the reserved line.
                val cacheOperation = (rt ushr 2) and 0x07
                val cacheSelector = rt and 0x03 // 0=I-cache, 1=D-cache.
                val invalidatesDataCache = cacheSelector == 1 &&
                    (cacheOperation == 0 || cacheOperation == 4 || cacheOperation == 5 ||
                        (cacheOperation == 2 && (cop0[28] and 0xC0L) == 0L))
                if (invalidatesDataCache) memory.clearLoadLinkedReservation()
                // Other cache data/tag operations remain unmodeled.
            }
            0x30 -> { // LL: load-linked signed word.
                val address = gpr[rs] + immediate
                requireAligned(address, 4)
                val physical = physicalAddress(address, R4300Tlb.Access.READ)
                gpr[rt] = memory.loadLinked(physical, 4)
                cop0[17] = (physical ushr 4).toLong() and 0x0FFFFFFFL // LLAddr diagnostic register.
            }
            0x34 -> { // LLD: load-linked doubleword.
                val address = gpr[rs] + immediate
                requireAligned(address, 8)
                val physical = physicalAddress(address, R4300Tlb.Access.READ)
                gpr[rt] = memory.loadLinked(physical, 8)
                cop0[17] = (physical ushr 4).toLong() and 0x0FFFFFFFL // LLAddr diagnostic register.
            }
            0x38 -> { // SC: store word only if its LL reservation is still valid.
                val address = gpr[rs] + immediate
                requireAligned(address, 4, store = true)
                val physical = physicalAddress(address, R4300Tlb.Access.WRITE)
                gpr[rt] = if (memory.storeConditional(physical, 4, gpr[rt])) 1L else 0L
            }
            0x3C -> { // SCD: store doubleword only if its LLD reservation is still valid.
                val address = gpr[rs] + immediate
                requireAligned(address, 8, store = true)
                val physical = physicalAddress(address, R4300Tlb.Access.WRITE)
                gpr[rt] = if (memory.storeConditional(physical, 8, gpr[rt])) 1L else 0L
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
                val physical = physicalAddress(address, R4300Tlb.Access.WRITE)
                memory.write32(physical, gpr[rt].toInt())
                recordTlbMemoryAccess("SW", address, physical, gpr[rt])
            }
            0x3F -> { // SD: store a big-endian doubleword
                val address = gpr[rs] + immediate
                requireAligned(address, 8, store = true)
                memory.write32(physicalAddress(address, R4300Tlb.Access.WRITE), (gpr[rt] ushr 32).toInt())
                memory.write32(physicalAddress(address + 4L, R4300Tlb.Access.WRITE), gpr[rt].toInt())
            }
            else -> throw ReservedInstructionFault("R4300 opcode 0x${opcode.toString(16)} at 0x${instructionAddress.toString(16)}")
        }

        recordGenericControlFlow(instructionAddress, instruction, opcode, rs, rt, funct, directPc)
        pc = directPc ?: targetFromPreviousBranch ?: nextSequential
        gpr[0] = 0L
    }

    /**
     * Records only control-transfer instructions, keeping diagnostic overhead out
     * of the normal arithmetic/load-store path. The bounded history is diagnostic
     * only and never participates in branch decisions or guest state.
     */
    private fun recordGenericControlFlow(
        address: Long,
        instruction: Int,
        opcode: Int,
        rs: Int,
        rt: Int,
        funct: Int,
        directPc: Long?
    ) {
        val kind = when {
            opcode == 0 && funct == 0x08 -> "JR"
            opcode == 0 && funct == 0x09 -> "JALR"
            opcode == 0x02 -> "J"
            opcode == 0x03 -> "JAL"
            opcode == 0x01 && rt != 0x08 && rt != 0x09 && rt != 0x0A && rt != 0x0B && rt != 0x0C && rt != 0x0E ->
                when (rt) {
                    0x00 -> "BLTZ"; 0x01 -> "BGEZ"; 0x02 -> "BLTZL"; 0x03 -> "BGEZL"
                    0x10 -> "BLTZAL"; 0x11 -> "BGEZAL"; 0x12 -> "BLTZALL"; 0x13 -> "BGEZALL"
                    else -> null
                }
            opcode == 0x11 && rs == 0x08 -> "BC1"
            (opcode == 0x04 || opcode == 0x05 || opcode == 0x06 || opcode == 0x07 ||
                opcode == 0x14 || opcode == 0x15 || opcode == 0x16 || opcode == 0x17) ->
                when (opcode) {
                    0x04 -> "BEQ"; 0x05 -> "BNE"; 0x06 -> "BLEZ"; 0x07 -> "BGTZ"
                    0x14 -> "BEQL"; 0x15 -> "BNEL"; 0x16 -> "BLEZL"; else -> "BGTZL"
                }
            else -> null
        } ?: return

        val taken = delayedTarget != null
        val target = delayedTarget ?: directPc ?: (address + 8L)
        val line = "FLOW kind=$kind PC=0x${address.toULong().toString(16).uppercase()} " +
            "INST=0x${instruction.toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "taken=$taken target=0x${target.toULong().toString(16).uppercase()} " +
            "RA=0x${gpr[31].toULong().toString(16).uppercase()} " +
            "A0=0x${gpr[4].toULong().toString(16).uppercase()} " +
            "A1=0x${gpr[5].toULong().toString(16).uppercase()} " +
            "A2=0x${gpr[6].toULong().toString(16).uppercase()} " +
            "A3=0x${gpr[7].toULong().toString(16).uppercase()}"
        recordControlFlowEvent(line)
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
        // Installing an entry does not toggle the CPU's translation mode.
        // The boot/runtime configuration controls tlbEnabled explicitly.
        val operation = if ((instructionWordForCurrentStep?.and(0x3F)) == 0x06) "TLBWR" else "TLBWI"
        val line = operation + " PC=0x" + (instructionAddressForCurrentStep ?: pc).toULong().toString(16).uppercase() +
            " index=$index PageMask=0x" + pageMask.toString(16).uppercase() +
            " EntryHi=0x" + entryHi.toString(16).uppercase() +
            " EntryLo0=0x" + cop0[2].toUInt().toString(16).uppercase().padStart(8, '0') +
            " EntryLo1=0x" + cop0[3].toUInt().toString(16).uppercase().padStart(8, '0')
        diagnosticTlbWriteHistory.add(line)
        if (diagnosticTlbWriteHistory.size > 64) diagnosticTlbWriteHistory.removeAt(0)
    }

    /** Complete, side-effect-free dump of all initialized architectural TLB entries. */
    fun diagnosticFullTlbSnapshot(): String = snapshotTlbEntriesForDiagnostic()

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
        return if (lines.isEmpty()) "(nenhuma entrada TLB inicializada)" else lines.joinToString("\n")
    }

    private fun enterException(
        instructionAddress: Long,
        exceptionCode: Int,
        branchAddress: Long?,
        coprocessorNumber: Int? = null
    ) {
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
        if (coprocessorNumber != null) {
            val coprocessorErrorMask = 0x3L shl 28
            cause = (cause and coprocessorErrorMask.inv()) or
                ((coprocessorNumber.toLong() and 0x3L) shl 28)
        }
        cop0[13] = cause
        cop0[12] = status or 0x2L // Set EXL.
        recordControlFlowEvent("EXCEPTION code=$exceptionCode pc=0x${instructionAddress.toULong().toString(16).uppercase()} " +
            "branch=${branchAddress?.let { "0x" + it.toULong().toString(16).uppercase() } ?: "none"} " +
            "vector=0x${exceptionVector().toULong().toString(16).uppercase()} " +
            "status=0x${cop0[12].toULong().toString(16).uppercase()} " +
            "cause=0x${cop0[13].toULong().toString(16).uppercase()} " +
            "epc=0x${cop0[14].toULong().toString(16).uppercase()}")
    }

    private fun enterTlbException(fault: R4300Tlb.TlbFault, branchAddress: Long?) {
        capturePreExceptionTlbTrace()
        if (lastTlbFaultKind == null) {
            firstTlbFaultIndirectJumpAddress = diagnosticLastIndirectJumpAddress
            firstTlbFaultIndirectJumpInstruction = diagnosticLastIndirectJumpInstruction
            firstTlbFaultIndirectTarget = diagnosticLastIndirectTarget
        }
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
        val trace = "TLB EXCEPTION kind=${fault.kind} access=${lastTlbFaultAccessType} " +
            "faultVA=0x${badAddress.toString(16).uppercase()} " +
            "faultPC=0x${(instructionAddressForCurrentStep ?: pc).toULong().toString(16).uppercase()} " +
            "instruction=0x${(instructionWordForCurrentStep ?: 0).toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "vector=0x${pc.toULong().toString(16).uppercase()} " +
            "Status=0x${cop0[12].toString(16).uppercase()} Cause=0x${cop0[13].toString(16).uppercase()} " +
            "EPC=0x${cop0[14].toULong().toString(16).uppercase()} EntryHi=0x${cop0[10].toString(16).uppercase()}"
        diagnosticTlbExceptionHistory.add(trace)
        if (diagnosticTlbExceptionHistory.size > 64) diagnosticTlbExceptionHistory.removeAt(0)
        // Capture the exception handler/refill path from its first instruction.
        diagnosticTlbTraceRemaining = 256
    }

    /** Formats the last successfully fetched instructions once, on the first TLB fault. */
    private fun capturePreExceptionTlbTrace() {
        if (preTlbFaultTraceCaptured) return
        preTlbFaultTraceCaptured = true
        val first = (preTlbFaultWriteIndex - preTlbFaultCount + TLB_PRE_EXCEPTION_TRACE_CAPACITY) %
            TLB_PRE_EXCEPTION_TRACE_CAPACITY
        repeat(preTlbFaultCount) { offset ->
            val index = (first + offset) % TLB_PRE_EXCEPTION_TRACE_CAPACITY
            val instruction = preTlbFaultInstruction[index]
            val opcode = (instruction ushr 26) and 0x3F
            val rs = (instruction ushr 21) and 0x1F
            val rt = (instruction ushr 16) and 0x1F
            diagnosticTlbPreExceptionHistory.add(
                "PC=0x${preTlbFaultPc[index].toULong().toString(16).uppercase()} " +
                    "INST=0x${instruction.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                    "OP=0x${opcode.toString(16).uppercase()} rs=$rs rt=$rt " +
                    "GPRrs=0x${preTlbFaultGprRs[index].toULong().toString(16).uppercase().padStart(16, '0')} " +
                    "GPRrt=0x${preTlbFaultGprRt[index].toULong().toString(16).uppercase().padStart(16, '0')} " +
                    "Status=0x${preTlbFaultStatus[index].toUInt().toString(16).uppercase().padStart(8, '0')}"
            )
        }
    }

    private fun recordPteSetupDiagnostic(line: String) {
        // Preserve the first 64 instructions (routine setup/loop entry), then keep
        // a rolling tail of 512 records. This prevents long loops from erasing
        // the initialization values while keeping diagnostics strictly bounded.
        if (diagnosticPteSetupHistory.size < PTE_TRACE_PREFIX_CAPACITY) {
            diagnosticPteSetupHistory.add(line)
            return
        }
        if (diagnosticPteSetupHistory.size == PTE_TRACE_PREFIX_CAPACITY) {
            diagnosticPteSetupHistory.add("... [instruções intermediárias omitidas; prefixo inicial + janela recente] ...")
        } else if (diagnosticPteSetupHistory.size >= PTE_TRACE_PREFIX_CAPACITY + 1 + PTE_TRACE_RECENT_CAPACITY) {
            diagnosticPteSetupHistory.removeAt(PTE_TRACE_PREFIX_CAPACITY + 1)
        }
        diagnosticPteSetupHistory.add(line)
    }

    private fun recordCallPathDiagnostic(address: Long, instruction: Int) {
        val lowAddress = address and 0xFFFFFFFFL
        val inReturnSite = lowAddress in 0x70001880L..0x70001894L
        val inOuterCaller = lowAddress in 0x70004E2CL..0x70004EACL
        val inOuterHelperA = lowAddress in 0x70004D20L..0x70004D80L
        val inOuterHelperB = lowAddress in 0x70004DE0L..0x70004E10L
        val inOuterHelperC = lowAddress in 0x70004EE0L..0x70004F20L
        val inInnerCaller = lowAddress in 0x70005B60L..0x70005D60L
        val inDmaHelper = lowAddress in 0x7000D6D0L..0x7000D790L
        val inDmaSetup = lowAddress in 0x7000DDD0L..0x7000DE30L
        // Include the branch target at 0x70010BAC; the previous window stopped before the main path.
        val inPiTransfer = lowAddress in 0x70010B20L..0x70010C80L
        val inPiCompletion = lowAddress in 0x7000F6A0L..0x7000F700L
        if (!inReturnSite && !inOuterCaller && !inOuterHelperA && !inOuterHelperB &&
            !inOuterHelperC && !inInnerCaller && !inDmaHelper && !inDmaSetup &&
            !inPiTransfer && !inPiCompletion
        ) return

        val opcode = (instruction ushr 26) and 0x3F
        val directTarget = if (opcode == 0x03 || opcode == 0x1D) {
            val pcPlusFour = (lowAddress + 4L) and 0xFFFFFFFFL
            (pcPlusFour and 0xF0000000L) or
                ((instruction.toLong() and 0x03FFFFFFL) shl 2)
        } else null
        val line = buildString {
            append("PC=0x${address.toULong().toString(16).uppercase()}")
            append(" INST=0x${instruction.toUInt().toString(16).uppercase().padStart(8, '0')}")
            append(" OP=0x${opcode.toString(16).uppercase()}")
            if (directTarget != null) append(" JAL_TARGET=0x${directTarget.toString(16).uppercase()}")
            append(" RA=0x${gpr[31].toULong().toString(16).uppercase().padStart(16, '0')}")
            append(" T9=0x${gpr[25].toULong().toString(16).uppercase().padStart(16, '0')}")
            append(" T0=0x${gpr[8].toULong().toString(16).uppercase().padStart(16, '0')}")
            append(" T1=0x${gpr[9].toULong().toString(16).uppercase().padStart(16, '0')}")
            append(" V0=0x${gpr[2].toULong().toString(16).uppercase().padStart(16, '0')}")
            append(" V1=0x${gpr[3].toULong().toString(16).uppercase().padStart(16, '0')}")
            for (index in 4..7) append(" A${index - 4}=0x${gpr[index].toULong().toString(16).uppercase().padStart(16, '0')}")
            append(" SP=0x${gpr[29].toULong().toString(16).uppercase().padStart(16, '0')}")
            for (index in 16..23) append(" S${index - 16}=0x${gpr[index].toULong().toString(16).uppercase().padStart(16, '0')}")
        }
        diagnosticCallPathHistory.add(line)
        if (diagnosticCallPathHistory.size > CALL_PATH_TRACE_CAPACITY) diagnosticCallPathHistory.removeAt(0)
    }

    private fun recordTlbDiagnosticInstruction(address: Long, instruction: Int) {
        diagnosticInstructionCount++
        val opcode = (instruction ushr 26) and 0x3F
        val rs = (instruction ushr 21) and 0x1F
        val rt = (instruction ushr 16) and 0x1F
        val rd = (instruction ushr 11) and 0x1F

        // Capture only the small code window implicated by the PTE initialization
        // watchpoint. This is inactive outside that address range.
        val lowAddress = address and 0xFFFFFFFFL
        recordCallPathDiagnostic(address, instruction)
        if (lowAddress in 0x700017C0L..0x70001840L) {
            val line = "PC=0x${address.toULong().toString(16).uppercase()} " +
                "INST=0x${instruction.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "OP=0x${opcode.toString(16).uppercase()} rs=$rs rt=$rt rd=$rd " +
                "GPR2=0x${gpr[2].toULong().toString(16).uppercase().padStart(16, '0')} " +
                "GPR3=0x${gpr[3].toULong().toString(16).uppercase().padStart(16, '0')} " +
                "GPR4=0x${gpr[4].toULong().toString(16).uppercase().padStart(16, '0')} " +
                "Context=0x${cop0[4].toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "EntryHi=0x${cop0[10].toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "EntryLo0=0x${cop0[2].toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "EntryLo1=0x${cop0[3].toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "PageMask=0x${cop0[5].toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "Status=0x${cop0[12].toUInt().toString(16).uppercase().padStart(8, '0')}"
            recordPteSetupDiagnostic(line)
        }

        // Keep a primitive-only rolling window on every successful fetch. Avoid
        // allocating diagnostic strings on the hot path; format only on fault.
        preTlbFaultPc[preTlbFaultWriteIndex] = address
        preTlbFaultInstruction[preTlbFaultWriteIndex] = instruction
        preTlbFaultGprRs[preTlbFaultWriteIndex] = gpr[rs]
        preTlbFaultGprRt[preTlbFaultWriteIndex] = gpr[rt]
        preTlbFaultStatus[preTlbFaultWriteIndex] = cop0[12]
        preTlbFaultWriteIndex = (preTlbFaultWriteIndex + 1) % TLB_PRE_EXCEPTION_TRACE_CAPACITY
        if (preTlbFaultCount < TLB_PRE_EXCEPTION_TRACE_CAPACITY) preTlbFaultCount++

        val captureBurst = diagnosticTlbTraceRemaining > 0
        val captureSample = diagnosticTlbExceptionHistory.isNotEmpty() && diagnosticInstructionCount % 250_000L == 0L
        if (!captureBurst && !captureSample) return
        if (captureBurst) diagnosticTlbTraceRemaining--
        val line = (if (captureSample && !captureBurst) "SAMPLE " else "") +
            "PC=0x${address.toULong().toString(16).uppercase()} INST=0x${instruction.toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "OP=0x${opcode.toString(16).uppercase()} rs=$rs rt=$rt rd=$rd " +
            "GPRrs=0x${gpr[rs].toULong().toString(16).uppercase().padStart(16, '0')} " +
            "GPRrt=0x${gpr[rt].toULong().toString(16).uppercase().padStart(16, '0')} " +
            "Status=0x${cop0[12].toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "Index=0x${cop0[0].toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "Random=0x${cop0[1].toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "EntryHi=0x${cop0[10].toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "PageMask=0x${cop0[5].toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "EntryLo0=0x${cop0[2].toUInt().toString(16).uppercase().padStart(8, '0')} " +
            "EntryLo1=0x${cop0[3].toUInt().toString(16).uppercase().padStart(8, '0')}"
        diagnosticTlbInstructionHistory.add(line)
        if (diagnosticTlbInstructionHistory.size > 512) diagnosticTlbInstructionHistory.removeAt(0)
    }

    private fun recordControlFlowEvent(event: String) {
        diagnosticControlFlowHistory.add(event)
        if (diagnosticControlFlowHistory.size > 64) diagnosticControlFlowHistory.removeAt(0)
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

    private class CoprocessorUnusableFault(val coprocessorNumber: Int) : RuntimeException()

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

    private fun recordTlbMemoryAccess(kind: String, virtualAddress: Long, physicalAddress: Int, value: Long) {
        if (diagnosticTlbTraceRemaining <= 0) return
        val line = kind + " PC=0x" + (instructionAddressForCurrentStep ?: pc).toULong().toString(16).uppercase() +
            " VA=0x" + virtualAddress.toULong().toString(16).uppercase() +
            " PA=0x" + physicalAddress.toUInt().toString(16).uppercase().padStart(8, '0') +
            " VALUE=0x" + value.toULong().toString(16).uppercase().padStart(16, '0')
        diagnosticTlbMemoryAccessHistory.add(line)
        if (diagnosticTlbMemoryAccessHistory.size > 512) diagnosticTlbMemoryAccessHistory.removeAt(0)
    }

    private fun physicalAddress(
        address: Long,
        access: R4300Tlb.Access = R4300Tlb.Access.READ
    ): Int {
        // Keep live access diagnostics accurate for FETCH, READ, and WRITE.
        // These are constant strings, so tracking fetches does not create a new
        // diagnostic string on every CPU step.
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
        private const val TLB_PRE_EXCEPTION_TRACE_CAPACITY = 64
        private const val PTE_TRACE_PREFIX_CAPACITY = 64
        private const val PTE_TRACE_RECENT_CAPACITY = 512
        private const val CALL_PATH_TRACE_CAPACITY = 512
        const val RESET_VECTOR: Long = -0x40400000L
    }
}
