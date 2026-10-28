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
            status.text = "Não foi possível preparar IPL3 na SP DMEM: ${error.message ?: error.javaClass.simpleName}"
            return
        }
        // Keep enough execution budget to finish the IPL3 copy loop, while
        // retaining only the last 80 instructions so copied logs stay readable.
        val trace = ArrayDeque<String>()
        // Keep a compact register timeline around the observed IPL3 copy loop.
        // The final register dump alone cannot explain why a branch repeats.
        val loopCheckpoints = ArrayDeque<String>()
        val cpu = R4300Cpu(bus)
        // This is an artificial IPL3 diagnostic, not a real PIF/CIC boot.
        // The R4300 reset state leaves GPR[29] at zero; the copied routine later
        // uses the stack, so seed SP to the top of the installed 8 MiB RDRAM.
        // KSEG1 0xA07FFFE0 maps to physical RDRAM 0x007FFFE0.
        cpu.gpr[29] = 0xFFFFFFFFA07FFFE0UL.toLong()
        cpu.setProgramCounter(0xFFFFFFFFA4000040UL.toLong())
        var executed = 0
        var stopReason = "Limite de 10000 passos alcançado."
        try {
            for (stepIndex in 0 until 10000) {
                val pc = cpu.pc
                if (pc in 0xFFFFFFFFA4000498UL.toLong()..0xFFFFFFFFA40004B8UL.toLong()) {
                    // Include the instruction word as well as the pre-instruction register state.
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
                // Stop at the normal exception vector instead of reading zero-filled RDRAM
                // as if it were useful IPL3 code. Capture COP0 state for the next diagnosis.
                if (stepIndex > 0 && pc == 0xFFFFFFFF80000180UL.toLong()) {
                    stopReason = "CPU entrou no vetor de exceção 0xFFFFFFFF80000180; interrompido para evitar rastrear RAM zerada."
                    break
                }
                val physical = (pc and 0xFFFFFFFFL) and 0x1FFFFFFFL
                val instruction = bus.read32(physical.toInt())
                trace.addLast(
                    "PC=0x" + pc.toULong().toString(16).uppercase().padStart(16, '0') +
                    "  INST=0x" + instruction.toUInt().toString(16).uppercase().padStart(8, '0')
                )
                if (trace.size > 80) trace.removeFirst()
                cpu.step()
                executed++
                // Detect the exception vector immediately after the instruction that enters it.
                // This preserves the useful IPL3 trace and COP0 state instead of tracing zero-filled RAM.
                if (cpu.pc == 0xFFFFFFFF80000180UL.toLong() ||
                    cpu.pc == 0xFFFFFFFFBFC00380UL.toLong() ||
                    cpu.pc == 0xFFFFFFFFBFC00200UL.toLong()) {
                    stopReason = "CPU entrou em vetor de exceção/boot ROM 0x${cpu.pc.toULong().toString(16).uppercase()}; interrompido para preservar o rastro."
                    break
                }
            }
        } catch (error: Exception) {
            stopReason = "Parou em erro/área não implementada: ${error.message ?: error.javaClass.simpleName}"
        }
        status.text = "DIAGNÓSTICO PARCIAL DA ROM" +
            "\nPassos executados: $executed" +
            "\nPC final: 0x${cpu.pc.toULong().toString(16).uppercase()}" +
            "\nMotivo: $stopReason" +
            "\nCOP0 Status: 0x${cpu.cop0[12].toULong().toString(16).uppercase()}" +
            "\nCOP0 Cause: 0x${cpu.cop0[13].toULong().toString(16).uppercase()}" +
            "\nCOP0 EPC: 0x${cpu.cop0[14].toULong().toString(16).uppercase()}" +
            "\nCOP0 BadVAddr: 0x${cpu.cop0[8].toULong().toString(16).uppercase()}" +
            "\n\nIsto NÃO é o boot real do jogo. O IPL3 foi executado diretamente como teste; PIF/CIC e dispositivos ainda faltam." +
            "\n\nRASTRO DAS ÚLTIMAS INSTRUÇÕES (máximo 80):\n" + trace.joinToString("\n")
        val registerDump = (1..31).joinToString("\n") { index ->
            "GPR[$index]: 0x" + cpu.gpr[index].toULong().toString(16).uppercase().padStart(16, '0')
        }
        // The observed IPL3 loop uses KSEG1 address 0xA00002B4 as a destination.
        // Its physical address is 0x000002B4, so dumping only RDRAM[0..15] misses it.
        val ramRanges = listOf(0x0000..0x003C, 0x02A0..0x02E0, 0x1000..0x103C)
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
        status.append("\n\nLINHA DO TEMPO DO LOOP IPL3 (registradores antes da instrução):\n" +
            if (loopCheckpoints.isEmpty()) "O PC não passou pelo intervalo monitorado." else loopCheckpoints.joinToString("\n"))
        status.append("\n\nTODOS OS REGISTRADORES GPR NO PONTO DE PARADA:\n" + registerDump)
        status.append("\n\nRDRAM NOS ENDEREÇOS DE INTERESSE:\n" + ramDump)
        status.append("\n\nSP DMEM PERTO DA ORIGEM DE CÓPIA (0xA4000774):\n" + spDmemDump)
        status.append("\n\nSP IMEM AMOSTRA:\n" + spImemDump)
        showRomDiagnosticPage(status.text.toString())
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
