package com.jsr.n64android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var selectedRom: N64Rom? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }
        val title = TextView(this).apply {
            text = "N64 Android"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Núcleo próprio em construção. Nenhuma ROM carregada."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        val choose = Button(this).apply {
            text = "Selecionar ROM (.z64 / .n64 / .v64)"
            setOnClickListener { chooseRom() }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(choose)
        setContentView(layout)
    }

    private fun chooseRom() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_ROM)
    }

    @Deprecated("Deprecated by Android, retained for compatibility with the minimum SDK.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Não foi possível abrir o arquivo.")
            when (val result = N64RomLoader.load(bytes)) {
                is RomLoadResult.Success -> {
                    selectedRom = result.rom
                    status.text = "ROM reconhecida: ${result.rom.byteSize} bytes\nFormato: ${result.rom.format}\nCPU e boot ainda em desenvolvimento."
                }
                is RomLoadResult.Failure -> status.text = result.reason
            }
        } catch (_: Exception) {
            status.text = "Não foi possível ler esse arquivo."
        }
    }

    companion object {
        private const val REQUEST_ROM = 640
    }
}
