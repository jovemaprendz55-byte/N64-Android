package com.jovemaprendz55byte.n64android

/**
 * Experimental orchestration layer for the partial R4300 core.
 *
 * It can map a normalized cartridge and execute a bounded instruction slice
 * from an explicitly supplied entry point. It does not implement PIF boot,
 * device initialization, frame timing, graphics, audio, or game compatibility.
 */
class N64EmulatorCore(
    /** Shared input state for the Android touch overlay and PIF Joybus reads. */
    val controllerPort: N64ControllerPort = N64ControllerPort()
) {
    enum class StopReason {
        INSTRUCTION_BUDGET_REACHED,
        EXECUTION_ERROR
    }

    data class ExecutionResult(
        val instructionsExecuted: Int,
        val programCounter: Long,
        val stopReason: StopReason,
        val errorMessage: String? = null
    )

    internal val memoryBus = N64MemoryBus(controllerPort = controllerPort)
    private val cpu = R4300Cpu(memoryBus)
    private val rsp = RspCore(memoryBus)
    private val bootSequence = N64BootSequence(memoryBus)
    private val pifBootSequence = N64PifBootSequence(memoryBus, cpu)
    private var romLoaded = false
    private var executionStarted = false
    private var normalizedRom: ByteArray? = null

    data class RspExecutionResult(
        val instructionsExecuted: Int,
        val programCounter: Int,
        val halted: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Runs a bounded diagnostic slice of the RSP from an explicitly supplied
     * IMEM offset. This shares SP memory/registers with the CPU bus, but is not
     * yet scheduled automatically by the R4300 CPU or a full RSP task system.
     */
    fun executeRspFromImem(entryPoint: Int, instructionBudget: Int): RspExecutionResult {
        require(instructionBudget > 0) { "Instruction budget must be positive" }
        rsp.start(entryPoint)
        var executed = 0
        return try {
            while (executed < instructionBudget && !rsp.halted && !memoryBus.isSpHalted()) {
                val continued = rsp.step()
                // A BREAK is still an executed instruction; a thrown decode/memory
                // error is not counted, while earlier completed instructions are.
                executed++
                if (!continued) break
            }
            RspExecutionResult(executed, rsp.pc, rsp.halted)
        } catch (error: Exception) {
            RspExecutionResult(
                instructionsExecuted = executed,
                programCounter = rsp.lastInstructionAddress,
                halted = rsp.halted,
                errorMessage = error.message ?: error::class.java.simpleName
            )
        }
    }

    /** Loads a canonical Z64 big-endian cartridge image into the CPU bus. */
    fun loadRom(normalizedRom: ByteArray) {
        cpu.loadCartridgeRom(normalizedRom)
        this.normalizedRom = normalizedRom.copyOf()
        romLoaded = true
        executionStarted = false
    }

    /**
     * Runs the clean-room CIC-6105 PIF-to-IPL3 handoff currently implemented.
     * This does not emulate the PIF boot ROM firmware or identify/authenticate CICs.
     */
    fun startCic6105BootSequence(): N64PifBootSequence.BootHandoff {
        require(romLoaded) { "Load a normalized ROM before execution" }
        val rom = normalizedRom ?: throw IllegalStateException("No normalized ROM is retained")
        val handoff = pifBootSequence.startCic6105(rom)
        executionStarted = true
        return handoff
    }

    /**
     * Starts IPL3 at its hardware-style location for a diagnostic shortcut:
     * copy the cartridge IPL3 image (ROM offset 0x40) into SP DMEM and enter
     * through KSEG1 address 0xA4000040. This still bypasses the PIF boot ROM
     * and does not initialize all hardware registers.
     */
    fun startIpl3DiagnosticExecution() {
        require(romLoaded) { "Load a normalized ROM before execution" }
        val rom = normalizedRom ?: throw IllegalStateException("No normalized ROM is retained")
        // This component copies the cartridge IPL3 payload. It does NOT run
        // the PIF boot ROM or identify the cartridge CIC from the country byte.
        val image = bootSequence.loadIpl3(rom)
        // Temporary diagnostic handoff retained for existing CPU tests only.
        // This is not a real boot: CIC family and hardware state are not verified.
        val pal = image.region == N64BootSequence.Region.PAL
        cpu.resetForCic6105Ipl3Diagnostic(image.entryPoint, pal)
        executionStarted = true
    }

    /**
     * Starts a persistent CPU session at an explicit address. Call [executeSlice]
     * repeatedly to continue execution without resetting registers or branch state.
     *
     * This bypasses the real PIF boot sequence and is intended for development
     * until hardware initialization and a complete boot path are implemented.
     */
    fun startExecution(entryPoint: Long) {
        require(romLoaded) { "Load a normalized ROM before execution" }
        require(entryPoint % 4L == 0L) { "Entry point must be 4-byte aligned" }
        cpu.resetExecutionState(entryPoint)
        executionStarted = true
    }

    /**
     * Starts a diagnostic-only direct entry into cartridge code with a usable
     * RDRAM stack. This is not a replacement for the PIF boot sequence.
     */
    fun startCartridgeDiagnosticExecution(entryPoint: Long) {
        require(romLoaded) { "Load a normalized ROM before execution" }
        require(entryPoint % 4L == 0L) { "Entry point must be 4-byte aligned" }
        cpu.resetForCartridgeDiagnostic(entryPoint)
        executionStarted = true
    }

    /**
     * Executes a bounded slice in the current session, preserving CPU state
     * between calls. A CPU exception is returned as a diagnostic stop result.
     */
    fun executeSlice(instructionBudget: Int): ExecutionResult {
        require(romLoaded) { "Load a normalized ROM before execution" }
        require(executionStarted) { "Call startExecution before running a slice" }
        require(instructionBudget > 0) { "Instruction budget must be positive" }

        var executed = 0
        while (executed < instructionBudget) {
            // step() advances PC before decoding/executing; preserve the address
            // of this instruction so diagnostics identify the actual failure.
            val instructionAddress = cpu.pc
            try {
                cpu.step()
                executed++
            } catch (error: Exception) {
                executionStarted = false
                return ExecutionResult(
                    instructionsExecuted = executed,
                    programCounter = instructionAddress,
                    stopReason = StopReason.EXECUTION_ERROR,
                    errorMessage = (error.message ?: error::class.java.simpleName) +
                        "\\nEstado da CPU: " + cpu.diagnosticSnapshot()
                )
            }
        }

        return ExecutionResult(
            instructionsExecuted = executed,
            programCounter = cpu.pc,
            stopReason = StopReason.INSTRUCTION_BUDGET_REACHED
        )
    }

    /** Copies the current VI framebuffer into [destination], if its format is supported. */
    fun renderVideoFrame(destination: N64VideoFrameBuffer, height: Int = destination.height): Boolean =
        memoryBus.renderVideoFrame(destination, height)

    /** Returns VI register values to help diagnose a blank output. */
    fun videoDiagnosticSummary(): String = memoryBus.videoDiagnosticSummary()

    /** Returns the most recent instruction and branch state for live diagnostics. */
    fun executionDiagnosticSummary(): String = cpu.executionDiagnosticSummary()

    /** Returns the full CPU snapshot, including all 31 general-purpose registers. */
    fun fullCpuDiagnosticSummary(): String = cpu.diagnosticSnapshot()

    /** Returns the early instruction sequence that led into a repeated-PC loop. */
    fun executionHistoryDiagnosticSummary(): String = cpu.executionHistoryDiagnosticSummary()

    /** Reads the stalled boot-code area and the memory address currently probed by the trace. */
    fun bootMemoryDiagnosticSummary(): String = buildString {
        val rom = normalizedRom
        if (rom != null && rom.size >= 0x1010) {
            append("ROM_SIZE=0x")
            append(rom.size.toString(16).uppercase(java.util.Locale.ROOT))
            append(", ROM_HEADER_CRC=")
            append(rom.copyOfRange(0x10, 0x18).joinToString("") {
                (it.toInt() and 0xFF).toString(16).uppercase(java.util.Locale.ROOT).padStart(2, '0')
            })
            append(", ROM_COUNTRY=0x")
            append((rom[0x3E].toInt() and 0xFF).toString(16).uppercase(java.util.Locale.ROOT).padStart(2, '0'))
            append(", ROM_BOOTCODE_0x1000=")
            append(rom.copyOfRange(0x1000, 0x1010).joinToString("") {
                (it.toInt() and 0xFF).toString(16).uppercase(java.util.Locale.ROOT).padStart(2, '0')
            })
            append(", IPL3_START=")
            append(rom.copyOfRange(0x40, 0x50).joinToString("") {
                (it.toInt() and 0xFF).toString(16).uppercase(java.util.Locale.ROOT).padStart(2, '0')
            })
            val dmemIpl3CopyMatches = (0x40 until 0x1000).all { offset ->
                (memoryBus.readByte(0x0400_0000L + offset).toInt() and 0xFF) ==
                    (rom[offset].toInt() and 0xFF)
            }
            append(", SP_DMEM_IPL3_COPY_MATCH=").append(dmemIpl3CopyMatches)
            append(", ROM_IPL3_0x750=")
            append(rom.copyOfRange(0x750, 0x760).joinToString("") {
                (it.toInt() and 0xFF).toString(16).uppercase(java.util.Locale.ROOT).padStart(2, '0')
            })
            append(", SP_DMEM_0x750=")
            append((0 until 16).joinToString("") { offset ->
                (memoryBus.readByte(0x0400_0750L + offset).toInt() and 0xFF)
                    .toString(16).uppercase(java.util.Locale.ROOT).padStart(2, '0')
            })
            N64RomChecksum.calculateCic6105(rom)?.let { (crc1, crc2) ->
                val expectedCrc1 = ((rom[0x10].toUInt() and 0xFFu) shl 24) or
                    ((rom[0x11].toUInt() and 0xFFu) shl 16) or
                    ((rom[0x12].toUInt() and 0xFFu) shl 8) or
                    (rom[0x13].toUInt() and 0xFFu)
                val expectedCrc2 = ((rom[0x14].toUInt() and 0xFFu) shl 24) or
                    ((rom[0x15].toUInt() and 0xFFu) shl 16) or
                    ((rom[0x16].toUInt() and 0xFFu) shl 8) or
                    (rom[0x17].toUInt() and 0xFFu)
                append(", ROM_HOST_CRC6105=")
                append(crc1.toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
                append(crc2.toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
                append(", ROM_HOST_CRC_MATCH=")
                append(crc1 == expectedCrc1 && crc2 == expectedCrc2)
            }
            append("; ")
        }
        append("RDRAM[0x1C8]=0x")
        append(memoryBus.readWord(0x1C8L).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", RDRAM[0x1CC]=0x")
        append(memoryBus.readWord(0x1CCL).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", RDRAM[0x400]=0x")
        append(memoryBus.readWord(0x400L).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        // The failing comparison uses virtual 0x80100400 (physical 0x00100400).
        append(", RDRAM[0x100400]=0x")
        append(memoryBus.readWord(0x0010_0400L).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        // The current boot trace loads from virtual 0xFFFFFFFF80036238 (physical 0x36238).
        append(", RDRAM[0x36234]=0x")
        append(memoryBus.readWord(0x36234L).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", RDRAM[0x36238]=0x")
        append(memoryBus.readWord(0x36238L).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_DRAM_ADDR=0x")
        append(memoryBus.readWord(N64MemoryBus.PI_DRAM_ADDR).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_CART_ADDR=0x")
        append(memoryBus.readWord(N64MemoryBus.PI_CART_ADDR).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_RD_LEN=0x")
        append(memoryBus.readWord(N64MemoryBus.PI_RD_LEN).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_WR_LEN=0x")
        append(memoryBus.readWord(N64MemoryBus.PI_WR_LEN).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", PI_STATUS=0x")
        append(memoryBus.readWord(N64MemoryBus.PI_STATUS).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        append(", ").append(memoryBus.piDmaDiagnosticSummary())
        append(", CPU_PI_WRITE_HISTORY=").append(cpu.piWriteDiagnosticSummary())
        append(", RDRAM_REG[0x03F08000]=0x")
        append(memoryBus.readWord(0x03F0_8000L).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
        // Instructions around current KSEG PC reveal what the CPU is waiting for.
        val physicalPc = cpu.pc and 0x1FFF_FFFFL
        append(", CPU_CODE_WINDOW_PC=0x")
        append(cpu.pc.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0'))
        append(", CPU_CODE_WINDOW=")
        if (physicalPc < 0x0080_0000L && physicalPc % 4L == 0L) {
            // Include the setup instructions before IPL3's failure branch so the
            // register comparison can be traced back to its loads/calculations.
            val start = (physicalPc - 0x48L).coerceAtLeast(0L)
            val end = (physicalPc + 0x20L).coerceAtMost(0x0080_0000L)
            var address = start
            while (address + 4L <= end) {
                if (address != start) append(";")
                append("0x").append(address.toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
                append("=0x").append(memoryBus.readWord(address).toUInt().toString(16).uppercase(java.util.Locale.ROOT).padStart(8, '0'))
                address += 4L
            }
        } else {
            append("fora_da_RDRAM")
        }
    }

    /**
     * Runs at most [instructionBudget] instructions from [entryPoint], resetting
     * the CPU first. Prefer startExecution + executeSlice for a persistent loop.
     */
    fun executeFrom(entryPoint: Long, instructionBudget: Int): ExecutionResult {
        require(instructionBudget > 0) { "Instruction budget must be positive" }
        startExecution(entryPoint)
        return executeSlice(instructionBudget)
    }
}
