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
    private var nativeLibraryLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configDirectory = File(filesDir, "mupen64plus/config")
        dataDirectory = File(filesDir, "mupen64plus/data")
        configDirectory.mkdirs()
        dataDirectory.mkdirs()

        statusView = TextView(this).apply {
            text = "N64 Android\\n\\nInterface iniciada.\\nBiblioteca nativa ainda nao foi carregada."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }

        val statusButton = Button(this).apply {
            text = "Verificar Core"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    loadNativeLibrary()
                    nativeGetStatus()
                } catch (error: LinkageError) {
                    "Falha ao carregar biblioteca nativa: ${error.message}"
                } catch (error: Exception) {
                    "Erro ao verificar Core: ${error.message}"
                }
            }
        }

        val initializeButton = Button(this).apply {
            text = "Inicializar Core"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    loadNativeLibrary()
                    nativeInitialize(configDirectory.absolutePath, dataDirectory.absolutePath)
                } catch (error: LinkageError) {
                    "Falha nativa durante inicializacao: ${error.message}"
                } catch (error: Exception) {
                    "Erro durante inicializacao: ${error.message}"
                }
            }
        }

        val shutdownButton = Button(this).apply {
            text = "Encerrar Core"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    if (nativeLibraryLoaded) nativeShutdown()
                    else "Biblioteca nativa ainda nao foi carregada."
                } catch (error: LinkageError) {
                    "Falha ao encerrar Core: ${error.message}"
                }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            addView(statusView)
            addView(statusButton)
            addView(initializeButton)
            addView(shutdownButton)
        }

        setContentView(layout)
    }

    private fun loadNativeLibrary() {
        if (!nativeLibraryLoaded) {
            System.loadLibrary("n64bridge")
            nativeLibraryLoaded = true
        }
    }

    override fun onDestroy() {
        if (nativeLibraryLoaded) {
            try {
                nativeShutdown()
            } catch (_: LinkageError) {
                // Native shutdown may be unavailable after a load failure.
            }
        }
        super.onDestroy()
    }
}
