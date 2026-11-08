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
    private external fun nativeStartCore(configPath: String, dataPath: String): String

    private lateinit var statusView: TextView
    private lateinit var configDirectory: File
    private lateinit var dataDirectory: File
    private var nativeLibraryLoaded = false
    private var nativeCoreLoaded = false
    private var nativeCoreInitialized = false

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

        val loadButton = Button(this).apply {
            text = "1 - Carregar biblioteca JNI"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    loadNativeLibrary()
                    "Biblioteca n64bridge carregada."
                } catch (error: LinkageError) {
                    "Falha ao carregar biblioteca: ${error.message}"
                } catch (error: Exception) {
                    "Erro ao carregar biblioteca: ${error.message}"
                }
            }
        }

        val jniButton = Button(this).apply {
            text = "2 - Testar JNI e SDL2"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    if (!nativeLibraryLoaded) "Carregue a biblioteca primeiro."
                    else nativeGetStatus()
                } catch (error: LinkageError) {
                    "Falha na chamada JNI: ${error.message}"
                } catch (error: Exception) {
                    "Erro na chamada JNI: ${error.message}"
                }
            }
        }

        val initializeButton = Button(this).apply {
            text = "3 - Carregar Core (teste isolado)"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    loadNativeLibrary()
                    val result = nativeInitialize(
                        configDirectory.absolutePath,
                        dataDirectory.absolutePath
                    )
                    nativeCoreLoaded = result.startsWith("Core Mupen64Plus carregado com sucesso")
                    result
                } catch (error: LinkageError) {
                    "Falha ao carregar Core: ${error.message}"
                } catch (error: Exception) {
                    "Erro ao carregar Core: ${error.message}"
                }
            }
        }

        val shutdownButton = Button(this).apply {
            text = "4 - Inicializar Core (sem ROM)"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    if (!nativeCoreLoaded) {
                        "Carregue o Core primeiro no botao 3."
                    } else if (nativeCoreInitialized) {
                        "Core ja inicializado. Use o botao 5 para encerrar."
                    } else {
                        nativeStartCore(
                            configDirectory.absolutePath,
                            dataDirectory.absolutePath
                        ).also {
                            nativeCoreInitialized = it.startsWith("CoreStartup concluido com sucesso")
                        }
                    }
                } catch (error: LinkageError) {
                    "Falha ao descarregar Core: ${error.message}"
                }
            }
        }


        val unloadButton = Button(this).apply {
            text = "5 - Encerrar Core e descarregar biblioteca"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    if (nativeCoreLoaded) {
                        nativeShutdown().also {
                            if (it.startsWith("Biblioteca do Core descarregada corretamente")) {
                                nativeCoreLoaded = false
                                nativeCoreInitialized = false
                            }
                        }
                    } else {
                        "Core nao esta carregado."
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
            addView(unloadButton)
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
        if (nativeCoreLoaded) {
            try {
                nativeShutdown()
            } catch (_: LinkageError) {
                // Descarrega apenas se o carregamento isolado foi confirmado.
            }
        }
        super.onDestroy()
    }
}
