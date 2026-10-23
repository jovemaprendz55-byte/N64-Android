package com.jovemaprendz55byte.n64android

import java.math.BigInteger

/**
 * Small, testable MIPS III/R4300 interpreter foundation.
 *
 * This remains a partial CPU core: exceptions, TLB/FPU, caches, and N64
 * peripherals are not implemented. Implemented RDRAM and SP DMEM/IMEM accesses
 * are routed through the physical memory bus.
 */
internal enum class TlbAccess { FETCH, LOAD, STORE }

internal class TlbTranslationFault(
    val virtualAddress: Long,
    val access: TlbAccess
) : IllegalArgumentException(
    "Virtual address 0x%016X requires TLB translation; no valid matching R4300 TLB entry".format(virtualAddress)
)

class R4300Cpu private constructor(memorySize: Int, internal val memoryBus: N64MemoryBus) {
    constructor(memorySize: Int = DEFAULT_RDRAM_SIZE) : this(memorySize, N64MemoryBus(memorySize))

    internal constructor(memoryBus: N64MemoryBus) : this(DEFAULT_RDRAM_SIZE, memoryBus)
    private val rdramSize = memorySize.also { size ->
        require(size > 0 && size % 4 == 0) {
            "RAM size must be a positive multiple of four bytes"
        }
        require(size <= DEFAULT_RDRAM_SIZE) {
            "RAM size cannot exceed the standard 8 MiB RDRAM capacity"
        }
    }

    companion object {
        /** Maximum standard Nintendo 64 RDRAM capacity (8 MiB). */
        const val DEFAULT_RDRAM_SIZE: Int = 8 * 1024 * 1024
    }
    private val gpr = LongArray(32)
    private val cpuMemoryProbeHistory = mutableListOf<String>()
    /** CPU-side stores that launch PI DMA, including the issuing instruction PC. */
    private val cpuPiWriteHistory = mutableListOf<String>()
    // CP0 register file and the R4300's 32-entry software-managed TLB.
    private val cp0 = LongArray(32)
    // COP1 floating-point registers and control registers. The current core
    // implements register transfers first; arithmetic/exception semantics remain partial.
    private val fpr = LongArray(32)
    private val fcr = IntArray(32)
    private data class TlbEntry(var pageMask: Int = 0, var entryHi: Int = 0, var entryLo0: Int = 0, var entryLo1: Int = 0)
    private val tlb = Array(32) { TlbEntry() }
    /** Recent TLB writes, including initialization writes before the faulting access. */
    private val tlbWriteHistory = java.util.ArrayDeque<String>(64)

    var pc: Long = 0L
    var hi: Long = 0L
        private set
    var lo: Long = 0L
        private set

    private var pendingBranchTarget: Long? = null
    /** Most recent branch/jump instruction and target, retained for ROM failure diagnostics. */
    private var lastBranchInstructionAddress: Long? = null
    private var lastBranchInstructionWord: Int? = null
    private var lastBranchTarget: Long? = null
    /** Source register/value for the most recent JR/JALR, to trace unexpected indirect targets. */
    private var lastIndirectJumpRegister: Int? = null
    private var lastIndirectJumpValue: Long? = null
    /** Address of the last instruction fetched successfully, for failure diagnostics. */
    private var lastInstructionAddress: Long? = null
    /** Last fetched instruction, retained so execution failures can be diagnosed. */
    internal var lastInstructionWord: Int = 0
        private set

    /** Bounded diagnostics: avoid allocating strings and growing a HashSet on every CPU step. */
    private val recentInstructions = java.util.ArrayDeque<String>(16)
    private val recentInstructionPcs = LongArray(64)
    private var recentInstructionPcIndex = 0
    /** Exact rolling trace, frozen at the first TLB fault to preserve the path into it. */
    private val instructionTracePcs = LongArray(32)
    private val instructionTraceWords = IntArray(32)
    private var instructionTraceIndex = 0
    private var instructionTraceCount = 0
    private var firstTlbFaultHistory: String? = null
    /** TLB contents captured before software handles the first translation fault. */
    private var firstTlbFaultTlbHistory: String? = null
    private var recentInstructionPcCount = 0
    private var frozenLoopTrace: String? = null
    private var totalSteps: Long = 0L

    internal val lastExecutedInstructionAddress: Long?
        get() = lastInstructionAddress

    /**
     * True only for the known IPL3 checksum-failure self-branch (BGEZAL $zero,-1).
     * It is diagnostic, not a general rule that all self-loops are errors.
     */
    internal fun isIpl3ChecksumFailureBranch(): Boolean {
        val address = lastInstructionAddress ?: return false
        if (lastInstructionWord != 0x0411FFFF || lastBranchTarget != address) return false
        val physical = address and 0x1FFF_FFFFL
        return physical < 0x1000L ||
            (physical >= 0x0400_0000L && physical < 0x0400_1000L)
    }

    /** Exact instruction sequence immediately preceding the first TLB fault, if one occurred. */
    internal fun executionHistoryDiagnosticSummary(): String =
        firstTlbFaultHistory ?: currentInstructionTraceSummary().ifBlank {
            frozenLoopTrace ?: recentInstructions.joinToString(" | ").ifBlank { "sem historico" }
        }

    /** Most recent instructions, including exception-handler execution after the first TLB fault. */
    internal fun currentExecutionTraceDiagnosticSummary(): String =
        currentInstructionTraceSummary().ifBlank { "sem historico atual" }

    /** Current live TLB state; unlike the first-fault snapshot, this includes later handler writes. */
    internal fun currentTlbDiagnosticSummary(): String = tlbDiagnosticSummary()

    private fun currentInstructionTraceSummary(): String {
        if (instructionTraceCount == 0) return ""
        val start = (instructionTraceIndex - instructionTraceCount + instructionTracePcs.size) % instructionTracePcs.size
        return (0 until instructionTraceCount).joinToString(" | ") { offset ->
            val index = (start + offset) % instructionTracePcs.size
            "0x" + instructionTracePcs[index].toULong().toString(16)
                .uppercase(java.util.Locale.ROOT).padStart(16, '0') +
                "=0x" + instructionTraceWords[index].toUInt().toString(16)
                .uppercase(java.util.Locale.ROOT).padStart(8, '0')
        }
    }

    /** Compact CPU state snapshot attached to ROM-test failures. */
    internal fun diagnosticSnapshot(): String = buildString {
        append("PC=0x")
        append(pc.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
        append("; ultimaInstrucaoEm=")
        append(lastInstructionAddress?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "nenhuma")
        append("; alvoPendente=")
        append(pendingBranchTarget?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "nenhum")
        append("; ultimoSaltoEm=")
        append(lastBranchInstructionAddress?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "nenhum")
        append("; instrucaoSalto=0x")
        append(lastBranchInstructionWord?.toUInt()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(8, '0') ?: "nenhuma")
        append("; CPU_MEMORY_PROBE_HISTORY=").append(cpuMemoryProbeHistory.joinToString(";"))
        append("; alvoUltimoSalto=")
        append(lastBranchTarget?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "nenhum")
        append("; JR_JALR_SOURCE=")
        append(lastIndirectJumpRegister?.let { "r$it" } ?: "nenhum")
        append("; JR_JALR_VALUE=0x")
        append(lastIndirectJumpValue?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "nenhum")
        append("; Instrucao=0x")
        append(lastInstructionWord.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append("; registradores:")
        for (index in 1..31) {
            append(" r").append(index).append("=0x")
            append(register(index).toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
        }
    }


    /** Compact live execution trace for diagnosing loops before video initialization. */
    internal fun executionDiagnosticSummary(): String = buildString {
        append("LAST_PC=0x")
        append(lastInstructionAddress?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "NONE")
        append(", LAST_INSTR=0x")
        append(lastInstructionWord.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", LAST_BRANCH=0x")
        append(lastBranchInstructionAddress?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "NONE")
        append(", BRANCH_TARGET=0x")
        append(lastBranchTarget?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "NONE")
        append(", R1=0x").append(register(1).toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
        append(", R2=0x").append(register(2).toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
        for (index in 8..17) {
            append(", R").append(index).append("=0x")
                .append(register(index).toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
        }
        append(", SP=0x").append(register(29).toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
    }

    /**
     * Enters the architectural TLB-refill exception path after an address
     * translation miss. The core does not synthesize a mapping: software at
     * the exception vector must install a real TLB entry before retrying.
     */
    internal fun handleTlbTranslationFault(fault: TlbTranslationFault, faultingPc: Long) {
        if (firstTlbFaultHistory == null) {
            firstTlbFaultHistory = currentInstructionTraceSummary()
            firstTlbFaultTlbHistory = tlbDiagnosticSummary(fault.virtualAddress)
        }
        val oldStatus = cp0[12].toInt()
        val alreadyExl = (oldStatus and 0x2) != 0
        val virtual = fault.virtualAddress.toInt()

        cp0[8] = fault.virtualAddress // BadVAddr
        cp0[4] = ((cp0[4].toInt() and 0xFF80_000F.toInt()) or
            ((virtual ushr 9) and 0x007F_FFF0)).toLong() // Context.BadVPN2
        cp0[10] = ((virtual and 0xFFFF_E000.toInt()) or (cp0[10].toInt() and 0xFF)).toLong() // EntryHi
        val exceptionCode = if (fault.access == TlbAccess.STORE) 3 else 2 // TLBS / TLBL
        cp0[13] = ((cp0[13].toInt() and 0xFFFF_FF83.toInt()) or (exceptionCode shl 2)).toLong()
        if (!alreadyExl) cp0[14] = faultingPc // EPC
        cp0[12] = (oldStatus or 0x2).toLong() // Status.EXL

        val exceptionVector = when {
            alreadyExl -> 0xFFFF_FFFF_8000_0180UL.toLong()
            (oldStatus and (1 shl 22)) != 0 -> 0xFFFF_FFFF_BFC0_0200UL.toLong() // Status.BEV
            else -> 0xFFFF_FFFF_8000_0000UL.toLong() // TLB refill vector
        }
        pendingBranchTarget = null
        pc = exceptionVector
    }

    /** Compact snapshot of CP0 registers relevant to boot and address exceptions. */
    internal fun cp0DiagnosticSummary(): String = buildString {
        fun reg(name: String, index: Int) {
            append(name).append("=0x")
                .append(cp0[index].toULong().toString(16)
                    .uppercase(java.util.Locale.ROOT).padStart(16, '0'))
        }
        reg("Status", 12)
        append(", ")
        reg("Cause", 13)
        append(", ")
        reg("EPC", 14)
        append(", ")
        reg("BadVAddr", 8)
        append(", ")
        reg("Context", 4)
        append(", ")
        reg("EntryHi", 10)
        append(", ")
        reg("EntryLo0", 2)
        append(", ")
        reg("EntryLo1", 3)
        append(", ")
        reg("Index", 0)
        append(", ")
        reg("Random", 1)
    }

    /**
     * Reports non-empty TLB slots and whether any slot covers the faulting VPN.
     * This is diagnostic only: it never creates or alters a translation.
     */
    internal fun tlbDiagnosticSummary(faultAddress: Long? = null): String {
        val address = (faultAddress ?: cp0[8]).toInt()
        val asid = cp0[10].toInt() and 0xFF
        var matchingVpnCount = 0
        var meaningfulEntryCount = 0
        val entries = mutableListOf<String>()
        tlb.forEachIndexed { index, entry ->
            // Reset-valued HI=0x80000000 by itself is not a populated mapping.
            val populated = entry.pageMask != 0 || entry.entryLo0 != 0 || entry.entryLo1 != 0 ||
                (entry.entryHi != 0 && entry.entryHi != 0x8000_0000.toInt())
            if (populated) {
                meaningfulEntryCount++
                val pairMask = entry.pageMask or 0x1FFF
                val vpnMask = pairMask.inv()
                val vpnMatches = (address and vpnMask) == (entry.entryHi and vpnMask)
                val global = (entry.entryLo0 and 1) != 0 && (entry.entryLo1 and 1) != 0
                val asidMatches = (entry.entryHi and 0xFF) == asid
                val pageSize = (pairMask + 1) ushr 1
                val odd = (address and pageSize) != 0
                val selectedLo = if (odd) entry.entryLo1 else entry.entryLo0
                val usable = vpnMatches && (global || asidMatches) && (selectedLo and 2) != 0
                if (vpnMatches && (global || asidMatches)) matchingVpnCount++
                entries += "TLB_ENTRY_$index=PM=0x%08X,HI=0x%08X,LO0=0x%08X,LO1=0x%08X,VPN_MATCH=%s,ASID_MATCH=%s,SELECTED=%s,VALID=%s".format(
                    java.util.Locale.ROOT,
                    entry.pageMask, entry.entryHi, entry.entryLo0, entry.entryLo1,
                    vpnMatches, global || asidMatches, if (odd) "LO1" else "LO0", usable
                )
            }
        }
        val writes = tlbWriteHistory.joinToString(";").ifBlank { "none" }
        return "TLB_NONZERO_COUNT=$meaningfulEntryCount, TLB_WRITE_HISTORY=$writes, TLB_FAULT_ADDRESS=0x%08X, TLB_FAULT_MATCHING_VPN_COUNT=%d, TLB_ENTRIES=%s".format(
            java.util.Locale.ROOT, address, matchingVpnCount, entries.joinToString(";").ifBlank { "none" }
        )
    }

    internal fun firstTlbFaultDiagnosticSummary(): String =
        firstTlbFaultTlbHistory ?: tlbDiagnosticSummary()

    internal fun readCp0ForTest(index: Int): Long = cp0[index]

    internal fun piWriteDiagnosticSummary(): String =
        cpuPiWriteHistory.joinToString(";").ifBlank { "sem_escritas_PI_pela_CPU" }

    fun register(index: Int): Long {
        require(index in 0..31) { "Register index must be 0..31" }
        return if (index == 0) 0L else gpr[index]
    }

    /**
     * Maps a normalized Z64 cartridge ROM into the physical cartridge domain.
     * This does not emulate the PIF boot process or initialize N64 hardware.
     */
    fun loadCartridgeRom(bytes: ByteArray) {
        require(bytes.size >= 0x1000 && bytes.size <= 64 * 1024 * 1024 &&
            bytes.size % 4 == 0) {
            "ROM size must be 4 KiB..64 MiB and a multiple of four bytes"
        }
        val header = RomHeader.parse(bytes)
        require(header?.format == RomHeader.Format.Z64_BIG_ENDIAN) {
            "Cartridge ROM must be normalized to Z64 big-endian order"
        }
        memoryBus.loadCartridgeRom(bytes)
    }

    /**
     * Executes at most [maxInstructions] instructions. Returns the number run.
     * This bounded loop is a CPU harness, not a complete game boot/frame loop.
     */
    fun run(maxInstructions: Int): Int {
        require(maxInstructions > 0) { "Instruction budget must be positive" }
        var executed = 0
        while (executed < maxInstructions) {
            step()
            executed++
        }
        return executed
    }

    /** Loads a program/data image into physical RDRAM through the memory bus. */
    fun loadBytes(address: Int, bytes: ByteArray) {
        require(address >= 0) { "Memory address must not be negative" }
        memoryBus.loadRdram(address.toLong(), bytes)
    }

    fun readByte(address: Int): Byte {
        require(address >= 0) { "Memory address must not be negative" }
        return memoryBus.readByte(address.toLong())
    }

    fun writeByte(address: Int, value: Byte) {
        require(address >= 0) { "Memory address must not be negative" }
        memoryBus.writeByte(address.toLong(), value)
    }

    /** Reads a signed, big-endian 16-bit value. */
    fun readHalf(address: Int): Short {
        require(address >= 0) { "Memory address must not be negative" }
        return memoryBus.readHalf(address.toLong())
    }

    fun writeHalf(address: Int, value: Short) {
        require(address >= 0) { "Memory address must not be negative" }
        memoryBus.writeHalf(address.toLong(), value)
    }

    fun readWord(address: Int): Int {
        require(address >= 0) { "Memory address must not be negative" }
        return memoryBus.readWord(address.toLong())
    }

    fun writeWord(address: Int, value: Int) {
        require(address >= 0) { "Memory address must not be negative" }
        memoryBus.writeWord(address.toLong(), value)
    }

    fun readLong(address: Int): Long {
        require(address >= 0) { "Memory address must not be negative" }
        require(address % 8 == 0) { "Doubleword access must be 8-byte aligned" }
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (readByte(address + i).toLong() and 0xFFL)
        }
        return value
    }

    fun writeLong(address: Int, value: Long) {
        require(address >= 0) { "Memory address must not be negative" }
        require(address % 8 == 0) { "Doubleword access must be 8-byte aligned" }
        for (i in 0 until 8) {
            writeByte(address + i, (value ushr (56 - i * 8)).toByte())
        }
    }

    /**
     * Resets CPU execution state while preserving RAM contents.
     * The entry point must be an aligned address inside configured RAM.
     */
    fun reset(entryPoint: Long = 0L) {
        require(entryPoint >= 0L && entryPoint % 4L == 0L &&
            entryPoint <= rdramSize.toLong() - 4L) {
            "Entry point must be aligned and inside configured RAM"
        }
        resetExecutionState(entryPoint)
    }

    /**
     * Clears CPU registers and branch state while preserving memory.
     * Unlike [reset], this accepts virtual entry points such as a cartridge
     * address in KSEG1; the next instruction fetch validates address mapping.
     */
    fun resetExecutionState(entryPoint: Long) {
        require(entryPoint % 4L == 0L) { "Entry point must be 4-byte aligned" }
        gpr.fill(0L)
        fpr.fill(0L)
        fcr.fill(0)
        cp0.fill(0L)
        tlb.forEach { it.pageMask = 0; it.entryHi = 0; it.entryLo0 = 0; it.entryLo1 = 0 }
        cp0[1] = 31L // Random starts at the last TLB slot
        hi = 0L
        lo = 0L
        pc = entryPoint
        pendingBranchTarget = null
        lastInstructionAddress = null
        lastInstructionWord = 0
        lastBranchInstructionAddress = null
        lastBranchInstructionWord = null
        lastBranchTarget = null
        lastIndirectJumpRegister = null
        lastIndirectJumpValue = null
        recentInstructions.clear()
        recentInstructionPcs.fill(0L)
        recentInstructionPcIndex = 0
        recentInstructionPcCount = 0
        instructionTracePcs.fill(0L)
        instructionTraceWords.fill(0)
        instructionTraceIndex = 0
        instructionTraceCount = 0
        firstTlbFaultHistory = null
        firstTlbFaultTlbHistory = null
        tlbWriteHistory.clear()
        frozenLoopTrace = null
        totalSteps = 0L
    }

    /**
     * Diagnostic-only approximation for directly entering cartridge IPL3 code.
     * A real N64 starts through the PIF boot ROM, which initializes hardware and
     * CPU state. This helper only supplies a usable RDRAM stack for this shortcut.
     */
    internal fun resetForCartridgeDiagnostic(entryPoint: Long) {
        resetExecutionState(entryPoint)
        gpr[29] = 0xFFFF_FFFF_803F_FFF0UL.toLong()
    }

    /**
     * Approximate the documented CIC-NUS-6102/7101 IPL3 handoff used by
     * common retail cartridges. This is still an HLE diagnostic shortcut,
     * not execution of the console's PIF boot ROM.
     */
    internal fun resetForCic6102Ipl3Diagnostic(entryPoint: Long, pal: Boolean) {
        resetExecutionState(entryPoint)

        // Register values follow the published Project64-derived R4300 boot
        // state for CIC-6102. In particular s6 MUST be 0x3F, not 0x91 (6105).
        gpr[1] = 1L
        gpr[2] = 0x0EBDA536L
        gpr[3] = 0x0EBDA536L
        gpr[4] = 0xA536L
        gpr[5] = 0xFFFF_FFFF_C0F1_D859UL.toLong()
        gpr[6] = 0xFFFF_FFFF_A400_1F0CUL.toLong()
        gpr[7] = 0xFFFF_FFFF_A400_1F08UL.toLong()
        gpr[8] = 0xC0L
        gpr[9] = 0L
        gpr[10] = 0x40L
        gpr[11] = 0xFFFF_FFFF_A400_0040UL.toLong()
        gpr[12] = 0xFFFF_FFFF_ED10_D0B3UL.toLong()
        gpr[13] = 0x1402_A4CCL
        gpr[14] = 0x2DE1_08EAL
        gpr[15] = 0x3103_E121L
        gpr[19] = 0L // s3: cartridge boot
        gpr[20] = if (pal) 0L else 1L // s4: PAL=0, NTSC=1
        gpr[21] = 0L // s5: cold reset
        gpr[22] = 0x3FL // s6: CIC-6102 checksum seed
        gpr[23] = 0L // s7: legacy IPL2 version
        gpr[24] = 3L
        gpr[25] = 0xFFFF_FFFF_9DEB_B54FUL.toLong()
        gpr[29] = 0xFFFF_FFFF_A400_1FF0UL.toLong()
        gpr[31] = 0xFFFF_FFFF_A400_1550UL.toLong()

        cp0[12] = 0x3400_0000L // Status
        cp0[16] = 0x0006_E463L // Config
    }

    /**
     * Approximate the register handoff from the PIF/ IPL2 boot path for a
     * CIC-NUS-6105 cartridge before entering its IPL3 image in SP DMEM.
     * This is not a substitute for executing the PIF boot ROM or validating
     * the cartridge CIC checksum; it supplies the known 6105 NTSC/PAL seeds.
     */
    internal fun resetForCic6105Ipl3Diagnostic(entryPoint: Long, pal: Boolean) {
        resetExecutionState(entryPoint)

        // CIC-6105 IPL2 handoff values, cross-checked against the documented
        // PIF/boot register setup used by N64 IPL3 implementations.
        // IPL3 depends on these values while descrambling itself and checking
        // the first MiB of the cartridge; plausible-looking partial seeds fail.
        gpr[1] = 0L // at
        gpr[2] = 0xFFFF_FFFF_D173_1BE9UL.toLong() // v0
        gpr[3] = 0xFFFF_FFFF_D173_1BE9UL.toLong() // v1
        gpr[4] = 0x1BE9L // a0
        gpr[5] = 0xFFFF_FFFF_F452_31E5UL.toLong() // a1
        gpr[6] = 0xFFFF_FFFF_A400_1F0CUL.toLong() // a2
        gpr[7] = 0xFFFF_FFFF_A400_1F08UL.toLong() // a3
        gpr[8] = 0xC0L // t0
        gpr[9] = 0L // t1
        gpr[10] = 0x40L // t2
        gpr[11] = 0xFFFF_FFFF_A400_0040UL.toLong() // t3: IPL3 entry in SP DMEM
        gpr[12] = 0xFFFF_FFFF_D133_0BC3UL.toLong() // t4
        gpr[13] = 0xFFFF_FFFF_D133_0BC3UL.toLong() // t5
        gpr[14] = 0x2561_3A26L // t6
        gpr[15] = 0x2EA0_4317L // t7
        gpr[16] = 0L // s0
        gpr[17] = 0L // s1
        gpr[18] = 0L // s2
        gpr[19] = 0L // s3: cartridge boot
        gpr[20] = if (pal) 0L else 1L // s4: PAL=0, NTSC=1
        gpr[21] = 0L // s5: cold reset
        gpr[22] = 0x91L // s6: CIC-NUS-6105 checksum seed
        gpr[23] = 6L // s7: IPL3 version
        gpr[24] = 0L // t8
        gpr[25] = 0xFFFF_FFFF_D73F_2993UL.toLong() // t9
        gpr[26] = 0L // k0
        gpr[27] = 0L // k1
        gpr[28] = 0L // gp
        gpr[29] = 0xFFFF_FFFF_A400_1FF0UL.toLong() // sp
        gpr[30] = 0L // s8
        gpr[31] = 0xFFFF_FFFF_A400_1554UL.toLong() // ra

        // PIF boot ROM's CP0 state and the eight-word IPL2 stub required by
        // CIC x105. IPL3 itself was copied into SP DMEM by the caller.
        cp0[12] = 0x3400_0000L // Status
        cp0[16] = 0x7006_E463L // Config
        val ipl2ImemStub = intArrayOf(
            0x3C0D_BFC0, 0x8DA8_07FCL.toInt(), 0x25AD_07C0, 0x3108_0080,
            0x5500_FFFC, 0x3C0D_BFC0, 0x8DA8_0024L.toInt(), 0x3C0B_B000
        )
        for (index in ipl2ImemStub.indices) {
            memoryBus.writeWord(0x0400_1000L + index * 4L, ipl2ImemStub[index])
        }
    }

    /** Executes one instruction; branches take effect after their delay slot. */
    fun step() {
        val instructionAddress = pc
        val instruction = fetchInstruction(pc)
        lastInstructionAddress = instructionAddress
        lastInstructionWord = instruction
        instructionTracePcs[instructionTraceIndex] = instructionAddress
        instructionTraceWords[instructionTraceIndex] = instruction
        instructionTraceIndex = (instructionTraceIndex + 1) % instructionTracePcs.size
        if (instructionTraceCount < instructionTracePcs.size) instructionTraceCount++
        totalSteps++
        // Keep the fixed-size PC ring updated every instruction, but only
        // scan it at the existing 64-instruction diagnostic cadence. Scanning
        // all recent PCs on every step adds up to 64 comparisons per emulated
        // instruction and does not affect CPU semantics.
        val diagnosticCheckpoint = (totalSteps and 63L) == 0L
        var repeatedRecentPc = false
        if (diagnosticCheckpoint) {
            var i = 0
            while (i < recentInstructionPcCount) {
                if (recentInstructionPcs[i] == instructionAddress) {
                    repeatedRecentPc = true
                    break
                }
                i++
            }
        }
        recentInstructionPcs[recentInstructionPcIndex] = instructionAddress
        recentInstructionPcIndex = (recentInstructionPcIndex + 1) % recentInstructionPcs.size
        if (recentInstructionPcCount < recentInstructionPcs.size) recentInstructionPcCount++

        // Format diagnostic text only during startup, periodically, or on a detected loop.
        if (totalSteps <= 256L || diagnosticCheckpoint || repeatedRecentPc) {
            val traceEntry = "0x" + instructionAddress.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0') +
                "=0x" + instruction.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0')
            if (recentInstructions.size >= 16) recentInstructions.removeFirst()
            recentInstructions.addLast(traceEntry)
        }
        // Preserve useful evidence when execution enters a repeated-PC loop.
        if (totalSteps > 256L && frozenLoopTrace == null && repeatedRecentPc) {
            frozenLoopTrace = recentInstructions.joinToString(" | ")
        }
        pc += 4L

        val branchToApply = pendingBranchTarget
        pendingBranchTarget = null

        val opcode = instruction ushr 26
        val rs = (instruction ushr 21) and 0x1F
        val rt = (instruction ushr 16) and 0x1F
        val rd = (instruction ushr 11) and 0x1F
        val shift = (instruction ushr 6) and 0x1F
        val funct = instruction and 0x3F
        val immediate = instruction and 0xFFFF
        val signedImmediate = immediate.toShort().toLong()
        val a = register(rs)
        val b = register(rt)

        when (opcode) {
            0x11 -> when (rs) {
                0x00 -> writeRegister(rt, fpr[rd].toInt().toLong()) // MFC1: move single-precision register bits
                0x01 -> writeRegister(rt, fpr[rd]) // DMFC1: move 64-bit FPR contents
                0x02 -> writeRegister(rt, fcr[rd].toLong()) // CFC1: read FPU control register (including FCR31)
                0x04 -> fpr[rd] = (fpr[rd] and 0xFFFF_FFFF_0000_0000UL.toLong()) or (b.toInt().toLong() and 0xFFFF_FFFFL) // MTC1
                0x05 -> fpr[rd] = b // DMTC1
                0x06 -> {
                    // FCR31: preserve only architecturally writable condition/rounding/flag bits.
                    fcr[rd] = if (rd == 31) {
                        (b.toInt() and 0x0183_FFFF)
                    } else {
                        b.toInt()
                    }
                } // CTC1
                0x08 -> {
                    // BC1F/T and likely variants; the FCR31 condition bit is bit 23.
                    val branchCondition = (fcr[31] and (1 shl 23)) != 0
                    val likely = (rt and 0x02) != 0
                    val take = if ((rt and 0x01) != 0) branchCondition else !branchCondition
                    if (take) pendingBranchTarget = pc + (signedImmediate shl 2)
                    else if (likely) pc += 4L
                }
                else -> unsupported(instruction, instructionAddress)
            }
            0x10 -> when (rs) {
                0x00 -> writeRegister(rt, cp0[rd].toInt().toLong()) // MFC0: sign-extend the 32-bit CP0 value
                0x04 -> {
                    val value = b.toInt()
                    cp0[rd] = if (rd == 13) {
                        // Only software interrupt pending bits IP0/IP1 are writable in Cause.
                        ((cp0[rd].toInt() and 0xFFFF_FCFF.toInt()) or (value and 0x0000_0300)).toLong()
                    } else {
                        value.toLong()
                    }
                } // MTC0: honor Cause's writable-bit mask
                0x10 -> when (funct) {
                    0x01 -> { // TLBR: copy the indexed TLB entry into CP0
                        val entry = tlb[cp0[0].toInt() and 31]
                        cp0[5] = entry.pageMask.toLong()
                        cp0[10] = entry.entryHi.toLong()
                        cp0[2] = entry.entryLo0.toLong()
                        cp0[3] = entry.entryLo1.toLong()
                    }
                    0x02 -> writeTlbEntry(cp0[0].toInt() and 31) // TLBWI
                    0x06 -> writeTlbEntry(cp0[1].toInt() and 31) // TLBWR
                    0x08 -> { // TLBP: probe EntryHi; set Index.P on miss
                        val hiValue = cp0[10].toInt()
                        val asid = hiValue and 0xFF
                        val vpnMask = (cp0[5].toInt() or 0x1FFF).inv()
                        val found = tlb.indexOfFirst { entry ->
                            (entry.entryHi and vpnMask) == (hiValue and vpnMask) &&
                                (((entry.entryLo0 and 1) != 0 && (entry.entryLo1 and 1) != 0) ||
                                    (entry.entryHi and 0xFF) == asid)
                        }
                        cp0[0] = if (found >= 0) found.toLong() else 0x8000_0000L
                    }
                    0x18 -> {
                        // ERET: return to EPC and clear Status.EXL. Exception
                        // entry itself is not modeled by this simplified core.
                        pc = cp0[14]
                        cp0[12] = cp0[12] and 0xFFFF_FFFDL
                    }
                    else -> unsupported(instruction, instructionAddress)
                }
                else -> unsupported(instruction, instructionAddress)
            }
            0 -> when (funct) {
                0x00 -> writeRegister(rd, (b.toInt() shl shift).toLong()) // SLL
                0x02 -> writeRegister(rd, (b.toInt() ushr shift).toLong()) // SRL
                0x03 -> writeRegister(rd, (b.toInt() shr shift).toLong()) // SRA
                0x04 -> writeRegister(rd, (b.toInt() shl (a.toInt() and 0x1F)).toLong()) // SLLV
                0x06 -> writeRegister(rd, (b.toInt() ushr (a.toInt() and 0x1F)).toLong()) // SRLV
                0x07 -> writeRegister(rd, (b.toInt() shr (a.toInt() and 0x1F)).toLong()) // SRAV
                0x08 -> {
                    if (a % 4L != 0L) throw IllegalArgumentException("JR target must be 4-byte aligned")
                    lastIndirectJumpRegister = rs
                    lastIndirectJumpValue = a
                    pendingBranchTarget = a
                } // JR
                0x09 -> {
                    if (a % 4L != 0L) throw IllegalArgumentException("JALR target must be 4-byte aligned")
                    lastIndirectJumpRegister = rs
                    lastIndirectJumpValue = a
                    writeRegister(rd, instructionAddress + 8L)
                    pendingBranchTarget = a
                } // JALR
                0x0A -> if (b == 0L) writeRegister(rd, a) // MOVZ: move rs when rt is zero
                0x0B -> if (b != 0L) writeRegister(rd, a) // MOVN: move rs when rt is nonzero
                0x0F -> Unit // SYNC: no concurrent memory model in this simplified core
                0x10 -> writeRegister(rd, hi) // MFHI
                0x11 -> hi = a // MTHI
                0x12 -> writeRegister(rd, lo) // MFLO
                0x13 -> lo = a // MTLO
                0x14 -> writeRegister(rd, b shl (a.toInt() and 0x3F)) // DSLLV
                0x16 -> writeRegister(rd, b ushr (a.toInt() and 0x3F)) // DSRLV
                0x17 -> writeRegister(rd, b shr (a.toInt() and 0x3F)) // DSRAV
                0x18 -> {
                    val product = a.toInt().toLong() * b.toInt().toLong()
                    hi = (product shr 32).toInt().toLong()
                    lo = product.toInt().toLong()
                } // MULT
                0x19 -> {
                    val product = Integer.toUnsignedLong(a.toInt()) * Integer.toUnsignedLong(b.toInt())
                    hi = (product ushr 32).toInt().toLong()
                    lo = product.toInt().toLong()
                } // MULTU
                0x1A -> {
                    val dividend = a.toInt()
                    val divisor = b.toInt()
                    if (divisor != 0) {
                        if (dividend == Int.MIN_VALUE && divisor == -1) {
                            lo = Int.MIN_VALUE.toLong()
                            hi = 0L
                        } else {
                            lo = (dividend / divisor).toLong()
                            hi = (dividend % divisor).toLong()
                        }
                    }
                } // DIV; division by zero leaves HI/LO unchanged in this simplified core
                0x1C -> {
                    val product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
                    lo = product.and(unsignedWordMask).toLong()
                    hi = product.shiftRight(64).toLong()
                } // DMULT: signed 64-bit product split across HI/LO
                0x1D -> {
                    val product = unsignedLongToBigInteger(a).multiply(unsignedLongToBigInteger(b))
                    lo = product.and(unsignedWordMask).toLong()
                    hi = product.shiftRight(64).toLong()
                } // DMULTU: unsigned 64-bit product split across HI/LO
                0x1E -> {
                    if (b != 0L) {
                        if (a == Long.MIN_VALUE && b == -1L) {
                            lo = Long.MIN_VALUE
                            hi = 0L
                        } else {
                            lo = a / b
                            hi = a % b
                        }
                    }
                } // DDIV: signed 64-bit division; HI/LO unchanged for divisor zero in this simplified core
                0x1F -> {
                    if (b != 0L) {
                        val dividend = unsignedLongToBigInteger(a)
                        val divisor = unsignedLongToBigInteger(b)
                        val division = dividend.divideAndRemainder(divisor)
                        lo = division[0].toLong()
                        hi = division[1].toLong()
                    }
                } // DDIVU: unsigned 64-bit division; HI/LO unchanged for divisor zero in this simplified core
                0x1B -> {
                    val dividend = a.toInt()
                    val divisor = b.toInt()
                    if (divisor != 0) {
                        val unsignedDividend = Integer.toUnsignedLong(dividend)
                        val unsignedDivisor = Integer.toUnsignedLong(divisor)
                        lo = (unsignedDividend / unsignedDivisor).toLong()
                        hi = (unsignedDividend % unsignedDivisor).toLong()
                    }
                } // DIVU
                0x20 -> writeRegister(rd, Math.addExact(a.toInt(), b.toInt()).toLong()) // ADD
                0x21 -> writeRegister(rd, (a.toInt() + b.toInt()).toLong()) // ADDU
                0x22 -> writeRegister(rd, Math.subtractExact(a.toInt(), b.toInt()).toLong()) // SUB
                0x23 -> writeRegister(rd, (a.toInt() - b.toInt()).toLong()) // SUBU: 32-bit wrapping subtraction
                0x24 -> writeRegister(rd, a and b) // AND
                0x25 -> writeRegister(rd, a or b) // OR
                0x26 -> writeRegister(rd, a xor b) // XOR
                0x27 -> writeRegister(rd, (a or b).inv()) // NOR
                0x2A -> writeRegister(rd, if (a < b) 1L else 0L) // SLT
                0x2B -> writeRegister(rd, if (java.lang.Long.compareUnsigned(a, b) < 0) 1L else 0L) // SLTU
                0x2C -> writeRegister(rd, Math.addExact(a, b)) // DADD (overflow detection; exception model is simplified)
                0x2D -> writeRegister(rd, a + b) // DADDU
                0x2E -> writeRegister(rd, Math.subtractExact(a, b)) // DSUB (overflow detection; exception model is simplified)
                0x2F -> writeRegister(rd, a - b) // DSUBU
                0x38 -> writeRegister(rd, b shl shift) // DSLL
                0x3A -> writeRegister(rd, b ushr shift) // DSRL
                0x3B -> writeRegister(rd, b shr shift) // DSRA
                0x3C -> writeRegister(rd, b shl (shift + 32)) // DSLL32
                0x3E -> writeRegister(rd, b ushr (shift + 32)) // DSRL32
                0x3F -> writeRegister(rd, b shr (shift + 32)) // DSRA32
                else -> unsupported(instruction, instructionAddress)
            }
            0x01 -> {
                val branchTarget = pc + (signedImmediate shl 2)
                when (rt) {
                    0x00 -> if (a < 0L) pendingBranchTarget = branchTarget // BLTZ
                    0x01 -> if (a >= 0L) pendingBranchTarget = branchTarget // BGEZ
                    0x02 -> if (a < 0L) pendingBranchTarget = branchTarget else pc += 4L // BLTZL
                    0x03 -> if (a >= 0L) pendingBranchTarget = branchTarget else pc += 4L // BGEZL
                    0x10 -> {
                        writeRegister(31, instructionAddress + 8L)
                        if (a < 0L) pendingBranchTarget = branchTarget
                    } // BLTZAL links whether or not the branch is taken
                    0x11 -> {
                        writeRegister(31, instructionAddress + 8L)
                        if (a >= 0L) pendingBranchTarget = branchTarget
                    } // BGEZAL links whether or not the branch is taken
                    0x12 -> {
                        writeRegister(31, instructionAddress + 8L)
                        if (a < 0L) pendingBranchTarget = branchTarget else pc += 4L
                    } // BLTZALL links even when not taken; annul delay slot
                    0x13 -> {
                        writeRegister(31, instructionAddress + 8L)
                        if (a >= 0L) pendingBranchTarget = branchTarget else pc += 4L
                    } // BGEZALL links even when not taken; annul delay slot
                    else -> unsupported(instruction, instructionAddress)
                }
            }
            0x03 -> {
                writeRegister(31, instructionAddress + 8L)
                pendingBranchTarget = jumpTarget(instruction, pc)
            } // JAL
            0x06 -> if (a <= 0L) pendingBranchTarget = pc + (signedImmediate shl 2) // BLEZ
            0x07 -> if (a > 0L) pendingBranchTarget = pc + (signedImmediate shl 2) // BGTZ
            0x14 -> if (a == b) pendingBranchTarget = pc + (signedImmediate shl 2) else pc += 4L // BEQL: annul delay slot if not taken
            0x15 -> if (a != b) pendingBranchTarget = pc + (signedImmediate shl 2) else pc += 4L // BNEL
            0x16 -> if (a <= 0L) pendingBranchTarget = pc + (signedImmediate shl 2) else pc += 4L // BLEZL
            0x17 -> if (a > 0L) pendingBranchTarget = pc + (signedImmediate shl 2) else pc += 4L // BGTZL
            0x08 -> writeRegister(rt, Math.addExact(a.toInt(), signedImmediate.toInt()).toLong()) // ADDI
            0x09 -> writeRegister(rt, (a.toInt() + signedImmediate.toInt()).toLong()) // ADDIU
            0x0A -> writeRegister(rt, if (a < signedImmediate) 1L else 0L) // SLTI
            0x0B -> writeRegister(rt, if (java.lang.Long.compareUnsigned(a, signedImmediate) < 0) 1L else 0L) // SLTIU
            0x0C -> writeRegister(rt, a and immediate.toLong()) // ANDI
            0x0D -> writeRegister(rt, a or immediate.toLong()) // ORI
            0x0E -> writeRegister(rt, a xor immediate.toLong()) // XORI
            0x0F -> writeRegister(rt, (immediate shl 16).toInt().toLong()) // LUI sign-extends the 32-bit result on MIPS III
            0x04 -> if (a == b) pendingBranchTarget = pc + (signedImmediate shl 2) // BEQ
            0x05 -> if (a != b) pendingBranchTarget = pc + (signedImmediate shl 2) // BNE
            0x18 -> writeRegister(rt, Math.addExact(a, signedImmediate)) // DADDI
            0x19 -> writeRegister(rt, a + signedImmediate) // DADDIU
            0x1A -> writeRegister(rt, loadDoublewordLeft(effectiveAddress(a, signedImmediate), b)) // LDL
            0x1B -> writeRegister(rt, loadDoublewordRight(effectiveAddress(a, signedImmediate), b)) // LDR
            0x22 -> writeRegister(rt, loadWordLeft(effectiveAddress(a, signedImmediate), b.toInt()).toLong()) // LWL
            0x20 -> writeRegister(rt, readByte(effectiveAddress(a, signedImmediate)).toLong()) // LB
            0x21 -> writeRegister(rt, readHalf(effectiveAddress(a, signedImmediate)).toLong()) // LH
            0x23 -> {
                val address = effectiveAddress(a, signedImmediate)
                val value = readWord(address)
                recordCpuMemoryProbe("LW", instructionAddress, address, value)
                writeRegister(rt, value.toLong())
            }
            0x26 -> writeRegister(rt, loadWordRight(effectiveAddress(a, signedImmediate), b.toInt()).toLong()) // LWR
            0x27 -> writeRegister(rt, Integer.toUnsignedLong(readWord(effectiveAddress(a, signedImmediate)))) // LWU
            0x24 -> writeRegister(rt, readByte(effectiveAddress(a, signedImmediate)).toLong() and 0xFFL) // LBU
            0x25 -> writeRegister(rt, readHalf(effectiveAddress(a, signedImmediate)).toLong() and 0xFFFFL) // LHU
            0x2C -> storeDoublewordLeft(effectiveAddress(a, signedImmediate, TlbAccess.STORE), b) // SDL
            0x2D -> storeDoublewordRight(effectiveAddress(a, signedImmediate, TlbAccess.STORE), b) // SDR
            0x2A -> storeWordLeft(effectiveAddress(a, signedImmediate, TlbAccess.STORE), b.toInt()) // SWL
            0x28 -> writeByte(effectiveAddress(a, signedImmediate, TlbAccess.STORE), b.toByte()) // SB
            0x29 -> writeHalf(effectiveAddress(a, signedImmediate, TlbAccess.STORE), b.toShort()) // SH
            0x2B -> {
                val address = effectiveAddress(a, signedImmediate, TlbAccess.STORE)
                val value = b.toInt()
                recordCpuMemoryProbe("SW", instructionAddress, address, value)
                if (address in 0x0460_0000..0x0460_000CL) {
                    val entry = "PC=0x" +
                        instructionAddress.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0') +
                        " INSTR=0x" + instruction.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0') +
                        " ADDR=0x" + address.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0') +
                        " VALUE=0x" + value.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0') +
                        " BASE=r" + rs + ":0x" + a.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0') +
                        " RT=r" + rt
                    cpuPiWriteHistory += entry
                    if (cpuPiWriteHistory.size > 12) cpuPiWriteHistory.removeAt(0)
                }
                writeWord(address, value)
            }
            0x2E -> storeWordRight(effectiveAddress(a, signedImmediate, TlbAccess.STORE), b.toInt()) // SWR
            0x2F -> Unit // CACHE: ignored because this interpreter has no modeled caches
            0x33 -> Unit // PREF: prefetch hint has no effect in this interpreter
            0x37 -> writeRegister(rt, readLong(effectiveAddress(a, signedImmediate))) // LD
            0x3F -> writeLong(effectiveAddress(a, signedImmediate, TlbAccess.STORE), b) // SD
            0x02 -> pendingBranchTarget = jumpTarget(instruction, pc) // J
            else -> unsupported(instruction, instructionAddress)
        }

        // Keep the instruction and destination that requested this delayed transfer.
        // A following fetch failure can then distinguish a bad jump target from a bus issue.
        pendingBranchTarget?.let { target ->
            lastBranchInstructionAddress = instructionAddress
            lastBranchInstructionWord = instruction
            lastBranchTarget = target
        }

        // The instruction just executed was a delay-slot instruction.
        if (branchToApply != null) pc = branchToApply
        gpr[0] = 0L
        advanceRandomTlbIndex()
    }

    /**
     * CP0 Random advances once per executed instruction and wraps at Wired.
     * TLBWR must not repeatedly overwrite the same slot because Random was
     * left at its reset value for the lifetime of the emulated CPU.
     */
    private fun advanceRandomTlbIndex() {
        val wired = (cp0[6].toInt() and 0x3F).coerceIn(0, 31)
        val random = cp0[1].toInt() and 0x3F
        cp0[1] = if (random <= wired) 31L else (random - 1).toLong()
    }


    /**
     * Builds a MIPS J/JAL target from the low 32-bit virtual PC.
     * The target is then sign-extended to the R4300's 64-bit address form.
     */
    private fun jumpTarget(instruction: Int, pcAfterFetch: Long): Long {
        val target32 = ((pcAfterFetch and 0xF000_0000L) or
            ((instruction.toLong() and 0x03FF_FFFFL) shl 2)).toInt()
        return target32.toLong()
    }

    /** Writes CP0 PageMask/EntryHi/EntryLo values into one software-managed TLB slot. */
    private fun writeTlbEntry(index: Int) {
        val entry = tlb[index]
        // EntryHi's VPN and ASID are stored as a 32-bit architectural value.
        entry.pageMask = cp0[5].toInt() and 0x01FF_E000
        entry.entryHi = cp0[10].toInt()
        entry.entryLo0 = cp0[2].toInt()
        entry.entryLo1 = cp0[3].toInt()
        val event = "PC=0x%08X,INDEX=%d,RANDOM=0x%02X,WIRED=0x%02X,PM=0x%08X,HI=0x%08X,LO0=0x%08X,LO1=0x%08X".format(
            java.util.Locale.ROOT,
            (lastInstructionAddress ?: pc).toInt(), index,
            cp0[1].toInt() and 0x3F, cp0[6].toInt() and 0x3F,
            entry.pageMask, entry.entryHi, entry.entryLo0, entry.entryLo1
        )
        if (tlbWriteHistory.size == 64) tlbWriteHistory.removeFirst()
        tlbWriteHistory.addLast(event)
    }

    /**
     * Translate a TLB-mapped virtual address using paired pages. Returns null
     * for a miss/invalid half. This interpreter currently reports faults rather
     * than entering the architectural TLB exception vector.
     */
    private fun translateTlbAddress(address: Long): Long? {
        val virtual = address.toInt()
        val currentAsid = cp0[10].toInt() and 0xFF
        for (entry in tlb) {
            val pairMask = entry.pageMask or 0x1FFF
            val vpnMask = pairMask.inv()
            val global = (entry.entryLo0 and 1) != 0 && (entry.entryLo1 and 1) != 0
            if ((virtual and vpnMask) != (entry.entryHi and vpnMask)) continue
            if (!global && (entry.entryHi and 0xFF) != currentAsid) continue

            val pageSize = (pairMask + 1) ushr 1
            val odd = (virtual and pageSize) != 0
            val lo = if (odd) entry.entryLo1 else entry.entryLo0
            if ((lo and 2) == 0) return null // EntryLo.V is clear
            val pfn = (lo ushr 6) and 0xFFFFF
            val physicalBase = (pfn.toLong() shl 12) and (pageSize - 1).inv().toLong()
            return physicalBase or (virtual.toLong() and (pageSize - 1).toLong())
        }
        return null
    }

    /** Fetches through the physical bus after direct-mapped KSEG0/KSEG1 translation. */
    private fun fetchInstruction(address: Long): Int {
        require(address % 4L == 0L) {
            "Instruction fetch must be 4-byte aligned"
        }
        return readWord(resolveRamAddress(address, TlbAccess.FETCH))
    }

    /**
     * Resolves an address into a physical region supported by the bus.
     *
     * Low physical addresses remain available for tests. KSEG0/KSEG1 virtual
     * addresses are translated to physical addresses; TLB-mapped segments and
     * unsupported device/cart regions are rejected. RDRAM mirrors are not modeled.
     */
    private fun resolveRamAddress(address: Long, access: TlbAccess = TlbAccess.LOAD): Int {
        val physicalAddress = N64Addressing.translateDirectMappedAddress(address)
            ?: if (address in 0L..0x1FFF_FFFFL) address
            else translateTlbAddress(address) ?: throw TlbTranslationFault(address, access)
        val region = N64MemoryMap.regionOf(physicalAddress)
        require(region == N64MemoryMap.Region.RDRAM ||
            region == N64MemoryMap.Region.RDRAM_REGISTERS ||
            region == N64MemoryMap.Region.SP_DMEM || region == N64MemoryMap.Region.SP_IMEM ||
            region == N64MemoryMap.Region.SP_REGISTERS ||
            region == N64MemoryMap.Region.MI_REGISTERS ||
            region == N64MemoryMap.Region.VI_REGISTERS ||
            region == N64MemoryMap.Region.AI_REGISTERS ||
            region == N64MemoryMap.Region.PI_REGISTERS ||
            region == N64MemoryMap.Region.RI_REGISTERS ||
            region == N64MemoryMap.Region.SI_REGISTERS ||
            region == N64MemoryMap.Region.CARTRIDGE_DOMAIN ||
            region == N64MemoryMap.Region.CARTRIDGE_SRAM ||
            region == N64MemoryMap.Region.PIF) {
            if (region == null) {
                "Physical address 0x${physicalAddress.toString(16)} is outside a mapped N64 region"
            } else {
                "Physical region $region is not implemented by the simplified RAM bus"
            }
        }
        require(physicalAddress <= Int.MAX_VALUE.toLong()) {
            "Physical address cannot be represented by this interpreter"
        }
        return physicalAddress.toInt()
    }

    private fun recordCpuMemoryProbe(operation: String, instructionAddress: Long, address: Int, value: Int) {
        if (address !in 0x0010_03F0..0x0010_0440) return
        val entry = operation + " PC=0x" + instructionAddress.toULong().toString(16).uppercase(java.util.Locale.ROOT) +
            " ADDR=0x" + address.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0') +
            " VALUE=0x" + value.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0')
        cpuMemoryProbeHistory += entry
        if (cpuMemoryProbeHistory.size > 32) cpuMemoryProbeHistory.removeAt(0)
    }

    /** Computes an effective address and resolves supported direct-mapped segments. */
    private fun effectiveAddress(base: Long, offset: Long, access: TlbAccess = TlbAccess.LOAD): Int {
        val address = try {
            Math.addExact(base, offset)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Effective address overflow", error)
        }
        return try {
            resolveRamAddress(address, access)
        } catch (error: IllegalArgumentException) {
            if (error is TlbTranslationFault) throw error
            throw IllegalArgumentException(
                "Falha ao resolver endereco efetivo: base=0x" +
                    base.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0') +
                    ", deslocamento=" + offset + ", endereco=0x" +
                    address.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0') +
                    ". " + (error.message ?: "endereco nao suportado"),
                error
            )
        }
    }

    /** Big-endian MIPS LDL: merge bytes from the effective address to the block's end. */
    private fun loadDoublewordLeft(address: Int, oldValue: Long): Long {
        val offset = address and 7
        val word = readLong(address and -8)
        val shift = offset * 8
        val preservedMask = if (shift == 0) 0L else (-1L ushr (64 - shift))
        return (word shl shift) or (oldValue and preservedMask)
    }

    /** Big-endian MIPS LDR: merge bytes from the block's start through the effective address. */
    private fun loadDoublewordRight(address: Int, oldValue: Long): Long {
        val offset = address and 7
        val word = readLong(address and -8)
        val shift = (7 - offset) * 8
        val preservedMask = if (offset == 7) 0L else (-1L shl ((offset + 1) * 8))
        return (oldValue and preservedMask) or (word ushr shift)
    }

    /** Big-endian MIPS SDL: store the leftmost bytes of rt through the effective byte. */
    private fun storeDoublewordLeft(address: Int, value: Long) {
        val offset = address and 7
        val aligned = address and -8
        val shift = offset * 8
        val preservedMask = if (shift == 0) 0L else (-1L shl (64 - shift))
        writeLong(aligned, (readLong(aligned) and preservedMask) or (value ushr shift))
    }

    /** Big-endian MIPS SDR: store the low portion of rt through the effective address. */
    private fun storeDoublewordRight(address: Int, value: Long) {
        val offset = address and 7
        val aligned = address and -8
        val preservedMask = if (offset == 7) 0L else (1L shl ((7 - offset) * 8)) - 1L
        val shift = (7 - offset) * 8
        writeLong(aligned, (readLong(aligned) and preservedMask) or (value shl shift))
    }

    /** Big-endian MIPS LWL: merge memory's left bytes into the high end of rt. */
    private fun loadWordLeft(address: Int, oldValue: Int): Int {
        val offset = address and 3
        val word = readWord(address and -4)
        val preservedMask = if (offset == 0) 0 else (1 shl (offset * 8)) - 1
        return (word shl (offset * 8)) or (oldValue and preservedMask)
    }

    /** Big-endian MIPS LWR: merge memory's right bytes into the low end of rt. */
    private fun loadWordRight(address: Int, oldValue: Int): Int {
        val offset = address and 3
        val word = readWord(address and -4)
        val preservedMask = when (offset) {
            0 -> 0xFFFFFF00.toInt()
            1 -> 0xFFFF0000.toInt()
            2 -> 0xFF000000.toInt()
            else -> 0
        }
        return (oldValue and preservedMask) or (word ushr ((3 - offset) * 8))
    }

    /** Big-endian MIPS SWL: store the leftmost bytes of rt through the effective byte. */
    private fun storeWordLeft(address: Int, value: Int) {
        val offset = address and 3
        val aligned = address and -4
        val shift = offset * 8
        val preservedMask = if (shift == 0) 0 else (-1 shl (32 - shift))
        writeWord(aligned, (readWord(aligned) and preservedMask) or (value ushr shift))
    }

    /** Big-endian MIPS SWR: store rt's right bytes from the start through the effective address. */
    private fun storeWordRight(address: Int, value: Int) {
        val offset = address and 3
        val aligned = address and -4
        val preservedMask = when (offset) {
            0 -> 0x00FFFFFF
            1 -> 0x0000FFFF
            2 -> 0x000000FF
            else -> 0
        }
        val shift = (3 - offset) * 8
        writeWord(aligned, (readWord(aligned) and preservedMask) or (value shl shift))
    }

    private val unsignedWordMask: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    private fun unsignedLongToBigInteger(value: Long): BigInteger =
        if (value >= 0L) BigInteger.valueOf(value)
        else BigInteger.valueOf(value and Long.MAX_VALUE).setBit(63)

    private fun writeRegister(index: Int, value: Long) {
        if (index != 0) gpr[index] = value
    }

    private fun unsupported(instruction: Int, address: Long): Nothing =
        throw IllegalArgumentException(
            "Unsupported R4300 instruction 0x%08X at 0x%X".format(instruction, address)
        )

    private fun requireHalfAligned(address: Int) {
        require(address >= 0 && address % 2 == 0) { "Halfword access must be 2-byte aligned" }
    }

    private fun requireAligned(address: Int) {
        require(address >= 0 && address % 4 == 0) { "Word access must be 4-byte aligned" }
    }

    private fun requireDoubleAligned(address: Int) {
        require(address >= 0 && address % 8 == 0) { "Doubleword access must be 8-byte aligned" }
    }

    private fun checkRange(address: Int, length: Int) {
        require(address >= 0 && length >= 0 && address.toLong() + length <= rdramSize) {
            "Memory access outside configured RAM"
        }
    }
}
