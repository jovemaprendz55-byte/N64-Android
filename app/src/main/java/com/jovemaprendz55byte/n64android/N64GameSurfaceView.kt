package com.jovemaprendz55byte.n64android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    // Keep at most one not-yet-presented frame; slow UI rendering must not queue
    // an unbounded number of bitmaps from the emulator worker.
    private val pendingBitmap = AtomicReference<Bitmap?>(null)
    private val frameUpdatePosted = AtomicBoolean(false)

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
        // Replace stale pending frames instead of posting one UI runnable per
        // frame. This bounds queued presentation memory if the UI is busy.
        pendingBitmap.getAndSet(bitmap)?.let { oldPending ->
            if (!oldPending.isRecycled) oldPending.recycle()
        }
        scheduleFrameUpdate()
    }

    private fun scheduleFrameUpdate() {
        if (!frameUpdatePosted.compareAndSet(false, true)) return
        post {
            if (!isAttachedToWindow) {
                pendingBitmap.getAndSet(null)?.let { pending ->
                    if (!pending.isRecycled) pending.recycle()
                }
                frameUpdatePosted.set(false)
                return@post
            }

            pendingBitmap.getAndSet(null)?.let { next ->
                val old = frameBitmap
                frameBitmap = next
                if (old !== next && old?.isRecycled == false) old.recycle()
                invalidate()
            }

            frameUpdatePosted.set(false)
            // Close the race where a worker publishes a frame while this
            // runnable is finishing and after it cleared the posted flag.
            if (pendingBitmap.get() != null) scheduleFrameUpdate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frameBitmap ?: return
        if (width <= 0 || height <= 0) return
        // Keep the console image's aspect ratio (normally 4:3) and letterbox
        // instead of stretching pixels to the phone's tall display.
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val drawHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        destination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun onDetachedFromWindow() {
        pendingBitmap.getAndSet(null)?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        frameBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        frameBitmap = null
        super.onDetachedFromWindow()
    }
}
