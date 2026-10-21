package com.jovemaprendz55byte.n64android

import java.math.BigInteger

/**
 * Small, testable MIPS III/R4300 interpreter foundation.
 *
 * This remains a partial CPU core: exceptions, TLB/FPU, caches, and N64
 * peripherals are not implemented. Implemented RDRAM and SP DMEM/IMEM accesses
 * are routed through the physical memory bus.
 */
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
    // Minimal CP0 register storage for common boot-code MFC0/MTC0 operations.
    private val cp0 = LongArray(32)

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
    /** Address of the last instruction fetched successfully, for failure diagnostics. */
    private var lastInstructionAddress: Long? = null
    /** Last fetched instruction, retained so execution failures can be diagnosed. */
    internal var lastInstructionWord: Int = 0
        private set

    /** Small rolling instruction history, frozen on the first sustained PC loop. */
    private val recentInstructions = java.util.ArrayDeque<String>()
    private val seenInstructionPcs = HashSet<Long>()
    private var frozenLoopTrace: String? = null
    private var totalSteps: Long = 0L

    /** Always show the live rolling trace; the frozen first-loop trace can be stale after execution advances. */
    internal fun executionHistoryDiagnosticSummary(): String =
        recentInstructions.joinToString(" | ").ifBlank { "sem historico" }

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
        append("; alvoUltimoSalto=")
        append(lastBranchTarget?.toULong()?.toString(16)?.uppercase(java.util.Locale.ROOT)?.padStart(16, '0') ?: "nenhum")
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
        append(", S1=0x").append(register(17).toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
        append(", SP=0x").append(register(29).toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
    }

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
        cp0.fill(0L)
        hi = 0L
        lo = 0L
        pc = entryPoint
        pendingBranchTarget = null
        lastInstructionAddress = null
        lastInstructionWord = 0
        lastBranchInstructionAddress = null
        lastBranchInstructionWord = null
        lastBranchTarget = null
        recentInstructions.clear()
        seenInstructionPcs.clear()
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

    /** Executes one instruction; branches take effect after their delay slot. */
    fun step() {
        val instructionAddress = pc
        val instruction = fetchInstruction(pc)
        lastInstructionAddress = instructionAddress
        lastInstructionWord = instruction
        totalSteps++
        val traceEntry = "0x" + instructionAddress.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0') +
            "=0x" + instruction.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0')
        if (recentInstructions.size >= 16) recentInstructions.removeFirst()
        recentInstructions.addLast(traceEntry)
        // Freeze the instructions leading into the first repeated PC after startup,
        // so a later infinite loop does not erase the useful evidence.
        if (totalSteps > 256L && frozenLoopTrace == null && !seenInstructionPcs.add(instructionAddress)) {
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
                    pendingBranchTarget = a
                } // JR
                0x09 -> {
                    if (a % 4L != 0L) throw IllegalArgumentException("JALR target must be 4-byte aligned")
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
            0x0F -> writeRegister(rt, (immediate shl 16).toLong()) // LUI (sign-extended 32-bit result)
            0x04 -> if (a == b) pendingBranchTarget = pc + (signedImmediate shl 2) // BEQ
            0x05 -> if (a != b) pendingBranchTarget = pc + (signedImmediate shl 2) // BNE
            0x18 -> writeRegister(rt, Math.addExact(a, signedImmediate)) // DADDI
            0x19 -> writeRegister(rt, a + signedImmediate) // DADDIU
            0x1A -> writeRegister(rt, loadDoublewordLeft(effectiveAddress(a, signedImmediate), b)) // LDL
            0x1B -> writeRegister(rt, loadDoublewordRight(effectiveAddress(a, signedImmediate), b)) // LDR
            0x22 -> writeRegister(rt, loadWordLeft(effectiveAddress(a, signedImmediate), b.toInt()).toLong()) // LWL
            0x20 -> writeRegister(rt, readByte(effectiveAddress(a, signedImmediate)).toLong()) // LB
            0x21 -> writeRegister(rt, readHalf(effectiveAddress(a, signedImmediate)).toLong()) // LH
            0x23 -> writeRegister(rt, readWord(effectiveAddress(a, signedImmediate)).toLong()) // LW
            0x26 -> writeRegister(rt, loadWordRight(effectiveAddress(a, signedImmediate), b.toInt()).toLong()) // LWR
            0x27 -> writeRegister(rt, Integer.toUnsignedLong(readWord(effectiveAddress(a, signedImmediate)))) // LWU
            0x24 -> writeRegister(rt, readByte(effectiveAddress(a, signedImmediate)).toLong() and 0xFFL) // LBU
            0x25 -> writeRegister(rt, readHalf(effectiveAddress(a, signedImmediate)).toLong() and 0xFFFFL) // LHU
            0x2C -> storeDoublewordLeft(effectiveAddress(a, signedImmediate), b) // SDL
            0x2D -> storeDoublewordRight(effectiveAddress(a, signedImmediate), b) // SDR
            0x2A -> storeWordLeft(effectiveAddress(a, signedImmediate), b.toInt()) // SWL
            0x28 -> writeByte(effectiveAddress(a, signedImmediate), b.toByte()) // SB
            0x29 -> writeHalf(effectiveAddress(a, signedImmediate), b.toShort()) // SH
            0x2B -> writeWord(effectiveAddress(a, signedImmediate), b.toInt()) // SW
            0x2E -> storeWordRight(effectiveAddress(a, signedImmediate), b.toInt()) // SWR
            0x2F -> Unit // CACHE: ignored because this interpreter has no modeled caches
            0x33 -> Unit // PREF: prefetch hint has no effect in this interpreter
            0x37 -> writeRegister(rt, readLong(effectiveAddress(a, signedImmediate))) // LD
            0x3F -> writeLong(effectiveAddress(a, signedImmediate), b) // SD
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

    /** Fetches through the physical bus after direct-mapped KSEG0/KSEG1 translation. */
    private fun fetchInstruction(address: Long): Int {
        require(address % 4L == 0L) {
            "Instruction fetch must be 4-byte aligned"
        }
        return readWord(resolveRamAddress(address))
    }

    /**
     * Resolves an address into a physical region supported by the bus.
     *
     * Low physical addresses remain available for tests. KSEG0/KSEG1 virtual
     * addresses are translated to physical addresses; TLB-mapped segments and
     * unsupported device/cart regions are rejected. RDRAM mirrors are not modeled.
     */
    private fun resolveRamAddress(address: Long): Int {
        val physicalAddress = N64Addressing.translateDirectMappedAddress(address)
            ?: if (address in 0L..0x1FFF_FFFFL) address
            else throw IllegalArgumentException(
                "Virtual address 0x%016X requires TLB translation or is outside the simplified RAM address range"
                    .format(address)
            )
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
            region == N64MemoryMap.Region.CARTRIDGE_DOMAIN) {
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

    /** Computes an effective address and resolves supported direct-mapped segments. */
    private fun effectiveAddress(base: Long, offset: Long): Int {
        val address = try {
            Math.addExact(base, offset)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Effective address overflow", error)
        }
        return try {
            resolveRamAddress(address)
        } catch (error: IllegalArgumentException) {
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
