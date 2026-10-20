package com.jovemaprendz55byte.n64android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Android presentation surface for frames produced by the N64 video pipeline.
 *
 * The caller submits a completed frame with presentFrame(). Frame production
 * and synchronization are intentionally owned by the emulator loop, not this
 * View. This is a presentation layer only; it does not emulate the RDP.
 */
class N64GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    private var frameBitmap: Bitmap? = null

    init {
        setBackgroundColor(Color.BLACK)
        contentDescription = "Tela de vídeo do emulador Nintendo 64"
    }

    /**
     * Copies [frame] into a bitmap and schedules a redraw.
     * May be called from a worker thread; the view swaps the bitmap on the UI thread.
     */
    fun presentFrame(frame: N64VideoFrameBuffer) {
        val pixels = frame.snapshot()
        val bitmap = Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        post {
            val old = frameBitmap
            frameBitmap = bitmap
            old?.recycle()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frameBitmap ?: return
        if (width <= 0 || height <= 0) return
        destination.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun onDetachedFromWindow() {
        frameBitmap?.recycle()
        frameBitmap = null
        super.onDetachedFromWindow()
    }
}
