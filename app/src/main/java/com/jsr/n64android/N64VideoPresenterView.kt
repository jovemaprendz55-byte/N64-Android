package com.jsr.n64android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Reusable Android surface for VI frames.
 *
 * Frames may be submitted by an emulation worker. A latest-frame-wins mailbox
 * coalesces UI updates so a fast producer cannot queue an unbounded number of
 * Bitmaps or UI tasks.
 */
class N64VideoPresenterView(context: Context) : View(context) {
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destination = RectF()
    private val frameLock = Any()
    private var frameBitmap: Bitmap? = null
    private var pendingBitmap: Bitmap? = null
    private var updatePosted = false

    fun submitFrame(frame: N64VideoFrame) {
        require(frame.width > 0 && frame.height > 0)
        require(frame.argbPixels.size == frame.width * frame.height)
        val nextBitmap = Bitmap.createBitmap(
            frame.argbPixels,
            frame.width,
            frame.height,
            Bitmap.Config.ARGB_8888
        )
        val shouldPost = synchronized(frameLock) {
            val replaced = pendingBitmap
            pendingBitmap = nextBitmap
            if (replaced != null && !replaced.isRecycled) replaced.recycle()
            if (updatePosted) false else {
                updatePosted = true
                true
            }
        }
        if (shouldPost) {
            post {
                synchronized(frameLock) {
                    val previous = frameBitmap
                    frameBitmap = pendingBitmap
                    pendingBitmap = null
                    updatePosted = false
                    if (previous != null && previous !== frameBitmap && !previous.isRecycled) {
                        previous.recycle()
                    }
                    invalidate()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        synchronized(frameLock) {
            val bitmap = frameBitmap ?: return
            if (bitmap.isRecycled || width <= 0 || height <= 0) return
            val scale = minOf(
                width.toFloat() / bitmap.width,
                height.toFloat() / bitmap.height
            )
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (width - drawWidth) / 2f
            val top = (height - drawHeight) / 2f
            destination.set(left, top, left + drawWidth, top + drawHeight)
            canvas.drawBitmap(bitmap, null, destination, framePaint)
        }
    }

    override fun onDetachedFromWindow() {
        synchronized(frameLock) {
            frameBitmap?.recycle()
            pendingBitmap?.recycle()
            frameBitmap = null
            pendingBitmap = null
            updatePosted = false
        }
        super.onDetachedFromWindow()
    }
}
