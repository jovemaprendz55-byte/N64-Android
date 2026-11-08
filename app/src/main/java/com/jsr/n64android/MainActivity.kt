package com.jsr.n64android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private external fun nativeGetStatus(): String
    private external fun nativeInitialize(configPath: String, dataPath: String): String
    private external fun nativeShutdown(): String

    private lateinit var statusView: TextView
    private lateinit var configDirectory: File
    private lateinit var dataDirectory: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configDirectory = File(filesDir, "mupen64plus/config")
        dataDirectory = File(filesDir, "mupen64plus/data")
        configDirectory.mkdirs()
        dataDirectory.mkdirs()

        val initialStatus = try {
            System.loadLibrary("n64bridge")
            nativeGetStatus()
        } catch (error: UnsatisfiedLinkError) {
            "Biblioteca nativa não carregada: ${error.message}"
        }

        statusView = TextView(this).apply {
            text = "N64 Android\\n\\n$initialStatus"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }

        val initializeButton = Button(this).apply {
            text = "Inicializar Core"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" +
                    nativeInitialize(configDirectory.absolutePath, dataDirectory.absolutePath)
            }
        }

        val shutdownButton = Button(this).apply {
            text = "Encerrar Core"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + nativeShutdown()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            addView(statusView)
            addView(initializeButton)
            addView(shutdownButton)
        }

        setContentView(layout)
    }

    override fun onDestroy() {
        try {
            nativeShutdown()
        } catch (_: UnsatisfiedLinkError) {
            // The native library may not have loaded.
        }
        super.onDestroy()
    }
}
