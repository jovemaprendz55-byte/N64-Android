package com.jovemaprendz55byte.n64android

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.util.Base64
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
    private lateinit var libraryContainer: LinearLayout
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
            text = "Biblioteca de ROMs"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, dp(24), 0, dp(8))
        }, matchWrap())
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(libraryContainer, matchWrap())
        root.addView(TextView(this).apply {
            text = "O autoteste executa um programa sintético; não comprova compatibilidade com jogos. O boot real, gráficos e áudio ainda estão incompletos."
            textSize = 13f
            setTextColor(Color.rgb(180, 185, 198))
            setPadding(0, dp(24), 0, dp(16))
        }, matchWrap())
        val scroll = ScrollView(this).apply { addView(root) }
        homeRoot = scroll
        refreshLibraryView()
        setContentView(scroll)
    }

    private fun startExperimentalEmulation() {
        val rom = preparedRom?.copyOf()
        if (rom == null) {
            showError("Selecione e prepare uma ROM válida antes de iniciar.")
            return
        }
        if (!emulationRunning.compareAndSet(false, true)) return

        // Keep Android from dimming/suspending the display during a long diagnostic run.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val core = N64EmulatorCore()
        try {
            core.loadRom(rom)
            // Development entry point only: the real PIF boot sequence is not implemented.
            core.startIpl3DiagnosticExecution()
        } catch (error: Exception) {
            emulationRunning.set(false)
            showError("Não foi possível iniciar a CPU: " + (error.message ?: "erro desconhecido"))
            return
        }

        val gameView = N64GameSurfaceView(this)
        val controls = N64TouchControllerView(this).apply { controllerPort = core.controllerPort }
        val gameStatus = TextView(this).apply {
            text = "Build experimental MI-FIX-20261020. Boot, RDP e áudio ainda estão incompletos."
            textSize = 11f
            maxLines = 8
            setTextIsSelectable(true)
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val diagnosticActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Copiar diagnóstico"
                setOnClickListener {
                    val logText = gameStatus.text?.toString().orEmpty()
                    if (logText.isBlank()) {
                        Toast.makeText(this@MainActivity, "Ainda não há diagnóstico para copiar.", Toast.LENGTH_SHORT).show()
                    } else {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico N64", logText))
                        Toast.makeText(this@MainActivity, "Diagnóstico copiado. Volte ao ChatGPT e cole a mensagem.", Toast.LENGTH_LONG).show()
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Compartilhar"
                setOnClickListener {
                    val logText = gameStatus.text?.toString().orEmpty()
                    if (logText.isBlank()) {
                        Toast.makeText(this@MainActivity, "Ainda não há diagnóstico para compartilhar.", Toast.LENGTH_SHORT).show()
                    } else {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico do emulador N64")
                            putExtra(Intent.EXTRA_TEXT, logText)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Enviar diagnóstico"))
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val gameLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(gameView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(controls, matchWrap())
            // Keep diagnostic actions visible; long logs scroll in a bounded area below them.
            addView(diagnosticActions, matchWrap())
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = false
                addView(gameStatus, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(112)
            ))
            addView(Button(this@MainActivity).apply {
                text = "Parar e voltar"
                setOnClickListener {
                    stopExperimentalEmulation()
                    homeRoot?.let { setContentView(it) }
                    status.text = "Emulação experimental interrompida."
                }
            }, matchWrap())
        }
        setContentView(gameLayout)

        emulationThread = Thread({
            val frame = N64VideoFrameBuffer(320, 240)
            var totalInstructions = 0L
            var renderedFrames = 0L
            var slicesSinceStatus = 0
            while (emulationRunning.get() && !Thread.currentThread().isInterrupted) {
                val result = core.executeSlice(20_000)
                totalInstructions += result.instructionsExecuted.toLong()
                if (result.stopReason == N64EmulatorCore.StopReason.EXECUTION_ERROR) {
                    emulationRunning.set(false)
                    runOnUiThread {
                        gameStatus.text = "[MI-FIX-20261020] CPU parou em PC 0x${result.programCounter.toString(16).uppercase(java.util.Locale.ROOT)}: " +
                            (result.errorMessage ?: "erro de execução")
                    }
                    break
                }
                if (core.renderVideoFrame(frame)) {
                    renderedFrames++
                    gameView.presentFrame(frame)
                }
                slicesSinceStatus++
                if (slicesSinceStatus >= 60) {
                    slicesSinceStatus = 0
                    val pcText = "0x" + result.programCounter.toULong().toString(16)
                        .uppercase(java.util.Locale.ROOT).padStart(16, '0')
                    val instructionText = totalInstructions.toString()
                    val frameText = renderedFrames.toString()
                    val videoText = core.videoDiagnosticSummary()
                    val cpuTraceText = core.executionDiagnosticSummary()
                    val fullCpuText = core.fullCpuDiagnosticSummary()
                    val bootMemoryText = core.bootMemoryDiagnosticSummary()
                    val historyTraceText = core.executionHistoryDiagnosticSummary()
                    runOnUiThread {
                        if (emulationRunning.get()) {
                            val shortHistory = historyTraceText.split(" | ").takeLast(12).joinToString(" | ")
                            gameStatus.text = "CPU: $instructionText instruções | PC $pcText | quadros: $frameText\n" +
                                "$videoText\n$cpuTraceText\n$fullCpuText\n$bootMemoryText\nHist.: $shortHistory"
                        }
                    }
                }
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
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    private fun runIpl3Diagnostic(core: N64EmulatorCore, budget: Int): N64EmulatorCore.ExecutionResult {
        core.startIpl3DiagnosticExecution()
        return core.executeSlice(budget)
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
            val result = runIpl3Diagnostic(core, 64)
            romDetails.text = buildString {
                append("Teste de execução da ROM (diagnóstico)\n")
                append("Instruções executadas: ").append(result.instructionsExecuted).append(" / 64\n")
                append("PC final: 0x").append(result.programCounter.toULong().toString(16).uppercase(java.util.Locale.ROOT).padStart(16, '0')).append('\n')
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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_ROM)
    }

    @Deprecated("Use the document picker result API in a future cleanup")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val grantedFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, grantedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions; the ROM still works for this session.
        }
        inspectRom(uri)
    }

    private fun inspectRom(uri: Uri, addToLibrary: Boolean = true) {
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
            if (addToLibrary) saveLibraryEntry(uri, normalizedHeader.title, originalHeader.format.name, normalized.size)
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
                append("ROM guardada na biblioteca do aparelho.")
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

    private data class LibraryEntry(val uri: String, val title: String, val format: String, val sizeBytes: Int)

    private fun encodeField(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeField(value: String): String =
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

    private fun loadLibraryEntries(): List<LibraryEntry> {
        val stored = getSharedPreferences(LIBRARY_PREFS, MODE_PRIVATE).getString(LIBRARY_KEY, "") ?: ""
        return stored.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            try {
                val fields = line.split('|')
                if (fields.size != 4) return@mapNotNull null
                LibraryEntry(decodeField(fields[0]), decodeField(fields[1]), decodeField(fields[2]), decodeField(fields[3]).toInt())
            } catch (_: Exception) {
                null
            }
        }.distinctBy { it.uri }.toList()
    }

    private fun saveLibraryEntry(uri: Uri, title: String, format: String, sizeBytes: Int) {
        val entries = loadLibraryEntries().toMutableList()
        entries.removeAll { it.uri == uri.toString() }
        entries.add(0, LibraryEntry(uri.toString(), title.ifBlank { "ROM sem título" }, format, sizeBytes))
        val serialized = entries.joinToString("\n") { entry ->
            listOf(entry.uri, entry.title, entry.format, entry.sizeBytes.toString())
                .joinToString("|") { encodeField(it) }
        }
        getSharedPreferences(LIBRARY_PREFS, MODE_PRIVATE).edit().putString(LIBRARY_KEY, serialized).apply()
        if (::libraryContainer.isInitialized) refreshLibraryView()
    }

    private fun removeLibraryEntry(uri: String) {
        val serialized = loadLibraryEntries().filterNot { it.uri == uri }.joinToString("\n") { entry ->
            listOf(entry.uri, entry.title, entry.format, entry.sizeBytes.toString())
                .joinToString("|") { encodeField(it) }
        }
        getSharedPreferences(LIBRARY_PREFS, MODE_PRIVATE).edit().putString(LIBRARY_KEY, serialized).apply()
        if (::libraryContainer.isInitialized) refreshLibraryView()
    }

    private fun refreshLibraryView() {
        libraryContainer.removeAllViews()
        val entries = loadLibraryEntries()
        if (entries.isEmpty()) {
            libraryContainer.addView(TextView(this).apply {
                text = "Nenhuma ROM salva ainda. Toque em “Selecionar e preparar ROM” para adicionar uma."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(4), 0, dp(12))
            }, matchWrap())
            return
        }

        entries.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.rgb(28, 36, 53))
            }
            card.addView(TextView(this).apply {
                text = entry.title.ifBlank { "ROM sem título" }
                textSize = 16f
                setTextColor(Color.WHITE)
            }, matchWrap())
            card.addView(TextView(this).apply {
                text = "${entry.format} • ${formatSize(entry.sizeBytes)}"
                textSize = 12f
                setTextColor(Color.rgb(170, 185, 210))
                setPadding(0, dp(3), 0, dp(6))
            }, matchWrap())
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this@MainActivity).apply {
                text = "Carregar"
                setOnClickListener { inspectRom(Uri.parse(entry.uri), addToLibrary = false) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(Button(this@MainActivity).apply {
                text = "Remover"
                setOnClickListener { removeLibraryEntry(entry.uri) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(actions, matchWrap())
            val cardParams = matchWrap().apply { bottomMargin = dp(8) }
            libraryContainer.addView(card, cardParams)
        }
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
        private const val LIBRARY_PREFS = "n64_rom_library"
        private const val LIBRARY_KEY = "entries_v1"
    }
}
