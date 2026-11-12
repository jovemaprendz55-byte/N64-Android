package com.jsr.n64android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Vulkan-only application shell.
 * EGL/GLSurfaceView gameplay and diagnostics are removed from this Activity.
 * Vulkan is not connected yet; the Play button never starts the legacy GL path.
 */
class MainActivity : Activity() {
    private val romPickerRequestCode = 4201
    private lateinit var selectedRomView: TextView
    private lateinit var playVulkanButton: Button
    private lateinit var homeLayout: LinearLayout
    private var audioReport = "Ainda não foi feita a leitura do diagnóstico."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildHomeScreen()
        setContentView(homeLayout)
        refreshSelectedRom()
    }

    private fun buildHomeScreen() {
        selectedRomView = TextView(this).apply {
            text = "Nenhuma ROM selecionada"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(12, 16, 12, 16)
        }
        playVulkanButton = Button(this).apply {
            text = "JOGAR VULKAN"
            isEnabled = false
            setOnClickListener {
                if (!selectedRomFile().isFile) {
                    selectedRomView.text = "Selecione uma ROM N64 primeiro."
                    isEnabled = false
                } else {
                    selectedRomView.text =
                        "ROM pronta. O renderizador Vulkan ainda está sendo integrado. " +
                        "O jogo não será iniciado pelo EGL/OpenGL."
                    Toast.makeText(
                        this@MainActivity,
                        "Backend Vulkan ainda em desenvolvimento.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        homeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            addView(TextView(this@MainActivity).apply {
                text = "N64 Android"
                textSize = 30f
                gravity = Gravity.CENTER
                setPadding(12, 24, 12, 12)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Emulador Nintendo 64 para Android"
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(12, 8, 12, 24)
            })
            addView(selectedRomView)
            addView(Button(this@MainActivity).apply {
                text = "Selecionar ROM"
                setOnClickListener { openRomPicker() }
            })
            addView(playVulkanButton)
            addView(Button(this@MainActivity).apply {
                text = "DIAGNÓSTICO DO ÁUDIO"
                setOnClickListener { showAudioDiagnostics() }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Renderizador Vulkan em desenvolvimento."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 28, 16, 12)
            })
        }
    }

    private fun selectedRomFile(): File =
        File(File(filesDir, "roms"), "selected.n64")

    private fun refreshSelectedRom() {
        val romFile = selectedRomFile()
        val ready = romFile.isFile && romFile.length() >= 4096
        selectedRomView.text = if (ready) {
            "ROM N64 selecionada (${romFile.length()} bytes)"
        } else {
            "Nenhuma ROM selecionada"
        }
        playVulkanButton.isEnabled = ready
    }

    private fun openRomPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, romPickerRequestCode)
        } catch (error: Exception) {
            selectedRomView.text = "Falha ao abrir seletor de ROM: ${error.message}"
        }
    }

    @Deprecated("Compatibilidade com a Activity atual")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != romPickerRequestCode) return
        if (resultCode != RESULT_OK) {
            selectedRomView.text = "Seleção de ROM cancelada."
            return
        }
        val uri = data?.data ?: run {
            selectedRomView.text = "Nenhum arquivo foi selecionado."
            return
        }
        selectedRomView.text = "Lendo e validando a ROM..."
        Thread {
            val result = try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Não foi possível abrir o arquivo.")
                when {
                    bytes.size < 4096 -> throw IllegalArgumentException("Arquivo menor que 4 KB.")
                    bytes.size > 64 * 1024 * 1024 -> throw IllegalArgumentException("Arquivo maior que 64 MB.")
                    bytes.size % 4 != 0 -> throw IllegalArgumentException("Tamanho não é múltiplo de 4 bytes.")
                }
                val signature = bytes.take(4).joinToString(" ") {
                    "%02X".format(it.toInt() and 0xff)
                }
                val valid = signature == "80 37 12 40" ||
                    signature == "37 80 40 12" ||
                    signature == "40 12 37 80"
                if (!valid) throw IllegalArgumentException(
                    "Assinatura não reconhecida como ROM N64: $signature"
                )
                val directory = File(filesDir, "roms")
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException("Não foi possível criar a pasta de ROMs.")
                }
                selectedRomFile().writeBytes(bytes)
                "ROM N64 reconhecida e copiada (${bytes.size} bytes)."
            } catch (error: Exception) {
                "Falha ao validar ROM: ${error.message}"
            }
            runOnUiThread {
                selectedRomView.text = result
                refreshSelectedRom()
                if (result.startsWith("ROM N64 reconhecida")) selectedRomView.text = result
            }
        }.start()
    }

    private fun showAudioDiagnostics() {
        val reportView = TextView(this).apply {
            text = "Lendo registros do áudio..."
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }
        fun refresh() {
            reportView.text = "Consultando logcat..."
            Thread {
                val result = try {
                    val process = ProcessBuilder("logcat", "-d", "-t", "2000")
                        .redirectErrorStream(true).start()
                    val lines = process.inputStream.bufferedReader().use { it.readLines() }
                    process.waitFor()
                    val markers = listOf(
                        "N64AndroidAudio", "N64ANDROID AUDIO:", "N64AndroidCore",
                        "SDL_OpenAudio", "SDL_Init", "AiLenChanged"
                    )
                    lines.filter { line ->
                        markers.any { marker -> line.contains(marker, ignoreCase = true) }
                    }.takeLast(100).joinToString("\n").ifBlank {
                        "Nenhum registro do plugin de áudio foi encontrado."
                    }
                } catch (error: Exception) {
                    "Não foi possível ler o logcat: ${error.message}"
                }
                runOnUiThread {
                    audioReport = result
                    reportView.text = result
                }
            }.start()
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Diagnóstico do áudio"
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(12, 16, 12, 16)
            })
            addView(Button(this@MainActivity).apply {
                text = "Atualizar diagnóstico"
                setOnClickListener { refresh() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Copiar diagnóstico"
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico de áudio N64", audioReport))
                    Toast.makeText(this@MainActivity, "Diagnóstico copiado.", Toast.LENGTH_SHORT).show()
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "Voltar ao início"
                setOnClickListener { setContentView(homeLayout) }
            })
            addView(reportView)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        })
        refresh()
    }
}
