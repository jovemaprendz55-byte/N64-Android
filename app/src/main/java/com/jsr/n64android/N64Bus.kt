package com.jsr.n64android

/**
 * Initial N64 physical bus.
 *
 * Maps RDRAM, optional cartridge/PIF ROM, and partial SP/DPC/MI/VI/AI/PI/SI/RI MMIO.
 * PIF RAM protocol and full hardware behavior are not implemented. Device timing and several register side effects remain approximate or unimplemented.
 */
class N64Bus(
    private val ram: N64Memory = N64Memory(),
    pifRom: ByteArray? = null,
    cartridgeRom: ByteArray? = null
) : N64AddressSpace {
    private val pif = pifRom?.copyOf()
    // SP scratch memories used during early boot: 4 KiB DMEM and 4 KiB IMEM.
    private val spDmem = ByteArray(SP_MEM_SIZE)
    private val spImem = ByteArray(SP_MEM_SIZE)
    private val cartridge = cartridgeRom?.copyOf()
    private var piDramAddress = 0
    private var piCartAddress = CARTRIDGE_ROM_BASE
    private var piStatus = 0
    // CPU-originated MMIO trace. The caller supplies the current virtual PC before each step.
    private var diagnosticCpuPc: Long = 0L
    private var diagnosticCpuInstructionWord: Int? = null
    private var diagnosticCpuRs: Int? = null
    private var diagnosticCpuRt: Int? = null
    private var diagnosticCpuRsValue: Long? = null
    private var diagnosticCpuRtValue: Long? = null
    private var diagnosticCpuEffectiveAddress: Long? = null
    val diagnosticMmioHistory = ArrayDeque<String>()
    /** Dedicated SP-only MMIO trace; it cannot be evicted by accesses to other devices. */
    val diagnosticSpMmioHistory = ArrayDeque<String>()
    val diagnosticSpMmioCounts = linkedMapOf<String, Long>()
    val diagnosticSpWriteHistory = ArrayDeque<String>()
    val diagnosticMmioCounts = linkedMapOf<String, Long>()
    /** Cumulative CPU-originated MMIO writes; unlike histories, these counters never evict old events. */
    val diagnosticMmioWriteCounts = linkedMapOf<String, Long>()
    fun setDiagnosticCpuPc(pc: Long) { diagnosticCpuPc = pc }
    /** Supplies the decoded CPU instruction context used by narrow memory watchpoints. */
    /** True when at least one enabled MI source is asserting the VR4300 external interrupt line. */
    fun isMiInterruptPending(): Boolean = (miInterrupts and miInterruptMask) != 0

    fun setDiagnosticCpuInstructionContext(
        pc: Long, instruction: Int, rs: Int, rt: Int,
        rsValue: Long, rtValue: Long, effectiveAddress: Long?
    ) {
        diagnosticCpuPc = pc
        diagnosticCpuInstructionWord = instruction
        diagnosticCpuRs = rs
        diagnosticCpuRt = rt
        diagnosticCpuRsValue = rsValue
        diagnosticCpuRtValue = rtValue
        diagnosticCpuEffectiveAddress = effectiveAddress
    }
    /** Reads SP status for the cooperative scheduler without recording a fake CPU MMIO access. */
    fun diagnosticSpStatus(): Int = spStatus
    private fun mmioDevice(address: Int): String? = when (address) {
        in SP_REG_BASE until SP_REG_BASE + 0x20, SP_PC_REG -> "SP"
        in DPC_REG_BASE until DPC_REG_BASE + DPC_REG_SIZE -> "DPC"
        in MI_REG_BASE until MI_REG_BASE + 0x10 -> "MI"
        in VI_REG_BASE until VI_REG_BASE + VI_REG_SIZE -> "VI"
        in AI_REG_BASE until AI_REG_BASE + 0x18 -> "AI"
        in PI_REG_BASE until PI_REG_BASE + 0x14 -> "PI"
        in SI_REG_BASE until SI_REG_BASE + 0x1C -> "SI"
        in RI_REG_BASE until RI_REG_BASE + RI_REG_SIZE -> "RI"
        else -> null
    }
    private fun recordMmio(kind: String, address: Int, value: Int) {
        val device = mmioDevice(address) ?: return
        diagnosticMmioCounts[device] = (diagnosticMmioCounts[device] ?: 0L) + 1L
        if (kind == "W") {
            diagnosticMmioWriteCounts[device] = (diagnosticMmioWriteCounts[device] ?: 0L) + 1L
        }
        if (diagnosticMmioHistory.size >= 256) diagnosticMmioHistory.removeFirst()
        val traceLine =
            "$kind $device PC=0x${diagnosticCpuPc.toULong().toString(16).uppercase()} " +
                "PADDR=0x${address.toString(16).uppercase().padStart(8, '0')} " +
                "VALUE=0x${value.toUInt().toString(16).uppercase().padStart(8, '0')}"
        diagnosticMmioHistory.addLast(traceLine)
        if (device == "SP") {
            val register = when (address) {
                SP_MEM_ADDR_REG -> "SP_MEM_ADDR"
                SP_DRAM_ADDR_REG -> "SP_DRAM_ADDR"
                SP_RD_LEN_REG -> "SP_RD_LEN"
                SP_WR_LEN_REG -> "SP_WR_LEN"
                SP_STATUS_REG -> "SP_STATUS"
                SP_DMA_FULL_REG -> "SP_DMA_FULL"
                SP_DMA_BUSY_REG -> "SP_DMA_BUSY"
                SP_PC_REG -> "SP_PC"
                SP_SEMAPHORE_REG -> "SP_SEMAPHORE"
                else -> "SP_UNKNOWN"
            }
            diagnosticSpMmioCounts[register] = (diagnosticSpMmioCounts[register] ?: 0L) + 1L
            if (diagnosticSpMmioHistory.size >= 512) diagnosticSpMmioHistory.removeFirst()
            diagnosticSpMmioHistory.addLast("$register $traceLine")
        }
        if (kind == "W" && device == "SP") {
            if (diagnosticSpWriteHistory.size >= 256) diagnosticSpWriteHistory.removeFirst()
            diagnosticSpWriteHistory.addLast(
                "W SP PC=0x${diagnosticCpuPc.toULong().toString(16).uppercase()} " +
                    "PADDR=0x${address.toString(16).uppercase().padStart(8, '0')} " +
                    "VALUE=0x${value.toUInt().toString(16).uppercase().padStart(8, '0')}"
            )
        }
    }
    private fun recordMmioRead(address: Int, value: Int): Int {
        recordMmio("R", address, value)
        return value
    }

    /** Returns a side-effect-free snapshot of implemented device register state. */
    fun diagnosticDeviceSnapshot(): List<String> = buildList {
        add("SP: MEM_ADDR=0x${spMemAddress.toUInt().toString(16).uppercase()} DRAM_ADDR=0x${spDramAddress.toUInt().toString(16).uppercase()} STATUS=0x${spStatus.toUInt().toString(16).uppercase()} PC=0x${spPc.toUInt().toString(16).uppercase()} SEMAPHORE=0x${spSemaphore.toUInt().toString(16).uppercase()}")
        add("MI: INIT_MODE=0x${miInitMode.toUInt().toString(16).uppercase()} INTR_MASK=0x${miInterruptMask.toUInt().toString(16).uppercase()} INTR=0x${miInterrupts.toUInt().toString(16).uppercase()}")
        add("PI: DRAM_ADDR=0x${piDramAddress.toUInt().toString(16).uppercase()} CART_ADDR=0x${piCartAddress.toUInt().toString(16).uppercase()} STATUS=0x${piStatus.toUInt().toString(16).uppercase()}")
        add("AI: DRAM_ADDR=0x${aiDramAddress.toUInt().toString(16).uppercase()} CONTROL=0x${aiControl.toUInt().toString(16).uppercase()} DACRATE=0x${aiDacRate.toUInt().toString(16).uppercase()} BITRATE=0x${aiBitRate.toUInt().toString(16).uppercase()} LEN=0 STATUS=0")
        add("SI: DRAM_ADDR=0x${siDramAddress.toUInt().toString(16).uppercase()} PIF_RD=0x${siPifReadAddress.toUInt().toString(16).uppercase()} PIF_WR=0x${siPifWriteAddress.toUInt().toString(16).uppercase()} STATUS=0x${siStatus.toUInt().toString(16).uppercase()}")
        add("RI: " + riRegisters.mapIndexed { index, value -> "R${index}=0x${value.toUInt().toString(16).uppercase().padStart(8, '0')}" }.joinToString(" "))
        add("VI: " + viRegisters.mapIndexed { index, value -> "R${index}=0x${value.toUInt().toString(16).uppercase().padStart(8, '0')}" }.joinToString(" ") + " CURRENT=0x${viCurrentLine.toUInt().toString(16).uppercase().padStart(8, '0')}")
    }
    // Bounded trace of PI DMA requests for boot diagnostics; avoids per-byte logging.
    val diagnosticPiDmaHistory = ArrayDeque<String>()
    // Bounded trace of SP DMA transfers, including direction and RDRAM destination ranges.
    val diagnosticSpDmaHistory = ArrayDeque<String>()
    // Passive evidence that software copied a complete conventional OSTask into DMEM.
    val diagnosticSpTaskDescriptorDmaHistory = ArrayDeque<String>()
    // Captures CPU-visible accesses to the suspected IPL3 copy-source window.
    val diagnosticBootMemoryAccessHistory = ArrayDeque<String>()
    // Bounded trace of CPU byte writes in the IPL3 destination range under investigation.
    var diagnosticBootDestinationWriteCount: Long = 0
        private set
    val diagnosticBootDestinationWriteHistory = ArrayDeque<String>()
    // Broad CPU RDRAM write trace: reveals the real destination before assuming a copy range.
    var diagnosticRdramWriteCount: Long = 0
        private set
    val diagnosticRdramWriteHistory = ArrayDeque<String>()
    // Watch the full 0x800-byte PTE structure inferred from the game's setup loop.
    // Keep enough history for every byte in the range so later valid-entry writes are not missed.
    var diagnosticPteWriteCount: Long = 0
        private set
    val diagnosticPteWriteHistory = ArrayDeque<String>()
    private var diagnosticBootMemoryAccessCount: Long = 0
    val diagnosticBootMemoryAccessTotal: Long get() = diagnosticBootMemoryAccessCount
    private fun recordRdramWrite(address: Int, value: Int) {
        diagnosticRdramWriteCount++
        // Keep the total exact, but avoid allocating a formatted String for every
        // byte written while IPL3 copies large blocks of RDRAM.
        if (diagnosticRdramWriteCount <= 16L || diagnosticRdramWriteCount % 256L == 0L) {
            if (diagnosticRdramWriteHistory.size >= 128) diagnosticRdramWriteHistory.removeFirst()
            diagnosticRdramWriteHistory.addLast(
                "W RDRAM #$diagnosticRdramWriteCount PC=0x${diagnosticCpuPc.toULong().toString(16).uppercase()} INST=${diagnosticCpuInstructionWord?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} paddr=0x${address.toString(16).uppercase()} value=0x${value.toString(16).uppercase().padStart(2, '0')}"
            )
        }
    }
    private fun recordPteTableWrite(address: Int, value: Int) {
        if (address !in 0x5DBF0 until 0x5E3F0) return
        diagnosticPteWriteCount++
        if (diagnosticPteWriteHistory.size >= 6144) diagnosticPteWriteHistory.removeFirst()
        val instruction = diagnosticCpuInstructionWord
        val context = if (instruction != null) {
            " INST=0x${instruction.toUInt().toString(16).uppercase().padStart(8, '0')}" +
                " OP=0x${(instruction ushr 26).toString(16).uppercase()}" +
                " RS=${diagnosticCpuRs} RT=${diagnosticCpuRt}" +
                " BASE=0x${diagnosticCpuRsValue?.toULong()?.toString(16)?.uppercase() ?: "?"}" +
                " RTVAL=0x${diagnosticCpuRtValue?.toULong()?.toString(16)?.uppercase() ?: "?"}" +
                (diagnosticCpuEffectiveAddress?.let { " EA=0x${it.toULong().toString(16).uppercase()}" } ?: "")
        } else {
            " CTX=unavailable"
        }
        diagnosticPteWriteHistory.addLast(
            "CPU W PTE PC=0x${diagnosticCpuPc.toULong().toString(16).uppercase()}" +
                context +
                " PADDR=0x${address.toString(16).uppercase().padStart(8, '0')}" +
                " VALUE=0x${(value and 0xFF).toString(16).uppercase().padStart(2, '0')}"
        )
    }
    private fun recordBootDestinationWrite(address: Int, value: Int) {
        if (address !in 0x1EE400..0x20159F) return
        diagnosticBootDestinationWriteCount++
        // Preserve early samples and periodic checkpoints; the exact write count
        // remains available separately for validating the descending copy.
        if (diagnosticBootDestinationWriteCount <= 16L || diagnosticBootDestinationWriteCount % 256L == 0L) {
            if (diagnosticBootDestinationWriteHistory.size >= 96) diagnosticBootDestinationWriteHistory.removeFirst()
            diagnosticBootDestinationWriteHistory.addLast(
                "W #$diagnosticBootDestinationWriteCount PC=0x${diagnosticCpuPc.toULong().toString(16).uppercase()} INST=${diagnosticCpuInstructionWord?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} paddr=0x${address.toString(16).uppercase()} value=0x${value.toString(16).uppercase().padStart(2, '0')}"
            )
        }
    }
    private fun recordBootMemoryAccess(kind: String, address: Int, value: Int) {
        if (address !in 0x33D00 until 0x34000) return
        diagnosticBootMemoryAccessCount++
        if (diagnosticBootMemoryAccessCount <= 16L || diagnosticBootMemoryAccessCount % 64L == 0L) {
            if (diagnosticBootMemoryAccessHistory.size >= 256) diagnosticBootMemoryAccessHistory.removeFirst()
            diagnosticBootMemoryAccessHistory.addLast(
                "$kind #$diagnosticBootMemoryAccessCount PC=0x${diagnosticCpuPc.toULong().toString(16).uppercase()} INST=${diagnosticCpuInstructionWord?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} paddr=0x${address.toString(16).uppercase()} value=0x${value.toString(16).uppercase().padStart(2, '0')}"
            )
        }
    }
    // RSP program counter register (SP_PC); the RSP core itself is not yet emulated.
    private var spPc = 0
    // SP DMA registers. Transfers are synchronous; RSP execution and DMA timing are not modeled.
    private var spMemAddress = 0
    private var spDramAddress = 0
    private var spStatus = SP_STATUS_HALT
    private var spSemaphore = 0
    // Minimal MIPS Interface state. Interrupt sources are not yet driven by emulated devices.
    private var miInitMode = 0
    private var miInterruptMask = 0
    private var miInterrupts = 0
    // Minimal RI register state; these values are placeholders, not full RDRAM timing emulation.
    private val riRegisters = IntArray(RI_REGISTER_COUNT).also { it[RI_SELECT_OFFSET / 4] = RI_SELECT_RESET }
    // SI uses a 64-byte PIF RAM window and synchronous DMA; Joybus processing/timing remain unimplemented.
    private val pifRam = ByteArray(PIF_RAM_SIZE)
    private var siDramAddress = 0
    private var siPifReadAddress = PIF_RAM_BASE
    private var siPifWriteAddress = PIF_RAM_BASE
    private var siStatus = 0
    // Minimal Audio Interface register model; audio DMA playback/timing is not emulated.
    private var aiDramAddress = 0
    private var aiControl = 0
    private var aiDacRate = 0
    private var aiBitRate = 0
    // Minimal VI timing model: one approximate scanline per fixed R4300 instruction budget.
    private val viRegisters = IntArray(VI_REGISTER_COUNT)
    private var viCurrentLine = 0
    private var viInstructionRemainder = 0L
    // Synchronous RDRAM-only DPC bridge; no XBUS, asynchronous FIFO, timing or IRQ.
    private var dpcStart = 0
    private var dpcEnd = 0
    private var dpcCurrent = 0
    var diagnosticLastDpcResult: N64DpcCommandProcessor.Result? = null
        private set
    var diagnosticLastDpcFailure: String? = null
        private set

    /** Side-effect-free DPC register/result summary for ROM execution diagnostics. */
    fun diagnosticDpcSnapshot(): String {
        val result = diagnosticLastDpcResult
        return "DPC: START=0x${dpcStart.toString(16).uppercase()} " +
            "END=0x${dpcEnd.toString(16).uppercase()} " +
            "CURRENT=0x${dpcCurrent.toString(16).uppercase()} " +
            (result?.let {
                "commands=${it.commandsProcessed} rectangles=${it.rectanglesFilled} " +
                    "triangles=${it.trianglesRasterized} pixels=${it.pixelsWritten} fullSync=${it.fullSyncCommands}"
            } ?: "result=none") +
            (diagnosticLastDpcFailure?.let { " failure=$it" } ?: "")
    }

    // Optional SP task coordinator callback; invoked after synchronous DPC Full Sync processing.
    private var dpDrainListener: (() -> Unit)? = null

    /** Completed SP DMA metadata, reported after all bytes have been transferred. */
    data class SpDmaTransfer(
        val toSp: Boolean,
        val dramStartAddress: Int,
        val spStartAddress: Int,
        val blockLength: Int,
        val blockCount: Int,
        val dramSkip: Int
    ) {
        val transferredBytes: Int
            get() = blockLength * blockCount
    }

    // Optional observer used by the SP scheduler to identify descriptor-matching microcode DMA.
    private var spDmaListener: ((SpDmaTransfer) -> Unit)? = null

    fun setDpDrainListener(listener: (() -> Unit)?) {
        dpDrainListener = listener
    }

    fun setSpDmaListener(listener: ((SpDmaTransfer) -> Unit)?) {
        spDmaListener = listener
    }

    /** Side-effect-free diagnostic used to verify task-runner cleanup after failures. */
    fun diagnosticHasSpDmaListener(): Boolean = spDmaListener != null
    private val dpcCommandProcessor by lazy {
        N64DpcCommandProcessor(this, ram.size, N64RdpFillProcessor(this, ram.size))
    }

    /**
     * Identifies a small supported subset of Nintendo CIC variants from the IPL3 word sum.
     * The sum covers ROM offsets 0x40..0xFFF as unsigned big-endian 32-bit words.
     * Returns the seed IPL3 expects in GPR[22] ($s6), or null when not recognized.
     */
    fun detectCicSeed(): Int? {
        val image = cartridge ?: return null
        if (image.size < 0x1000) return null

        var checksum = 0L
        for (offset in 0x40 until 0x1000 step 4) {
            val word = ((image[offset].toLong() and 0xFFL) shl 24) or
                ((image[offset + 1].toLong() and 0xFFL) shl 16) or
                ((image[offset + 2].toLong() and 0xFFL) shl 8) or
                (image[offset + 3].toLong() and 0xFFL)
            checksum += word
        }

        return when (checksum) {
            0xD057C85244L -> 0x3F // CIC-NUS-6102 / 7101, used by GoldenEye 007 (USA).
            0x11A49F60E96L -> 0x91 // CIC-NUS-6105 / 7105.
            else -> null
        }
    }

    init {
        require(pif == null || pif.size == PIF_ROM_SIZE) {
            "PIF ROM must be exactly $PIF_ROM_SIZE bytes."
        }
        require(cartridge == null || cartridge.isNotEmpty()) {
            "Cartridge ROM cannot be empty."
        }
        require(cartridge == null || cartridge.size <= MAX_CARTRIDGE_ROM_SIZE) {
            "Cartridge ROM exceeds the currently supported 64 MiB limit."
        }
    }

    /** Cartridge metadata and header bytes, captured only when the user exports diagnostics. */
    fun diagnosticCartridgeSummary(): String {
        val image = cartridge ?: return "Cartridge: não carregado."
        fun word(offset: Int): String = if (offset + 4 <= image.size) {
            (0..3).fold(0L) { value, index -> (value shl 8) or (image[offset + index].toLong() and 0xFFL) }
                .toString(16).uppercase().padStart(8, '0')
        } else "N/A"
        val header = (0 until minOf(0x40, image.size)).joinToString(" ") {
            image[it].toUByte().toString(16).uppercase().padStart(2, '0')
        }
        return "ROM_BYTES=${image.size} MAGIC=0x${word(0)} CRC1=0x${word(0x10)} CRC2=0x${word(0x14)} CIC_SEED=${detectCicSeed()?.let { "0x" + it.toString(16).uppercase() } ?: "unknown"} " +
            "TITLE_BYTES[0x20..0x33]=" + (0x20 until minOf(0x34, image.size)).map { image[it].toInt().toChar() }.joinToString("") +
            "\nHEADER_0x00_0x3F=$header\nPIF_ROM_PRESENT=${pif != null}"
    }

    /** Dumps explicitly selected physical RDRAM/cartridge ranges without changing MMIO histories. */
    fun diagnosticMemoryDump(ranges: List<IntRange>): String = buildString {
        for (range in ranges) {
            appendLine("RANGE 0x${range.first.toString(16).uppercase()}..0x${range.last.toString(16).uppercase()}")
            if (range.first < 0 || range.last < range.first || range.last >= ram.size) {
                appendLine("FORA DA RDRAM DISPONÍVEL (bytes=${ram.size})")
                continue
            }
            var address = range.first
            while (address <= range.last) {
                val end = minOf(address + 15, range.last)
                append("0x").append(address.toString(16).uppercase().padStart(8, '0')).append(": ")
                for (byteAddress in address..end) {
                    append(peek8ForDiagnostic(byteAddress).toString(16).uppercase().padStart(2, '0')).append(' ')
                }
                appendLine()
                address = end + 1
            }
        }
    }

    /** Exact bytes from the loaded cartridge image for PI-DMA source comparison. */
    fun diagnosticCartridgeDump(ranges: List<IntRange>): String = buildString {
        val image = cartridge ?: return "Cartridge não carregado."
        for (range in ranges) {
            appendLine("CART RANGE offset=0x${range.first.toString(16).uppercase()}..0x${range.last.toString(16).uppercase()}")
            if (range.first < 0 || range.last < range.first || range.last >= image.size) {
                appendLine("FORA DA ROM CARREGADA (bytes=${image.size})")
                continue
            }
            var address = range.first
            while (address <= range.last) {
                val end = minOf(address + 15, range.last)
                append("0x").append(address.toString(16).uppercase().padStart(8, '0')).append(": ")
                for (index in address..end) append((image[index].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')).append(' ')
                appendLine()
                address = end + 1
            }
        }
    }

    /** Full 64-byte PIF RAM snapshot, without initiating SI DMA or Joybus processing. */
    fun diagnosticPifRamDump(): String = pifRam.indices.chunked(16).joinToString("\n") { row ->
        val start = row.first()
        "0x" + start.toString(16).uppercase().padStart(2, '0') + ": " +
            row.joinToString(" ") { index ->
                (pifRam[index].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
            }
    }

    /**
     * Reads the conventional libultra OSTask descriptor at the end of SP DMEM.
     * This is diagnostic-only: it does not validate, enqueue, or execute the task.
     */
    data class SpTaskDescriptor(
        val type: Int,
        val flags: Int,
        val ucodeBoot: Int,
        val ucodeBootSize: Int,
        val ucode: Int,
        val ucodeSize: Int,
        val ucodeData: Int,
        val ucodeDataSize: Int,
        val dramStack: Int,
        val dramStackSize: Int,
        val outputBuffer: Int,
        val outputBufferSize: Int,
        val yieldData: Int,
        val yieldDataSize: Int,
        val dataPointer: Int,
        val dataSize: Int
    )

    fun diagnosticSpTaskDescriptor(): SpTaskDescriptor {
        fun word(offset: Int): Int {
            val base = SP_DMEM_BASE + SP_TASK_DESCRIPTOR_OFFSET + offset
            return ((spDmem[base - SP_DMEM_BASE].toInt() and 0xFF) shl 24) or
                ((spDmem[base - SP_DMEM_BASE + 1].toInt() and 0xFF) shl 16) or
                ((spDmem[base - SP_DMEM_BASE + 2].toInt() and 0xFF) shl 8) or
                (spDmem[base - SP_DMEM_BASE + 3].toInt() and 0xFF)
        }
        return SpTaskDescriptor(
            type = word(0x00), flags = word(0x04),
            ucodeBoot = word(0x08), ucodeBootSize = word(0x0C),
            ucode = word(0x10), ucodeSize = word(0x14),
            ucodeData = word(0x18), ucodeDataSize = word(0x1C),
            dramStack = word(0x20), dramStackSize = word(0x24),
            outputBuffer = word(0x28), outputBufferSize = word(0x2C),
            dataPointer = word(0x30), dataSize = word(0x34),
            yieldData = word(0x38), yieldDataSize = word(0x3C)
        )
    }

    /** Full SP scratchpad snapshot, read-only and only on explicit log export. */
    fun diagnosticSpMemoryDump(): String = buildString {
        appendLine("SP DMEM 0x04000000..0x04000FFF")
        for (offset in 0 until SP_MEM_SIZE step 16) {
            append("0x").append(offset.toString(16).uppercase().padStart(4, '0')).append(": ")
            for (index in offset until offset + 16) append((spDmem[index].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')).append(' ')
            appendLine()
        }
        appendLine("SP IMEM 0x04001000..0x04001FFF")
        for (offset in 0 until SP_MEM_SIZE step 16) {
            append("0x").append(offset.toString(16).uppercase().padStart(4, '0')).append(": ")
            for (index in offset until offset + 16) append((spImem[index].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')).append(' ')
            appendLine()
        }
    }

    /** Reads memory for report generation without polluting CPU-access diagnostic histories. */
    fun peek8ForDiagnostic(address: Int): Int {
        require(address >= 0) { "Negative physical address." }
        if (address < ram.size) return ram.read8(address)
        return read8(address)
    }

    override fun read8(address: Int): Int {
        require(address >= 0) { "Negative physical address: 0x${address.toUInt().toString(16)}" }
        if (address < ram.size) {
            val value = ram.read8(address)
            recordBootMemoryAccess("R", address, value)
            return value
        }
        if (address in SP_DMEM_BASE until SP_DMEM_BASE + SP_MEM_SIZE) {
            return spDmem[address - SP_DMEM_BASE].toInt() and 0xFF
        }
        if (address in SP_IMEM_BASE until SP_IMEM_BASE + SP_MEM_SIZE) {
            return spImem[address - SP_IMEM_BASE].toInt() and 0xFF
        }

        // No 64DD is attached: Domain 2 Address 1 reads return open bus (0xFF).
        if (address in DD_DOMAIN2_ADDRESS1_BASE until DD_DOMAIN2_ADDRESS1_END ||
            address in DD_DOMAIN1_ADDRESS1_BASE until DD_DOMAIN1_ADDRESS1_END
        ) return 0xFF

        if (address >= CARTRIDGE_ROM_BASE && address < PIF_ROM_BASE) {
            val image = cartridge ?: throw UnsupportedOperationException(
                "Cartridge ROM is not loaded (physical address 0x${address.toString(16)})."
            )
            val offset = address - CARTRIDGE_ROM_BASE
            if (offset >= image.size) {
                throw UnsupportedOperationException(
                    "Cartridge read is beyond the loaded ROM size at 0x${address.toString(16)}."
                )
            }
            return image[offset].toInt() and 0xFF
        }

        if (address >= PIF_RAM_BASE && address < PIF_RAM_BASE + PIF_RAM_SIZE) {
            return pifRam[address - PIF_RAM_BASE].toInt() and 0xFF
        }

        if (address >= PIF_ROM_BASE && address < PIF_ROM_BASE + PIF_ROM_SIZE) {
            val image = pif ?: throw UnsupportedOperationException(
                "PIF boot ROM is not loaded (physical address 0x${address.toString(16)})."
            )
            return image[address - PIF_ROM_BASE].toInt() and 0xFF
        }
        throw UnsupportedOperationException(
            "Unmapped N64 physical read at 0x${address.toString(16)}."
        )
    }

    override fun read16(address: Int): Int {
        requireAligned(address, 2)
        if (address < ram.size) {
            val value = ram.read16(address)
            recordBootMemoryAccess("R", address, value ushr 8)
            recordBootMemoryAccess("R", address + 1, value and 0xFF)
            return value
        }
        return (read8(address) shl 8) or read8(address + 1)
    }

    override fun read32(address: Int): Int {
        requireAligned(address, 4)
        when (address) {
            DPC_START_REG -> return recordMmioRead(address, dpcStart)
            DPC_END_REG -> return recordMmioRead(address, dpcEnd)
            DPC_CURRENT_REG -> return recordMmioRead(address, dpcCurrent)
            DPC_STATUS_REG -> return recordMmioRead(address, DPC_STATUS_IDLE)
            in DPC_REG_BASE + 0x10 until DPC_REG_BASE + DPC_REG_SIZE ->
                return recordMmioRead(address, 0)
            SP_MEM_ADDR_REG -> return recordMmioRead(address, spMemAddress)
            SP_DRAM_ADDR_REG -> return recordMmioRead(address, spDramAddress)
            SP_PC_REG -> return recordMmioRead(address, spPc)
            SP_STATUS_REG -> return recordMmioRead(address, spStatus)
            SP_DMA_FULL_REG -> return recordMmioRead(address, 0)
            SP_DMA_BUSY_REG -> return recordMmioRead(address, 0)
            SP_SEMAPHORE_REG -> return recordMmioRead(address, spSemaphore)
            PI_DRAM_ADDR_REG -> return recordMmioRead(address, piDramAddress)
            PI_CART_ADDR_REG -> return recordMmioRead(address, piCartAddress)
            PI_RD_LEN_REG, PI_WR_LEN_REG -> return recordMmioRead(address, 0)
            PI_STATUS_REG -> return recordMmioRead(address, piStatus)
            MI_INIT_MODE_REG -> return recordMmioRead(address, miInitMode)
            MI_VERSION_REG -> return recordMmioRead(address, MI_VERSION_VALUE)
            MI_INTR_REG -> return recordMmioRead(address, miInterrupts)
            MI_INTR_MASK_REG -> return recordMmioRead(address, miInterruptMask)
            in RI_REG_BASE until RI_REG_BASE + RI_REG_SIZE -> return recordMmioRead(address, riRegisters[(address - RI_REG_BASE) / 4])
            SI_DRAM_ADDR_REG -> return recordMmioRead(address, siDramAddress)
            SI_PIF_ADDR_RD64B_REG -> return recordMmioRead(address, siPifReadAddress)
            SI_PIF_ADDR_WR64B_REG -> return recordMmioRead(address, siPifWriteAddress)
            SI_STATUS_REG -> return recordMmioRead(address, siStatus)
            AI_DRAM_ADDR_REG -> return recordMmioRead(address, aiDramAddress)
            AI_LEN_REG -> return recordMmioRead(address, 0)
            AI_CONTROL_REG -> return recordMmioRead(address, aiControl)
            AI_STATUS_REG -> return recordMmioRead(address, 0)
            AI_DACRATE_REG -> return recordMmioRead(address, aiDacRate)
            AI_BITRATE_REG -> return recordMmioRead(address, aiBitRate)
            VI_CURRENT_REG -> return recordMmioRead(address, viCurrentLine)
            in VI_REG_BASE until VI_REG_BASE + VI_REG_SIZE -> return recordMmioRead(address, viRegisters[(address - VI_REG_BASE) / 4])
        }
        if (address < ram.size) {
            val value = ram.read32(address)
            recordBootMemoryAccess("R", address, value ushr 24)
            recordBootMemoryAccess("R", address + 1, value ushr 16)
            recordBootMemoryAccess("R", address + 2, value ushr 8)
            recordBootMemoryAccess("R", address + 3, value)
            return value
        }
        return (read8(address) shl 24) or (read8(address + 1) shl 16) or
            (read8(address + 2) shl 8) or read8(address + 3)
    }

    /**
     * Advances the coarse VI timing model. One R4300 instruction is treated as one
     * timing unit; this is intentionally approximate until per-opcode cycle timing exists.
     * When VI_CURRENT reaches VI_INTR, the VI source is latched in MI_INTR_REG.
     */
    @Synchronized
    fun advanceViInstructions(instructions: Long = 1L) {
        require(instructions >= 0L) { "VI instruction advance cannot be negative." }
        viInstructionRemainder += instructions
        while (viInstructionRemainder >= VI_INSTRUCTIONS_PER_SCANLINE) {
            viInstructionRemainder -= VI_INSTRUCTIONS_PER_SCANLINE
            val programmedLines = viRegisters[VI_V_SYNC_OFFSET / 4] and VI_WINDOW_COORD_MASK
            val totalLines = programmedLines.takeIf { it > 0 } ?: VI_DEFAULT_TOTAL_SCANLINES
            viCurrentLine = (viCurrentLine + 1) % totalLines
            val interruptLine = viRegisters[VI_INTR_OFFSET / 4] and VI_WINDOW_COORD_MASK
            if (viCurrentLine == interruptLine) {
                miInterrupts = miInterrupts or MI_INTR_VI
            }
        }
    }

    @Synchronized
    override fun loadLinked(address: Int, width: Int): Long {
        require(address >= 0 && address <= ram.size - width) {
            "Load-linked must target installed RDRAM: address=0x${address.toString(16)}, width=$width."
        }
        return ram.loadLinked(address, width)
    }

    @Synchronized
    override fun storeConditional(address: Int, width: Int, value: Long): Boolean {
        require(address >= 0 && address <= ram.size - width) {
            "Store-conditional must target installed RDRAM: address=0x${address.toString(16)}, width=$width."
        }
        return ram.storeConditional(address, width, value)
    }

    @Synchronized
    override fun clearLoadLinkedReservation() {
        ram.clearLoadLinkedReservation()
    }

    override fun write8(address: Int, value: Int) {
        require(address >= 0) { "Negative physical address." }
        if (address < ram.size) {
            ram.write8(address, value)
            recordRdramWrite(address, value and 0xFF)
            recordPteTableWrite(address, value and 0xFF)
            recordBootMemoryAccess("W", address, value and 0xFF)
            recordBootDestinationWrite(address, value and 0xFF)
            return
        }
        if (address in SP_DMEM_BASE until SP_DMEM_BASE + SP_MEM_SIZE) {
            spDmem[address - SP_DMEM_BASE] = value.toByte()
            return
        }
        if (address in SP_IMEM_BASE until SP_IMEM_BASE + SP_MEM_SIZE) {
            spImem[address - SP_IMEM_BASE] = value.toByte()
            return
        }
        if (address >= PIF_RAM_BASE && address < PIF_RAM_BASE + PIF_RAM_SIZE) {
            pifRam[address - PIF_RAM_BASE] = value.toByte()
            return
        }
        // Writes to the absent 64DD address window are ignored.
        if (address in DD_DOMAIN2_ADDRESS1_BASE until DD_DOMAIN2_ADDRESS1_END ||
            address in DD_DOMAIN1_ADDRESS1_BASE until DD_DOMAIN1_ADDRESS1_END
        ) return
        if ((address >= CARTRIDGE_ROM_BASE && address < PIF_ROM_BASE) ||
            (address >= PIF_ROM_BASE && address < PIF_ROM_BASE + PIF_ROM_SIZE)
        ) {
            throw UnsupportedOperationException("Cartridge/PIF ROM is read-only.")
        }
        throw UnsupportedOperationException(
            "Unmapped N64 physical write at 0x${address.toString(16)}."
        )
    }

    override fun write16(address: Int, value: Int) {
        requireAligned(address, 2)
        write8(address, value ushr 8)
        write8(address + 1, value)
    }

    override fun write32(address: Int, value: Int) {
        requireAligned(address, 4)
        recordMmio("W", address, value)
        when (address) {
            DPC_START_REG -> {
                dpcStart = value and DPC_ADDRESS_MASK
                dpcCurrent = dpcStart
                return
            }
            DPC_END_REG -> {
                dpcEnd = value and DPC_ADDRESS_MASK
                try {
                    val result = dpcCommandProcessor.process(dpcStart, dpcEnd)
                    diagnosticLastDpcResult = result
                    diagnosticLastDpcFailure = null
                    dpcCurrent = result.endAddressExclusive
                } catch (error: UnsupportedOperationException) {
                    // An unsupported RDP command is an emulator capability gap,
                    // not a CPU bus fault. Keep the failure visible to diagnostics
                    // without injecting a false DBE into the guest CPU.
                    diagnosticLastDpcFailure =
                        "${error.javaClass.simpleName}: ${error.message ?: "no details"}"
                    return
                } catch (error: Throwable) {
                    diagnosticLastDpcFailure =
                        "${error.javaClass.simpleName}: ${error.message ?: "no details"}"
                    throw error
                }
                val result = diagnosticLastDpcResult ?: return
                if (result.fullSyncCommands > 0) {
                    miInitMode = miInitMode or MI_INIT_MODE_DP_INTR
                    miInterrupts = miInterrupts or MI_INTR_DP
                    dpDrainListener?.invoke()
                }
                return
            }
            DPC_CURRENT_REG, DPC_STATUS_REG -> return
            in DPC_REG_BASE + 0x10 until DPC_REG_BASE + DPC_REG_SIZE -> return
            SP_MEM_ADDR_REG -> { spMemAddress = value and SP_MEM_ADDR_MASK; return }
            SP_DRAM_ADDR_REG -> { spDramAddress = value and 0x00FFFFFF; return }
            SP_RD_LEN_REG -> { dmaRdramToSp(value); return }
            SP_WR_LEN_REG -> { dmaSpToRdram(value); return }
            SP_PC_REG -> { spPc = value and SP_PC_MASK; return }
            SP_STATUS_REG -> { writeSpStatusCommand(value); return }
            SP_SEMAPHORE_REG -> { spSemaphore = 1; return }
            PI_DRAM_ADDR_REG -> { piDramAddress = value and 0x00FFFFFF; return }
            PI_CART_ADDR_REG -> { piCartAddress = value; return }
            // PI DMA direction is named from RDRAM's point of view:
            // WR_LEN writes cartridge data into RDRAM; RD_LEN reads RDRAM data to the cartridge.
            PI_RD_LEN_REG -> { dmaRdramToCartridge((value and 0x00FFFFFF) + 1); return }
            PI_WR_LEN_REG -> { dmaCartridgeToRam((value and 0x00FFFFFF) + 1); return }
            PI_STATUS_REG -> {
                if ((value and PI_STATUS_RESET_CONTROLLER) != 0) {
                    // This synchronous PI model has no in-flight request to abort.
                    piStatus = piStatus and (PI_STATUS_DMA_BUSY or PI_STATUS_IO_BUSY or PI_STATUS_ERROR).inv()
                }
                if ((value and PI_STATUS_CLEAR_INTR) != 0) {
                    miInterrupts = miInterrupts and MI_INTR_PI.inv()
                }
                return
            }
            MI_INIT_MODE_REG -> { writeMiInitModeCommand(value); return }
            MI_INTR_MASK_REG -> { writeMiInterruptMaskCommand(value); return }
            in RI_REG_BASE until RI_REG_BASE + RI_REG_SIZE -> {
                riRegisters[(address - RI_REG_BASE) / 4] = value
                return
            }
            SI_DRAM_ADDR_REG -> { siDramAddress = value and 0x00FFFFFF; return }
            SI_PIF_ADDR_RD64B_REG -> {
                siPifReadAddress = value and 0x1FFFFFFF
                dmaPifToRdram()
                return
            }
            SI_PIF_ADDR_WR64B_REG -> {
                siPifWriteAddress = value and 0x1FFFFFFF
                dmaRdramToPif()
                return
            }
            SI_STATUS_REG -> {
                if ((value and SI_STATUS_INTERRUPT) != 0) siStatus = siStatus and SI_STATUS_INTERRUPT.inv()
                return
            }
            AI_DRAM_ADDR_REG -> { aiDramAddress = value and 0x00FFFFFF; return }
            AI_LEN_REG -> { return } // Consume the request without audio DMA or timing.
            AI_CONTROL_REG -> { aiControl = value and 1; return }
            AI_STATUS_REG -> { return } // Write clears the AI interrupt; interrupts are not yet modeled.
            AI_DACRATE_REG -> { aiDacRate = value and 0x3FFF; return }
            AI_BITRATE_REG -> { aiBitRate = value and 0xF; return }
            VI_CURRENT_REG -> {
                // VI_CURRENT is read-only; writing it acknowledges the VI interrupt.
                miInterrupts = miInterrupts and MI_INTR_VI.inv()
                return
            }
            in VI_REG_BASE until VI_REG_BASE + VI_REG_SIZE -> {
                viRegisters[(address - VI_REG_BASE) / 4] = value
                return
            }
        }
        write8(address, value ushr 24)
        write8(address + 1, value ushr 16)
        write8(address + 2, value ushr 8)
        write8(address + 3, value)
    }

    /** Simplified PI WR_LEN DMA from cartridge ROM into RDRAM, without timing or interrupts. */
    private fun dmaCartridgeToRam(length: Int) {
        val image = cartridge ?: throw UnsupportedOperationException(
            "PI DMA requested but cartridge ROM is not loaded."
        )
        require(length > 0 && piDramAddress <= ram.size - length) {
            "PI DMA destination is outside installed RDRAM: address=0x${piDramAddress.toString(16)}, length=$length."
        }
        require(piCartAddress >= CARTRIDGE_ROM_BASE) {
            "PI DMA source is not in the cartridge ROM window: 0x${piCartAddress.toString(16)}."
        }
        val offset = piCartAddress - CARTRIDGE_ROM_BASE
        require(offset >= 0 && offset <= image.size - length) {
            "PI DMA source exceeds loaded cartridge ROM: offset=0x${offset.toString(16)}, length=$length."
        }
        if (diagnosticPiDmaHistory.size >= 64) diagnosticPiDmaHistory.removeFirst()
        diagnosticPiDmaHistory.addLast(
            "PI_WR_LEN cart=0x${piCartAddress.toUInt().toString(16).uppercase()} romOffset=0x${offset.toString(16).uppercase()} " +
                "dram=0x${piDramAddress.toString(16).uppercase()} length=0x${length.toString(16).uppercase()}"
        )
        piStatus = piStatus or PI_STATUS_DMA_BUSY
        for (index in 0 until length) {
            ram.write8(piDramAddress + index, image[offset + index].toInt() and 0xFF)
        }
        piDramAddress = (piDramAddress + length) and 0x00FFFFFF
        piCartAddress += length
        piStatus = piStatus and PI_STATUS_DMA_BUSY.inv()
        // PI completion is latched in MI_INTR_REG until PI_STATUS.CLEAR_INTR is written.
        miInterrupts = miInterrupts or MI_INTR_PI
    }

    /**
     * Minimal PI RD_LEN DMA behavior for a read-only cartridge ROM.
     *
     * The PI consumes the RDRAM-to-cartridge request and advances its address registers,
     * but reads targeting the ROM window do not modify the loaded ROM image.
     * Writable cartridge devices (SRAM/Flash) are not implemented by this bus.
     */
    private fun dmaRdramToCartridge(length: Int) {
        require(length > 0 && piDramAddress <= ram.size - length) {
            "PI write DMA source is outside installed RDRAM: address=0x${piDramAddress.toString(16)}, length=$length."
        }
        require(piCartAddress >= CARTRIDGE_ROM_BASE && piCartAddress < PIF_ROM_BASE) {
            "PI write DMA destination is outside the read-only cartridge ROM window: 0x${piCartAddress.toString(16)}."
        }
        // A ROM cannot be changed by this DMA direction. Model the transfer as
        // ignored writes rather than aborting CPU execution; save-device writes
        // need a separate writable cartridge-device implementation.
        piStatus = piStatus or PI_STATUS_DMA_BUSY
        piDramAddress = (piDramAddress + length) and 0x00FFFFFF
        piCartAddress += length
        piStatus = piStatus and PI_STATUS_DMA_BUSY.inv()
        miInterrupts = miInterrupts or MI_INTR_PI
    }

    /** Copies exactly 64 bytes from RDRAM into PIF RAM (SI write DMA). */
    private fun dmaRdramToPif() {
        // Invalid PIF targets do not start a transfer; preserve the programmed register value.
        if (siPifWriteAddress != PIF_RAM_BASE) return
        val source = siDramAddress and 0x00FFFFFF
        require(source <= ram.size - PIF_RAM_SIZE) {
            "SI DMA source is outside installed RDRAM: address=0x${source.toString(16)}."
        }
        require(siPifWriteAddress == PIF_RAM_BASE) {
            "SI write DMA must target PIF RAM at 0x${PIF_RAM_BASE.toString(16)}; got 0x${siPifWriteAddress.toString(16)}."
        }
        siStatus = siStatus or SI_STATUS_DMA_BUSY or SI_STATUS_IO_BUSY
        for (index in 0 until PIF_RAM_SIZE) pifRam[index] = ram.read8(source + index).toByte()
        siDramAddress = (source + PIF_RAM_SIZE) and 0x00FFFFFF
        siStatus = (siStatus and (SI_STATUS_DMA_BUSY or SI_STATUS_IO_BUSY).inv()) or SI_STATUS_INTERRUPT
    }

    /** Copies exactly 64 bytes from PIF RAM into RDRAM (SI read DMA). */
    private fun dmaPifToRdram() {
        // Invalid PIF targets do not start a transfer; preserve the programmed register value.
        if (siPifReadAddress != PIF_RAM_BASE) return
        val destination = siDramAddress and 0x00FFFFFF
        require(destination <= ram.size - PIF_RAM_SIZE) {
            "SI DMA destination is outside installed RDRAM: address=0x${destination.toString(16)}."
        }
        require(siPifReadAddress == PIF_RAM_BASE) {
            "SI read DMA must target PIF RAM at 0x${PIF_RAM_BASE.toString(16)}; got 0x${siPifReadAddress.toString(16)}."
        }
        siStatus = siStatus or SI_STATUS_DMA_BUSY or SI_STATUS_IO_BUSY
        for (index in 0 until PIF_RAM_SIZE) ram.write8(destination + index, pifRam[index].toInt() and 0xFF)
        siDramAddress = (destination + PIF_RAM_SIZE) and 0x00FFFFFF
        siStatus = (siStatus and (SI_STATUS_DMA_BUSY or SI_STATUS_IO_BUSY).inv()) or SI_STATUS_INTERRUPT
    }

    /**
     * Executes the SP read-length DMA: RDRAM -> SP DMEM/IMEM.
     * Length/count/skip fields follow the hardware register layout; timing and interrupts are omitted.
     */
    private fun dmaRdramToSp(command: Int) = transferSpDma(command, toSp = true)

    /** Executes the SP write-length DMA: SP DMEM/IMEM -> RDRAM. */
    private fun dmaSpToRdram(command: Int) = transferSpDma(command, toSp = false)

    private fun transferSpDma(command: Int, toSp: Boolean) {
        val blockLength = (command and 0xFFF) + 1
        val blockCount = ((command ushr 12) and 0xFF) + 1
        val dramSkip = (command ushr 20) and 0xFFF
        val memorySelect = spMemAddress and 0x1000
        var memoryOffset = spMemAddress and 0xFFF
        var dramAddress = spDramAddress and 0x00FFFFFF
        val dramStartAddress = dramAddress
        val spStartAddress = memorySelect or memoryOffset

        require(blockLength > 0 && blockCount > 0) { "Invalid SP DMA length/count." }
        for (block in 0 until blockCount) {
            require(dramAddress >= 0 && dramAddress <= ram.size - blockLength) {
                "SP DMA RDRAM range is invalid: address=0x${dramAddress.toString(16)}, length=$blockLength."
            }
            for (index in 0 until blockLength) {
                val spAddress = memoryOffset and 0xFFF
                val spValue = if (toSp) ram.read8(dramAddress + index) else {
                    val memory = if (memorySelect == 0) spDmem else spImem
                    memory[spAddress].toInt() and 0xFF
                }
                if (toSp) {
                    val memory = if (memorySelect == 0) spDmem else spImem
                    memory[spAddress] = spValue.toByte()
                } else {
                    ram.write8(dramAddress + index, spValue)
                }
                memoryOffset = (memoryOffset + 1) and 0xFFF
            }
            dramAddress += blockLength + dramSkip
        }
        if (diagnosticSpDmaHistory.size >= 64) diagnosticSpDmaHistory.removeFirst()
        val direction = if (toSp) "RDRAM_TO_SP" else "SP_TO_RDRAM"
        val dramEnd = dramAddress - dramSkip - 1
        diagnosticSpDmaHistory.addLast(
            "$direction dramStart=0x${dramStartAddress.toString(16).uppercase()} " +
                "dramLast=0x${dramEnd.toString(16).uppercase()} blockLength=0x${blockLength.toString(16).uppercase()} " +
                "blocks=$blockCount skip=0x${dramSkip.toString(16).uppercase()} spStart=0x${spStartAddress.toString(16).uppercase()}"
        )
        // Observe only the canonical 64-byte OSTask DMA into DMEM[0xFC0].
        // This is passive diagnostics: it never releases HALT or schedules the RSP.
        if (toSp && spStartAddress == SP_TASK_DESCRIPTOR_OFFSET &&
            blockLength * blockCount == SP_TASK_DESCRIPTOR_SIZE
        ) {
            val task = diagnosticSpTaskDescriptor()
            if (diagnosticSpTaskDescriptorDmaHistory.size >= 32) {
                diagnosticSpTaskDescriptorDmaHistory.removeFirst()
            }
            diagnosticSpTaskDescriptorDmaHistory.addLast(
                "OSTASK_DESCRIPTOR_DMA PC=0x${diagnosticCpuPc.toULong().toString(16).uppercase()} " +
                    "RDRAM=0x${dramStartAddress.toString(16).uppercase().padStart(8, '0')} " +
                    "TYPE=0x${task.type.toUInt().toString(16).uppercase()} " +
                    "FLAGS=0x${task.flags.toUInt().toString(16).uppercase()} " +
                    "BOOT=0x${task.ucodeBoot.toUInt().toString(16).uppercase()} " +
                    "BOOT_SIZE=0x${task.ucodeBootSize.toUInt().toString(16).uppercase()} " +
                    "UCODE=0x${task.ucode.toUInt().toString(16).uppercase()} " +
                    "UCODE_SIZE=0x${task.ucodeSize.toUInt().toString(16).uppercase()} " +
                    "DATA=0x${task.dataPointer.toUInt().toString(16).uppercase()} " +
                    "DATA_SIZE=0x${task.dataSize.toUInt().toString(16).uppercase()} " +
                    "YIELD=0x${task.yieldData.toUInt().toString(16).uppercase()} " +
                    "YIELD_SIZE=0x${task.yieldDataSize.toUInt().toString(16).uppercase()}"
            )
        }
        spMemAddress = memorySelect or memoryOffset
        spDramAddress = dramAddress and 0x00FFFFFF
        spDmaListener?.invoke(
            SpDmaTransfer(
                toSp = toSp,
                dramStartAddress = dramStartAddress,
                spStartAddress = spStartAddress,
                blockLength = blockLength,
                blockCount = blockCount,
                dramSkip = dramSkip
            )
        )
    }

    /** Applies MI_INIT_MODE write-one command bits; reset and device side effects remain partial. */
    private fun writeMiInitModeCommand(command: Int) {
        if ((command and MI_INIT_MODE_CLR_INIT) != 0) miInitMode = miInitMode and MI_INIT_MODE_INIT.inv()
        if ((command and MI_INIT_MODE_SET_INIT) != 0) miInitMode = miInitMode or MI_INIT_MODE_INIT
        if ((command and MI_INIT_MODE_CLR_EBUS) != 0) miInitMode = miInitMode and MI_INIT_MODE_EBUS.inv()
        if ((command and MI_INIT_MODE_SET_EBUS) != 0) miInitMode = miInitMode or MI_INIT_MODE_EBUS
        if ((command and MI_INIT_MODE_CLR_DP_INTR) != 0) {
            miInitMode = miInitMode and MI_INIT_MODE_DP_INTR.inv()
            miInterrupts = miInterrupts and MI_INTR_DP.inv()
        }
        if ((command and MI_INIT_MODE_CLR_RDRAM_REG) != 0) miInitMode = miInitMode and MI_INIT_MODE_RDRAM_REG.inv()
        if ((command and MI_INIT_MODE_SET_RDRAM_REG) != 0) miInitMode = miInitMode or MI_INIT_MODE_RDRAM_REG
    }

    /** MI_INTR_MASK uses paired clear/set command bits for SP, SI, AI, VI, PI and DP. */
    private fun writeMiInterruptMaskCommand(command: Int) {
        for (source in 0 until 6) {
            val clearCommand = 1 shl (source * 2)
            val setCommand = 1 shl (source * 2 + 1)
            val maskBit = 1 shl source
            if ((command and clearCommand) != 0) miInterruptMask = miInterruptMask and maskBit.inv()
            if ((command and setCommand) != 0) miInterruptMask = miInterruptMask or maskBit
        }
    }

    /** Records the architectural effect of an RSP BREAK instruction. */
    fun signalSpBreak() {
        spStatus = spStatus or SP_STATUS_HALT or SP_STATUS_BROKE
        if ((spStatus and SP_STATUS_INTR_BREAK) != 0) {
            spStatus = spStatus or SP_STATUS_INTR
            miInterrupts = miInterrupts or MI_INTR_SP
        }
    }

    /** Applies the SP_STATUS write-one command bits to the readable status bits. */
    private fun writeSpStatusCommand(command: Int) {
        if ((command and SP_STATUS_CMD_CLR_HALT) != 0) spStatus = spStatus and SP_STATUS_HALT.inv()
        if ((command and SP_STATUS_CMD_SET_HALT) != 0) spStatus = spStatus or SP_STATUS_HALT
        if ((command and SP_STATUS_CMD_CLR_BROKE) != 0) spStatus = spStatus and SP_STATUS_BROKE.inv()
        if ((command and SP_STATUS_CMD_CLR_INTR) != 0) {
            spStatus = spStatus and SP_STATUS_INTR.inv()
            miInterrupts = miInterrupts and MI_INTR_SP.inv()
        }
        if ((command and SP_STATUS_CMD_SET_INTR) != 0) {
            spStatus = spStatus or SP_STATUS_INTR
            miInterrupts = miInterrupts or MI_INTR_SP
        }
        if ((command and SP_STATUS_CMD_CLR_SSTEP) != 0) spStatus = spStatus and SP_STATUS_SSTEP.inv()
        if ((command and SP_STATUS_CMD_SET_SSTEP) != 0) spStatus = spStatus or SP_STATUS_SSTEP
        if ((command and SP_STATUS_CMD_CLR_INTR_BREAK) != 0) spStatus = spStatus and SP_STATUS_INTR_BREAK.inv()
        if ((command and SP_STATUS_CMD_SET_INTR_BREAK) != 0) spStatus = spStatus or SP_STATUS_INTR_BREAK
        for (signal in 0 until 8) {
            val clearCommand = 1 shl (9 + signal * 2)
            val setCommand = 1 shl (10 + signal * 2)
            val signalBit = 1 shl (7 + signal)
            if ((command and clearCommand) != 0) spStatus = spStatus and signalBit.inv()
            if ((command and setCommand) != 0) spStatus = spStatus or signalBit
        }
    }

    /**
     * Captures a VI framebuffer using the currently programmed VI_ORIGIN,
     * VI_WIDTH and VI_STATUS registers. Height is explicit until VI timing/
     * active-scanline calculation is implemented.
     *
     * Reads RDRAM directly (not through bus MMIO), so capture does not add
     * artificial CPU-access events to the diagnostic MMIO trace.
     */
    fun captureViFrame(height: Int, outputWidth: Int? = null): N64VideoFrame? {
        require(height in 1..N64ViFramebufferReader.MAX_HEIGHT) {
            "Unsupported VI framebuffer height: $height"
        }
        val origin = viRegisters[VI_ORIGIN_OFFSET / 4]
        val stridePixels = viRegisters[VI_WIDTH_OFFSET / 4]
        val status = viRegisters[VI_STATUS_OFFSET / 4]
        if (stridePixels == 0) return null
        val width = outputWidth ?: stridePixels
        require(width in 1..stridePixels) {
            "VI output width $width must be between 1 and VI_WIDTH stride $stridePixels"
        }
        val reader = N64ViFramebufferReader(ram, ram.size)
        return reader.capture(origin, width, height, status, stridePixels)
    }

    /**
     * Derives active framebuffer height from VI_V_START and VI_Y_SCALE.
     * VI_V_START stores start/end in half-line units; VI_Y_SCALE is 11-bit
     * fixed-point with 2048 representing 1.0 output lines per half-line unit.
     * Integer division intentionally matches the truncating dimension
     * calculation used by established N64 video implementations.
     */
    fun captureViFrameFromRegisters(): N64VideoFrame? {
        val verticalWindow = viRegisters[VI_V_START_OFFSET / 4]
        val verticalStart = (verticalWindow ushr 16) and VI_WINDOW_COORD_MASK
        val verticalEnd = verticalWindow and VI_WINDOW_COORD_MASK
        val verticalScale = viRegisters[VI_Y_SCALE_OFFSET / 4] and VI_SCALE_MASK
        if (verticalEnd <= verticalStart || verticalScale == 0) return null
        val activeHalfLines = verticalEnd - verticalStart
        val height = (activeHalfLines.toLong() * verticalScale.toLong() / VI_SCALE_ONE).toInt()
        if (height !in 1..N64ViFramebufferReader.MAX_HEIGHT) return null

        val horizontalWindow = viRegisters[VI_H_START_OFFSET / 4]
        val horizontalStart = (horizontalWindow ushr 16) and VI_WINDOW_COORD_MASK
        val horizontalEnd = horizontalWindow and VI_WINDOW_COORD_MASK
        val horizontalScale = viRegisters[VI_X_SCALE_OFFSET / 4] and VI_SCALE_MASK
        if (horizontalEnd <= horizontalStart || horizontalScale == 0) return null
        val activeClocks = horizontalEnd - horizontalStart
        val width = (activeClocks.toLong() * horizontalScale.toLong() / VI_X_SCALE_ONE).toInt()
        val stridePixels = viRegisters[VI_WIDTH_OFFSET / 4]
        if (width !in 1..N64ViFramebufferReader.MAX_WIDTH || width > stridePixels) return null
        return captureViFrame(height, width)
    }

    private fun requireAligned(address: Int, alignment: Int) {
        require(address >= 0 && (address and (alignment - 1)) == 0) {
            "Physical address must be $alignment-byte aligned: 0x${address.toString(16)}."
        }
    }

    companion object {
        const val DPC_REG_BASE = 0x04100000
        const val DPC_START_REG = DPC_REG_BASE
        const val DPC_END_REG = DPC_REG_BASE + 0x04
        const val DPC_CURRENT_REG = DPC_REG_BASE + 0x08
        const val DPC_STATUS_REG = DPC_REG_BASE + 0x0C
        const val DPC_REG_SIZE = 0x20
        const val DPC_ADDRESS_MASK = 0x00FFFFFF
        const val DPC_STATUS_IDLE = 0

        const val SP_DMEM_BASE = 0x04000000
        const val SP_IMEM_BASE = 0x04001000
        const val SP_MEM_SIZE = 0x1000
        const val SP_TASK_DESCRIPTOR_OFFSET = 0x0FC0
        const val SP_TASK_DESCRIPTOR_SIZE = 0x40
        const val SP_REG_BASE = 0x04040000
        const val SP_MEM_ADDR_REG = SP_REG_BASE
        const val SP_MEM_ADDR_MASK = 0x00001FFF
        const val SP_DRAM_ADDR_REG = SP_REG_BASE + 0x04
        const val SP_RD_LEN_REG = SP_REG_BASE + 0x08
        const val SP_WR_LEN_REG = SP_REG_BASE + 0x0C
        const val SP_STATUS_REG = SP_REG_BASE + 0x10
        const val SP_DMA_FULL_REG = SP_REG_BASE + 0x14
        const val SP_DMA_BUSY_REG = SP_REG_BASE + 0x18
        const val SP_SEMAPHORE_REG = SP_REG_BASE + 0x1C
        const val SP_STATUS_HALT = 1 shl 0
        const val SP_STATUS_BROKE = 1 shl 1
        const val SP_STATUS_INTR = 1 shl 2
        const val SP_STATUS_SSTEP = 1 shl 5
        const val SP_STATUS_INTR_BREAK = 1 shl 6
        const val SP_STATUS_CMD_CLR_HALT = 1 shl 0
        const val SP_STATUS_CMD_SET_HALT = 1 shl 1
        const val SP_STATUS_CMD_CLR_BROKE = 1 shl 2
        const val SP_STATUS_CMD_CLR_INTR = 1 shl 3
        const val SP_STATUS_CMD_SET_INTR = 1 shl 4
        const val SP_STATUS_CMD_CLR_SSTEP = 1 shl 5
        const val SP_STATUS_CMD_SET_SSTEP = 1 shl 6
        const val SP_STATUS_CMD_CLR_INTR_BREAK = 1 shl 7
        const val SP_STATUS_CMD_SET_INTR_BREAK = 1 shl 8
        const val SP_PC_REG = 0x04080000
        const val SP_PC_MASK = 0x00001FFC
        const val DD_DOMAIN2_ADDRESS1_BASE = 0x05000000
        const val DD_DOMAIN2_ADDRESS1_END = 0x06000000
        const val DD_DOMAIN1_ADDRESS1_BASE = 0x06000000
        const val DD_DOMAIN1_ADDRESS1_END = 0x08000000
        const val CARTRIDGE_ROM_BASE = 0x10000000
        const val MAX_CARTRIDGE_ROM_SIZE = 64 * 1024 * 1024
        const val PIF_ROM_BASE = 0x1FC00000
        const val PIF_ROM_SIZE = 0x7C0
        const val PI_REG_BASE = 0x04600000
        const val PI_DRAM_ADDR_REG = PI_REG_BASE
        const val PI_CART_ADDR_REG = PI_REG_BASE + 0x04
        const val PI_RD_LEN_REG = PI_REG_BASE + 0x08
        const val PI_WR_LEN_REG = PI_REG_BASE + 0x0C
        const val PI_STATUS_REG = PI_REG_BASE + 0x10
        const val PI_STATUS_DMA_BUSY = 0x01
        const val PI_STATUS_IO_BUSY = 0x02
        const val PI_STATUS_ERROR = 0x04
        const val PI_STATUS_RESET_CONTROLLER = 0x01
        const val PI_STATUS_CLEAR_INTR = 0x02
        const val MI_REG_BASE = 0x04300000
        const val MI_INIT_MODE_REG = MI_REG_BASE
        const val MI_VERSION_REG = MI_REG_BASE + 0x04
        const val MI_INTR_REG = MI_REG_BASE + 0x08
        const val MI_INTR_MASK_REG = MI_REG_BASE + 0x0C
        const val MI_VERSION_VALUE = 0x02020102
        const val MI_INTR_SP = 1 shl 0
        const val MI_INTR_SI = 1 shl 1
        const val MI_INTR_AI = 1 shl 2
        const val MI_INTR_VI = 1 shl 3
        const val MI_INTR_PI = 1 shl 4
        const val MI_INTR_DP = 1 shl 5
        const val MI_INIT_MODE_INIT = 1 shl 7
        const val MI_INIT_MODE_EBUS = 1 shl 8
        const val MI_INIT_MODE_DP_INTR = 1 shl 11
        const val MI_INIT_MODE_RDRAM_REG = 1 shl 12
        const val MI_INIT_MODE_CLR_INIT = 1 shl 0
        const val MI_INIT_MODE_SET_INIT = 1 shl 1
        const val MI_INIT_MODE_CLR_EBUS = 1 shl 2
        const val MI_INIT_MODE_SET_EBUS = 1 shl 3
        const val MI_INIT_MODE_CLR_DP_INTR = 1 shl 4
        const val MI_INIT_MODE_CLR_RDRAM_REG = 1 shl 6
        const val MI_INIT_MODE_SET_RDRAM_REG = 1 shl 7
        const val RI_REG_BASE = 0x04700000
        const val RI_REG_SIZE = 0x20
        const val RI_SELECT_OFFSET = 0x0C
        const val RI_SELECT_RESET = 0x00000014
        const val RI_REGISTER_COUNT = RI_REG_SIZE / 4
        const val SI_REG_BASE = 0x04800000
        const val SI_DRAM_ADDR_REG = SI_REG_BASE
        const val SI_PIF_ADDR_RD64B_REG = SI_REG_BASE + 0x04
        const val SI_PIF_ADDR_WR64B_REG = SI_REG_BASE + 0x10
        const val SI_STATUS_REG = SI_REG_BASE + 0x18
        const val SI_STATUS_DMA_BUSY = 1 shl 0
        const val SI_STATUS_IO_BUSY = 1 shl 1
        const val SI_STATUS_INTERRUPT = 1 shl 12
        const val PIF_RAM_BASE = PIF_ROM_BASE + PIF_ROM_SIZE
        const val PIF_RAM_SIZE = 0x40
        const val AI_REG_BASE = 0x04500000
        const val AI_DRAM_ADDR_REG = AI_REG_BASE
        const val AI_LEN_REG = AI_REG_BASE + 0x04
        const val AI_CONTROL_REG = AI_REG_BASE + 0x08
        const val AI_STATUS_REG = AI_REG_BASE + 0x0C
        const val AI_DACRATE_REG = AI_REG_BASE + 0x10
        const val AI_BITRATE_REG = AI_REG_BASE + 0x14
        const val VI_REG_BASE = 0x04400000
        const val VI_STATUS_OFFSET = 0x00
        const val VI_ORIGIN_OFFSET = 0x04
        const val VI_WIDTH_OFFSET = 0x08
        const val VI_INTR_OFFSET = 0x0C
        const val VI_CURRENT_OFFSET = 0x10
        const val VI_V_SYNC_OFFSET = 0x18
        const val VI_INSTRUCTIONS_PER_SCANLINE = 5_661L
        const val VI_DEFAULT_TOTAL_SCANLINES = 525
        const val MI_INTR_MASK_VI_CLEAR = 1 shl 6
        const val MI_INTR_MASK_VI_SET = 1 shl 7
        const val VI_H_START_OFFSET = 0x24
        const val VI_V_START_OFFSET = 0x28
        const val VI_X_SCALE_OFFSET = 0x30
        const val VI_Y_SCALE_OFFSET = 0x34
        const val VI_WINDOW_COORD_MASK = 0x3FF
        const val VI_SCALE_MASK = 0xFFF
        const val VI_X_SCALE_ONE = 1024L
        const val VI_SCALE_ONE = 2048L
        const val VI_REG_SIZE = 0x38
        const val VI_CURRENT_REG = VI_REG_BASE + 0x10
        const val VI_REGISTER_COUNT = VI_REG_SIZE / 4
    }
}
