package com.jsr.n64android

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First native launcher screen for the clean HLE reboot.
 *
 * ROM selection and bounded cartridge inspection use Android's Storage Access
 * Framework. A successful inspection does not boot or execute the selected game.
 */
class MainActivity : Activity() {
    private lateinit var selectedRomText: TextView
    private lateinit var statusText: TextView
    private lateinit var cpuDiagnosticsText: TextView
    private lateinit var hlePreparationText: TextView
    private lateinit var prepareHleButton: Button
    private var selectedRomUri: Uri? = null
    private var loadedCartridgeSession: N64HleCartridgeSession? = null
    private var preparedHandoff: N64HlePreparedHandoff? = null
    private var preparedExecutionSession: N64HleR4300ExecutionSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 17, 27)
        window.navigationBarColor = Color.rgb(12, 17, 27)
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(Color.rgb(12, 17, 27))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        root.addView(label("N64 ANDROID", 13, Color.rgb(91, 190, 255), true))
        root.addView(space(10))
        root.addView(label("Seu Nintendo 64 no Android", 25, Color.WHITE, true))
        root.addView(space(8))
        root.addView(label(
            "Projeto de emulador próprio • HLE em desenvolvimento",
            14,
            Color.rgb(177, 190, 207),
            false
        ))
        root.addView(space(28))

        root.addView(sectionTitle("BIBLIOTECA DE JOGOS"))
        root.addView(space(10))

        val romPanel = panel()
        romPanel.addView(label("ROM selecionada", 14, Color.rgb(177, 190, 207), false))
        selectedRomText = label("Nenhum jogo selecionado", 16, Color.WHITE, true)
        romPanel.addView(space(8))
        romPanel.addView(selectedRomText)
        root.addView(romPanel)
        root.addView(space(14))

        val chooseButton = Button(this).apply {
            text = "ESCOLHER ROM"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { openRomPicker() }
        }
        root.addView(chooseButton, matchWidthWrapHeight())

        root.addView(space(18))
        root.addView(sectionTitle("PREPARAÇÃO HLE MANUAL"))
        root.addView(space(8))
        val hlePanel = panel()
        hlePreparationText = label(
            "Selecione uma ROM compatível para verificar o perfil CIC 6102/7101. Nenhuma CPU será executada automaticamente.",
            13,
            Color.WHITE,
            false
        )
        hlePanel.addView(hlePreparationText)
        hlePanel.addView(space(12))
        prepareHleButton = Button(this).apply {
            text = "PREPARAR HANDOFF HLE"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { prepareSelectedHleHandoff() }
        }
        hlePanel.addView(prepareHleButton, matchWidthWrapHeight())
        root.addView(hlePanel)

        root.addView(space(28))
        root.addView(sectionTitle("ESTADO DO EMULADOR"))
        root.addView(space(10))

        val statusPanel = panel()
        statusText = label(
            "Aplicativo aberto. Núcleo de emulação ainda em desenvolvimento.",
            14,
            Color.WHITE,
            false
        )
        statusPanel.addView(label("STATUS", 12, Color.rgb(91, 190, 255), true))
        statusPanel.addView(space(8))
        statusPanel.addView(statusText)
        root.addView(statusPanel)

        root.addView(space(22))
        root.addView(sectionTitle("DIAGNÓSTICO DA CPU R4300"))
        root.addView(space(10))
        val diagnosticsPanel = panel()
        cpuDiagnosticsText = label("", 13, Color.WHITE, false)
        diagnosticsPanel.addView(cpuDiagnosticsText)
        root.addView(diagnosticsPanel)
        renderCpuDiagnostics(null)

        root.addView(space(22))
        root.addView(label(
            "Esta versão lê e identifica o cabeçalho da ROM selecionada. " +
                "A execução do jogo, vídeo e áudio ainda não estão conectados à tela.",
            13,
            Color.rgb(177, 190, 207),
            false
        ))
        root.addView(space(18))
        root.addView(label("N64 Android • versão de desenvolvimento", 12, Color.rgb(115, 130, 150), false))

        setContentView(scroll)
    }

    /**
     * Renders real session diagnostics when a CPU session is supplied.
     * Null means there is no active guest CPU session; no counters or PCs are fabricated.
     */
    private fun renderCpuDiagnostics(session: N64HleR4300ExecutionSession?) {
        if (session == null) {
            cpuDiagnosticsText.text = buildString {
                appendLine("Nenhuma sessão de CPU ativa.")
                appendLine("Instruções executadas: —")
                appendLine("Último PC: —")
                appendLine("Última instrução: —")
                appendLine("Última falha: —")
                append("A ROM pode estar identificada, mas ainda falta um perfil HLE validado.")
            }
            return
        }

        val snapshot = session.diagnostics()
        cpuDiagnosticsText.text = buildString {
            appendLine("Instruções executadas: ${snapshot.totalExecutedInstructions}")
            appendLine("Último PC concluído: ${snapshot.lastSuccessfulInstructionPc?.let(::formatAddress) ?: "—"}")
            appendLine(
                "Última instrução: " +
                    (snapshot.lastSuccessfulInstruction?.rawWord?.let {
                        "0x" + it.toUInt().toString(16).uppercase().padStart(8, '0')
                    } ?: "—")
            )
            appendLine("PC da última tentativa: ${snapshot.lastAttemptedInstructionPc?.let(::formatAddress) ?: "—"}")
            appendLine("Tipo da falha: ${snapshot.failureType ?: "—"}")
            append("Mensagem: ${snapshot.failureMessage ?: "—"}")
        }
    }

    private fun formatAddress(address: Long): String =
        "0x" + address.toULong().toString(16).uppercase().padStart(16, '0')

    private fun openRomPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK_ROM)
    }

    @Deprecated("Uses the platform activity result contract to avoid an AndroidX dependency in the clean source set.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_ROM || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers return a temporary grant only.
        }
        selectedRomUri = uri
        loadedCartridgeSession = null
        preparedHandoff = null
        preparedExecutionSession = null
        prepareHleButton.isEnabled = false
        hlePreparationText.text = "Aguardando inspeção da nova ROM."
        renderCpuDiagnostics(null)
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Arquivo selecionado"
        selectedRomText.text = name
        statusText.text = "Lendo arquivo e verificando o cabeçalho da ROM..."
        inspectSelectedRom(uri)
    }

    private fun inspectSelectedRom(uri: Uri) {
        Thread({
            val result = try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("O provedor não abriu o arquivo.")
                stream.use { N64RomDocumentReader.read(it) }
            } catch (error: Exception) {
                N64RomDocumentReader.Result.Failure(
                    "Não foi possível abrir a ROM: ${error.message ?: "erro desconhecido"}."
                )
            }

            runOnUiThread {
                when (result) {
                    is N64RomDocumentReader.Result.Success -> {
                        val cartridge = result.cartridge
                        loadedCartridgeSession = N64HleCartridgeSession.fromValidatedImage(cartridge)
                        preparedHandoff = null
                        preparedExecutionSession = null
                        prepareHleButton.isEnabled = true
                        hlePreparationText.text = "ROM carregada. Toque em PREPARAR HANDOFF HLE para validar CIC 6102/7101 e preparar a memória convidada."
                        val byteOrder = when (cartridge.byteOrder) {
                            N64HleCartridgeImage.ByteOrder.BIG_ENDIAN -> "Z64 (big-endian)"
                            N64HleCartridgeImage.ByteOrder.BYTE_SWAPPED_16 -> "V64 (bytes trocados)"
                            N64HleCartridgeImage.ByteOrder.LITTLE_ENDIAN_32 -> "N64 (palavras invertidas)"
                        }
                        statusText.text = buildString {
                            appendLine("ROM identificada com sucesso.")
                            appendLine("Título: ${cartridge.title.ifBlank { "(sem título)" }}")
                            appendLine("Tamanho: ${result.sourceSizeBytes} bytes")
                            appendLine("Formato: $byteOrder")
                            appendLine("CRC1: 0x${cartridge.crc1.toString(16).uppercase().padStart(8, '0')}")
                            append("CRC2: 0x${cartridge.crc2.toString(16).uppercase().padStart(8, '0')}")
                            appendLine()
                            appendLine("ROM mantida na sessão de memória.")
                            append("CPU/HLE boot ainda não foram iniciados.")
                        }
                        renderCpuDiagnostics(null)
                    }
                    is N64RomDocumentReader.Result.Failure -> {
                        loadedCartridgeSession = null
                        preparedHandoff = null
                        preparedExecutionSession = null
                        prepareHleButton.isEnabled = false
                        hlePreparationText.text = "Não foi possível preparar: ROM não identificada."
                        statusText.text = "Falha ao identificar a ROM. ${result.reason}"
                        renderCpuDiagnostics(null)
                    }
                }
            }
        }, "N64-ROM-Reader").start()
    }

    private fun prepareSelectedHleHandoff() {
        val cartridgeSession = loadedCartridgeSession
        if (cartridgeSession == null) {
            hlePreparationText.text = "Nenhuma ROM válida está carregada."
            return
        }

        prepareHleButton.isEnabled = false
        hlePreparationText.text = "Verificando CIC 6102/7101, CRC1/CRC2 e preparando RDRAM..."
        try {
            val profile = N64HleBootProfileFactory.createCic6102Profile(cartridgeSession.cartridge)
            val handoff = cartridgeSession.prepareHleHandoff(profile)
            val executionSession = handoff.createR4300ExecutionSession()
            preparedHandoff = handoff
            preparedExecutionSession = executionSession
            renderCpuDiagnostics(executionSession)
            hlePreparationText.text = buildString {
                appendLine("HANDOFF HLE PREPARADO")
                appendLine("Perfil: CIC 6102/7101 (seed 0x3F)")
                appendLine("PC de entrada: 0x${handoff.cpuEntryState.programCounter.toString(16).uppercase()}")
                appendLine("RDRAM preparada: ${handoff.guestMemory.sizeBytes} bytes")
                appendLine("CPU R4300: sessão criada, 0 instruções executadas")
                append("IPL3/PIF não executados; vídeo, RSP e áudio não iniciados.")
            }
        } catch (error: Exception) {
            preparedHandoff = null
            preparedExecutionSession = null
            renderCpuDiagnostics(null)
            hlePreparationText.text = "PERFIL HLE REJEITADO\n${error.message ?: error.javaClass.simpleName}"
        } finally {
            prepareHleButton.isEnabled = true
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Color.rgb(25, 34, 48))
    }

    private fun sectionTitle(value: String): TextView =
        label(value, 12, Color.rgb(91, 190, 255), true)

    private fun label(value: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            contentDescription = value
        }

    private fun space(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun matchWidthWrapHeight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_PICK_ROM = 6401
    }
}
