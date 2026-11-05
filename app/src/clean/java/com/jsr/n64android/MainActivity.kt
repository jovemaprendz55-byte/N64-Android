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
    private var selectedRomUri: Uri? = null
    private var loadedCartridgeSession: N64HleCartridgeSession? = null

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
                    }
                    is N64RomDocumentReader.Result.Failure -> {
                        statusText.text = "Falha ao identificar a ROM. ${result.reason}"
                    }
                }
            }
        }, "N64-ROM-Reader").start()
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
