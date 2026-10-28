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
    private var romTraceButton: Button? = null
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
            text = "Executar diagnóstico de instruções da ROM"
            setOnClickListener { runRomInstructionDiagnostic() }
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
        }
        setContentView(mainLayout)
    }

    /**
     * Runs a known instruction sequence on the real R4300Cpu implementation.
     * This is an internal CPU test, not a claim that the selected game boots.
     */
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
        // Detect a tight two-PC loop early instead of spending millions of steps
        // repeatedly executing a branch and its delay slot. Keep the full trace
        // and register dump so the first stable loop can be diagnosed.
        var previousPc = Long.MIN_VALUE
        var twoStepsAgoPc = Long.MIN_VALUE
        var repeatedTwoPcVisits = 0
        // The BNE at 0x8000018C compares t0 with ra (0x00100000), not with t1).
        // t0 advances by four bytes, so this routine can need about 262,144 iterations
        // (~5.2 million instructions) before reaching its end. Keep a bounded 6M-step test.
        val maxSteps = 6_000_000
        var stopReason = "Limite de $maxSteps passos alcançado (rastro amostrado a cada 256 instruções)."
        try {
            for (stepIndex in 0 until maxSteps) {
                val pc = cpu.pc
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
                val physical = (pc and 0xFFFFFFFFL) and 0x1FFFFFFFL
                val instruction = bus.read32(physical.toInt())
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
                // Stop at a vector only when the CPU actually recorded exception entry.
                // This avoids false positives when ordinary sequential execution reaches 0x80000180.
                val enteredException = (cpu.cop0[12] and 0x2L) != 0L
                val atExceptionVector = cpu.pc == 0xFFFFFFFF80000180UL.toLong() ||
                    cpu.pc == 0xFFFFFFFFBFC00380UL.toLong() ||
                    cpu.pc == 0xFFFFFFFFBFC00200UL.toLong()
                if (enteredException && atExceptionVector) {
                    stopReason = "CPU registrou entrada em vetor de exceção 0x${cpu.pc.toULong().toString(16).uppercase()}; interrompido para preservar o rastro."
                    break
                }
            }
        } catch (error: Exception) {
            stopReason = "Parou em erro/área não implementada: ${error.message ?: error.javaClass.simpleName}"
        }
        var diagnostic = "DIAGNÓSTICO PARCIAL DA ROM" +
            "\nPassos executados: $executed" +
            "\nPC final: 0x${cpu.pc.toULong().toString(16).uppercase()}" +
            "\nMotivo: $stopReason" +
            "\nCOP0 Status: 0x${cpu.cop0[12].toULong().toString(16).uppercase()}" +
            "\nCOP0 Cause: 0x${cpu.cop0[13].toULong().toString(16).uppercase()}" +
            "\nCOP0 EPC: 0x${cpu.cop0[14].toULong().toString(16).uppercase()}" +
            "\nCOP0 BadVAddr: 0x${cpu.cop0[8].toULong().toString(16).uppercase()}" +
            "\n\nIsto NÃO é o boot real do jogo. O IPL3 foi executado diretamente como teste; PIF/CIC e dispositivos ainda faltam." +
            "\n\nPRIMEIRA PASSAGEM PELO LOOP KSEG0 (máximo 48):\n" + firstLoopTrace.joinToString("\n") +
            "\n\nRASTRO DAS ÚLTIMAS INSTRUÇÕES (máximo 80):\n" + trace.joinToString("\n")
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
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
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
                cartridgeBus = N64Bus(cartridgeRom = result.rom.bytes)
                romTraceButton?.isEnabled = true
                val info = N64RomInspector.inspect(result.rom.bytes)
                status.text = "ROM carregada no barramento: ${result.rom.byteSize} bytes" +
                    "\nFormato: ${result.rom.format}" +
                    "\nEndereço físico: 0x10000000" +
                    "\n\n" + info.toDisplayText() +
                    "\n\nDiagnóstico do cabeçalho concluído. O jogo ainda não foi inicializado: faltam boot PIF/CIC e integração completa do núcleo."
            }
            is RomLoadResult.Failure -> {
                cartridgeBus = null
                romTraceButton?.isEnabled = false
                status.text = result.reason
            }
        }
    }

    companion object { private const val REQUEST_ROM = 640 }
}
