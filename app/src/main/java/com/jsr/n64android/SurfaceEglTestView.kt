package com.jsr.n64android

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

/**
 * Isolated SurfaceView + EGL proof of concept.
 *
 * This view deliberately does not load or call Mupen64Plus/GLideN64.
 * All EGL/GLES work is confined to one HandlerThread.
 */
class SurfaceEglTestView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val thread = HandlerThread("N64-Surface-EGL").apply { start() }
    private val eglHandler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var released = false
    @Volatile private var renderingEnabled = true
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var width = 1
    private var height = 1
    private var frameCount = 0L
    private var renderQueued = false

    var onStatus: ((String) -> Unit)? = null

    init {
        holder.addCallback(this)
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        eglHandler.post {
            if (released) return@post
            val result = createEgl(holder)
            report(result)
            if (result.startsWith("EGL pronto")) scheduleFrame()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (released) return
        eglHandler.post {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            if (makeCurrent()) {
                GLES20.glViewport(0, 0, this.width, this.height)
                report("Superfície redimensionada: ${this.width}x${this.height}")
                scheduleFrame()
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released) return
        val done = CountDownLatch(1)
        eglHandler.post {
            destroyEgl()
            done.countDown()
        }
        try {
            done.await(1500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        report("Superfície Android destruída; recursos EGL liberados.")
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setRenderingEnabled(visibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    fun setRenderingEnabled(enabled: Boolean) {
        renderingEnabled = enabled
        if (enabled && !released) eglHandler.post { scheduleFrame() }
    }

    fun release() {
        if (released) return
        released = true
        holder.removeCallback(this)
        val done = CountDownLatch(1)
        eglHandler.post {
            destroyEgl()
            done.countDown()
        }
        try {
            done.await(1500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread.quitSafely()
    }

    private fun createEgl(surfaceHolder: SurfaceHolder): String {
        destroyEgl()
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return eglFailure("eglGetDisplay")
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
            destroyEgl()
            return eglFailure("eglInitialize")
        }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, count, 0) ||
            count[0] == 0 || configs[0] == null) {
            destroyEgl()
            return eglFailure("eglChooseConfig")
        }

        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            destroyEgl()
            return eglFailure("eglCreateContext")
        }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], surfaceHolder.surface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            destroyEgl()
            return eglFailure("eglCreateWindowSurface")
        }
        if (!makeCurrent()) {
            destroyEgl()
            return eglFailure("eglMakeCurrent")
        }

        val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "desconhecida"
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "desconhecido"
        frameCount = 0
        return "EGL pronto (versão ${versions[0]}.${versions[1]}). GLES: $glVersion. GPU: $renderer"
    }

    private fun makeCurrent(): Boolean {
        return eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglSurface != EGL14.EGL_NO_SURFACE &&
            eglContext != EGL14.EGL_NO_CONTEXT &&
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun scheduleFrame() {
        if (released || !renderingEnabled || renderQueued ||
            eglSurface == EGL14.EGL_NO_SURFACE) return
        renderQueued = true
        eglHandler.postDelayed({
            renderQueued = false
            renderFrame()
        }, 16L)
    }

    private fun renderFrame() {
        if (released || !renderingEnabled || !makeCurrent()) return
        val phase = ((frameCount % 240L).toFloat() / 240f)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.035f + phase * 0.12f, 0.075f, 0.14f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            report(eglFailure("eglSwapBuffers"))
            destroyEgl()
            return
        }
        frameCount++
        if (frameCount == 1L || frameCount % 120L == 0L) {
            report("EGL ativo — frames apresentados: $frameCount")
        }
        scheduleFrame()
    }

    private fun destroyEgl() {
        renderQueued = false
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun eglFailure(operation: String): String {
        val error = EGL14.eglGetError()
        return "$operation falhou: EGL error 0x${error.toString(16)}"
    }

    private fun report(message: String) {
        mainHandler.post {
            if (!released) onStatus?.invoke(message)
        }
        Log.i("N64SurfaceEGL", message)
    }
}
