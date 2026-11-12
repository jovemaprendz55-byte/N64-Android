package com.jsr.n64android

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig as LegacyEGLConfig
import javax.microedition.khronos.opengles.GL10

private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

/**
 * Gameplay SurfaceView backed by one EGL context owned by N64-Game-EGL.
 * All renderer callbacks and JNI work submitted through queueEvent run on
 * this same thread, so the Core, GLideN64 and video swap callback share context.
 */
class SurfaceEglGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        const val RENDERMODE_WHEN_DIRTY = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        const val RENDERMODE_CONTINUOUSLY = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private val thread = HandlerThread("N64-Game-EGL").apply { start() }
    private val eglHandler = Handler(thread.looper)
    @Volatile private var released = false
    @Volatile private var resumed = true
    private var renderer: GLSurfaceView.Renderer? = null
    private var rendererCreated = false
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var width = 1
    private var height = 1
    private var renderQueued = false
    private var requestedClientVersion = 3

    var preserveEGLContextOnPause: Boolean = true
    var renderMode: Int = RENDERMODE_WHEN_DIRTY
        set(value) {
            field = value
            if (value == RENDERMODE_CONTINUOUSLY) requestRender()
        }

    init {
        holder.addCallback(this)
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    fun setEGLContextClientVersion(version: Int) {
        requestedClientVersion = version.coerceAtLeast(2)
    }

    fun setRenderer(value: GLSurfaceView.Renderer) {
        check(renderer == null) { "Renderer já foi configurado." }
        renderer = value
        eglHandler.post {
            if (!released && makeCurrent() && eglSurface != EGL14.EGL_NO_SURFACE) {
                dispatchSurfaceCreated()
                dispatchSurfaceChanged()
                requestRenderOnEglThread()
            }
        }
    }

    /** Same FIFO contract as GLSurfaceView.queueEvent, on the EGL owner thread. */
    fun queueEvent(action: Runnable) {
        if (!released) eglHandler.post {
            if (!released && makeCurrent()) action.run()
        }
    }

    fun requestRender() {
        if (!released) eglHandler.post { requestRenderOnEglThread() }
    }

    fun onResume() {
        resumed = true
        requestRender()
    }

    fun onPause() {
        resumed = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        eglHandler.post {
            if (released) return@post
            if (createEgl(holder)) {
                dispatchSurfaceCreated()
                dispatchSurfaceChanged()
                requestRenderOnEglThread()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (released) return
        eglHandler.post {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            if (makeCurrent()) {
                GLES20.glViewport(0, 0, this.width, this.height)
                dispatchSurfaceChanged()
                requestRenderOnEglThread()
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released) return
        val done = CountDownLatch(1)
        eglHandler.post {
            destroyEgl()
            rendererCreated = false
            done.countDown()
        }
        try {
            done.await(2500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
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
            done.await(2500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread.quitSafely()
    }

    private fun createEgl(surfaceHolder: SurfaceHolder): Boolean {
        destroyEgl()
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
            destroyEgl()
            return false
        }
        val renderable = if (requestedClientVersion >= 3) EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, count, 0) ||
            count[0] == 0 || configs[0] == null) {
            destroyEgl()
            return false
        }
        eglConfig = configs[0]
        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, requestedClientVersion, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            destroyEgl()
            return false
        }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surfaceHolder.surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE || !makeCurrent()) {
            destroyEgl()
            return false
        }
        return true
    }

    private fun makeCurrent(): Boolean =
        eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglSurface != EGL14.EGL_NO_SURFACE &&
            eglContext != EGL14.EGL_NO_CONTEXT &&
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

    private fun dispatchSurfaceCreated() {
        val target = renderer ?: return
        if (!rendererCreated) {
            target.onSurfaceCreated(null, null)
            rendererCreated = true
        }
    }

    private fun dispatchSurfaceChanged() {
        renderer?.onSurfaceChanged(null, width, height)
    }

    private fun requestRenderOnEglThread() {
        if (released || !resumed || renderQueued || !makeCurrent()) return
        if (renderMode != RENDERMODE_CONTINUOUSLY && rendererCreated.not()) return
        renderQueued = true
        eglHandler.post {
            renderQueued = false
            if (released || !resumed || !makeCurrent()) return@post
            val target = renderer ?: return@post
            target.onDrawFrame(null)
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                android.util.Log.e("N64GameEGL", "eglSwapBuffers falhou: 0x${EGL14.eglGetError().toString(16)}")
                return@post
            }
            if (renderMode == RENDERMODE_CONTINUOUSLY) {
                eglHandler.postDelayed({ requestRenderOnEglThread() }, 16L)
            }
        }
    }

    private fun destroyEgl() {
        renderQueued = false
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
    }
}
