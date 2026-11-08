package com.jsr.n64android

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
    private lateinit var graphicsGroup: LinearLayout
    private var diagnosticScreenVisible = false
    private lateinit var configDirectory: File
    private lateinit var dataDirectory: File
    private var nativeLibraryLoaded = false
    private var nativeCoreLoaded = false
    private var nativeCoreInitialized = false
    private var glView: GLSurfaceView? = null
    @Volatile private var glSurfaceReady = false
    @Volatile private var glSurfaceCreatedCount = 0
    @Volatile private var lifecycleTestEnabled = false

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

        val glDrawButton = Button(this).apply {
            text = "10 - Testar desenho OpenGL"
            setOnClickListener { showOpenGlTest(drawTriangle = true) }
        }

        val glThreadButton = Button(this).apply {
            text = "8 - Testar chamada JNI na thread GL"
            setOnClickListener { testGlThreadBridge() }
        }

        val glOperationsButton = Button(this).apply {
            text = "9 - Testar operacoes OpenGL pela JNI"
            setOnClickListener { testGlOperationsOnGlThread() }
        }

        val glLifecycleButton = Button(this).apply {
            text = "11 - Testar pausa e retomada GL"
            setOnClickListener {
                lifecycleTestEnabled = true
                showOpenGlTest(drawTriangle = true)
            }
        }

        val backButton = Button(this).apply {
            text = "Voltar ao inicio"
            setOnClickListener { showHomeScreen() }
        }

        val jniGroup = createDiagnosticGroup().apply {
            addView(loadButton)
            addView(jniButton)
        }
        val coreGroup = createDiagnosticGroup().apply {
            addView(initializeButton)
            addView(startCoreButton)
            addView(unloadButton)
            addView(videoAbiButton)
        }
        val graphicsGroup = createDiagnosticGroup().apply {
            addView(glButton)
            addView(glDrawButton)
            addView(glThreadButton)
            addView(glOperationsButton)
            addView(glLifecycleButton)
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 20, 20, 20)
            addView(backButton)
            addView(statusView)
            addView(categoryButton("Biblioteca e JNI", jniGroup))
            addView(jniGroup)
            addView(categoryButton("Core Mupen64Plus", coreGroup))
            addView(coreGroup)
            addView(categoryButton("Gráficos e OpenGL", graphicsGroup))
            addView(graphicsGroup)
        }
        jniGroup.visibility = ViewGroup.GONE
        coreGroup.visibility = ViewGroup.GONE
        graphicsGroup.visibility = ViewGroup.GONE
        this.graphicsGroup = graphicsGroup

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
                    setContentView(ScrollView(this@MainActivity).apply {
                        isFillViewport = true
                        addView(rootLayout)
                    })
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

    private fun createDiagnosticGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 8, 0, 8)
    }

    private fun categoryButton(label: String, group: LinearLayout): Button =
        Button(this).apply {
            text = label
            setOnClickListener {
                group.visibility = if (group.visibility == ViewGroup.VISIBLE) ViewGroup.GONE else ViewGroup.VISIBLE
            }
        }

    private fun showHomeScreen() {
        diagnosticScreenVisible = false
        glView?.let {
            graphicsGroup.removeView(it)
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

    private fun showOpenGlTest(drawTriangle: Boolean = false) {
        loadNativeLibrary()
        glSurfaceReady = false
        glView?.let {
            graphicsGroup.removeView(it)
            it.onPause()
        }

        val surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                private var triangleProgram = 0
                private var triangleVertices: FloatBuffer? = null

                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    glSurfaceReady = true
                    glSurfaceCreatedCount += 1
                    val createdCount = glSurfaceCreatedCount
                    GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1.0f)
                    if (drawTriangle) {
                        triangleProgram = createTriangleProgram()
                        triangleVertices = ByteBuffer.allocateDirect(15 * 4)
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer()
                            .apply {
                                put(floatArrayOf(
                                    -0.72f, -0.62f, 1.0f, 0.2f, 0.2f,
                                     0.72f, -0.62f, 0.2f, 1.0f, 0.3f,
                                     0.00f,  0.72f, 0.2f, 0.5f, 1.0f
                                ))
                                position(0)
                            }
                    }
                    val eglStatus = try {
                        nativeVerifyEglContext()
                    } catch (error: LinkageError) {
                        "Falha ao verificar contexto EGL: " + error.message
                    }
                    runOnUiThread {
                        val visualStatus = if (!drawTriangle) {
                            "Teste de superficie OpenGL pronto."
                        } else if (triangleProgram != 0) {
                            "Programa GLES compilado. Triangulo colorido sendo desenhado."
                        } else {
                            "Falha ao criar o programa de desenho OpenGL. Consulte o log."
                        }
                        val lifecycleStatus = if (lifecycleTestEnabled) {
                            "Superficie EGL criada: $createdCount vez(es)."
                        } else {
                            ""
                        }
                        statusView.text = "N64 Android\\n\\n" + visualStatus + "\\n" +
                            lifecycleStatus + "\\n\\n" + eglStatus +
                            "\\n\\nNenhuma ROM ou plugin foi iniciado."
                    }
                }

                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    GLES20.glViewport(0, 0, width, height)
                }

                override fun onDrawFrame(gl: GL10?) {
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    if (drawTriangle && triangleProgram != 0) {
                        drawColoredTriangle(triangleProgram, triangleVertices)
                    }
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        glView = surface
        graphicsGroup.addView(
            surface,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            )
        )
        surface.onResume()
        surface.requestRender()
    }

    private fun createTriangleProgram(): Int {
        val vertexSource = """
            attribute vec2 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vColor = aColor;
            }
        """.trimIndent()
        val fragmentSource = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """.trimIndent()

        val vertexShader = compileTriangleShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileTriangleShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        if (program == 0) return 0
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glBindAttribLocation(program, 0, "aPosition")
        GLES20.glBindAttribLocation(program, 1, "aColor")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (linkStatus[0] == 0) {
            android.util.Log.e("N64AndroidGLES", "Falha no link do shader: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileTriangleShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            android.util.Log.e("N64AndroidGLES", "Falha ao compilar shader: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun drawColoredTriangle(program: Int, vertices: FloatBuffer?) {
        if (vertices == null) return
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 20, vertices)
        GLES20.glEnableVertexAttribArray(0)
        vertices.position(2)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 20, vertices)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glUseProgram(0)
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
        glView?.let { surface ->
            surface.onResume()
            if (lifecycleTestEnabled) {
                surface.queueEvent {
                    val eglStatus = try {
                        nativeVerifyEglContext()
                    } catch (error: LinkageError) {
                        "Falha ao verificar EGL depois da retomada: " + error.message
                    } catch (error: Exception) {
                        "Erro ao verificar EGL depois da retomada: " + error.message
                    }
                    val createdCount = glSurfaceCreatedCount
                    runOnUiThread {
                        statusView.text = "N64 Android\\n\\nTeste de ciclo de vida GL\\n" +
                            "Activity retomada. Superficie criada: $createdCount vez(es).\\n\\n" +
                            eglStatus + "\\n\\nO teste nao iniciou ROM nem plugin."
                    }
                }
            }
        }
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
