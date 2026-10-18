package com.jovemaprendz55byte.n64android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var romDetails: TextView

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
            text = "Selecionar ROM"
            setOnClickListener { openRomPicker() }
        }, matchWrap())
        status = TextView(this).apply {
            text = "Escolha um arquivo de jogo N64 (.z64, .v64 ou .n64)."
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
            text = "Aviso: esta primeira versão apenas identifica o cabeçalho da ROM. CPU, áudio, gráficos e execução dos jogos ainda serão desenvolvidos."
            textSize = 13f
            setTextColor(Color.rgb(180, 185, 198))
            setPadding(0, dp(32), 0, 0)
        }, matchWrap())
        setContentView(root)
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
        try {
            val headerBytes = contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(0x40)
                var offset = 0
                while (offset < buffer.size) {
                    val count = input.read(buffer, offset, buffer.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset == buffer.size) buffer else buffer.copyOf(offset)
            } ?: throw IllegalStateException("Não foi possível abrir o arquivo.")
            val header = RomHeader.parse(headerBytes)
            if (header == null) {
                status.text = "O arquivo selecionado não tem um cabeçalho N64 reconhecido."
                romDetails.text = ""
                return
            }
            status.text = "Cabeçalho N64 reconhecido."
            romDetails.text = buildString {
                append("Título: ").append(header.title).append('\n')
                append("Formato: ").append(when (header.format) {
                    RomHeader.Format.Z64_BIG_ENDIAN -> ".z64 (big-endian)"
                    RomHeader.Format.V64_BYTE_SWAPPED -> ".v64 (bytes trocados)"
                    RomHeader.Format.N64_LITTLE_ENDIAN -> ".n64 (little-endian)"
                })
                header.countryCode?.let { append('\n').append("Código de região: ").append(it) }
            }
        } catch (error: Exception) {
            status.text = "Erro ao ler o arquivo: " + (error.message ?: "erro desconhecido")
            romDetails.text = ""
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object { private const val REQUEST_ROM = 1001 }
}
