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
    private var nativeCoreStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configDirectory = File(filesDir, "mupen64plus/config")
        dataDirectory = File(filesDir, "mupen64plus/data")
        configDirectory.mkdirs()
        dataDirectory.mkdirs()

        statusView = TextView(this).apply {
            text = "N64 Android\n\nInterface iniciada.\nBiblioteca nativa ainda nao foi carregada."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }

        val loadButton = Button(this).apply {
            text = "1 - Carregar biblioteca"
            setOnClickListener {
                statusView.text = "N64 Android\n\n" + try {
                    loadNativeLibrary()
                    "Biblioteca n64bridge carregada. Nenhuma funcao JNI foi chamada."
                } catch (error: LinkageError) {
                    "Falha ao carregar biblioteca: ${error.message}"
                } catch (error: Exception) {
                    "Erro ao carregar biblioteca: ${error.message}"
                }
            }
        }

        val jniButton = Button(this).apply {
            text = "2 - Testar JNI"
            setOnClickListener {
                statusView.text = "N64 Android\n\n" + try {
                    if (!nativeLibraryLoaded) {
                        "Carregue a biblioteca primeiro."
                    } else {
                        nativeGetStatus()
                    }
                } catch (error: LinkageError) {
                    "Falha na chamada JNI: ${error.message}"
                } catch (error: Exception) {
                    "Erro na chamada JNI: ${error.message}"
                }
            }
        }

        val initializeButton = Button(this).apply {
            text = "3 - Inicializar Core"
            setOnClickListener {
                statusView.text = "N64 Android\n\n" + try {
                    loadNativeLibrary()
                    val result = nativeInitialize(
                        configDirectory.absolutePath,
                        dataDirectory.absolutePath
                    )
                    nativeCoreStarted = result.startsWith("CoreStartup concluido com sucesso")
                    result
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
                statusView.text = "N64 Android\n\n" + try {
                    if (nativeCoreStarted) {
                        nativeShutdown().also { nativeCoreStarted = false }
                    } else {
                        "Core nao foi inicializado."
                    }
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
            addView(loadButton)
            addView(jniButton)
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
        if (nativeCoreStarted) {
            try {
                nativeShutdown()
            } catch (_: LinkageError) {
                // Shutdown is attempted only after a confirmed successful startup.
            }
        }
        super.onDestroy()
    }
}
