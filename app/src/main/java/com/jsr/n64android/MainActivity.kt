package com.jsr.n64android

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity() {

    private external fun nativeGetStatus(): String
    private external fun nativeInitialize(configPath: String, dataPath: String): String
    private external fun nativeShutdown(): String
    private external fun nativeStartCore(configPath: String, dataPath: String): String
    private external fun nativeTestVideoOverrideAbi(): String
    private external fun nativeVerifyEglContext(): String
    private external fun nativeTestGlOperations(): String

    private lateinit var statusView: TextView
    private lateinit var rootLayout: LinearLayout
    private lateinit var homeLayout: LinearLayout
    private var diagnosticScreenVisible = false
    private lateinit var configDirectory: File
    private lateinit var dataDirectory: File
    private var nativeLibraryLoaded = false
    private var nativeCoreLoaded = false
    private var nativeCoreInitialized = false
    private var glView: GLSurfaceView? = null
    @Volatile private var glSurfaceReady = false

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

        val startCoreButton = Button(this).apply {
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
                    "Falha ao inicializar Core: ${error.message}"
                } catch (error: Exception) {
                    "Erro ao inicializar Core: ${error.message}"
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
                } catch (error: Exception) {
                    "Erro ao encerrar Core: ${error.message}"
                }
            }
        }

        val videoAbiButton = Button(this).apply {
            text = "7 - Testar ABI de vídeo (sem ativar)"
            setOnClickListener {
                statusView.text = "N64 Android\\n\\n" + try {
                    if (!nativeCoreInitialized) "Inicialize o Core primeiro no botao 4."
                    else nativeTestVideoOverrideAbi()
                } catch (error: LinkageError) {
                    "Falha no teste ABI de video: ${error.message}"
                } catch (error: Exception) {
                    "Erro no teste ABI de video: ${error.message}"
                }
            }
        }

        val glButton = Button(this).apply {
            text = "6 - Testar tela OpenGL ES 2.0"
            setOnClickListener { showOpenGlTest() }
        }

        val glThreadButton = Button(this).apply {
            text = "8 - Testar chamada JNI na thread GL"
            setOnClickListener { testGlThreadBridge() }
        }

        val glOperationsButton = Button(this).apply {
            text = "9 - Testar operacoes OpenGL pela JNI"
            setOnClickListener { testGlOperationsOnGlThread() }
        }

        val backButton = Button(this).apply {
            text = "Voltar ao inicio"
            setOnClickListener { showHomeScreen() }
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 20, 20, 20)
            addView(backButton)
            addView(statusView)
            addView(loadButton)
            addView(jniButton)
            addView(initializeButton)
            addView(startCoreButton)
            addView(unloadButton)
            addView(glButton)
            addView(videoAbiButton)
            addView(glThreadButton)
            addView(glOperationsButton)
        }

        homeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
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
                setPadding(12, 8, 12, 28)
            })
            addView(Button(this@MainActivity).apply {
                text = "Diagnostico e testes"
                setOnClickListener {
                    diagnosticScreenVisible = true
                    setContentView(rootLayout)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Jogos e carregamento de ROMs ainda estao em desenvolvimento."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 28, 16, 12)
            })
        }

        setContentView(homeLayout)
    }

    private fun showHomeScreen() {
        diagnosticScreenVisible = false
        glView?.let {
            rootLayout.removeView(it)
            it.onPause()
        }
        glSurfaceReady = false
        setContentView(homeLayout)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (diagnosticScreenVisible) {
            showHomeScreen()
        } else {
            super.onBackPressed()
        }
    }

    private fun showOpenGlTest() {
        loadNativeLibrary()
        glSurfaceReady = false
        glView?.let {
            rootLayout.removeView(it)
            it.onPause()
        }

        val surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    glSurfaceReady = true
                    GLES20.glClearColor(0.08f, 0.18f, 0.32f, 1.0f)
                    val eglStatus = try {
                        nativeVerifyEglContext()
                    } catch (error: LinkageError) {
                        "Falha ao verificar contexto EGL: " + error.message
                    }
                    runOnUiThread {
                        statusView.text = "N64 Android\\n\\nOpenGL ES 2.0 inicializado.\\nSuperficie grafica Android criada.\\n\\n" + eglStatus + "\\n\\nNenhuma ROM foi carregada."
                    }
                }

                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    GLES20.glViewport(0, 0, width, height)
                }

                override fun onDrawFrame(gl: GL10?) {
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        glView = surface
        rootLayout.addView(
            surface,
            rootLayout.indexOfChild(rootLayout.getChildAt(rootLayout.childCount - 1)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            )
        )
        surface.onResume()
        surface.requestRender()
    }

    private fun testGlThreadBridge() {
        val surface = glView
        if (surface == null || !glSurfaceReady) {
            statusView.text = "N64 Android\\n\\nAbra primeiro o teste 6 e aguarde o contexto EGL ficar valido."
            return
        }

        statusView.text = "N64 Android\\n\\nEnviando chamada JNI para a thread GL..."
        surface.queueEvent {
            val result = try {
                nativeVerifyEglContext()
            } catch (error: LinkageError) {
                "Falha na chamada JNI da thread GL: " + error.message
            } catch (error: Exception) {
                "Erro na chamada JNI da thread GL: " + error.message
            }
            runOnUiThread {
                statusView.text = "N64 Android\\n\\nPonte JNI via GLSurfaceView.queueEvent\\n" + result +
                    "\\n\\nNenhuma ROM foi carregada."
            }
        }
    }

    private fun testGlOperationsOnGlThread() {
        val surface = glView
        if (surface == null || !glSurfaceReady) {
            statusView.text = "N64 Android\\n\\nAbra primeiro o teste 6 e aguarde o contexto EGL ficar valido."
            return
        }

        statusView.text = "N64 Android\\n\\nExecutando operacoes GLES pela JNI na thread GL..."
        surface.queueEvent {
            val result = try {
                nativeTestGlOperations()
            } catch (error: LinkageError) {
                "Falha na operacao GLES pela JNI: " + error.message
            } catch (error: Exception) {
                "Erro na operacao GLES pela JNI: " + error.message
            }
            runOnUiThread {
                statusView.text = "N64 Android\\n\\nTeste 9 - Operacoes GLES na thread GL\\n" +
                    result + "\\n\\nNenhuma ROM foi carregada."
            }
        }
        surface.requestRender()
    }

    private fun loadNativeLibrary() {
        if (!nativeLibraryLoaded) {
            System.loadLibrary("n64bridge")
            nativeLibraryLoaded = true
        }
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
    }

    override fun onPause() {
        glView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        glView?.onPause()
        if (nativeCoreLoaded) {
            try {
                nativeShutdown()
            } catch (_: LinkageError) {
                // Mantem o encerramento nativo protegido contra falhas JNI.
            }
        }
        super.onDestroy()
    }
}
