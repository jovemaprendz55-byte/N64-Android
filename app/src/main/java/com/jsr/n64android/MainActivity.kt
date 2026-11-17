package com.jsr.n64android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private val romPickerRequestCode = 4201
    private lateinit var homeLayout: LinearLayout
    private lateinit var homeStatus: TextView
    private lateinit var videoSpinner: Spinner
    private lateinit var rspSpinner: Spinner
    private lateinit var audioSpinner: Spinner
    private lateinit var inputSpinner: Spinner
    private var gameScreenVisible = false
    private var launchInProgress = false
    private var pressedButtons = 0
    private var selectedRomBytes: ByteArray? = null

    private val videoPluginId = "vulkan_android"
    private val rspPluginId = "cxd4"
    private val audioPluginId = "none"
    private val inputPluginId = "android_virtual"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildHomeScreen()
        setContentView(homeLayout)
    }

    private fun buildHomeScreen() {
        homeStatus = TextView(this).apply {
            text = "Escolha os plugins disponíveis e toque em Selecionar ROM e jogar."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(12, 16, 12, 16)
        }
        videoSpinner = pluginSpinner(listOf("Vulkan Android (experimental)"))
        rspSpinner = pluginSpinner(listOf("RSP CXD4 LLE"))
        audioSpinner = pluginSpinner(listOf("Áudio: não disponível nesta versão")).apply {
            isEnabled = false
        }
        inputSpinner = pluginSpinner(listOf("Controle virtual Android"))

        homeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            addView(TextView(this@MainActivity).apply {
                text = "N64 Android"
                textSize = 30f
                gravity = Gravity.CENTER
                setPadding(8, dp(22), 8, dp(8))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Configuração dos plugins"
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(8, dp(8), 8, dp(18))
            })
            addPluginField(this, "Vídeo / GFX", videoSpinner)
            addPluginField(this, "RSP", rspSpinner)
            addPluginField(this, "Áudio", audioSpinner)
            addPluginField(this, "Controle / Input", inputSpinner)
            addView(homeStatus, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(Button(this@MainActivity).apply {
                text = "SELECIONAR ROM E JOGAR"
                textSize = 16f
                setOnClickListener { openRomPicker() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            ).apply { topMargin = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = "Os seletores mostram somente plugins incluídos no APK. O áudio ainda não está instalado."
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(8, dp(18), 8, 8)
            })
        }
    }

    private fun pluginSpinner(items: List<String>): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items
            )
        }
    }

    private fun addPluginField(parent: LinearLayout, label: String, spinner: Spinner) {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(4, dp(8), 4, 0)
        })
        parent.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ))
    }

    private fun openRomPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, romPickerRequestCode)
        } catch (error: Exception) {
            homeStatus.text = "Não foi possível abrir os arquivos: ${error.message}"
        }
    }

    @Deprecated("Compatibilidade com a Activity atual")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != romPickerRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        homeStatus.text = "Lendo e verificando a ROM..."
        Thread {
            val result = try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Não foi possível abrir o arquivo.")
                if (bytes.size < 4096 || bytes.size > 64 * 1024 * 1024 || bytes.size % 4 != 0)
                    throw IllegalArgumentException("Tamanho de ROM inválido.")
                val signature = bytes.take(4).joinToString(" ") {
                    "%02X".format(it.toInt() and 0xff)
                }
                if (signature !in setOf("80 37 12 40", "37 80 40 12", "40 12 37 80"))
                    throw IllegalArgumentException("O arquivo não tem assinatura de ROM N64: $signature")
                bytes
            } catch (error: Exception) {
                runOnUiThread { homeStatus.text = "Falha ao abrir ROM: ${error.message}" }
                null
            }
            if (result != null) runOnUiThread {
                selectedRomBytes = result
                homeStatus.text = "ROM validada (${result.size} bytes). Preparando a tela do jogo..."
                showGameplayScreen()
            }
        }.start()
    }

    private fun showGameplayScreen() {
        val romBytes = selectedRomBytes ?: return
        gameScreenVisible = true
        launchInProgress = false
        pressedButtons = 0

        val status = TextView(this).apply {
            text = "Aguardando superfície Vulkan..."
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(android.graphics.Color.argb(190, 0, 0, 0))
        }
        val surfaceView = SurfaceView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val gameLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surfaceView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(Button(this@MainActivity).apply {
                text = "COPIAR LOGS DE DIAGNÓSTICO"
                textSize = 14f
                setOnClickListener { copyDiagnosticLogs(status) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { setMargins(dp(8), dp(6), dp(8), dp(6)) })
            addView(buildTouchControls(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(Button(this@MainActivity).apply {
                text = "FECHAR JOGO"
                setOnClickListener { returnToHome() }
            })
        }

        var initialized = false
        var prepared = false
        fun initialize(holder: SurfaceHolder, width: Int, height: Int) {
            if (initialized || launchInProgress || width <= 0 || height <= 0 ||
                !holder.surface.isValid) return
            launchInProgress = true
            try {
                status.text = "Conectando a superfície Vulkan..."
                val vulkan = VulkanNative.nativeInitialize(holder.surface)
                if (!vulkan.startsWith("Superfície Vulkan Android criada")) {
                    status.text = vulkan
                    launchInProgress = false
                    return
                }
                initialized = true
                status.text = "Vulkan pronta. Conectando ROM, Core e plugins..."
                val prepare = VulkanNative.nativePrepareRom(
                    romBytes,
                    File(filesDir, "n64-config").absolutePath,
                    File(filesDir, "n64-data").absolutePath,
                    videoPluginId,
                    rspPluginId,
                    audioPluginId,
                    inputPluginId
                )
                if (!prepare.startsWith("ROM aberta no Mupen64Plus")) {
                    status.text = prepare
                    launchInProgress = false
                    return
                }
                prepared = true
                status.text = "Plugins conectados. Iniciando o jogo..."
                status.text = VulkanNative.nativeStartEmulation()
                if (status.text.toString().startsWith("Comando de execução")) {
                    status.text = "Jogo iniciado. Use os controles na parte inferior."
                }
            } catch (error: Throwable) {
                status.text = "Falha ao iniciar: ${error.message}"
                if (prepared) try { VulkanNative.nativeCloseRom() } catch (_: Throwable) {}
                if (initialized) try { VulkanNative.nativeShutdown() } catch (_: Throwable) {}
                initialized = false
                prepared = false
            } finally {
                launchInProgress = false
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceView.post { initialize(holder, surfaceView.width, surfaceView.height) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                initialize(holder, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                try { VulkanNative.nativeCloseRom() } catch (_: Throwable) {}
                try { VulkanNative.nativeShutdown() } catch (_: Throwable) {}
                initialized = false
                prepared = false
                pressedButtons = 0
            }
        })
        setContentView(gameLayout)
    }

    private fun copyDiagnosticLogs(status: TextView) {
        status.text = "Coletando logs do RDP e Vulkan..."
        Thread {
            val result = try {
                val process = ProcessBuilder(
                    "logcat", "-d", "-v", "threadtime", "-t", "2500"
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                if (exitCode != 0) throw IllegalStateException("logcat retornou código $exitCode")

                val relevant = output.lineSequence().filter { line ->
                    line.contains("N64AndroidVulkanGFX") ||
                    line.contains("N64AndroidVulkan") ||
                    line.contains("N64AndroidCore") ||
                    line.contains("DPC RDP pixels") ||
                    line.contains("RDP real processado") ||
                    line.contains("Vulkan frame OK") ||
                    line.contains("RDP scanout") ||
                    line.contains("DPC VI input") ||
                    line.contains("DPC RDP[") ||
                    line.contains("ProcessRDPList") ||
                    line.contains("RDP progresso parcial") ||
                    line.contains("RDP comando parcial") ||
                    line.contains("Quadro Vulkan") ||
                    line.contains("Quadro do núcleo N64")
                }.toList().takeLast(600).joinToString("\n")

                if (relevant.isBlank()) {
                    "N64 ANDROID - LOGS DE DIAGNÓSTICO\n" +
                    "Nenhuma mensagem dos componentes N64 foi encontrada.\n" +
                    "Abra o jogo, aguarde alguns segundos com a tela preta e tente copiar novamente."
                } else {
                    "N64 ANDROID - LOGS DE DIAGNÓSTICO\n" +
                    "Inclui Core, RSP/CXD4, DPC/RDP, scanout e apresentação Vulkan.\n\n" +
                    relevant
                }
            } catch (error: Throwable) {
                "Falha ao coletar Logcat: ${error.message}\n" +
                "Mantenha o jogo aberto e tente novamente."
            }
            runOnUiThread {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("N64 Android logs", result))
                status.text = "Logs copiados. Cole o conteúdo aqui na conversa."
                Toast.makeText(this, "Logs copiados para a área de transferência.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun buildTouchControls(): View {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(android.graphics.Color.rgb(28, 28, 28))
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addTouchButton(row1, "←", 1 shl 1)
        addTouchButton(row1, "↑", 1 shl 3)
        addTouchButton(row1, "↓", 1 shl 2)
        addTouchButton(row1, "→", 1 shl 0)
        controls.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addTouchButton(row2, "START", 1 shl 4)
        addTouchButton(row2, "Z", 1 shl 5)
        addTouchButton(row2, "B", 1 shl 6)
        addTouchButton(row2, "A", 1 shl 7)
        controls.addView(row2)
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addTouchButton(row3, "L", 1 shl 13)
        addTouchButton(row3, "C ←", 1 shl 9)
        addTouchButton(row3, "C ↑", 1 shl 11)
        addTouchButton(row3, "C ↓", 1 shl 10)
        addTouchButton(row3, "C →", 1 shl 8)
        addTouchButton(row3, "R", 1 shl 12)
        controls.addView(row3)
        return controls
    }

    private fun addTouchButton(parent: LinearLayout, label: String, mask: Int) {
        val button = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
        }
        parent.addView(button, LinearLayout.LayoutParams(
            0, dp(48), 1f
        ).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        })
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    pressedButtons = pressedButtons or mask
                    sendControllerState()
                    button.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_UP -> {
                    pressedButtons = pressedButtons and mask.inv()
                    sendControllerState()
                    button.isPressed = false
                }
            }
            true
        }
    }

    private fun sendControllerState() {
        try { VulkanNative.nativeSetControllerState(pressedButtons, 0, 0) }
        catch (_: Throwable) {}
    }

    private fun returnToHome() {
        gameScreenVisible = false
        pressedButtons = 0
        try { VulkanNative.nativeSetControllerState(0, 0, 0) } catch (_: Throwable) {}
        try { VulkanNative.nativeCloseRom() } catch (_: Throwable) {}
        try { VulkanNative.nativeShutdown() } catch (_: Throwable) {}
        setContentView(homeLayout)
    }

    override fun onBackPressed() {
        if (gameScreenVisible) returnToHome() else super.onBackPressed()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
