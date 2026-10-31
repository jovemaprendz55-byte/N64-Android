package com.jsr.n64android

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.method.ScrollingMovementMethod
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var cartridgeBus: N64Bus? = null
    private var cartridgeRomBytes: ByteArray? = null
    private var romTraceButton: Button? = null
    private var hleBootButton: Button? = null
    private lateinit var mainLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Núcleo próprio em construção. Nenhuma ROM carregada."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            movementMethod = ScrollingMovementMethod()
        }
        val chooseRom = Button(this).apply {
            text = "Selecionar ROM (.z64 / .v64 / .n64)"
            setOnClickListener { openRomPicker() }
        }
        val cpuDiagnostic = Button(this).apply {
            text = "Executar teste interno da CPU"
            setOnClickListener { runCpuDiagnostic() }
        }
        romTraceButton = Button(this).apply {
            text = "Executar diagnóstico de instruções da ROM (IPL3 direto)"
            setOnClickListener { runRomInstructionDiagnostic() }
            isEnabled = false
        }
        hleBootButton = Button(this).apply {
            text = "Iniciar jogo (HLE sem IPL3)"
            setOnClickListener { runHleBootDiagnostic() }
            isEnabled = false
        }
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(TextView(this@MainActivity).apply {
                text = "N64 Android"
                textSize = 30f
                gravity = Gravity.CENTER
            })
            addView(status)
            addView(chooseRom)
            addView(cpuDiagnostic)
            addView(romTraceButton)
            addView(hleBootButton)
        }
        setContentView(mainLayout)
    }

    /**
     * Runs a known instruction sequence on the real R4300Cpu implementation.
     * This is an internal CPU test, not a claim that the selected game boots.
     */
    /**
     * Experimental HLE handoff: bypasses external PIF firmware and stages the
     * first 0xFC0 bytes of the cartridge boot payload at RDRAM 0x400.
     * This is a bounded CPU bring-up trace, not full game execution/rendering.
     */
    /**
     * Executes a bounded bring-up trace from the game bootcode staged by HLE.
     * This path deliberately does not execute the cartridge IPL3 in SP DMEM.
     */
    private fun runHleBootDiagnostic() {
        val bus = cartridgeBus
        if (bus == null) {
            status.text = "Selecione uma ROM de jogo primeiro."
            return
        }
        hleBootButton?.isEnabled = false
        romTraceButton?.isEnabled = false
        status.text = "Preparando HLE sem executar IPL3..."
        Thread {
            val cpu = R4300Cpu(bus)
            val lines = ArrayList<String>()
            var steps = 0
            var stopReason = "Limite de instruções alcançado."
            try {
                val handoff = N64HleBoot.prepare(bus, cpu)
                lines.add("BOOT HLE — IPL3 NÃO EXECUTADO")
                lines.add("PIF ROM utilizada: ${handoff.pifRomUsed}")
                lines.add("CIC seed: 0x${handoff.cicSeed.toString(16).uppercase()}")
                lines.add("Payload: ${handoff.copiedBytes} bytes | ROM 0x${handoff.romOffset.toString(16).uppercase()} -> RDRAM 0x${handoff.ramAddress.toString(16).uppercase()}")
                lines.add("PC inicial: 0x${cpu.pc.toULong().toString(16).uppercase()}")
                lines.add("SP inicial: 0x${cpu.gpr[29].toULong().toString(16).uppercase()}")
                // The previous 500k-step run was still inside the byte-copy loop
                // around 0x70000574..0x70000588. Extend the bounded run and capture
                // the loop's actual exit instead of relying only on distant checkpoints.
                val maxSteps = 2_000_000
                var copyLoopIterations = 0L
                var copyLoopTakenBranches = 0L
                var copyLoopExitObserved = false
                val copyLoopTrace = ArrayList<String>()
                val copyLoopSamples = ArrayList<String>()
                val postCopyTrace = ArrayList<String>()
                var postCopyTraceRemaining = 0
                // Always retain the final execution window; unlike the old one-shot
                // post-copy trace, this ring is useful even when the CPU stops much later.
                val recentInstructionTrace = ArrayDeque<String>()
                val pcVisitCounts = LinkedHashMap<Long, Int>()
                fun recordExecution(pc: Long, opcode: Int?, nextPc: Long) {
                    if (recentInstructionTrace.size >= 256) recentInstructionTrace.removeFirst()
                    recentInstructionTrace.addLast(
                        "PC=0x${pc.toULong().toString(16).uppercase()} " +
                        "INST=${opcode?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} " +
                        "NEXT=0x${nextPc.toULong().toString(16).uppercase()}"
                    )
                    if (pcVisitCounts.containsKey(pc) || pcVisitCounts.size < 4096) {
                        pcVisitCounts[pc] = (pcVisitCounts[pc] ?: 0) + 1
                    }
                }
                fun copyLoopRegisters(): String =
                    "r2=0x${cpu.gpr[2].toULong().toString(16).uppercase()} " +
                    "r3=0x${cpu.gpr[3].toULong().toString(16).uppercase()} " +
                    "r4=0x${cpu.gpr[4].toULong().toString(16).uppercase()} " +
                    "r5=0x${cpu.gpr[5].toULong().toString(16).uppercase()} " +
                    "r8=0x${cpu.gpr[8].toULong().toString(16).uppercase()} " +
                    "r9=0x${cpu.gpr[9].toULong().toString(16).uppercase()} " +
                    "r10=0x${cpu.gpr[10].toULong().toString(16).uppercase()} " +
                    "r17=0x${cpu.gpr[17].toULong().toString(16).uppercase()} " +
                    "r24=0x${cpu.gpr[24].toULong().toString(16).uppercase()} " +
                    "r31=0x${cpu.gpr[31].toULong().toString(16).uppercase()}"
                while (steps < maxSteps) {
                    val pcBefore = cpu.pc
                    val inCopyLoop = pcBefore in 0x70000570L..0x70000588L
                    val sampleIteration = pcBefore == 0x70000588L &&
                        (copyLoopIterations < 8L || (copyLoopIterations + 1L) % 4096L == 0L)
                    val registersBefore = if (inCopyLoop && (copyLoopTrace.size < 32 || sampleIteration)) copyLoopRegisters() else ""
                    bus.setDiagnosticCpuPc(pcBefore)
                    cpu.step()
                    val instruction = cpu.diagnosticInstructionWord
                    recordExecution(pcBefore, instruction, cpu.pc)
                    if (inCopyLoop && copyLoopTrace.size < 32) {
                        copyLoopTrace.add(
                            "PC=0x${pcBefore.toULong().toString(16).uppercase()} " +
                            "INST=${instruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} " +
                            "NEXT=0x${cpu.pc.toULong().toString(16).uppercase()} " +
                            "BEFORE{$registersBefore} AFTER{${copyLoopRegisters()}}"
                        )
                    }
                    if (pcBefore == 0x70000588L) {
                        copyLoopIterations++
                        val branchTaken = cpu.pc == 0x70000574L
                        if (branchTaken) copyLoopTakenBranches++ else copyLoopExitObserved = true
                        if (sampleIteration || !branchTaken) {
                            copyLoopSamples.add(
                                "iteration=$copyLoopIterations branchTaken=$branchTaken " +
                                "NEXT=0x${cpu.pc.toULong().toString(16).uppercase()} " +
                                "BEFORE{$registersBefore} AFTER{${copyLoopRegisters()}}"
                            )
                        }
                    }
                    if (copyLoopExitObserved && postCopyTraceRemaining == 0 && postCopyTrace.isEmpty()) {
                        postCopyTraceRemaining = 64
                    }
                    if (postCopyTraceRemaining > 0) {
                        postCopyTrace.add(
                            "PC=0x${pcBefore.toULong().toString(16).uppercase()} " +
                            "INST=${instruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} " +
                            "NEXT=0x${cpu.pc.toULong().toString(16).uppercase()} REG{${copyLoopRegisters()}}"
                        )
                        postCopyTraceRemaining--
                    }
                    if (steps < 48) {
                        lines.add("step=$steps PC=0x${pcBefore.toULong().toString(16).uppercase()} INST=${instruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} NEXT=0x${cpu.pc.toULong().toString(16).uppercase()}")
                    }
                    steps++
                    if (steps % 50_000 == 0) {
                        lines.add("checkpoint=$steps PC=0x${cpu.pc.toULong().toString(16).uppercase()} t0=0x${cpu.gpr[8].toULong().toString(16).uppercase()} t1=0x${cpu.gpr[9].toULong().toString(16).uppercase()} a0=0x${cpu.gpr[4].toULong().toString(16).uppercase()} sp=0x${cpu.gpr[29].toULong().toString(16).uppercase()}")
                    }
                    if (cpu.lastExceptionCode != null) {
                        stopReason = "Exceção CPU code=${cpu.lastExceptionCode} em 0x${(cpu.lastExceptionInstructionAddress ?: pcBefore).toULong().toString(16).uppercase()}."
                        break
                    }
                }
                lines.add("=== LAÇO DE CÓPIA 0x70000588 ===")
                lines.add("Iterações=$copyLoopIterations branches_taken=$copyLoopTakenBranches saída_observada=$copyLoopExitObserved")
                lines.addAll(copyLoopTrace)
                lines.add("=== AMOSTRAS DO LAÇO ===")
                lines.addAll(copyLoopSamples)
                lines.add("=== PRIMEIRAS INSTRUÇÕES APÓS A SAÍDA ===")
                lines.addAll(postCopyTrace)
                lines.add("=== ÚLTIMAS 256 INSTRUÇÕES ===")
                lines.addAll(recentInstructionTrace)
                lines.add("=== PCs MAIS VISITADOS (até 16) ===")
                lines.addAll(pcVisitCounts.entries.sortedByDescending { it.value }.take(16).map { (pc, count) ->
                    "PC=0x${pc.toULong().toString(16).uppercase()} visits=$count"
                })
                lines.add("PCs distintos contabilizados=${pcVisitCounts.size} (limite 4096)")
                lines.add("Instruções executadas: $steps")
                lines.add("PC final: 0x${cpu.pc.toULong().toString(16).uppercase()}")
                lines.add("Exceção: ${cpu.lastExceptionCode ?: "nenhuma"}")
                lines.add("Exception PC: 0x${(cpu.lastExceptionInstructionAddress ?: 0L).toULong().toString(16).uppercase()}")
                lines.add("COP0 Status: 0x${cpu.cop0[12].toULong().toString(16).uppercase()}")
                lines.add("COP0 Cause: 0x${cpu.cop0[13].toULong().toString(16).uppercase()}")
                lines.add("COP0 EPC: 0x${cpu.cop0[14].toULong().toString(16).uppercase()}")
                lines.add("COP0 BadVAddr: 0x${cpu.cop0[8].toULong().toString(16).uppercase()}")
                lines.add("GPR: " + (0 until 32).joinToString(" ") { index -> "r$index=0x${cpu.gpr[index].toULong().toString(16).uppercase()}" })
                lines.add("TLB fault: ${cpu.lastTlbFaultKind ?: "nenhuma"} / ${cpu.lastTlbFaultAccessType ?: "sem acesso"} / 0x${(cpu.lastTlbFaultVirtualAddress ?: 0L).toULong().toString(16).uppercase()}")
                lines.add("=== EVENTOS DO BARRAMENTO ===")
                lines.add("=== MMIO CPU (MI/VI/AI/PI/SP/SI/RI) ===")
                lines.add("Contagens: " + (if (bus.diagnosticMmioCounts.isEmpty()) "(nenhum acesso)" else bus.diagnosticMmioCounts.entries.joinToString(" | ") { "${it.key}=${it.value}" }))
                lines.addAll(bus.diagnosticMmioHistory)
                lines.add("=== SNAPSHOT DOS DISPOSITIVOS (estado implementado) ===")
                lines.addAll(bus.diagnosticDeviceSnapshot())
                lines.add("PI DMA count=${bus.diagnosticPiDmaHistory.size}: " + bus.diagnosticPiDmaHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("SP DMA count=${bus.diagnosticSpDmaHistory.size}: " + bus.diagnosticSpDmaHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("RDRAM writes total=${bus.diagnosticRdramWriteCount}: " + bus.diagnosticRdramWriteHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("Boot memory accesses: " + bus.diagnosticBootMemoryAccessHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("Boot destination writes total=${bus.diagnosticBootDestinationWriteCount}: " + bus.diagnosticBootDestinationWriteHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("SI DRAM=0x${bus.read32(N64Bus.SI_DRAM_ADDR_REG).toUInt().toString(16).uppercase()} PIF_RD=0x${bus.read32(N64Bus.SI_PIF_ADDR_RD64B_REG).toUInt().toString(16).uppercase()} PIF_WR=0x${bus.read32(N64Bus.SI_PIF_ADDR_WR64B_REG).toUInt().toString(16).uppercase()} STATUS=0x${bus.read32(N64Bus.SI_STATUS_REG).toUInt().toString(16).uppercase()}")
                lines.add("PIF RAM 64B: " + (0 until N64Bus.PIF_RAM_SIZE).joinToString("") { index -> bus.read8(N64Bus.PIF_RAM_BASE + index).toString(16).uppercase().padStart(2, '0') })
                lines.add("Resultado: $stopReason")
            } catch (error: Throwable) {
                val failedInstructionPc = cpu.diagnosticInstructionAddress
                val failedInstruction = cpu.diagnosticInstructionWord
                lines.add("=== FALHA HLE — CONTEXTO PRESERVADO ===")
                lines.add("Erro: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"}")
                lines.add("Instruções executadas: $steps")
                lines.add("PC atual: 0x${cpu.pc.toULong().toString(16).uppercase()}")
                lines.add("Instrução que provocou a falha: PC=0x${failedInstructionPc?.toULong()?.toString(16)?.uppercase() ?: "N/A"} INST=0x${failedInstruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"}")
                lines.add("Acesso que falhou: VADDR=0x${cpu.diagnosticVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"} PADDR=${cpu.diagnosticPhysicalAddress?.let { "0x" + it.toUInt().toString(16).uppercase() } ?: "N/A"} tipo=${cpu.diagnosticAccessType ?: "N/A"}")
                lines.add("Último JR/JALR: PC=${cpu.diagnosticLastIndirectJumpAddress?.let { "0x" + it.toULong().toString(16).uppercase() } ?: "N/A"} INST=${cpu.diagnosticLastIndirectJumpInstruction?.let { "0x" + it.toUInt().toString(16).uppercase().padStart(8, '0') } ?: "N/A"} DESTINO=${cpu.diagnosticLastIndirectTarget?.let { "0x" + it.toULong().toString(16).uppercase() } ?: "N/A"}")
                lines.add("TLB habilitada=${cpu.tlbEnabled} COP0 Index=0x${cpu.cop0[0].toULong().toString(16).uppercase()} Wired=0x${cpu.cop0[6].toULong().toString(16).uppercase()} EntryHi=0x${cpu.cop0[10].toULong().toString(16).uppercase()}")
                val lastJumpTarget = cpu.diagnosticLastIndirectTarget
                lines.add("Tradução do último destino JR/JALR: " + (lastJumpTarget?.let { cpu.describeTlbAddressForDiagnostic(it) } ?: "N/A"))
                lines.add("TLBWI/TLBWR executados: " + (if (cpu.diagnosticTlbWriteHistory.isEmpty()) "(nenhum)" else cpu.diagnosticTlbWriteHistory.joinToString(" | ")))
                lines.add("Última exceção CPU: código=${cpu.lastExceptionCode ?: "nenhuma"} PC=0x${cpu.lastExceptionInstructionAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"}")
                lines.add("COP0 Status=0x${cpu.cop0[12].toULong().toString(16).uppercase()} Cause=0x${cpu.cop0[13].toULong().toString(16).uppercase()} EPC=0x${cpu.cop0[14].toULong().toString(16).uppercase()} BadVAddr=0x${cpu.cop0[8].toULong().toString(16).uppercase()}")
                lines.add("TLB fault=${cpu.lastTlbFaultKind ?: "nenhuma"} acesso=${cpu.lastTlbFaultAccessType ?: "N/A"} VADDR=0x${cpu.lastTlbFaultVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"}")
                lines.add("GPR: " + (0 until 32).joinToString(" ") { index -> "r$index=0x${cpu.gpr[index].toULong().toString(16).uppercase()}" })
                lines.add("=== EVENTOS DO BARRAMENTO NA FALHA ===")
                lines.add("=== MMIO CPU (MI/VI/AI/PI/SP/SI/RI) ===")
                lines.add("Contagens: " + (if (bus.diagnosticMmioCounts.isEmpty()) "(nenhum acesso)" else bus.diagnosticMmioCounts.entries.joinToString(" | ") { "${it.key}=${it.value}" }))
                lines.addAll(bus.diagnosticMmioHistory)
                lines.add("=== SNAPSHOT DOS DISPOSITIVOS (estado implementado) ===")
                lines.addAll(bus.diagnosticDeviceSnapshot())
                lines.add("PI DMA count=${bus.diagnosticPiDmaHistory.size}: " + bus.diagnosticPiDmaHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("SP DMA count=${bus.diagnosticSpDmaHistory.size}: " + bus.diagnosticSpDmaHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("RDRAM writes total=${bus.diagnosticRdramWriteCount}: " + bus.diagnosticRdramWriteHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("Boot memory accesses: " + bus.diagnosticBootMemoryAccessHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("Boot destination writes total=${bus.diagnosticBootDestinationWriteCount}: " + bus.diagnosticBootDestinationWriteHistory.toList().takeLast(32).joinToString(" | "))
                lines.add("SI DRAM=0x${bus.read32(N64Bus.SI_DRAM_ADDR_REG).toUInt().toString(16).uppercase()} PIF_RD=0x${bus.read32(N64Bus.SI_PIF_ADDR_RD64B_REG).toUInt().toString(16).uppercase()} PIF_WR=0x${bus.read32(N64Bus.SI_PIF_ADDR_WR64B_REG).toUInt().toString(16).uppercase()} STATUS=0x${bus.read32(N64Bus.SI_STATUS_REG).toUInt().toString(16).uppercase()}")
                lines.add("PIF RAM 64B: " + (0 until N64Bus.PIF_RAM_SIZE).joinToString("") { index -> bus.read8(N64Bus.PIF_RAM_BASE + index).toString(16).uppercase().padStart(2, '0') })
            }
            runOnUiThread {
                showRomDiagnosticPage(lines.joinToString("\n"))
                hleBootButton?.isEnabled = true
                romTraceButton?.isEnabled = true
            }
        }.start()
    }

    private fun runIpl3HandoffDiagnosticLegacy() {
        val bus = cartridgeBus
        if (bus == null) {
            status.text = "Selecione uma ROM de jogo primeiro."
            return
        }
        hleBootButton?.isEnabled = false
        romTraceButton?.isEnabled = false
        status.text = "Preparando boot HLE experimental sem PIF ROM..."
        Thread({
            val cpu = R4300Cpu(bus)
            var steps = 0
            // The post-IPL3 startup loop scans a large RDRAM range before it can
            // hand off to the game. Two million steps stop partway through it;
            // allow up to eight million while keeping execution off the UI thread.
            val maxSteps = 8_000_000
            var stopReason = "Limite de $maxSteps instruções alcançado."
            val trace = ArrayDeque<String>()
            val firstTrace = ArrayList<String>()
            // Full register snapshots for the actual post-IPL3 loop seen at 0x80000130.
            val postIpl3LoopTrace = ArrayList<String>()
            val loopEntryTrace = ArrayList<String>()
            val copyLoopFirstTrace = ArrayList<String>()
            val copyLoopRecentTrace = ArrayDeque<String>()
            var copyLoopIterations = 0L
            var copyLoopTakenBranches = 0L
            var copyLoopExitObserved = false
            // Capture the first instructions after the byte-copy loop exits. The
            // previous report only retained the final ring, which hid the path
            // from the completed copy routine to the later stack-region fault.
            var postCopyTraceRemaining = 0
            val postCopyTrace = ArrayList<String>()
            var stackRegionEntryObserved = false
            val stackRegionEntryContext = ArrayList<String>()
            val stackRegionTrace = ArrayList<String>()
            // Capture the indirect handoff at JR $a3 and inspect its mapped target.
            var indirectJumpTarget: Long? = null
            var indirectJumpRegisters = ""
            val indirectTargetWords = ArrayList<String>()
            val payloadWords = ArrayList<String>()
            val cicSeed = bus.detectCicSeed()
            fun hex(value: Long): String = "0x" + value.toULong().toString(16).uppercase().padStart(16, '0')
            fun watchedRegisters(): String =
                "v0=" + hex(cpu.gpr[2]) + " v1=" + hex(cpu.gpr[3]) +
                " t0=" + hex(cpu.gpr[8]) + " t1=" + hex(cpu.gpr[9]) +
                " t2=" + hex(cpu.gpr[10]) + " t3=" + hex(cpu.gpr[11]) +
                " a0=" + hex(cpu.gpr[4]) + " a1=" + hex(cpu.gpr[5]) +
                " s6=" + hex(cpu.gpr[22]) + " sp=" + hex(cpu.gpr[29]) +
                " ra=" + hex(cpu.gpr[31])
            fun allRegisters(): String = (0 until 32).joinToString(" ") { index ->
                "r" + index + "=" + hex(cpu.gpr[index])
            }
            try {
                require(cicSeed != null) {
                    "CIC/seed do IPL3 não reconhecido. Este HLE inicial suporta apenas CIC 6102/7101 e 6105/7105."
                }
                // IPL3 is stored at cartridge offset 0x40 and executes from SP DMEM.
                // The previous HLE path incorrectly staged game data from 0x1000 into
                // RDRAM 0x400 and started at 0x80000400, bypassing the real IPL3 entry.
                for (offset in 0 until 0xFC0) {
                    bus.write8(0x04000040 + offset, bus.read8(0x10000040 + offset))
                }
                // Record the actual staged words so the report can verify the code
                // that the CPU is about to execute, independently of the trace.
                for (offset in 0 until 0x40 step 4) {
                    payloadWords.add(
                        "SP_DMEM[0x" + (0x04000040 + offset).toString(16).uppercase() +
                        "]=0x" + bus.read32(0x04000040 + offset).toUInt().toString(16).uppercase().padStart(8, '0')
                    )
                }
                cpu.reset()
                cpu.tlbEnabled = true // KUSEG handoff targets must use architectural TLB translation.
                cpu.gpr[22] = cicSeed.toLong() // CIC seed ($s6), synthesized by HLE.
                cpu.gpr[29] = 0xFFFFFFFFA07FFFE0UL.toLong() // Initial 8 MiB RDRAM stack.
                cpu.setProgramCounter(0xFFFFFFFFA4000040UL.toLong())
                while (steps < maxSteps) {
                    val before = cpu.pc
                    val inStackRegion = before in 0x703A0000L..0x703BFFFFL
                    val nextCopyIteration = if (before == 0x70000588L) copyLoopIterations + 1 else 0L
                    // Register formatting allocates many strings. Capture it only
                    // where a diagnostic section will actually retain the values,
                    // rather than on all ~78k byte-copy iterations.
                    val captureCopySample = before == 0x70000588L &&
                        (nextCopyIteration <= 8L || nextCopyIteration % 4096L == 0L)
                    val captureBeforeRegisters =
                        firstTrace.size < 64 ||
                        captureCopySample ||
                        (copyLoopExitObserved && (postCopyTraceRemaining > 0 || postCopyTrace.isEmpty())) ||
                        (inStackRegion && (!stackRegionEntryObserved || stackRegionTrace.size < 128)) ||
                        (before in 0xFFFFFFFF80000410UL.toLong()..0xFFFFFFFF80000420UL.toLong() &&
                            loopEntryTrace.size < 24)
                    val registersBefore = if (captureBeforeRegisters || before == 0x70000500L) watchedRegisters() else ""
                    if (before == 0x70000500L) {
                        // 0x00E00008 is JR $a3; retain the source register before execution.
                        indirectJumpTarget = cpu.gpr[7]
                        indirectJumpRegisters = registersBefore
                    }
                    cpu.step()
                    // Capture complete GPR state in the actual loop found by the new log.
                    if (before in 0xFFFFFFFF80000130UL.toLong()..0xFFFFFFFF80000190UL.toLong() &&
                        postIpl3LoopTrace.size < 256
                    ) {
                        postIpl3LoopTrace.add(
                            "PC=" + hex(before) +
                            " INST=" + (cpu.diagnosticInstructionWord?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A") +
                            " NEXT_PC=" + hex(cpu.pc) +
                            " GPR{" + allRegisters() + "}"
                        )
                    }
                    // The observed byte-copy loop branches at 0x70000584 and
                    // executes its store in the delay slot at 0x70000588.
                    if (before == 0x70000588L) {
                        copyLoopIterations++
                        val branchTaken = cpu.pc == 0x70000574L
                        if (branchTaken) copyLoopTakenBranches++ else copyLoopExitObserved = true
                        // Keep the first samples, periodic checkpoints and final exit.
                        // Formatting every iteration made the diagnostic itself a
                        // major source of delay on mid-range Android devices.
                        if (captureCopySample || !branchTaken) {
                            val sample = "copyLoop iteration=$copyLoopIterations branchTaken=$branchTaken " +
                                "nextPC=${hex(cpu.pc)} BEFORE{${if (captureCopySample) registersBefore else "not sampled"}} " +
                                "AFTER{${watchedRegisters()}}"
                            if (copyLoopFirstTrace.size < 8) copyLoopFirstTrace.add(sample)
                            copyLoopRecentTrace.addLast(sample)
                            if (copyLoopRecentTrace.size > 16) copyLoopRecentTrace.removeFirst()
                        }
                    }
                    val word = cpu.diagnosticInstructionWord
                    // Formatting a diagnostic line allocates several strings. Most
                    // instructions are not retained, so only format sampled steps or
                    // steps belonging to a section that explicitly captures detail.
                    val captureInstructionText =
                        steps < 64 ||
                        (copyLoopExitObserved && (postCopyTraceRemaining > 0 || postCopyTrace.isEmpty())) ||
                        inStackRegion ||
                        (before in 0xFFFFFFFF80000410UL.toLong()..0xFFFFFFFF80000420UL.toLong() &&
                            loopEntryTrace.size < 24) ||
                        (steps and 0xFF) == 0
                    val instructionText = if (captureInstructionText) {
                        "step=$steps PC=" + hex(before) +
                            " INST=" + (word?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A") +
                            " NEXT_PC=" + hex(cpu.pc)
                    } else ""
                    if (copyLoopExitObserved && postCopyTraceRemaining == 0 && postCopyTrace.isEmpty()) {
                        postCopyTraceRemaining = 256
                    }
                    if (postCopyTraceRemaining > 0) {
                        postCopyTrace.add(
                            instructionText + " BEFORE{" + registersBefore + "} AFTER{" + watchedRegisters() + "}"
                        )
                        postCopyTraceRemaining--
                    }
                    // The previous run faulted at 0x703AB3F8, close to its stack
                    // pointer. Capture the first entry into that address window
                    // together with the preceding ring and the first instructions there.
                    if (before in 0x703A0000L..0x703BFFFFL) {
                        if (!stackRegionEntryObserved) {
                            stackRegionEntryObserved = true
                            stackRegionEntryContext.addAll(trace)
                            stackRegionEntryContext.add(
                                "FIRST STACK-REGION ENTRY: " + instructionText +
                                " BEFORE{" + registersBefore + "} AFTER{" + watchedRegisters() + "}"
                            )
                        }
                        if (stackRegionTrace.size < 128) {
                            stackRegionTrace.add(
                                instructionText + " BEFORE{" + registersBefore + "} AFTER{" + watchedRegisters() + "}"
                            )
                        }
                    }
                    if (firstTrace.size < 64) {
                        firstTrace.add(instructionText + " BEFORE{" + registersBefore + "} AFTER{" + watchedRegisters() + "}")
                    }
                    if (before in 0xFFFFFFFF80000410UL.toLong()..0xFFFFFFFF80000420UL.toLong() &&
                        loopEntryTrace.size < 24
                    ) {
                        loopEntryTrace.add(instructionText + " BEFORE{" + registersBefore + "} AFTER{" + watchedRegisters() + "}")
                    }
                    if (captureInstructionText) {
                        trace.addLast(instructionText)
                        if (trace.size > 80) trace.removeFirst()
                    }
                    steps++
                    if (cpu.lastExceptionCode != null) {
                        stopReason = "Exceção arquitetural: código=${cpu.lastExceptionCode}, PC=" +
                            hex(cpu.lastExceptionInstructionAddress ?: before)
                        break
                    }
                }
            } catch (error: Throwable) {
                stopReason = "Execução interrompida: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"}"
            }
            // Inspect the indirect jump target after execution stops; no per-step overhead.
            indirectJumpTarget?.let { target ->
                for (offset in -16L..32L step 4L) {
                    val address = target + offset
                    val word = try {
                        "0x" + cpu.readInstructionForDiagnostic(address).toUInt().toString(16).uppercase().padStart(8, '0')
                    } catch (error: Throwable) {
                        "ERRO(${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"})"
                    }
                    indirectTargetWords.add("VADDR=0x${address.toULong().toString(16).uppercase().padStart(16, '0')} INST=$word")
                }
            }
            // The observed descending memcpy reads KSEG0 0x80033F2E and writes
            // TLB-mapped 0x7020159F. IPL3 DMA-copied ROM offset 0x1000 to RDRAM
            // physical 0x400, so RDRAM address X corresponds to ROM address X+0xC00.
            val copySourceStart = 0x33F2E
            val copyDestinationStart = 0x20159F
            val copyLength = 0x131A0
            val copySourceEnd = copySourceStart - copyLength + 1
            val copyDestinationEnd = copyDestinationStart - copyLength + 1
            // Verify every byte of the descending IPL3 memory transfer.
            val copyVerificationDiagnostics = buildString {
                var sourceRomMismatches = 0
                var sourceDestinationMismatches = 0
                var readErrors = 0
                val samples = ArrayList<String>()
                for (index in 0 until copyLength) {
                    val sourceAddress = copySourceStart - index
                    val destinationAddress = copyDestinationStart - index
                    val source = try { bus.peek8ForDiagnostic(sourceAddress) } catch (error: Throwable) { -1 }
                    val rom = try { bus.peek8ForDiagnostic(N64Bus.CARTRIDGE_ROM_BASE + 0x0C00 + sourceAddress) } catch (error: Throwable) { -1 }
                    val destination = try { bus.peek8ForDiagnostic(destinationAddress) } catch (error: Throwable) { -1 }
                    if (source < 0 || destination < 0 || rom < 0) readErrors++
                    if (source >= 0 && rom >= 0 && source != rom) {
                        sourceRomMismatches++
                        if (samples.size < 12) samples.add("SRC/ROM off=0x${index.toString(16).uppercase()} src=0x${source.toString(16).uppercase().padStart(2, '0')} rom=0x${rom.toString(16).uppercase().padStart(2, '0')}")
                    }
                    if (source >= 0 && destination >= 0 && source != destination) {
                        sourceDestinationMismatches++
                        if (samples.size < 12) samples.add("SRC/DST off=0x${index.toString(16).uppercase()} src=0x${source.toString(16).uppercase().padStart(2, '0')} dst=0x${destination.toString(16).uppercase().padStart(2, '0')}")
                    }
                }
                append("Bytes verificados: ").append(copyLength).append("\n")
                append("Origem RDRAM diferente da ROM (+0xC00): ").append(sourceRomMismatches).append("\n")
                append("Destino diferente da origem: ").append(sourceDestinationMismatches).append("\n")
                append("Erros de leitura: ").append(readErrors).append("\n")
                append("Resultado: ").append(
                    if (readErrors == 0 && sourceRomMismatches == 0 && sourceDestinationMismatches == 0)
                        "PASS — ROM, origem e destino correspondem"
                    else "DIVERGÊNCIA — consultar amostras"
                ).append("\n")
                if (samples.isEmpty()) append("Nenhuma divergência amostrada.")
                else append(samples.joinToString("\n"))
            }
            val transferMemoryDiagnostics = buildString {
                append("CÓPIA DE MEMÓRIA — ORIGEM RDRAM / ROM (+0xC00) / DESTINO\n")
                append("Laço descendente observado: origem física 0x")
                    .append(copySourceStart.toString(16).uppercase())
                    .append(" -> destino físico 0x")
                    .append(copyDestinationStart.toString(16).uppercase())
                    .append(" | bytes=0x")
                    .append(copyLength.toString(16).uppercase())
                    .append("\n")
                append("Fim esperado: origem física 0x")
                    .append(copySourceEnd.toString(16).uppercase())
                    .append(" -> destino físico 0x")
                    .append(copyDestinationEnd.toString(16).uppercase())
                    .append("\n")
                append("AMOSTRA NO INÍCIO DA CÓPIA (offset relativo -8..+23):\n")
                for (offset in -8..23) {
                    val sourceAddress = copySourceStart + offset
                    val romAddress = N64Bus.CARTRIDGE_ROM_BASE + 0x0C00 + sourceAddress
                    val destinationAddress = copyDestinationStart + offset
                    val sourceByte = try { bus.peek8ForDiagnostic(sourceAddress) } catch (error: Throwable) { -1 }
                    val romByte = try { bus.peek8ForDiagnostic(romAddress) } catch (error: Throwable) { -1 }
                    val destinationByte = try { bus.peek8ForDiagnostic(destinationAddress) } catch (error: Throwable) { -1 }
                    append("off=").append(offset)
                        .append(" SRC_RDRAM[0x").append(sourceAddress.toString(16).uppercase()).append("]=0x")
                        .append(if (sourceByte >= 0) sourceByte.toString(16).uppercase().padStart(2, '0') else "ERRO")
                        .append(" ROM[0x").append((sourceAddress + 0x0C00).toString(16).uppercase()).append("]=0x")
                        .append(if (romByte >= 0) romByte.toString(16).uppercase().padStart(2, '0') else "ERRO")
                        .append(" DST[0x").append(destinationAddress.toString(16).uppercase()).append("]=0x")
                        .append(if (destinationByte >= 0) destinationByte.toString(16).uppercase().padStart(2, '0') else "ERRO")
                        .append("\n")
                }
                append("AMOSTRA NO FIM DA CÓPIA (offset relativo -8..+23):\n")
                for (offset in -8..23) {
                    val sourceAddress = copySourceEnd + offset
                    val destinationAddress = copyDestinationEnd + offset
                    val sourceByte = try { bus.peek8ForDiagnostic(sourceAddress) } catch (error: Throwable) { -1 }
                    val romByte = try { bus.peek8ForDiagnostic(N64Bus.CARTRIDGE_ROM_BASE + 0x0C00 + sourceAddress) } catch (error: Throwable) { -1 }
                    val destinationByte = try { bus.peek8ForDiagnostic(destinationAddress) } catch (error: Throwable) { -1 }
                    append("off=").append(offset)
                        .append(" SRC_RDRAM[0x").append(sourceAddress.toString(16).uppercase()).append("]=0x")
                        .append(if (sourceByte >= 0) sourceByte.toString(16).uppercase().padStart(2, '0') else "ERRO")
                        .append(" ROM[0x").append((sourceAddress + 0x0C00).toString(16).uppercase()).append("]=0x")
                        .append(if (romByte >= 0) romByte.toString(16).uppercase().padStart(2, '0') else "ERRO")
                        .append(" DST[0x").append(destinationAddress.toString(16).uppercase()).append("]=0x")
                        .append(if (destinationByte >= 0) destinationByte.toString(16).uppercase().padStart(2, '0') else "ERRO")
                        .append("\n")
                }
            }
            val report = "BOOT HLE EXPERIMENTAL — SEM PIF ROM" +
                "\nPIF ROM externa: não utilizada" +
                "\nCIC seed detectado: " + (cicSeed?.let { "0x" + it.toString(16).uppercase() } ?: "não reconhecido") +
                "\nIPL3 preparado: 0xFC0 bytes do cartucho (offset 0x40) para SP DMEM 0x04000040" +
                "\nPC inicial: 0xFFFFFFFFA4000040" +
                "\nInstruções executadas: $steps" +
                "\nMotivo da parada: $stopReason" +
                "\nPC final: " + hex(cpu.pc) +
                "\nRegistradores finais: " + watchedRegisters() +
                "\nCOP0 Status=0x" + cpu.cop0[12].toULong().toString(16).uppercase() +
                " Cause=0x" + cpu.cop0[13].toULong().toString(16).uppercase() +
                " EPC=0x" + cpu.cop0[14].toULong().toString(16).uppercase() +
                " BadVAddr=0x" + cpu.cop0[8].toULong().toString(16).uppercase() +
                "\nÚltima falha TLB: VADDR=0x" + (cpu.lastTlbFaultVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A") +
                " tipo=" + (cpu.lastTlbFaultAccessType ?: "N/A") +
                "\nEstado do endereço: " + cpu.describeTlbAddressForDiagnostic(cpu.lastTlbFaultVirtualAddress ?: cpu.pc) +
                "\nÚltimo acesso de memória: VADDR=0x" + (cpu.diagnosticVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A") +
                " PADDR=" + (cpu.diagnosticPhysicalAddress?.let { "0x" + it.toUInt().toString(16).uppercase() } ?: "N/A") +
                " tipo=" + (cpu.diagnosticAccessType ?: "N/A") +
                "\n\nESCRITAS TLB EXECUTADAS:\n" +
                (if (cpu.diagnosticTlbWriteHistory.isEmpty()) "(nenhuma)" else cpu.diagnosticTlbWriteHistory.joinToString("\n")) +
                "\n\nENTRADAS TLB NO MOMENTO DA FALHA:\n" + cpu.lastTlbEntrySnapshot +
                "\n\nSALTO INDIRETO JR registrador 7 EM 0x70000500:\n" +
                (indirectJumpTarget?.let { target ->
                    "Registradores antes do JR: $indirectJumpRegisters\n" +
                    "Destino virtual de registrador 7: 0x${target.toULong().toString(16).uppercase()}\n" +
                    "Mapeamento: ${cpu.describeTlbAddressForDiagnostic(target)}\n" +
                    "Palavras ao redor do destino (-16..+32):\n" + indirectTargetWords.joinToString("\n")
                } ?: "Instrução JR registrador 7 não foi observada.") +
                "\n\nIPL3 REAL PREPARADO NA SP DMEM (primeiros 64 bytes):\n" + payloadWords.joinToString("\n") +
                "\n\nPRIMEIRAS INSTRUÇÕES COM REGISTRADORES (máximo 64):\n" + firstTrace.joinToString("\n") +
                "\n\nPRIMEIRAS PASSAGENS NO LAÇO 0x80000410–0x80000420:\n" + loopEntryTrace.joinToString("\n") +
                "\n\nLAÇO REAL PÓS-IPL3 0x80000130–0x80000190 (registradores completos):\n" +
                (if (postIpl3LoopTrace.isEmpty()) "(não observado)" else postIpl3LoopTrace.joinToString("\n")) +
                "\n\nDIAGNÓSTICO DO LAÇO DE CÓPIA 0x70000574–0x70000588:\n" +
                "Iterações observadas: $copyLoopIterations" +
                "\nDesvios que retornaram ao início: $copyLoopTakenBranches" +
                "\nSaída do laço observada: $copyLoopExitObserved" +
                "\nPrimeiras passagens:\n" + copyLoopFirstTrace.joinToString("\n") +
                "\nÚltimas passagens:\n" + copyLoopRecentTrace.joinToString("\n") +
                "\n\nPRIMEIRAS 256 INSTRUÇÕES APÓS A SAÍDA DO LAÇO:\n" +
                (if (postCopyTrace.isEmpty()) "(saída não capturada)" else postCopyTrace.joinToString("\n")) +
                "\n\nCONTEXTO DA PRIMEIRA ENTRADA NA REGIÃO 0x703A0000–0x703BFFFF:\n" +
                (if (!stackRegionEntryObserved) "(região não visitada)" else stackRegionEntryContext.joinToString("\n")) +
                "\n\nPRIMEIRAS INSTRUÇÕES NA REGIÃO DA PILHA (máximo 128):\n" +
                (if (stackRegionTrace.isEmpty()) "(nenhuma)" else stackRegionTrace.joinToString("\n")) +
                "\n\nÚLTIMAS INSTRUÇÕES (máximo 80):\n" + trace.joinToString("\n") +
                "\n\nREGISTRADORES FINAIS: " + watchedRegisters() +
                "\\n\\nVERIFICAÇÃO INTEGRAL DA CÓPIA:\\n" + copyVerificationDiagnostics +
                "\\n\\n" + transferMemoryDiagnostics +
                "\n\nHISTÓRICO DE DMA PI (até 64 solicitações):\n" +
                (if (bus.diagnosticPiDmaHistory.isEmpty()) "(nenhuma transferência PI cartucho -> RDRAM observada)" else bus.diagnosticPiDmaHistory.joinToString("\n")) +
                "\n\nHISTÓRICO DE DMA SP (até 64 transferências):\n" +
                (if (bus.diagnosticSpDmaHistory.isEmpty()) "(nenhuma transferência SP observada)" else bus.diagnosticSpDmaHistory.joinToString("\n")) +
                "\n\nACESSOS À JANELA DE ORIGEM RDRAM (0x33D00–0x33FFF):\n" +
                (if (bus.diagnosticBootMemoryAccessHistory.isEmpty()) "(nenhum acesso observado)" else bus.diagnosticBootMemoryAccessHistory.joinToString("\n")) +
                "\n\nESCRITAS CPU NA REGIÃO DE DESTINO 0x1EE400–0x20159F:\n" +
                "Total de bytes gravados: " + bus.diagnosticBootDestinationWriteCount + "\n" +
                (if (bus.diagnosticBootDestinationWriteHistory.isEmpty()) "(nenhuma escrita observada nessa região)" else bus.diagnosticBootDestinationWriteHistory.joinToString("\n")) +
                "\n\nESCRITAS CPU EM TODA A RDRAM:\n" +
                "Total de bytes gravados: " + bus.diagnosticRdramWriteCount + "\n" +
                (if (bus.diagnosticRdramWriteHistory.isEmpty()) "(nenhuma escrita CPU na RDRAM)" else bus.diagnosticRdramWriteHistory.joinToString("\n")) +
                "\n\nLimitações: HLE inicial de handoff, não emula PIF/Joybus/CIC completo, RSP, GPU/VI, áudio ou controles. A execução da CPU não comprova que o jogo esteja jogável."
            runOnUiThread {
                hleBootButton?.isEnabled = cartridgeBus != null
                romTraceButton?.isEnabled = cartridgeBus != null
                showRomDiagnosticPage(report)
            }
        }, "N64-HLE-Boot-Diagnostic").start()
    }

    private fun runRomInstructionDiagnostic() {
        val bus = cartridgeBus
        if (bus == null) {
            status.text = "Selecione uma ROM primeiro."
            return
        }
        romTraceButton?.isEnabled = false
        status.text = "Executando diagnóstico em segundo plano. Aguarde; a tela continuará responsiva."
        Thread({
            try {
                runRomInstructionDiagnosticWorker(bus)
            } catch (error: Throwable) {
                val message = "Falha no diagnóstico: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"}"
                runOnUiThread {
                    status.text = message + "\nA ROM permanece selecionada se o processo não tiver sido reiniciado."
                    romTraceButton?.isEnabled = cartridgeBus != null
                }
            }
        }, "N64-ROM-Diagnostic").start()
    }

    private fun runRomInstructionDiagnosticWorker(bus: N64Bus) {
        // IPL3 is stored in the cartridge image at offset 0x40, but real N64
        // boot executes it from SP scratch memory. Stage the 0xFC0-byte bootcode
        // into SP DMEM at physical 0x04000040 before running the diagnostic.
        // This models only the copy/staging step; it is NOT a full PIF/CIC boot.
        val ipl3Length = 0xFC0
        try {
            for (offset in 0 until ipl3Length) {
                val byteValue = bus.read8(0x10000040 + offset)
                bus.write8(0x04000040 + offset, byteValue)
            }
        } catch (error: Exception) {
            runOnUiThread {
                status.text = "Não foi possível preparar IPL3 na SP DMEM: ${error.message ?: error.javaClass.simpleName}"
                romTraceButton?.isEnabled = true
            }
            return
        }
        // Keep enough execution budget to finish the IPL3 copy loop, while
        // retaining only the last 80 instructions so copied logs stay readable.
        val trace = ArrayDeque<String>()
        // Preserve every recent executed instruction, including the instruction that
        // produced an invalid next PC. The periodic trace alone can miss abrupt jumps.
        val recentInstructionTrace = ArrayDeque<String>()
        // Keep a compact register timeline around the observed IPL3 copy loop.
        // The final register dump alone cannot explain why a branch repeats.
        val loopCheckpoints = ArrayDeque<String>()
        // Preserve the first instructions after IPL3 hands control to the game.
        // The end-of-run trace alone can hide the transition into the looping routine.
        val handoffTrace = ArrayDeque<String>()
        var handoffTraceCount = 0
        // Capture the first pass through the suspected post-handoff loop, not only
        // periodic samples thousands of instructions later.
        val firstLoopTrace = ArrayDeque<String>()
        // Capture the first entry into the IPL3 terminal/error path before its self-loop overwrites context.
        val terminalPathTrace = ArrayDeque<String>()
        var terminalPathCaptureStarted = false
        val cicSeed = bus.detectCicSeed()
        if (cicSeed == null) {
            runOnUiThread {
                status.text = "CIC/seed do IPL3 não identificado. O diagnóstico foi interrompido para evitar um falso erro de checksum; por enquanto, só CIC 6102/7101 e 6105/7105 são reconhecidos."
                romTraceButton?.isEnabled = cartridgeBus != null
            }
            return
        }
        val cpu = R4300Cpu(bus)
        // A real R4300 translates KUSEG addresses through the TLB. Keep the CPU's
        // compatibility fallback for isolated unit tests, but do not use it when
        // executing a cartridge diagnostic: otherwise a jump to 0x70000510 is
        // incorrectly treated as a physical bus address instead of a virtual one.
        cpu.tlbEnabled = true
        // PIF/CIC normally supplies the seed in s6 ($22). This diagnostic skips
        // the real PIF boot, so derive that seed from the IPL3 signature instead.
        cpu.gpr[22] = cicSeed.toLong()
        // This is an artificial IPL3 diagnostic, not a real PIF/CIC boot.
        // The R4300 reset state leaves GPR[29] at zero; the copied routine later
        // uses the stack, so seed SP to the top of the installed 8 MiB RDRAM.
        // KSEG1 0xA07FFFE0 maps to physical RDRAM 0x007FFFE0.
        cpu.gpr[29] = 0xFFFFFFFFA07FFFE0UL.toLong()
        cpu.setProgramCounter(0xFFFFFFFFA4000040UL.toLong())
        var executed = 0
        // Count recurring PCs in the post-IPL3 0x70000000 region. The latest
        // report reaches 0x7020090C without an exception but cycles through several
        // routines, so a two-PC detector alone cannot identify the hot loop.
        // Cap unique addresses to keep memory bounded on mid-range Android devices.
        val postHandoffPcVisits = LinkedHashMap<Long, Int>()
        val postHandoffPcRangeStart = 0x70000000L
        val postHandoffPcRangeEnd = 0x70400000L
        val maxTrackedPostHandoffPcs = 2048
        // Capture sparse progress checkpoints so repeated hot PCs can be distinguished
        // from a genuinely stuck loop by observing changing pointers/counters.
        val postHandoffProgressTrace = ArrayDeque<String>()
        var nextPostHandoffProgressStep = 250_000
        // Detect a tight two-PC loop early instead of spending millions of steps
        // repeatedly executing a branch and its delay slot. Keep the full trace
        // and register dump so the first stable loop can be diagnosed.
        var previousPc = Long.MIN_VALUE
        var twoStepsAgoPc = Long.MIN_VALUE
        var repeatedTwoPcVisits = 0
        // The BNE at 0x8000018C compares t0 with ra (0x00100000), not with t1).
        // t0 advances by four bytes, so this routine can need about 262,144 iterations
        // (~5.2 million instructions) before reaching its end. Keep a bounded 6M-step test.
        val maxSteps = 12_000_000
        var stopReason = "Limite de $maxSteps passos alcançado (rastro amostrado a cada 256 instruções)."
        var lastAttemptedPc = cpu.pc
        var lastAttemptedInstruction: Int? = null
        // Keep a low-allocation ring buffer of the instructions immediately before a fault.
        // This captures the jump/return that led to an invalid virtual fetch without
        // formatting millions of log strings during the long ROM diagnostic.
        val faultHistoryCapacity = 32
        val faultHistoryPc = LongArray(faultHistoryCapacity)
        val faultHistoryWord = IntArray(faultHistoryCapacity) { Int.MIN_VALUE }
        val faultHistoryNextPc = LongArray(faultHistoryCapacity)
        val faultHistoryT0 = LongArray(faultHistoryCapacity)
        val faultHistoryT1 = LongArray(faultHistoryCapacity)
        val faultHistoryT2 = LongArray(faultHistoryCapacity)
        val faultHistoryT3 = LongArray(faultHistoryCapacity)
        val faultHistoryRa = LongArray(faultHistoryCapacity)
        // CPU captures JR/JALR targets directly; avoid copying 31 GPRs on every step.
        val faultHistoryIndirectTarget = LongArray(faultHistoryCapacity)
        var faultHistoryCount = 0
        var faultHistoryNext = 0
        try {
            for (stepIndex in 0 until maxSteps) {
                val pc = cpu.pc
                lastAttemptedPc = pc
                if (stepIndex >= nextPostHandoffProgressStep &&
                    pc >= postHandoffPcRangeStart && pc < postHandoffPcRangeEnd
                ) {
                    val checkpointInstruction = try {
                        cpu.readInstructionForDiagnostic(pc).toUInt().toString(16).uppercase().padStart(8, '0')
                    } catch (_: Exception) {
                        "unavailable"
                    }
                    val watchedRegisters = listOf(
                        "v0" to 2, "v1" to 3, "a0" to 4, "a1" to 5, "a2" to 6, "a3" to 7,
                        "t0" to 8, "t1" to 9, "t2" to 10, "t3" to 11,
                        "s0" to 16, "s1" to 17, "s2" to 18, "sp" to 29, "ra" to 31
                    ).joinToString(" ") { (name, index) ->
                        name + "=0x" + cpu.gpr[index].toULong().toString(16).uppercase()
                    }
                    postHandoffProgressTrace.addLast(
                        "step=$stepIndex PC=0x" + pc.toULong().toString(16).uppercase().padStart(8, '0') +
                            " INST=0x" + checkpointInstruction + " " + watchedRegisters
                    )
                    if (postHandoffProgressTrace.size > 32) postHandoffProgressTrace.removeFirst()
                    nextPostHandoffProgressStep += 250_000
                }
                // PC-frequency diagnostics are sampled; they are not architectural state.
                if (stepIndex % 256 == 0 &&
                    pc >= postHandoffPcRangeStart && pc < postHandoffPcRangeEnd &&
                    (postHandoffPcVisits.containsKey(pc) || postHandoffPcVisits.size < maxTrackedPostHandoffPcs)
                ) {
                    postHandoffPcVisits[pc] = (postHandoffPcVisits[pc] ?: 0) + 1
                }
                if (pc == twoStepsAgoPc && pc != previousPc) {
                    repeatedTwoPcVisits++
                } else {
                    repeatedTwoPcVisits = 0
                }
                if (repeatedTwoPcVisits >= 4096) {
                    stopReason = "Ciclo repetitivo de dois PCs detectado em torno de 0x${pc.toULong().toString(16).uppercase()}; interrompido após confirmar repetição estável."
                    break
                }
                twoStepsAgoPc = previousPc
                previousPc = pc

                val inIpl3Loop = pc in 0xFFFFFFFFA4000498UL.toLong()..0xFFFFFFFFA40004B8UL.toLong()
                val inPostHandoffLoop = pc in 0xFFFFFFFF80000130UL.toLong()..0xFFFFFFFF80000190UL.toLong()
                // Most instructions are not included in the bounded report. Avoid fetching
                // each one twice (once for logging and again inside cpu.step()), which made
                // multi-million-step diagnostics unnecessarily slow on phones. The CPU still
                // performs the authoritative fetch and raises any architectural TLB exception.
                val needsInstructionForTrace =
                    ((inIpl3Loop || inPostHandoffLoop) && stepIndex % 4096 == 0) ||
                    (pc in 0xFFFFFFFF80000000UL.toLong()..0xFFFFFFFF8000012CUL.toLong() && handoffTraceCount < 64) ||
                    (inPostHandoffLoop && firstLoopTrace.size < 48) ||
                    (pc in 0xFFFFFFFF800001C0UL.toLong()..0xFFFFFFFF800001D0UL.toLong() && terminalPathTrace.size < 32) ||
                    stepIndex % 256 == 0 || stepIndex >= maxSteps - 80
                val instruction = if (needsInstructionForTrace) {
                    try {
                        cpu.readInstructionForDiagnostic(pc)
                    } catch (_: R4300Tlb.TlbFault) {
                        0 // cpu.step() records the architectural TLB exception below.
                    }
                } else {
                    0
                }
                if (needsInstructionForTrace) lastAttemptedInstruction = instruction
                if ((inIpl3Loop || inPostHandoffLoop) && stepIndex % 4096 == 0) {
                    // Sample loop state periodically to avoid millions of temporary strings/allocations.
                    // The previous window stopped at 0x4B0 and could hide the final branch/jump
                    // sequence at 0x4B4/0x4B8 that determines whether the loop exits.
                    val physicalPc = ((pc and 0xFFFFFFFFL) and 0x1FFFFFFFL).toInt()
                    val loopInstruction = bus.read32(physicalPc).toUInt().toString(16).uppercase().padStart(8, '0')
                    loopCheckpoints.addLast(
                        "PC=0x" + pc.toULong().toString(16).uppercase().padStart(16, '0') +
                        " INST=0x" + loopInstruction +
                        " t0=0x" + cpu.gpr[8].toULong().toString(16).uppercase() +
                        " t1=0x" + cpu.gpr[9].toULong().toString(16).uppercase() +
                        " t2=0x" + cpu.gpr[10].toULong().toString(16).uppercase() +
                        " t3=0x" + cpu.gpr[11].toULong().toString(16).uppercase() +
                        " at=0x" + cpu.gpr[1].toULong().toString(16).uppercase() +
                        " t5=0x" + cpu.gpr[13].toULong().toString(16).uppercase() +
                        " RDRAM[02B4]=0x" + bus.read32(0x02B4).toUInt().toString(16).uppercase().padStart(8, '0')
                    )
                    if (loopCheckpoints.size > 24) loopCheckpoints.removeFirst()
                }
                // Do not stop merely because PC equals 0x80000180: the previous
                // diagnostic reached it sequentially from 0x8000017C, while COP0 stayed zero.
                // A real exception entry must set Status.EXL before we classify this as a vector.
                // Capture the initial KSEG0 handoff path, with a strict allocation cap.
                if (pc in 0xFFFFFFFF80000000UL.toLong()..0xFFFFFFFF8000012CUL.toLong() &&
                    handoffTraceCount < 64
                ) {
                    handoffTrace.addLast(
                        "step=$stepIndex PC=0x" + pc.toULong().toString(16).uppercase().padStart(16, '0') +
                        " INST=0x" + instruction.toUInt().toString(16).uppercase().padStart(8, '0') +
                        " t0=0x" + cpu.gpr[8].toULong().toString(16).uppercase() +
                        " t1=0x" + cpu.gpr[9].toULong().toString(16).uppercase() +
                        " t2=0x" + cpu.gpr[10].toULong().toString(16).uppercase() +
                        " t3=0x" + cpu.gpr[11].toULong().toString(16).uppercase()
                    )
                    handoffTraceCount++
                }
                if (inPostHandoffLoop && firstLoopTrace.size < 48) {
                    firstLoopTrace.addLast(
                        "step=$stepIndex PC=0x" + pc.toULong().toString(16).uppercase().padStart(16, '0') +
                        " INST=0x" + instruction.toUInt().toString(16).uppercase().padStart(8, '0') +
                        " v0=0x" + cpu.gpr[2].toULong().toString(16).uppercase() +
                        " at=0x" + cpu.gpr[1].toULong().toString(16).uppercase() +
                        " a0=0x" + cpu.gpr[4].toULong().toString(16).uppercase() +
                        " a1=0x" + cpu.gpr[5].toULong().toString(16).uppercase() +
                        " a2=0x" + cpu.gpr[6].toULong().toString(16).uppercase() +
                        " a3=0x" + cpu.gpr[7].toULong().toString(16).uppercase() +
                        " t0=0x" + cpu.gpr[8].toULong().toString(16).uppercase() +
                        " t1=0x" + cpu.gpr[9].toULong().toString(16).uppercase() +
                        " t2=0x" + cpu.gpr[10].toULong().toString(16).uppercase() +
                        " t3=0x" + cpu.gpr[11].toULong().toString(16).uppercase() +
                        " t4=0x" + cpu.gpr[12].toULong().toString(16).uppercase() +
                        " t5=0x" + cpu.gpr[13].toULong().toString(16).uppercase() +
                        " t6=0x" + cpu.gpr[14].toULong().toString(16).uppercase() +
                        " t7=0x" + cpu.gpr[15].toULong().toString(16).uppercase() +
                        " s0=0x" + cpu.gpr[16].toULong().toString(16).uppercase() +
                        " s1=0x" + cpu.gpr[17].toULong().toString(16).uppercase() +
                        " s2=0x" + cpu.gpr[18].toULong().toString(16).uppercase()
                    )
                }
                // Capture only the first few instructions around 0x800001C0, where this ROM
                // currently enters a stable branch loop, so the report preserves its cause.
                if (pc in 0xFFFFFFFF800001C0UL.toLong()..0xFFFFFFFF800001D0UL.toLong() && terminalPathTrace.size < 32) {
                    terminalPathCaptureStarted = true
                    terminalPathTrace.addLast(
                        "step=$stepIndex PC=0x" + pc.toULong().toString(16).uppercase().padStart(16, '0') +
                        " INST=0x" + instruction.toUInt().toString(16).uppercase().padStart(8, '0') +
                        " v0=0x" + cpu.gpr[2].toULong().toString(16).uppercase() +
                        " ra=0x" + cpu.gpr[31].toULong().toString(16).uppercase() +
                        " t0=0x" + cpu.gpr[8].toULong().toString(16).uppercase() +
                        " t1=0x" + cpu.gpr[9].toULong().toString(16).uppercase()
                    )
                }
                // Sampling prevents heavy allocation churn during long diagnostics on Android phones.
                if (stepIndex % 256 == 0 || stepIndex >= maxSteps - 80) {
                    trace.addLast(
                        "step=$stepIndex PC=0x" + pc.toULong().toString(16).uppercase().padStart(16, '0') +
                        "  INST=0x" + instruction.toUInt().toString(16).uppercase().padStart(8, '0')
                    )
                    if (trace.size > 80) trace.removeFirst()
                }
                if (stepIndex > 0 && stepIndex % 50000 == 0) {
                    runOnUiThread {
                        status.text = "Diagnóstico em andamento: $stepIndex / $maxSteps instruções. ROM mantida carregada."
                    }
                }
                cpu.step()
                executed++
                // Record primitive values only; format strings later, only if we need the report.
                faultHistoryPc[faultHistoryNext] = pc
                faultHistoryWord[faultHistoryNext] = cpu.diagnosticInstructionWord ?: Int.MIN_VALUE
                faultHistoryNextPc[faultHistoryNext] = cpu.pc
                faultHistoryIndirectTarget[faultHistoryNext] = cpu.diagnosticLastIndirectTarget ?: Long.MIN_VALUE
                faultHistoryT0[faultHistoryNext] = cpu.gpr[8]
                faultHistoryT1[faultHistoryNext] = cpu.gpr[9]
                faultHistoryT2[faultHistoryNext] = cpu.gpr[10]
                faultHistoryT3[faultHistoryNext] = cpu.gpr[11]
                faultHistoryRa[faultHistoryNext] = cpu.gpr[31]
                faultHistoryNext = (faultHistoryNext + 1) % faultHistoryCapacity
                if (faultHistoryCount < faultHistoryCapacity) faultHistoryCount++
                // Building a long diagnostic string after EVERY instruction creates
                // millions of temporary objects on mid-range phones. Keep this bounded
                // trace sampled (the separate early-stop reason preserves the failing PC).
                if (stepIndex % 256 == 0 || stepIndex >= maxSteps - 80) {
                    recentInstructionTrace.addLast(
                        "step=$stepIndex PC=0x" + pc.toULong().toString(16).uppercase().padStart(16, '0') +
                        " INST=0x" + instruction.toUInt().toString(16).uppercase().padStart(8, '0') +
                        " NEXT_PC=0x" + cpu.pc.toULong().toString(16).uppercase().padStart(16, '0') +
                        " t0=0x" + cpu.gpr[8].toULong().toString(16).uppercase() +
                        " t1=0x" + cpu.gpr[9].toULong().toString(16).uppercase() +
                        " t2=0x" + cpu.gpr[10].toULong().toString(16).uppercase() +
                        " t3=0x" + cpu.gpr[11].toULong().toString(16).uppercase() +
                        " v1=0x" + cpu.gpr[3].toULong().toString(16).uppercase() +
                        " ra=0x" + cpu.gpr[31].toULong().toString(16).uppercase()
                    )
                    if (recentInstructionTrace.size > 80) recentInstructionTrace.removeFirst()
                }
                if (recentInstructionTrace.size > 80) recentInstructionTrace.removeFirst()
                // Stop on the first architectural exception, not after executing the
                // exception handler. Continuing at the vector can run millions of instructions
                // and overwrite the useful fault context on slower phones.
                if (cpu.lastExceptionCode != null) {
                    val faultPc = cpu.lastExceptionInstructionAddress
                        ?.toULong()?.toString(16)?.uppercase() ?: "N/A"
                    val faultWord = cpu.lastExceptionInstructionWord
                        ?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"
                    stopReason = "Primeira exceção arquitetural registrada; parada imediata para preservar a falha: código=${cpu.lastExceptionCode}, PC=0x$faultPc, INST=0x$faultWord."
                    break
                }
            }
        } catch (error: Exception) {
            val failurePc = cpu.diagnosticInstructionAddress ?: lastAttemptedPc
            val failureInstruction = cpu.diagnosticInstructionWord ?: lastAttemptedInstruction
            val failedPc = failurePc.toULong().toString(16).uppercase().padStart(16, '0')
            val failedInstruction = failureInstruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"
            val virtualAddress = cpu.diagnosticVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"
            val physicalAddress = cpu.diagnosticPhysicalAddress?.toUInt()?.toString(16)?.uppercase() ?: "N/A"
            val accessType = cpu.diagnosticAccessType ?: "N/A"
            stopReason = "Parou em erro/área não implementada: ${error.message ?: error.javaClass.simpleName}; PC=0x$failedPc INST=0x$failedInstruction; acesso=$accessType VADDR=0x$virtualAddress PADDR=0x$physicalAddress"
        }
        val causeValue = cpu.cop0[13]
        val causeCode = ((causeValue ushr 2) and 0x1FL).toInt()
        val causeName = when (causeCode) {
            1 -> "TLB Modified"
            2 -> "TLB Load/Fetch Miss ou Invalid"
            3 -> "TLB Store Miss ou Invalid"
            4 -> "Address Error Load/Fetch"
            5 -> "Address Error Store"
            8 -> "Syscall"
            9 -> "Breakpoint"
            10 -> "Reserved Instruction"
            12 -> "Arithmetic Overflow"
            else -> "Exceção MIPS código $causeCode"
        }
        val branchDelay = (causeValue and 0x80000000L) != 0L
        val faultHistoryTrace = (0 until faultHistoryCount).map { offset ->
            val index = (faultHistoryNext - faultHistoryCount + offset + faultHistoryCapacity) % faultHistoryCapacity
            "PC=0x" + faultHistoryPc[index].toULong().toString(16).uppercase().padStart(16, '0') +
                " INST=" + (if (faultHistoryWord[index] == Int.MIN_VALUE) "N/A" else
                    "0x" + faultHistoryWord[index].toUInt().toString(16).uppercase().padStart(8, '0')) +
                " NEXT_PC=0x" + faultHistoryNextPc[index].toULong().toString(16).uppercase().padStart(16, '0') +
                " t0=0x" + faultHistoryT0[index].toULong().toString(16).uppercase() +
                " t1=0x" + faultHistoryT1[index].toULong().toString(16).uppercase() +
                " t2=0x" + faultHistoryT2[index].toULong().toString(16).uppercase() +
                " t3=0x" + faultHistoryT3[index].toULong().toString(16).uppercase() +
                " ra=0x" + faultHistoryRa[index].toULong().toString(16).uppercase()
        }.joinToString("\\n")
        // Decode the control transfer immediately before the delay slot when the next
        // fetch faults. Cover direct J/JAL and indirect JR/JALR instructions.
        val transferAnalysis = run {
            if (faultHistoryCount < 3) {
                "Não há instruções suficientes no rastro para decodificar a transferência."
            } else {
                val jumpIndex = (faultHistoryNext - 3 + faultHistoryCapacity) % faultHistoryCapacity
                val jumpPc = faultHistoryPc[jumpIndex]
                val jumpInstruction = faultHistoryWord[jumpIndex]
                val opcode = if (jumpInstruction == Int.MIN_VALUE) -1 else jumpInstruction ushr 26
                val function = jumpInstruction and 0x3F
                val rs = (jumpInstruction ushr 21) and 0x1F
                val decoded = when {
                    opcode == 2 || opcode == 3 -> {
                        val target = ((jumpPc + 4L) and -0x10000000L) or
                            ((jumpInstruction.toLong() and 0x03FFFFFFL) shl 2)
                        (if (opcode == 3) "JAL" else "J") to target
                    }
                    opcode == 0 && (function == 0x08 || function == 0x09) -> {
                        val target = faultHistoryIndirectTarget[jumpIndex]
                        (if (function == 0x09) "JALR (rs=$rs)" else "JR (rs=$rs)") to target
                    }
                    else -> null
                }
                if (decoded != null) {
                    val (kind, target) = decoded
                    val virtual32 = target and 0xFFFFFFFFL
                    val segment = (virtual32 ushr 29) and 0x7L
                    val region = when (segment) {
                        4L -> "KSEG0: mapeamento direto"
                        5L -> "KSEG1: mapeamento direto"
                        in 0L..3L -> "KUSEG: requer tradução TLB"
                        6L -> "KSEG2: requer tradução TLB"
                        else -> "KSEG3: requer tradução TLB"
                    }
                    "$kind em PC=0x${jumpPc.toULong().toString(16).uppercase()}: destino calculado=0x${target.toULong().toString(16).uppercase()}; $region"
                } else {
                    "A instrução antes do delay slot não é J/JAL/JR/JALR (opcode=${if (opcode < 0) "N/A" else opcode.toString()}, funct=0x${function.toString(16)}); transferência não decodificada."
                }
            }
        }
        // For TLB exceptions, COP0 BadVAddr is authoritative. A later fetch of the
        // exception vector may overwrite the last attempted-access diagnostic address.
        val tlbFaultAddress = if (causeCode == 2 || causeCode == 3) {
            cpu.lastTlbFaultVirtualAddress ?: cpu.cop0[8]
        } else {
            cpu.diagnosticVirtualAddress ?: cpu.cop0[8]
        }
        var diagnostic = "DIAGNÓSTICO PARCIAL DA ROM" +
            "\nPassos executados: $executed" +
            "\nPC final: 0x${cpu.pc.toULong().toString(16).uppercase()}" +
            "\nMotivo: $stopReason" +
            "\nCOP0 Status: 0x${cpu.cop0[12].toULong().toString(16).uppercase()}" +
            "\nCOP0 Cause: 0x${cpu.cop0[13].toULong().toString(16).uppercase()}" +
            "\nCause decodificado: $causeName; BD (delay slot)=$branchDelay; ExcCode=$causeCode" +
            "\nCOP0 EPC: 0x${cpu.cop0[14].toULong().toString(16).uppercase()}" +
            "\nCOP0 BadVAddr: 0x${cpu.cop0[8].toULong().toString(16).uppercase()}" +
            "\nÚltimo acesso tentado: VADDR=0x" + (cpu.diagnosticVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A") +
            "; PADDR=" + (cpu.diagnosticPhysicalAddress?.let { "0x" + it.toUInt().toString(16).uppercase() } ?: "N/A") +
            "; tipo=" + (cpu.diagnosticAccessType ?: "N/A") +
            "\nTipo de acesso da exceção: " + (cpu.lastExceptionAccessType ?: "N/A") +
            "\nÚltima falha TLB preservada: VADDR=0x" +
                (cpu.lastTlbFaultVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A") +
                "; tipo=" + (cpu.lastTlbFaultAccessType ?: "N/A") +
                "; motivo=" + (cpu.lastTlbFaultKind?.name ?: "N/A") +
            "\nEstado TLB no endereço da falha: " + cpu.describeTlbAddressForDiagnostic(tlbFaultAddress) +
            "\n\nENTRADAS TLB ATIVAS NO MOMENTO DA FALHA:\n" + cpu.lastTlbEntrySnapshot +
            "\n\nGRAVAÇÕES TLBWI/TLBWR (últimas 64):\n" + (if (cpu.diagnosticTlbWriteHistory.isEmpty()) "(nenhuma gravação TLB registrada)" else cpu.diagnosticTlbWriteHistory.joinToString("\n")) +
            "\nÚltima exceção registrada: código=" + (cpu.lastExceptionCode?.toString() ?: "nenhuma") +
            " PC=0x" + (cpu.lastExceptionInstructionAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A") +
            " INST=0x" + (cpu.lastExceptionInstructionWord?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A") +
            " delay-slot da branch=0x" + (cpu.lastExceptionBranchAddress?.toULong()?.toString(16)?.uppercase() ?: "não") +
            "\n\nIsto NÃO é o boot real do jogo. O IPL3 foi executado diretamente como teste; PIF/CIC e dispositivos ainda faltam." +
            "\n\nANÁLISE DA TRANSFERÊNCIA PARA A FALHA: " + transferAnalysis +
            "\n\nRASTRO IMEDIATO ANTES DA PARADA (máximo 32 instruções):\n" + faultHistoryTrace +
            "\n\nPRIMEIRA PASSAGEM PELO LOOP KSEG0 (máximo 48):\n" + firstLoopTrace.joinToString("\n") +
            "\n\nRASTRO RECENTE AMOSTRADO (máximo 80 entradas, a cada 256 instruções):\n" + recentInstructionTrace.joinToString("\n") +
            "\n\nRASTRO AMOSTRADO DAS ÚLTIMAS INSTRUÇÕES (máximo 80):\n" + trace.joinToString("\n")
        val registerDump = (1..31).joinToString("\n") { index ->
            "GPR[$index]: 0x" + cpu.gpr[index].toULong().toString(16).uppercase().padStart(16, '0')
        }
        // The observed IPL3 loop uses KSEG1 address 0xA00002B4 as a destination.
        // Its physical address is 0x000002B4, so dumping only RDRAM[0..15] misses it.
        val ramRanges = listOf(0x0000..0x003C, 0x02A0..0x02E0, 0x0400..0x0440, 0x1000..0x103C)
        val ramDump = ramRanges.joinToString("\n\n") { range ->
            "RDRAM RANGE 0x" + range.first.toString(16).uppercase().padStart(4, '0') +
                "..0x" + range.last.toString(16).uppercase().padStart(4, '0') + "\n" +
                (range.first..range.last step 4).joinToString("\n") { address ->
                    "RDRAM[0x" + address.toString(16).uppercase().padStart(4, '0') + "]: 0x" +
                        bus.read32(address).toUInt().toString(16).uppercase().padStart(8, '0')
                }
        }
        val spDmemDump = (0x04000740..0x0400077C step 4).joinToString("\n") { address ->
            "SP_DMEM[0x" + address.toString(16).uppercase().padStart(8, '0') + "]: 0x" +
                bus.read32(address).toUInt().toString(16).uppercase().padStart(8, '0')
        }
        val spImemDump = (0x04001000..0x0400103C step 4).joinToString("\n") { address ->
            "SP_IMEM[0x" + address.toString(16).uppercase().padStart(8, '0') + "]: 0x" +
                bus.read32(address).toUInt().toString(16).uppercase().padStart(8, '0')
        }
        diagnostic += "\n\nPRIMEIRAS INSTRUÇÕES APÓS A TRANSFERÊNCIA PARA KSEG0 (máximo 64):\n" +
            if (handoffTrace.isEmpty()) "O PC não passou pela região KSEG0 monitorada." else handoffTrace.joinToString("\n")
        diagnostic += "\n\nPRIMEIRA ENTRADA NO CAMINHO TERMINAL (máximo 32):\n" +
            if (terminalPathTrace.isEmpty()) "O PC não passou pelo intervalo 0x800001C0..0x800001D0." else terminalPathTrace.joinToString("\n")
        diagnostic += "\n\nLINHA DO TEMPO DO LOOP IPL3 (registradores antes da instrução):\n" +
            if (loopCheckpoints.isEmpty()) "O PC não passou pelo intervalo monitorado." else loopCheckpoints.joinToString("\n")
        val hotPostHandoffPcs = postHandoffPcVisits.entries
            .sortedByDescending { it.value }
            .take(24)
            .joinToString("\n") { (address, visits) ->
                "PC=0x" + address.toULong().toString(16).uppercase().padStart(8, '0') +
                    " visitas=" + visits
            }
        diagnostic += "\n\nPCs MAIS VISITADOS APÓS A TRANSFERÊNCIA (região 0x70000000..0x703FFFFF):\n" +
            if (hotPostHandoffPcs.isEmpty()) "Nenhum PC dessa região foi amostrado." else
                hotPostHandoffPcs + "\nTotal de PCs distintos rastreados: " + postHandoffPcVisits.size
        diagnostic += "\n\nPROGRESSO APÓS A TRANSFERÊNCIA (amostras espaçadas por 250.000 instruções):\n" +
            if (postHandoffProgressTrace.isEmpty()) "Nenhum checkpoint de progresso capturado." else
                postHandoffProgressTrace.joinToString("\n")
        diagnostic += "\n\nTODOS OS REGISTRADORES GPR NO PONTO DE PARADA:\n" + registerDump
        diagnostic += "\n\nRDRAM NOS ENDEREÇOS DE INTERESSE:\n" + ramDump
        diagnostic += "\n\nSP DMEM PERTO DA ORIGEM DE CÓPIA (0xA4000774):\n" + spDmemDump
        diagnostic += "\n\nSP IMEM AMOSTRA:\n" + spImemDump
        diagnostic += "\n\nREGISTRADORES PI APÓS O DIAGNÓSTICO:\n" +
            "PI_DRAM_ADDR: 0x" + bus.read32(N64Bus.PI_DRAM_ADDR_REG).toUInt().toString(16).uppercase().padStart(8, '0') + "\n" +
            "PI_CART_ADDR: 0x" + bus.read32(N64Bus.PI_CART_ADDR_REG).toUInt().toString(16).uppercase().padStart(8, '0') + "\n" +
            "PI_STATUS: 0x" + bus.read32(N64Bus.PI_STATUS_REG).toUInt().toString(16).uppercase().padStart(8, '0') + "\n" +
            "PI_RD_LEN readback: 0x" + bus.read32(N64Bus.PI_RD_LEN_REG).toUInt().toString(16).uppercase().padStart(8, '0') + "\n" +
            "PI_WR_LEN readback: 0x" + bus.read32(N64Bus.PI_WR_LEN_REG).toUInt().toString(16).uppercase().padStart(8, '0')
        runOnUiThread {
            romTraceButton?.isEnabled = true
            showRomDiagnosticPage(diagnostic)
        }
    }


    private fun showRomDiagnosticPage(diagnostic: String) {
        val diagnosticText = TextView(this).apply {
            text = diagnostic
            textSize = 14f
            setPadding(24, 24, 24, 24)
            movementMethod = ScrollingMovementMethod()
        }
        val copyButton = Button(this).apply {
            text = "Copiar logs deste diagnóstico"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("N64 Android diagnóstico parcial da ROM", diagnosticText.text))
                Toast.makeText(this@MainActivity, "Logs copiados. Volte ao chat e cole aqui.", Toast.LENGTH_LONG).show()
            }
        }
        val backButton = Button(this).apply {
            text = "Voltar ao menu"
            setOnClickListener { setContentView(mainLayout) }
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(diagnosticText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(copyButton)
            addView(backButton)
        }
        setContentView(page)
    }

    private fun runCpuDiagnostic() {
        try {
            val testMemory = N64Memory(64)
            // addiu $t0, $zero, 42; addiu $t1, $t0, 1
            testMemory.write32(0, (0x09 shl 26) or (8 shl 16) or 42)
            testMemory.write32(4, (0x09 shl 26) or (8 shl 21) or (9 shl 16) or 1)
            val cpu = R4300Cpu(testMemory)
            cpu.setProgramCounter(0L)
            cpu.step()
            cpu.step()

            val passed = cpu.gpr[8] == 42L && cpu.gpr[9] == 43L && cpu.pc == 8L
            status.text = if (passed) {
                "TESTE INTERNO DA CPU: PASSOU" +
                    "\nInstruções executadas: 2" +
                    "\nRegistrador t0: ${cpu.gpr[8]}" +
                    "\nRegistrador t1: ${cpu.gpr[9]}" +
                    "\nPC final: 0x${cpu.pc.toString(16)}" +
                    "\nIsso comprova somente a sequência de teste; a ROM do jogo ainda não foi inicializada."
            } else {
                "TESTE INTERNO DA CPU: FALHOU" +
                    "\nRegistrador t0: ${cpu.gpr[8]}" +
                    "\nRegistrador t1: ${cpu.gpr[9]}" +
                    "\nPC final: 0x${cpu.pc.toString(16)}"
            }
        } catch (error: Exception) {
            status.text = "Falha no teste interno da CPU: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun openRomPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_ROM)
    }

    @Deprecated("Uses the platform Activity result API for the minimum dependency base.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        if (requestCode != REQUEST_ROM) return
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > N64RomLoader.MAX_ROM_SIZE_BYTES) {
                        throw IOException("ROM maior que o limite suportado de 64 MiB.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: IOException) {
            status.text = error.message ?: "Erro ao ler o arquivo selecionado."
            return
        } catch (_: Exception) {
            null
        }
        if (bytes == null) {
            status.text = "Não foi possível ler o arquivo selecionado."
            return
        }
        when (val result = N64RomLoader.load(bytes)) {
            is RomLoadResult.Success -> {
                cartridgeRomBytes = result.rom.bytes
                cartridgeBus = N64Bus(cartridgeRom = result.rom.bytes)
                romTraceButton?.isEnabled = true
                hleBootButton?.isEnabled = true
                val info = N64RomInspector.inspect(result.rom.bytes)
                status.text = "ROM carregada no barramento: ${result.rom.byteSize} bytes" +
                    "\nFormato: ${result.rom.format}" +
                    "\nEndereço físico: 0x10000000" +
                    "\n\n" + info.toDisplayText() +
                    "\n\nROM pronta. Use Boot HLE experimental para testar a inicialização sem firmware PIF externo."
            }
            is RomLoadResult.Failure -> {
                cartridgeBus = null
                romTraceButton?.isEnabled = false
                hleBootButton?.isEnabled = false
                status.text = result.reason
            }
        }
    }

    companion object {
        private const val REQUEST_ROM = 640
    }
}
