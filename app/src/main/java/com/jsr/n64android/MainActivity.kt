package com.jsr.n64android

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.method.ScrollingMovementMethod
import java.io.IOException

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var cartridgeBus: N64Bus? = null
    private var cartridgeRomBytes: ByteArray? = null
    @Volatile private var romLoadGeneration = 0
    @Volatile private var diagnosticExportInProgress = false
    @Volatile private var romTraceInProgress = false
    private var romTraceButton: Button? = null
    private var hleBootButton: Button? = null
    private var mainActionButton: Button? = null
    private lateinit var mainLayout: LinearLayout
    @Volatile private var hleStopRequested = false
    @Volatile private var romTraceStopRequested = false
    @Volatile private var hleWorker: N64ExecutionWorker? = null
    @Volatile private var romVideoStopRequested = false
    @Volatile private var romVideoSession: N64RomVideoSession? = null
    @Volatile private var lastRomVideoSession: N64RomVideoSession? = null
    @Volatile private var romVideoCpu: R4300Cpu? = null
    @Volatile private var romVideoThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Nenhum jogo selecionado.\nEscolha uma ROM para começar."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(20, 18, 20, 18)
        }

        mainActionButton = Button(this).apply {
            text = "ESCOLHER JOGO"
            textSize = 18f
            isAllCaps = false
            setPadding(18, 18, 18, 18)
            setOnClickListener {
                if (cartridgeBus == null) openRomPicker() else startRomVideoExecution()
            }
            setOnLongClickListener {
                openRomPicker()
                true
            }
        }
        hleBootButton = Button(this).apply {
            text = "DIAGNÓSTICO HLE"
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                if (romVideoThread?.isAlive == true || hleWorker != null) {
                    status.text = "Pare a execução atual antes de iniciar outro diagnóstico."
                } else {
                    runHleBootDiagnostic()
                }
            }
        }
        romTraceButton = Button(this).apply {
            text = "DIAGNÓSTICO IPL3 / CPU"
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                if (romVideoThread?.isAlive == true || hleWorker != null) {
                    status.text = "Pare a execução atual antes de iniciar outro diagnóstico."
                } else {
                    runRomInstructionDiagnostic()
                }
            }
        }
        val completeDiagnosticButton = createCompleteDiagnosticButton { status.text.toString() }
        val diagnosticToolsButton = Button(this).apply {
            text = "FERRAMENTAS DE TESTE"
            isAllCaps = false
            setOnClickListener { openDiagnosticTools() }
        }
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 36, 24, 28)
            addView(TextView(this@MainActivity).apply {
                text = "N64 ANDROID"
                textSize = 30f
                gravity = Gravity.CENTER
                setPadding(8, 18, 8, 6)
            })
            addView(TextView(this@MainActivity).apply {
                text = "EMULADOR NINTENDO 64"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(8, 0, 8, 30)
            })
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 })
            addView(mainActionButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(hleBootButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 })
            addView(romTraceButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 })
            addView(completeDiagnosticButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 })
            addView(diagnosticToolsButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 })
            addView(TextView(this@MainActivity).apply {
                text = "Toque para escolher ou iniciar um jogo.\nMantenha pressionado para trocar a ROM."
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(8, 18, 8, 8)
            })
        }
        setContentView(mainLayout)
        restoreLastRomSelection()
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
    override fun onDestroy() {
        hleStopRequested = true
        romTraceStopRequested = true
        hleWorker?.requestStop()
        romVideoStopRequested = true
        romVideoSession?.requestStop()
        super.onDestroy()
    }

    @Deprecated("Uses the platform back callback for the current Activity implementation.")
    override fun onBackPressed() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        val currentPage = content?.getChildAt(0)
        if (currentPage != null && currentPage !== mainLayout) {
            if (romVideoThread?.isAlive == true) {
                romVideoStopRequested = true
                romVideoSession?.requestStop()
            }
            setContentView(mainLayout)
            return
        }
        super.onBackPressed()
    }


    /**
     * Experimental continuous ROM execution path.
     * HLE prepares the R4300 entry point; the worker then runs CPU instructions
     * and forwards changed VI frames to the Android presenter. RSP task dispatch
     * is not yet automatic, so this is a bring-up path, not a playable-game claim.
     */
    private fun startRomVideoExecution() {
        if (diagnosticExportInProgress || romTraceInProgress || hleWorker != null) {
            status.text = "Aguarde o diagnóstico ou a exportação atual terminar."
            return
        }
        val bus = cartridgeBus
        if (bus == null) {
            status.text = "Selecione uma ROM primeiro."
            return
        }
        if (romVideoThread?.isAlive == true) {
            Toast.makeText(this, "A execução da ROM já está ativa.", Toast.LENGTH_SHORT).show()
            return
        }

        romVideoStopRequested = false
        lastRomVideoSession = null
        romVideoCpu = null
        mainActionButton?.isEnabled = false
        val frameView = N64VideoPresenterView(this).apply {
            contentDescription = "Vídeo experimental da ROM Nintendo 64"
        }
        val details = TextView(this).apply {
            text = "Preparando HLE e aguardando frames VI da ROM..."
            textSize = 14f
            setPadding(12, 12, 12, 12)
        }
        val diagnosticExportButton = createCompleteDiagnosticButton { details.text.toString() }
        val stopButton = Button(this).apply {
            text = "PARAR EXECUÇÃO"
            setOnClickListener {
                romVideoStopRequested = true
                romVideoSession?.requestStop()
                details.text = "Solicitando parada segura entre instruções..."
            }
        }
        val backButton = Button(this).apply {
            text = "Voltar ao menu"
            setOnClickListener {
                romVideoStopRequested = true
                romVideoSession?.requestStop()
                setContentView(mainLayout)
            }
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(frameView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(details)
            addView(diagnosticExportButton)
            addView(stopButton)
            addView(backButton)
        }
        setContentView(page)

        val worker = Thread({
            try {
                val cpu = R4300Cpu(bus)
                romVideoCpu = cpu
                val boot = N64HleBoot.prepare(bus, cpu)
                var previousIdleLoopSignature: String? = null
                var stableIdleLoopSamples = 0
                val session = N64RomVideoSession(
                    bus = bus,
                    cpu = cpu,
                    onProgress = { count ->
                        val deviceLines = bus.diagnosticDeviceSnapshot()
                            .filter { it.startsWith("SP:") || it.startsWith("VI:") }
                            .joinToString("\n")
                        val mmioCounts = bus.diagnosticMmioCounts.entries.sortedBy { it.key }
                            .joinToString(" ") { it.key + "=" + it.value }
                        val lastMmio = bus.diagnosticMmioHistory.lastOrNull()
                        val failure = romVideoSession?.rspFailureMessage()
                        val rspPc = romVideoSession?.lastRspInstructionAddress()
                        val rspWord = romVideoSession?.lastRspInstructionWord()
                        val dpcSnapshot = bus.diagnosticDpcSnapshot()
                        val lastPc = cpu.diagnosticInstructionAddress
                        val lastOpcode = cpu.diagnosticInstructionWord
                        val exception = cpu.lastExceptionCode?.let { "CPU EXCEPTION code=" + it + " EPC=0x" + (cpu.lastExceptionInstructionAddress ?: 0L).toULong().toString(16).uppercase() }
                        val tlbFault = cpu.lastTlbFaultVirtualAddress?.let { "TLB " + cpu.lastTlbFaultKind + " " + cpu.lastTlbFaultAccessType + " VA=0x" + it.toULong().toString(16).uppercase() }
                        if (!isFinishing && !isDestroyed) runOnUiThread {
                            if (!isFinishing && !isDestroyed) details.text =
                                "HLE: ${boot.copiedBytes} bytes preparados" +
                                "\nCPU: $count instruções" +
                                "\nFrames VI: ${sessionSafeFrameCount(romVideoSession)}" +
                                "\nRSP: ${romVideoSession?.executedRspInstructionCount() ?: 0L} instruções" +
                                "\nÚltima RSP: " + (rspPc?.let { "0x" + it.toString(16).uppercase().padStart(4, '0') } ?: "N/A") + " / " + (rspWord?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A") +
                                "\n" + dpcSnapshot +
                                "\nPC: 0x${cpu.pc.toULong().toString(16).uppercase()}" +
                                "\nÚltima instrução: " + (lastPc?.let { "0x" + it.toULong().toString(16).uppercase() } ?: "N/A") + " / " + (lastOpcode?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A") +
                                "\nMMIO: " + mmioCounts.ifBlank { "nenhum acesso registrado" } +
                                (lastMmio?.let { "\nÚltimo MMIO: " + it } ?: "") +
                                (exception?.let { "\n" + it } ?: "") +
                                (tlbFault?.let { "\n" + it } ?: "") +
                                "\n$deviceLines" +
                                (failure?.let { "\nFalha RSP: $it" } ?: "")
                        }
                    },
                    onFrame = { frame ->
                        if (!isFinishing && !isDestroyed) frameView.submitFrame(frame)
                    }
                )
                romVideoSession = session
                lastRomVideoSession = session
                if (romVideoStopRequested) session.requestStop()
                if (!isFinishing && !isDestroyed) runOnUiThread {
                    if (!isFinishing && !isDestroyed) details.text =
                        "HLE concluído. CPU executando a ROM..." +
                        "\nAguardando o jogo configurar VI e produzir pixels." +
                        "\nRSP será executado em pequenos intervalos cooperativos."
                }
                val result = session.execute()
                if (!isFinishing && !isDestroyed) runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                    details.text =
                        "EXECUÇÃO FINALIZADA: ${result.state}" +
                        "\nInstruções: ${result.executedInstructions}" +
                        "\nFrames VI publicados: ${session.publishedFrameCount()}" +
                        (session.frameCaptureFailureMessage()?.let { "\nConfiguração VI inválida: $it" } ?: "") +
                        "\nInstruções RSP: ${session.executedRspInstructionCount()}" +
                        "\nÚltima RSP: " + (session.lastRspInstructionAddress()?.let { "0x" + it.toString(16).uppercase().padStart(4, '0') } ?: "N/A") + " / " + (session.lastRspInstructionWord()?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A") +
                        "\n" + bus.diagnosticDpcSnapshot() +
                        "\nPC final: 0x${cpu.pc.toULong().toString(16).uppercase()}" +
                        (session.rspFailureMessage()?.let { "\nFalha RSP: $it" } ?: "") +
                        (result.failureMessage?.let { "\nFalha CPU: $it" } ?: "")
                    mainActionButton?.isEnabled = true
                    }
                }
            } catch (error: Throwable) {
                if (!isFinishing && !isDestroyed) runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                    details.text =
                        "FALHA NA EXECUÇÃO DA ROM" +
                        "\n${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"}" +
                        "\nAinda não foi possível iniciar o caminho de vídeo."
                    mainActionButton?.isEnabled = true
                    }
                }
            } finally {
                romVideoSession = null
                romVideoThread = null
            }
        }, "N64-ROM-VIDEO-CPU")
        romVideoThread = worker
        worker.start()
    }

    private fun sessionSafeFrameCount(session: N64RomVideoSession?): Long =
        session?.publishedFrameCount() ?: 0L

    private fun appendBusDiagnostics(lines: MutableList<String>, bus: N64Bus, heading: String) {
        lines.add(heading)
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

    private fun runHleBootDiagnostic() {
        if (diagnosticExportInProgress || romTraceInProgress || romVideoThread?.isAlive == true || hleWorker != null) {
            status.text = "Aguarde a operação de diagnóstico ou exportação atual terminar."
            return
        }
        val bus = cartridgeBus
        if (bus == null) {
            status.text = "Selecione uma ROM de jogo primeiro."
            return
        }
        hleStopRequested = false
        mainActionButton?.isEnabled = false
        hleBootButton?.isEnabled = false
        romTraceButton?.isEnabled = false
        status.text = "Preparando HLE sem executar IPL3..."
        val cpu = R4300Cpu(bus)
        val session = N64ExecutionSession(
            instructionLimit = 1L,
            batchSize = 1
        ) {
            val lines = ArrayList<String>()
            var steps = 0
            var stopReason = "Limite de instruções alcançado."
            var capturedVideoFrame: N64VideoFrame? = null
            var videoCaptureMessage = "VI: captura ainda não tentada."
            var diagnosticFailureMessage: String? = null
            val instructionTrace = N64HleInstructionTrace()
            try {
                val preparation = N64HleDiagnosticRunner().prepare(bus, cpu)
                val handoff = preparation.handoff
                lines.addAll(preparation.summaryLines)
                // The previous 500k-step run was still inside the byte-copy loop
                // around 0x70000574..0x70000588. Extend the bounded run and capture
                // the loop's actual exit instead of relying only on distant checkpoints.
                val maxSteps = 2_000_000
                // Keep both the general instruction window and the specialized copy-loop
                // trace in bounded, UI-independent collectors.
                val copyLoopTrace = N64HleCopyLoopTrace()
                fun recordExecution(pc: Long, opcode: Int?, nextPc: Long) {
                    instructionTrace.record(pc, opcode, nextPc)
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
                var pcBeforeInstruction = cpu.pc
                var copyLoopObservation = copyLoopTrace.beforeInstruction(cpu.pc) { "" }
                val executionSnapshot = N64HleDiagnosticRunner().executeCpu(
                    cpu = cpu,
                    instructionLimit = maxSteps.toLong(),
                    batchSize = 512,
                    beforeInstruction = { observedCpu ->
                        pcBeforeInstruction = observedCpu.pc
                        copyLoopObservation = copyLoopTrace.beforeInstruction(pcBeforeInstruction) {
                            copyLoopRegisters()
                        }
                        bus.setDiagnosticCpuPc(pcBeforeInstruction)
                    },
                    afterInstruction = { observedCpu ->
                        val instruction = observedCpu.diagnosticInstructionWord
                        recordExecution(pcBeforeInstruction, instruction, observedCpu.pc)
                        copyLoopTrace.afterInstruction(
                            before = copyLoopObservation,
                            instruction = instruction,
                            nextPc = observedCpu.pc,
                            registersAfter = { copyLoopRegisters() }
                        )
                        if (steps < 48) {
                            lines.add("step=$steps PC=0x${pcBeforeInstruction.toULong().toString(16).uppercase()} INST=${instruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} NEXT=0x${observedCpu.pc.toULong().toString(16).uppercase()}")
                        }
                        steps++
                        if (steps % 50_000 == 0) {
                            lines.add("checkpoint=$steps PC=0x${observedCpu.pc.toULong().toString(16).uppercase()} t0=0x${observedCpu.gpr[8].toULong().toString(16).uppercase()} t1=0x${observedCpu.gpr[9].toULong().toString(16).uppercase()} a0=0x${observedCpu.gpr[4].toULong().toString(16).uppercase()} sp=0x${observedCpu.gpr[29].toULong().toString(16).uppercase()}")
                        }
                        observedCpu.lastExceptionCode != null || hleStopRequested
                    }
                )
                steps = executionSnapshot.executedInstructions.toInt()
                if (cpu.lastExceptionCode != null) {
                    stopReason = "Exceção CPU code=${cpu.lastExceptionCode} em 0x${(cpu.lastExceptionInstructionAddress ?: pcBeforeInstruction).toULong().toString(16).uppercase()}."
                } else if (hleStopRequested || executionSnapshot.state == N64ExecutionSession.State.CANCELLED) {
                    stopReason = "Execução cancelada porque a Activity foi encerrada."
                } else if (executionSnapshot.state == N64ExecutionSession.State.FAILED) {
                    stopReason = "Falha na sessão CPU: ${executionSnapshot.failureMessage ?: "sem detalhes"}"
                }
                if (hleStopRequested) stopReason = "Execução cancelada porque a Activity foi encerrada."
                // One diagnostic sample after the bounded CPU trace; this is not timed VI scanout.
                try {
                    capturedVideoFrame = bus.captureViFrameFromRegisters()
                    videoCaptureMessage = if (capturedVideoFrame == null) {
                        "VI: nenhum framebuffer válido (registradores não programados ou configuração inválida)."
                    } else {
                        val frame = capturedVideoFrame!!
                        "VI: framebuffer ${frame.width}x${frame.height}, pixelSize=${frame.pixelSize}, origin=0x${frame.origin.toString(16).uppercase()}."
                    }
                } catch (videoError: Exception) {
                    videoCaptureMessage = "VI: falha na captura: ${videoError.javaClass.simpleName}: ${videoError.message}"
                }
                lines.add(videoCaptureMessage)
                lines.add("Nota: captura única ao fim do diagnóstico; não representa VBlank/scanout temporizado.")
                val copyLoopSnapshot = copyLoopTrace.snapshot()
                lines.add("=== LAÇO DE CÓPIA 0x70000588 ===")
                lines.add("Iterações=${copyLoopSnapshot.iterations} branches_taken=${copyLoopSnapshot.takenBranches} saída_observada=${copyLoopSnapshot.exitObserved}")
                lines.addAll(copyLoopSnapshot.loopLines)
                lines.add("=== AMOSTRAS DO LAÇO ===")
                lines.addAll(copyLoopSnapshot.sampleLines)
                lines.add("=== PRIMEIRAS INSTRUÇÕES APÓS A SAÍDA ===")
                lines.addAll(copyLoopSnapshot.postExitLines)
                val instructionTraceSnapshot = instructionTrace.snapshot()
                lines.add("=== ÚLTIMAS 256 INSTRUÇÕES ===")
                lines.addAll(instructionTraceSnapshot.recentLines)
                lines.add("=== PCs MAIS VISITADOS (até 16) ===")
                lines.addAll(instructionTraceSnapshot.mostVisitedLines)
                lines.add("PCs distintos contabilizados=${instructionTraceSnapshot.distinctPcCount} (limite 4096)")
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
                appendBusDiagnostics(lines, bus, "=== EVENTOS DO BARRAMENTO ===")
                lines.add("Resultado: $stopReason")
            } catch (error: Throwable) {
                diagnosticFailureMessage = "${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"}"
                val failedInstructionPc = cpu.diagnosticInstructionAddress
                val failedInstruction = cpu.diagnosticInstructionWord
                lines.add("=== FALHA HLE — CONTEXTO PRESERVADO ===")
                lines.add("Erro: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"}")
                val failedTraceSnapshot = instructionTrace.snapshot()
                lines.add("=== ÚLTIMAS 256 INSTRUÇÕES ANTES DA FALHA ===")
                lines.addAll(failedTraceSnapshot.recentLines)
                lines.add("=== PCs MAIS VISITADOS ANTES DA FALHA (até 16) ===")
                lines.addAll(failedTraceSnapshot.mostVisitedLines)
                lines.add("PCs distintos contabilizados=${failedTraceSnapshot.distinctPcCount} (limite 4096)")
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
                appendBusDiagnostics(lines, bus, "=== EVENTOS DO BARRAMENTO NA FALHA ===")
            }
            if (!hleStopRequested) {
                val diagnosticResult = N64HleDiagnosticResult(
                    reportLines = lines.toList(),
                    videoFrame = capturedVideoFrame,
                    executedInstructions = steps,
                    stopReason = stopReason,
                    failureMessage = diagnosticFailureMessage
                )
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showRomDiagnosticPage(
                            diagnosticResult.reportLines.joinToString("\n"),
                            diagnosticResult.videoFrame
                        )
                        mainActionButton?.isEnabled = true
                        hleBootButton?.isEnabled = true
                        romTraceButton?.isEnabled = true
                    }
                }
            }
        }
        lateinit var workerRef: N64ExecutionWorker
        val worker = N64ExecutionWorker(session) { snapshot ->
            runOnUiThread {
                if (hleWorker === workerRef) hleWorker = null
                if (!isFinishing && !isDestroyed) {
                    when (snapshot.state) {
                        N64ExecutionSession.State.FAILED -> {
                            status.text = "Falha no worker HLE: ${snapshot.failureMessage ?: "sem detalhes"}"
                            mainActionButton?.isEnabled = true
                            hleBootButton?.isEnabled = true
                            romTraceButton?.isEnabled = true
                        }
                        N64ExecutionSession.State.CANCELLED -> {
                            mainActionButton?.isEnabled = true
                            hleBootButton?.isEnabled = true
                            romTraceButton?.isEnabled = true
                        }
                        else -> Unit
                    }
                }
            }
        }
        workerRef = worker
        hleWorker = worker
        if (!worker.start()) {
            hleWorker = null
            status.text = "Não foi possível iniciar o worker HLE."
            mainActionButton?.isEnabled = true
            hleBootButton?.isEnabled = true
            romTraceButton?.isEnabled = true
        }
    }

    private fun runRomInstructionDiagnostic() {
        if (diagnosticExportInProgress || romTraceInProgress || romVideoThread?.isAlive == true || hleWorker != null) {
            status.text = "Aguarde a operação de diagnóstico ou exportação atual terminar."
            return
        }
        val bus = cartridgeBus
        if (bus == null) {
            status.text = "Selecione uma ROM primeiro."
            return
        }
        romTraceStopRequested = false
        romTraceInProgress = true
        mainActionButton?.isEnabled = false
        romTraceButton?.isEnabled = false
        hleBootButton?.isEnabled = false
        status.text = "Executando diagnóstico em segundo plano. Aguarde; a tela continuará responsiva."
        Thread({
            try {
                runRomInstructionDiagnosticWorker(bus)
            } catch (error: Throwable) {
                val message = "Falha no diagnóstico: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"}"
                if (!isFinishing && !isDestroyed) runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                    status.text = message + "\nA ROM permanece selecionada se o processo não tiver sido reiniciado."
                    mainActionButton?.isEnabled = true
                    romTraceButton?.isEnabled = cartridgeBus != null
                    hleBootButton?.isEnabled = cartridgeBus != null
                    }
                }
            } finally {
                romTraceInProgress = false
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
            if (!isFinishing && !isDestroyed) runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                status.text = "Não foi possível preparar IPL3 na SP DMEM: ${error.message ?: error.javaClass.simpleName}"
                mainActionButton?.isEnabled = true
                romTraceButton?.isEnabled = cartridgeBus != null
                hleBootButton?.isEnabled = cartridgeBus != null
                }
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
                if (!isFinishing && !isDestroyed) {
                status.text = "CIC/seed do IPL3 não identificado. O diagnóstico foi interrompido para evitar um falso erro de checksum; por enquanto, só CIC 6102/7101 e 6105/7105 são reconhecidos."
                mainActionButton?.isEnabled = true
                romTraceButton?.isEnabled = cartridgeBus != null
                hleBootButton?.isEnabled = cartridgeBus != null
                }
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
                if (romTraceStopRequested) {
                    stopReason = "Diagnóstico cancelado porque a Activity foi encerrada."
                    break
                }
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
                    if (!isFinishing && !isDestroyed) runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = "Diagnóstico em andamento: $stepIndex / $maxSteps instruções. ROM mantida carregada."
                        }
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
        }.joinToString("\n")
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
        if (!isFinishing && !isDestroyed) runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                mainActionButton?.isEnabled = true
                romTraceButton?.isEnabled = cartridgeBus != null
                hleBootButton?.isEnabled = cartridgeBus != null
                showRomDiagnosticPage(diagnostic)
            }
        }
    }


    /**
     * One focused diagnostic exporter shared by the ROM execution and diagnostic screens.
     * Stops the emulation worker before reading mutable CPU/bus diagnostic collections.
     */
    private fun createCompleteDiagnosticButton(reportText: () -> String): Button {
        return Button(this).apply {
            text = "GERAR LOG COMPLETO DO EMULADOR"
            isAllCaps = false
            setOnClickListener {
                if (diagnosticExportInProgress) return@setOnClickListener
                if (hleWorker != null || romTraceInProgress) {
                    Toast.makeText(this@MainActivity, "Aguarde o diagnóstico em andamento terminar antes de exportar.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                diagnosticExportInProgress = true
                mainActionButton?.isEnabled = false
                romTraceButton?.isEnabled = false
                hleBootButton?.isEnabled = false
                isEnabled = false
                val button = this
                // Capture UI text on the main thread; all expensive work stays on the exporter thread.
                val screenReport = runCatching { reportText() }
                    .getOrDefault("Texto da tela indisponível.")
                val appContext = applicationContext
                Thread({
                    try {
                        val worker = romVideoThread
                        val sessionAtStart = romVideoSession ?: lastRomVideoSession
                        if (worker?.isAlive == true) {
                            romVideoStopRequested = true
                            sessionAtStart?.requestStop()
                            worker.join(LOG_EXPORT_STOP_TIMEOUT_MS)
                            if (worker.isAlive) {
                                throw IOException("A emulação não parou a tempo. Tente novamente.")
                            }
                        }
                        val bus = cartridgeBus
                        val cpu = romVideoCpu
                        val session = lastRomVideoSession ?: sessionAtStart
                        val output = buildString {
                            appendLine("N64 ANDROID — LOG COMPLETO DE DIAGNÓSTICO V2")
                            appendLine("Data: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", java.util.Locale.US).format(java.util.Date())}")
                            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ABI=${Build.SUPPORTED_ABIS.joinToString(",")}")
                            appendLine("App: ${appContext.packageName} | versionCode=${runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode }.getOrDefault(-1L)}")
                            appendLine("ROM carregada: ${cartridgeRomBytes?.size ?: 0} bytes")
                            appendLine("Estado da execução: ${if (worker?.isAlive == true) "ATIVA" else "PARADA"}")
                            appendLine()
                            appendLine("=== TELA / CONTEXTO ===")
                            appendLine(screenReport)
                            appendLine()
                            appendLine("=== INTEGRIDADE / VOLUME DA COLETA ===")
                            appendLine("MMIO total por dispositivo=${bus?.diagnosticMmioCounts?.entries?.sortedBy { it.key }?.joinToString { "${it.key}:${it.value}" } ?: "sem barramento"}")
                            appendLine("MMIO eventos retidos=${bus?.diagnosticMmioHistory?.size ?: 0}; SP-MMIO retidos=${bus?.diagnosticSpMmioHistory?.size ?: 0}; SP writes retidos=${bus?.diagnosticSpWriteHistory?.size ?: 0}")
                            appendLine("PI DMA retidos=${bus?.diagnosticPiDmaHistory?.size ?: 0}; SP DMA retidos=${bus?.diagnosticSpDmaHistory?.size ?: 0}; OSTask DMA retidos=${bus?.diagnosticSpTaskDescriptorDmaHistory?.size ?: 0}")
                            appendLine("PTE writes total=${bus?.diagnosticPteWriteCount ?: 0}; PTE eventos retidos=${bus?.diagnosticPteWriteHistory?.size ?: 0}")
                            appendLine("RDRAM writes total=${bus?.diagnosticRdramWriteCount ?: 0}; eventos amostrados retidos=${bus?.diagnosticRdramWriteHistory?.size ?: 0}")
                            appendLine("Boot accesses total=${bus?.diagnosticBootMemoryAccessTotal ?: 0}; boot writes total=${bus?.diagnosticBootDestinationWriteCount ?: 0}")
                            appendLine("COP0 MTC0 total=${cpu?.diagnosticCop0WriteCount ?: 0}; eventos retidos=${cpu?.diagnosticCop0WriteHistory?.size ?: 0}; evictados=${cpu?.diagnosticCop0WriteEvictedCount ?: 0}")
                            appendLine("Observação: históricos limitados/amostrados são identificados separadamente dos contadores cumulativos; o arquivo não afirma conter eventos que o coletor não reteve.")
                            appendLine()
                            appendLine("=== RESUMO AUTOMÁTICO HLE ===")
                            if (bus != null && cpu != null) {
                                appendLine(N64HleAutoDiagnostic.build(
                                    cpu = cpu,
                                    bus = bus,
                                    instructionCount = session?.executedInstructionCount() ?: 0L,
                                    rspInstructionCount = session?.executedRspInstructionCount() ?: 0L,
                                    frameCount = session?.publishedFrameCount() ?: 0L,
                                    loop = session?.diagnosticRepeatedInstructionLoop()
                                ))
                                appendLine()
                                appendLine("=== CPU / COP0 ===")
                                appendLine("PC=0x${cpu.pc.toULong().toString(16).uppercase()}")
                                appendLine("HI=0x${cpu.hi.toULong().toString(16).uppercase()} LO=0x${cpu.lo.toULong().toString(16).uppercase()}")
                                appendLine("SNAPSHOT ARQUITETURAL COMPLETO (inclui delay slot, branch pendente, fase Count e TLB):")
                                appendLine(cpu.snapshotContext().toString())
                                cpu.gpr.forEachIndexed { i, value -> appendLine("GPR[$i]=0x${value.toULong().toString(16).uppercase()}") }
                                cpu.cop0.forEachIndexed { i, value -> appendLine("COP0[$i]=0x${value.toULong().toString(16).uppercase()}") }
                                appendLine("=== REGISTRADORES FPU ===")
                                cpu.fpr.forEachIndexed { i, value -> appendLine("FPR[$i]=0x${value.toULong().toString(16).uppercase()}") }
                                cpu.fcr.forEachIndexed { i, value -> appendLine("FCR[$i]=0x${value.toUInt().toString(16).uppercase().padStart(8, '0')}") }
                                appendLine("=== CONTEXTO EXATO DA CPU ===")
                                appendLine("Instrucao atual PC=0x${cpu.diagnosticInstructionAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"} INST=0x${cpu.diagnosticInstructionWord?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"}")
                                appendLine("Acesso virtual=0x${cpu.diagnosticVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"} fisico=0x${cpu.diagnosticPhysicalAddress?.toUInt()?.toString(16)?.uppercase() ?: "N/A"} tipo=${cpu.diagnosticAccessType ?: "N/A"}")
                                appendLine("Ultimo JR/JALR PC=0x${cpu.diagnosticLastIndirectJumpAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"} INST=0x${cpu.diagnosticLastIndirectJumpInstruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} TARGET=0x${cpu.diagnosticLastIndirectTarget?.toULong()?.toString(16)?.uppercase() ?: "N/A"}")
                                appendLine("Primeiro JR/JALR antes da primeira falha TLB PC=0x${cpu.firstTlbFaultIndirectJumpAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"} INST=0x${cpu.firstTlbFaultIndirectJumpInstruction?.toUInt()?.toString(16)?.uppercase()?.padStart(8, '0') ?: "N/A"} TARGET=0x${cpu.firstTlbFaultIndirectTarget?.toULong()?.toString(16)?.uppercase() ?: "N/A"}")
                                appendLine("TLB habilitada=${cpu.tlbEnabled}")
                                appendLine("Última exceção=${cpu.lastExceptionCode ?: "nenhuma"} EPC=0x${cpu.lastExceptionInstructionAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"}")
                                appendLine("Falha TLB=${cpu.lastTlbFaultKind ?: "nenhuma"} VA=0x${cpu.lastTlbFaultVirtualAddress?.toULong()?.toString(16)?.uppercase() ?: "N/A"}")
                                appendLine("=== ÚLTIMAS INSTRUÇÕES CPU ===")
                                appendLine(session?.diagnosticInstructionTraceSnapshot()?.takeLast(40)?.joinToString("\n") {
                                    "PC=0x${it.pc.toULong().toString(16).uppercase()} INST=0x${it.instruction.toUInt().toString(16).uppercase()}"
                                } ?: "Sem sessão.")
                                appendLine("=== HISTÓRICO DE EXCEÇÕES / FLUXO DE CONTROLE ===")
                                appendLine(cpu.diagnosticTlbExceptionHistory.toList().joinToString("\n").ifEmpty { "(sem eventos TLB registrados)" })
                                appendLine(cpu.diagnosticControlFlowHistory.toList().joinToString("\n").ifEmpty { "(sem eventos de controle registrados)" })
                                appendLine(cpu.diagnosticInterruptHistory.toList().joinToString("\n").ifEmpty { "(sem interrupções registradas)" })
                                appendLine("=== ESCRITAS MTC0 / HISTÓRICO COP0 ===")
                                appendLine("Total=${cpu.diagnosticCop0WriteCount}; retidas=${cpu.diagnosticCop0WriteHistory.size}; evictadas=${cpu.diagnosticCop0WriteEvictedCount}")
                                appendLine(cpu.diagnosticCop0WriteHistory.toList().joinToString("\n").ifEmpty { "(nenhuma escrita MTC0 registrada)" })
                                appendLine("=== RETORNOS ERET (HISTÓRICO PRESERVADO) ===")
                                appendLine(cpu.diagnosticEretHistory.toList().joinToString("\n").ifEmpty { "(sem ERET registrado)" })
                                appendLine("=== PRIMEIRAS ENTRADAS EM LOOPS BEQ AUTO-SALTO ===")
                                appendLine(cpu.diagnosticSelfLoopEntryHistory.toList().joinToString("\n").ifEmpty { "(nenhum loop BEQ para si mesmo registrado)" })
                                appendLine("=== ESTADO FINAL DA SESSAO DE EXECUCAO ===")
                                appendLine(session?.snapshot()?.toString() ?: "(snapshot de sessão indisponível)")
                                appendLine("=== INSTRUÇÕES ANTES DA PRIMEIRA FALHA TLB ===")
                                appendLine(cpu.diagnosticTlbPreExceptionHistory.toList().joinToString("\n").ifEmpty { "(sem histórico pré-falha)" })
                                appendLine("=== INSTRUÇÕES APÓS A FALHA TLB ===")
                                appendLine(cpu.diagnosticTlbInstructionHistory.toList().joinToString("\n").ifEmpty { "(sem histórico pós-falha)" })
                                appendLine("=== ACESSOS DE MEMÓRIA DURANTE REFILL TLB ===")
                                appendLine(cpu.diagnosticTlbMemoryAccessHistory.toList().joinToString("\n").ifEmpty { "(sem acessos capturados)" })
                                appendLine("=== ROTINA DE CONFIGURAÇÃO DE PÁGINAS ===")
                                appendLine(cpu.diagnosticPteSetupHistory.toList().joinToString("\n").ifEmpty { "(sem instruções da rotina capturadas)" })
                                appendLine("=== CAMINHO DE CHAMADAS / RETORNOS ===")
                                appendLine(cpu.diagnosticCallPathHistory.toList().joinToString("\n").ifEmpty { "(sem eventos no caminho monitorado)" })
                                appendLine("=== ESCRITAS TLBWI / TLBWR ===")
                                appendLine(cpu.diagnosticTlbWriteHistory.toList().joinToString("\n").ifEmpty { "(nenhuma escrita TLB registrada)" })
                                appendLine("=== SNAPSHOT TLB CAPTURADO NA PRIMEIRA FALHA (HISTÓRICO) ===")
                                appendLine(cpu.lastTlbEntrySnapshot)
                                appendLine("=== SNAPSHOT TLB ATUAL NO MOMENTO DA EXPORTAÇÃO ===")
                                appendLine(cpu.diagnosticFullTlbSnapshot())
                                appendLine("=== ESTADO DOS DISPOSITIVOS ===")
                                appendLine(bus.diagnosticDeviceSnapshot().joinToString("\n"))
                                appendLine("=== TRANSIÇÕES DE INTERRUPÇÃO MI (ORIGEM / ACK / MÁSCARA) ===")
                                appendLine(bus.diagnosticMiInterruptSourceHistory.toList().joinToString("\n").ifEmpty { "(sem transições MI registradas)" })
                                appendLine("=== CONTADORES MMIO (LEITURAS + ESCRITAS) ===")
                                appendLine(bus.diagnosticMmioCounts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} total=${it.value} writes=${bus.diagnosticMmioWriteCounts[it.key] ?: 0L}" })
                                appendLine("=== HISTORICO MMIO RECENTE (TODOS OS DISPOSITIVOS) ===")
                                appendLine(bus.diagnosticMmioHistory.toList().joinToString("\n").ifEmpty { "(nenhum acesso MMIO registrado)" })
                                appendLine("=== HISTORICO COMPLETO DE ACESSOS SP ===")
                                appendLine(bus.diagnosticSpMmioHistory.toList().joinToString("\n").ifEmpty { "(nenhum acesso SP registrado)" })
                                appendLine("=== ESCRITAS SP ===")
                                appendLine(bus.diagnosticSpWriteHistory.toList().joinToString("\n").ifEmpty { "(nenhuma escrita SP registrada)" })
                                appendLine("=== CONTADORES SP POR REGISTRADOR ===")
                                appendLine(bus.diagnosticSpMmioCounts.entries.joinToString("\n") { "${it.key}=${it.value}" })
                                appendLine("=== SP / DMA / OSTASK ===")
                                appendLine(bus.diagnosticSpDmaHistory.toList().joinToString("\n").ifEmpty { "(nenhum DMA SP)" })
                                appendLine(bus.diagnosticSpTaskDescriptorDmaHistory.toList().joinToString("\n").ifEmpty { "(nenhum descritor OSTask detectado)" })
                                appendLine("=== DMA PI ===")
                                appendLine(bus.diagnosticPiDmaHistory.toList().joinToString("\n").ifEmpty { "(nenhum DMA PI)" })
                                appendLine("=== ACESSOS DE BOOT / IPL3 ===")
                                appendLine("Total acessos monitorados=${bus.diagnosticBootMemoryAccessTotal}")
                                appendLine(bus.diagnosticBootMemoryAccessHistory.toList().joinToString("\n").ifEmpty { "(nenhum acesso monitorado)" })
                                appendLine("=== ESCRITAS NA REGIAO DE DESTINO BOOT ===")
                                appendLine("Total=${bus.diagnosticBootDestinationWriteCount}")
                                appendLine(bus.diagnosticBootDestinationWriteHistory.toList().joinToString("\n").ifEmpty { "(nenhuma escrita capturada)" })
                                appendLine("=== ESCRITAS GERAIS EM RDRAM ===")
                                appendLine("Total=${bus.diagnosticRdramWriteCount}")
                                appendLine(bus.diagnosticRdramWriteHistory.toList().joinToString("\n").ifEmpty { "(nenhuma escrita RDRAM capturada)" })
                                appendLine("=== ESCRITAS NA ESTRUTURA PTE ===")
                                appendLine("Total=${bus.diagnosticPteWriteCount}")
                                appendLine(bus.diagnosticPteWriteHistory.toList().joinToString("\n").ifEmpty { "(nenhuma escrita PTE capturada)" })
                                appendLine("=== DPC / RDP ===")
                                appendLine(bus.diagnosticDpcSnapshot())
                                appendLine("=== MEMORIA RDRAM: REGIOES INICIAIS E BOOT ===")
                                appendLine(bus.diagnosticMemoryDump(listOf(0x00000000..0x00000FFF, 0x00000400..0x000013FF, 0x00010000..0x00010FFF)))
                                appendLine("=== MEMORIA SP DMEM + IMEM (8 KiB) ===")
                                appendLine(bus.diagnosticSpMemoryDump())
                                appendLine("=== PIF RAM ===")
                                appendLine(bus.diagnosticPifRamDump())
                                appendLine("=== DESCRITOR OSTASK ATUAL (SP DMEM) ===")
                                appendLine(bus.diagnosticSpTaskDescriptor().toString())
                                appendLine("=== RSP ===")
                                appendLine(session?.diagnosticRspSnapshot() ?: "Sessão RSP indisponível.")
                                appendLine("=== CONTADORES DA SESSÃO ===")
                                appendLine("CPU instruções=${session?.executedInstructionCount() ?: 0}")
                                appendLine("RSP instruções=${session?.executedRspInstructionCount() ?: 0}")
                                appendLine("Frames VI=${session?.publishedFrameCount() ?: 0}")
                                appendLine("Falha RSP=${session?.rspFailureMessage() ?: "nenhuma"}")
                                appendLine("Falha VI=${session?.frameCaptureFailureMessage() ?: "nenhuma"}")
                                appendLine("=== ROM ===")
                                appendLine(bus.diagnosticCartridgeSummary())
                            } else {
                                appendLine("CPU ou barramento ainda não disponível; execute uma ROM antes de coletar o diagnóstico.")
                            }
                            appendLine("=== FIM ===")
                        }
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, "N64-Android-diagnostico-${System.currentTimeMillis()}.txt")
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/N64Android")
                        }
                        val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("Não foi possível criar o arquivo em Downloads.")
                        val stream = appContext.contentResolver.openOutputStream(uri)
                            ?: throw IOException("Não foi possível abrir o arquivo.")
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(output) }
                        if (!isFinishing && !isDestroyed) runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this@MainActivity, "Diagnóstico salvo em Downloads/N64Android.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (error: Exception) {
                        if (!isFinishing && !isDestroyed) runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this@MainActivity, "Falha ao salvar diagnóstico: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        if (!isFinishing && !isDestroyed) runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                diagnosticExportInProgress = false
                                button.isEnabled = true
                                val idle = romVideoThread?.isAlive != true && hleWorker == null && !romTraceInProgress
                                mainActionButton?.isEnabled = idle
                                romTraceButton?.isEnabled = idle && cartridgeBus != null
                                hleBootButton?.isEnabled = idle && cartridgeBus != null
                            }
                        }
                    }
                }, "N64-Diagnostic-Export").start()
            }
        }
    }

    private fun showRomDiagnosticPage(diagnostic: String, videoFrame: N64VideoFrame? = null) {
        val diagnosticText = TextView(this).apply {
            text = diagnostic
            textSize = 14f
            setPadding(24, 24, 24, 24)
            movementMethod = ScrollingMovementMethod()
        }
        val diagnosticExportButton = createCompleteDiagnosticButton { diagnosticText.text.toString() }
        val backButton = Button(this).apply {
            text = "Voltar ao menu"
            setOnClickListener { setContentView(mainLayout) }
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            if (videoFrame != null) {
                val frameView = N64VideoPresenterView(this@MainActivity).apply {
                    contentDescription = "Framebuffer VI capturado no diagnóstico"
                    submitFrame(videoFrame)
                }
                addView(frameView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            addView(diagnosticText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(diagnosticExportButton)
            addView(backButton)
        }
        setContentView(page)
    }

    /**
     * Exercises the synthetic RDRAM -> VI registers -> framebuffer reader
     * -> Android ImageView path. This is a video-output test, not game rendering.
     */
    /**
     * Opens isolated synthetic diagnostics. These tools do not boot or modify the
     * selected cartridge session and must never be presented as game compatibility.
     */
    private fun openDiagnosticTools() {
        if (diagnosticExportInProgress || romTraceInProgress || hleWorker != null || romVideoThread?.isAlive == true) {
            status.text = "Aguarde a operação atual terminar antes de abrir as ferramentas."
            return
        }
        val title = TextView(this).apply {
            text = "FERRAMENTAS DE TESTE\n\nTestes sintéticos isolados. Nenhum deles comprova que uma ROM funciona."
            textSize = 16f
            setPadding(12, 16, 12, 20)
        }
        val videoButton = Button(this).apply {
            text = "TESTE DE VÍDEO"
            setOnClickListener { runVideoTest() }
        }
        val rdpButton = Button(this).apply {
            text = "DEMONSTRAÇÃO DO PIPELINE RDP"
            setOnClickListener { runTrianglePipelineDemo() }
        }
        val cpuButton = Button(this).apply {
            text = "TESTE INTERNO DA CPU"
            setOnClickListener {
                setContentView(mainLayout)
                runCpuDiagnostic()
            }
        }
        val backButton = Button(this).apply {
            text = "VOLTAR AO MENU"
            setOnClickListener { setContentView(mainLayout) }
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(title)
            addView(videoButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(rdpButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 })
            addView(cpuButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 })
            addView(backButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 })
        }
        setContentView(page)
    }

    private fun runVideoTest() {
        try {
            val frame = N64VideoTestPattern.createFrame()
            showVideoTestPage(frame)
        } catch (error: Exception) {
            status.text = "Falha no teste de vídeo: ${error.javaClass.simpleName}: ${error.message}"
            setContentView(mainLayout)
        }
    }

    private fun runTrianglePipelineDemo() {
        try {
            val result = N64TrianglePipelineDemo.createFrame()
            showVideoTestPage(
                result.frame,
                title = "TRIÂNGULO RDP SINTÉTICO",
                description = "Lista de comandos sintética lida pelo DPC → RDP flat triangle → pixels RGBA5551 na RDRAM → captura pelos registradores VI → imagem Android. " +
                    "Comandos processados: ${result.commandsProcessed}; triângulos: ${result.trianglesRasterized}; pixels escritos: ${result.pixelsWritten}. " +
                    "Isto ainda não é uma ROM nem um jogo emulado."
            )
        } catch (error: Exception) {
            status.text = "Falha no pipeline de triângulo: ${error.javaClass.simpleName}: ${error.message}"
            setContentView(mainLayout)
        }
    }

    private fun showVideoTestPage(
        frame: N64VideoFrame,
        title: String = "TESTE DE VÍDEO",
        description: String = "Imagem sintética lida pelos registradores VI e exibida pelo Android. " +
            "Isto valida o caminho de vídeo de teste, não a renderização de um jogo."
    ) {
        val image = N64VideoPresenterView(this).apply {
            contentDescription = title
            submitFrame(frame)
        }
        val details = TextView(this).apply {
            text = title + "\n" +
                "Framebuffer: ${frame.width}x${frame.height}\n" +
                "Formato: RGBA5551 (16 bits)\n" +
                "Origem RDRAM: 0x${frame.origin.toString(16).uppercase()}\n\n" +
                description
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }
        val backButton = Button(this).apply {
            text = "Voltar ao menu"
            setOnClickListener { setContentView(mainLayout) }
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(details)
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
        if (diagnosticExportInProgress || romTraceInProgress || hleWorker != null || romVideoThread?.isAlive == true) {
            status.text = "Aguarde a operação atual terminar antes de selecionar outra ROM."
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_ROM)
    }

    /** Restores the last ROM only when Android still grants persisted document access. */
    private fun restoreLastRomSelection() {
        val savedUri = getSharedPreferences(ROM_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_ROM_URI, null) ?: return
        loadRomFromUri(Uri.parse(savedUri), rememberSelection = false, restoring = true)
    }

    /** Reads and validates a document off the UI thread to keep large ROM loads responsive. */
    private fun loadRomFromUri(uri: Uri, rememberSelection: Boolean, restoring: Boolean = false) {
        if (diagnosticExportInProgress || romTraceInProgress || hleWorker != null || romVideoThread?.isAlive == true) {
            status.text = "Não é possível trocar a ROM enquanto outra operação está em andamento."
            return
        }
        val loadGeneration = ++romLoadGeneration
        if (rememberSelection) getSharedPreferences(ROM_PREFS, MODE_PRIVATE).edit().remove(KEY_LAST_ROM_URI).apply()
        mainActionButton?.isEnabled = false
        status.text = if (restoring) "Restaurando a última ROM..." else "Lendo e verificando a ROM..."
        cartridgeBus = null
        cartridgeRomBytes = null

        Thread({
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { input -> N64RomLoader.readAllBytes(input) }
            } catch (_: Exception) {
                null
            }
            val displayName = try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "ROM selecionada"
            } catch (_: Exception) { "ROM selecionada" }

            runOnUiThread {
                if (isFinishing || isDestroyed || loadGeneration != romLoadGeneration) return@runOnUiThread
                if (bytes == null) {
                    if (restoring) getSharedPreferences(ROM_PREFS, MODE_PRIVATE).edit().remove(KEY_LAST_ROM_URI).apply()
                    mainActionButton?.apply { isEnabled = true; text = "ESCOLHER JOGO" }
                    status.text = "Não foi possível ler a ROM. Verifique se o arquivo ainda está acessível."
                    return@runOnUiThread
                }
                when (val result = N64RomLoader.load(bytes)) {
                    is RomLoadResult.Success -> {
                        cartridgeRomBytes = result.rom.bytes
                        cartridgeBus = N64Bus(cartridgeRom = result.rom.bytes)
                        romTraceButton?.isEnabled = true
                        hleBootButton?.isEnabled = true
                        mainActionButton?.apply { isEnabled = true; text = "▶ INICIAR JOGO" }
                        if (rememberSelection) {
                            getSharedPreferences(ROM_PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_LAST_ROM_URI, uri.toString()).apply()
                        }
                        val metadata = try {
                            if (result.rom.byteSize >= 0x60) {
                                "\n\n" + N64RomInspector.inspect(result.rom.bytes).toDisplayText()
                            } else ""
                        } catch (_: Exception) { "" }
                        status.text = "JOGO SELECIONADO\n$displayName" +
                            "\nTamanho: ${result.rom.byteSize} bytes" +
                            "\nFormato: ${result.rom.format}" +
                            metadata +
                            "\n\nROM pronta. Toque em INICIAR JOGO para testar a execução experimental."
                    }
                    is RomLoadResult.Failure -> {
                        if (restoring) getSharedPreferences(ROM_PREFS, MODE_PRIVATE).edit().remove(KEY_LAST_ROM_URI).apply()
                        romTraceButton?.isEnabled = false
                        hleBootButton?.isEnabled = false
                        mainActionButton?.apply { isEnabled = true; text = "ESCOLHER JOGO" }
                        status.text = result.reason
                    }
                }
            }
        }, "N64-ROM-LOADER").start()
    }

    @Deprecated("Uses the platform Activity result API for the minimum dependency base.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        var persisted = false
        val grantedRead = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persistable = data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (grantedRead != 0 && persistable != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                persisted = true
            } catch (_: SecurityException) { persisted = false }
        }
        if (!persisted) getSharedPreferences(ROM_PREFS, MODE_PRIVATE).edit().remove(KEY_LAST_ROM_URI).apply()
        loadRomFromUri(uri, rememberSelection = persisted)
    }
    companion object {
        private const val ROM_PREFS = "n64_rom_selection"
        private const val KEY_LAST_ROM_URI = "last_rom_uri"
        private const val REQUEST_ROM = 640
        private const val LOG_EXPORT_STOP_TIMEOUT_MS = 5_000L
    }
}
