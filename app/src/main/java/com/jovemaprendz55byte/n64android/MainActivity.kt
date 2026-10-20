package com.jovemaprendz55byte.n64android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var romDetails: TextView
    // Retain the normalized ROM in memory so the emulator core can use it later.
    private var preparedRom: ByteArray? = null
    private var homeRoot: View? = null
    private val emulationRunning = AtomicBoolean(false)
    private var emulationThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
            setBackgroundColor(Color.rgb(15, 20, 32))
        }
        root.addView(TextView(this).apply {
            text = "N64 Android"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Projeto em desenvolvimento"
            textSize = 16f
            setTextColor(Color.rgb(160, 190, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Selecionar e preparar ROM"
            setOnClickListener { openRomPicker() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Executar autoteste da CPU"
            setOnClickListener { runCpuSelfTest() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Testar execução inicial da ROM (64 instruções)"
            setOnClickListener { runRomInstructionTest() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Iniciar emulação experimental"
            setOnClickListener { startExperimentalEmulation() }
        }, matchWrap())
        status = TextView(this).apply {
            text = "Escolha uma ROM N64 válida (.z64, .v64 ou .n64)."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(24), 0, dp(12))
        }
        root.addView(status, matchWrap())
        romDetails = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        root.addView(romDetails, matchWrap())
        root.addView(TextView(this).apply {
            text = "O autoteste executa uma instrução sintética na CPU; ele não inicia a ROM nem comprova compatibilidade com jogos. O boot real, gráficos e áudio ainda não estão implementados."
            textSize = 13f
            setTextColor(Color.rgb(180, 185, 198))
            setPadding(0, dp(32), 0, 0)
        }, matchWrap())
        homeRoot = root
        setContentView(root)
    }

    private fun startExperimentalEmulation() {
        val rom = preparedRom?.copyOf()
        if (rom == null) {
            showError("Selecione e prepare uma ROM válida antes de iniciar.")
            return
        }
        if (!emulationRunning.compareAndSet(false, true)) return

        val core = N64EmulatorCore()
        try {
            core.loadRom(rom)
            // Development entry point only: the real PIF boot sequence is not implemented.
            core.startExecution(0xFFFF_FFFF_B000_0040UL.toLong())
        } catch (error: Exception) {
            emulationRunning.set(false)
            showError("Não foi possível iniciar a CPU: " + (error.message ?: "erro desconhecido"))
            return
        }

        val gameView = N64GameSurfaceView(this)
        val controls = N64TouchControllerView(this).apply { controllerPort = core.controllerPort }
        val gameStatus = TextView(this).apply {
            text = "Emulação experimental iniciada. Boot, RDP e áudio ainda estão incompletos."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val gameLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(gameView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(controls, matchWrap())
            addView(gameStatus, matchWrap())
            addView(Button(this@MainActivity).apply {
                text = "Parar e voltar"
                setOnClickListener {
                    stopExperimentalEmulation()
                    setContentView(homeRoot)
                    status.text = "Emulação experimental interrompida."
                }
            }, matchWrap())
        }
        setContentView(gameLayout)

        emulationThread = Thread({
            val frame = N64VideoFrameBuffer(320, 240)
            while (emulationRunning.get() && !Thread.currentThread().isInterrupted) {
                val result = core.executeSlice(20_000)
                if (result.stopReason == N64EmulatorCore.StopReason.EXECUTION_ERROR) {
                    emulationRunning.set(false)
                    runOnUiThread {
                        gameStatus.text = "CPU parou em PC 0x${result.programCounter.toString(16).uppercase(java.util.Locale.ROOT)}: " +
                            (result.errorMessage ?: "erro de execução")
                    }
                    break
                }
                if (core.renderVideoFrame(frame)) gameView.presentFrame(frame)
                try {
                    Thread.sleep(16L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }, "N64-Emulation-Loop").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopExperimentalEmulation() {
        emulationRunning.set(false)
        emulationThread?.interrupt()
        emulationThread = null
    }

    override fun onDestroy() {
        stopExperimentalEmulation()
        super.onDestroy()
    }

    private fun runCpuSelfTest() {
        val result = N64CpuSelfTest.run()
        status.text = if (result.success) {
            "Autoteste da CPU aprovado."
        } else {
            "Autoteste da CPU falhou."
        }
        romDetails.text = buildString {
            append("Tipo: programa sintético (não é uma ROM de jogo)\n")
            append("Instruções executadas: ").append(result.instructionsExecuted).append('\n')
            append("Instruções esperadas: 8 (ADDIU, ADDU, SW/LW e branch)\n")
            append("Valor esperado da soma: ").append(result.expectedRegisterValue).append('\n')
            append("Soma obtida em r3: ").append(result.actualRegisterValue).append('\n')
            append("Valor recuperado da RAM em r4: ").append(result.loadedBackValue).append('\n')
            append("Valor do delay slot em r5: ").append(result.delaySlotValue).append('\n')
            append("Valor do destino do branch em r6: ").append(result.branchTargetValue).append('\n')
            append("Contador de programa: 0x").append(result.programCounter.toString(16).uppercase(java.util.Locale.ROOT))
        }
    }

    private fun runRomInstructionTest() {
        val rom = preparedRom
        if (rom == null) {
            showError("Selecione e prepare uma ROM válida antes de executar o teste.")
            return
        }

        status.text = "Executando até 64 instruções iniciais da ROM..."
        try {
            val core = N64EmulatorCore()
            core.loadRom(rom)
            // This is a diagnostic entry into the cartridge boot code, not a
            // replacement for the missing PIF boot and hardware initialization.
            val result = core.executeFrom(0xFFFF_FFFF_B000_0040UL.toLong(), 64)
            romDetails.text = buildString {
                append("Teste de execução da ROM (diagnóstico)\n")
                append("Instruções executadas: ").append(result.instructionsExecuted).append(" / 64\n")
                append("PC final: 0x").append(result.programCounter.toString(16).uppercase(java.util.Locale.ROOT)).append('\n')
                append("Resultado: ").append(when (result.stopReason) {
                    N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED -> "limite de instruções atingido"
                    N64EmulatorCore.StopReason.EXECUTION_ERROR -> "erro durante a execução"
                }).append('\n')
                result.errorMessage?.let { append("Erro: ").append(it).append('\n') }
                append("Observação: este teste não executa o boot completo nem comprova compatibilidade com jogos.")
            }
            status.text = if (result.stopReason == N64EmulatorCore.StopReason.INSTRUCTION_BUDGET_REACHED) {
                "A CPU executou as 64 instruções do teste."
            } else {
                "A execução da ROM parou em um erro; veja o diagnóstico abaixo."
            }
        } catch (error: Exception) {
            showError("Não foi possível iniciar o teste da ROM: " + (error.message ?: "erro desconhecido"))
        }
    }

    private fun openRomPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_ROM)
    }

    @Deprecated("Use the document picker result API in a future cleanup")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        inspectRom(uri)
    }

    private fun inspectRom(uri: Uri) {
        preparedRom = null
        try {
            val rawRom = contentResolver.openInputStream(uri)?.use { input ->
                readRomLimited(input)
            } ?: throw IllegalStateException("Não foi possível abrir o arquivo.")

            if (rawRom.size < MIN_ROM_SIZE) {
                showError("Arquivo muito pequeno para uma ROM N64 válida (mínimo de 4 KiB).")
                return
            }
            if (rawRom.size % 4 != 0) {
                showError("O tamanho da ROM não é múltiplo de 4 bytes.")
                return
            }

            val originalHeader = RomHeader.parse(rawRom)
            if (originalHeader == null) {
                showError("O arquivo selecionado não tem um cabeçalho N64 reconhecido.")
                return
            }

            val normalized = RomNormalizer.normalize(rawRom)
            if (normalized == null) {
                showError("Não foi possível normalizar esta ROM.")
                return
            }

            val normalizedHeader = RomHeader.parse(normalized)
            if (normalizedHeader == null) {
                showError("O cabeçalho ficou inválido após a conversão.")
                return
            }

            preparedRom = normalized
            status.text = "ROM validada e preparada em memória."
            romDetails.text = buildString {
                append("Título: ").append(normalizedHeader.title).append('\n')
                append("Formato original: ").append(when (originalHeader.format) {
                    RomHeader.Format.Z64_BIG_ENDIAN -> ".z64 (big-endian)"
                    RomHeader.Format.V64_BYTE_SWAPPED -> ".v64 (bytes trocados)"
                    RomHeader.Format.N64_LITTLE_ENDIAN -> ".n64 (little-endian)"
                }).append('\n')
                append("Formato preparado: Z64 (big-endian)").append('\n')
                append("Tamanho: ").append(formatSize(normalized.size)).append('\n')
                originalHeader.countryCode?.let {
                    append("Código de região: ").append(it).append('\n')
                }
                append("Próxima etapa: conectar esta ROM ao núcleo de emulação.")
            }
        } catch (error: Exception) {
            showError("Erro ao ler a ROM: " + (error.message ?: "erro desconhecido"))
        }
    }

    private fun readRomLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > MAX_ROM_SIZE - count) {
                throw IllegalArgumentException("A ROM excede o limite de 64 MiB.")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun showError(message: String) {
        status.text = message
        romDetails.text = ""
    }

    private fun formatSize(bytes: Int): String =
        if (bytes >= 1024 * 1024) {
            String.format(java.util.Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0))
        } else {
            String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val REQUEST_ROM = 1001
        private const val MIN_ROM_SIZE = 0x1000
        private const val MAX_ROM_SIZE = 64 * 1024 * 1024
    }
}
