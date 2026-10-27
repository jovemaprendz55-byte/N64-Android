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
        val trace = StringBuilder()
        val cpu = R4300Cpu(bus)
        // Diagnostic only: execute the first cartridge IPL3 words through the
        // KSEG1 cartridge mapping. This deliberately does not emulate PIF/CIC boot.
        cpu.setProgramCounter(0xFFFFFFFFB0000040UL.toLong())
        var executed = 0
        var stopReason = "Limite de 32 passos alcançado."
        try {
            repeat(32) {
                val pc = cpu.pc
                val physical = (pc and 0xFFFFFFFFL) and 0x1FFFFFFFL
                val instruction = bus.read32(physical.toInt())
                trace.append("PC=0x").append(pc.toULong().toString(16).uppercase().padStart(16, '0'))
                    .append("  INST=0x").append(instruction.toUInt().toString(16).uppercase().padStart(8, '0'))
                    .appendLine()
                cpu.step()
                executed++
            }
        } catch (error: Exception) {
            stopReason = "Parou em erro/área não implementada: ${error.message ?: error.javaClass.simpleName}"
        }
        status.text = "DIAGNÓSTICO PARCIAL DA ROM" +
            "\nPassos executados: $executed" +
            "\nPC final: 0x${cpu.pc.toULong().toString(16).uppercase()}" +
            "\nMotivo: $stopReason" +
            "\n\nIsto NÃO é o boot real do jogo. O IPL3 foi executado diretamente como teste; PIF/CIC e dispositivos ainda faltam." +
            "\n\nRASTRO DE INSTRUÇÕES:\n" + trace.toString()
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
