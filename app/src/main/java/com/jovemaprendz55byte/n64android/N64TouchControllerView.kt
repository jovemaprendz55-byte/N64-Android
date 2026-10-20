package com.jovemaprendz55byte.n64android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Reusable touch overlay for a future gameplay surface.
 *
 * It draws a compact D-pad, A/B, Start, and an analog stick, forwarding state
 * into N64ControllerPort. The view does not render the game or implement SI/PIF.
 */
class N64TouchControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonRects = linkedMapOf<Int, RectF>()
    private val activePointers = mutableMapOf<Int, Int>()
    private val stickPointerIds = mutableSetOf<Int>()
    private var stickCenterX = 0f
    private var stickCenterY = 0f
    private var stickRadius = 1f
    private var stickPointerId: Int? = null

    var controllerPort: N64ControllerPort = N64ControllerPort()
        set(value) {
            if (field !== value) {
                field.releaseAll()
                field = value
                activePointers.clear()
                stickPointerIds.clear()
                stickPointerId = null
                invalidate()
            }
        }

    init {
        isClickable = true
        contentDescription = "Controles táteis do Nintendo 64"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (280 * resources.displayMetrics.density).toInt()
        val desiredHeight = (150 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        buttonRects.clear()
        val radius = min(w * 0.075f, h * 0.18f).coerceAtLeast(12f * density)
        val dpadX = w * 0.19f
        val dpadY = h * 0.52f
        val step = radius * 1.35f
        addButton(canvas, N64ControllerState.D_UP, "↑", dpadX, dpadY - step, radius)
        addButton(canvas, N64ControllerState.D_DOWN, "↓", dpadX, dpadY + step, radius)
        addButton(canvas, N64ControllerState.D_LEFT, "←", dpadX - step, dpadY, radius)
        addButton(canvas, N64ControllerState.D_RIGHT, "→", dpadX + step, dpadY, radius)

        addButton(canvas, N64ControllerState.B, "B", w * 0.76f, h * 0.64f, radius * 1.05f)
        addButton(canvas, N64ControllerState.A, "A", w * 0.88f, h * 0.43f, radius * 1.05f)
        addButton(canvas, N64ControllerState.START, "START", w * 0.51f, h * 0.27f, radius * 0.8f)

        stickCenterX = w * 0.47f
        stickCenterY = h * 0.69f
        stickRadius = min(w * 0.09f, h * 0.23f).coerceAtLeast(16f * density)
        paint.color = 0x665B8DEF
        canvas.drawCircle(stickCenterX, stickCenterY, stickRadius, paint)
        paint.color = 0xCCB8C7E8.toInt()
        canvas.drawCircle(stickCenterX, stickCenterY, stickRadius * 0.36f, paint)
    }

    private fun addButton(
        canvas: Canvas,
        button: Int,
        label: String,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        buttonRects[button] = rect
        paint.color = if (controllerPort.snapshot().isPressed(button)) 0xFF91B5FF.toInt()
            else 0xAA29364F.toInt()
        canvas.drawRoundRect(rect, radius * 0.35f, radius * 0.35f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (label == "START") radius * 0.42f else radius * 0.9f
        canvas.drawText(label, cx, cy + paint.textSize * 0.34f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                handlePointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    handlePointerMove(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                handlePointerUp(event.getPointerId(index))
            }
            MotionEvent.ACTION_CANCEL -> clearInput()
        }
        invalidate()
        return true
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float) {
        val button = buttonRects.entries.firstOrNull { it.value.contains(x, y) }?.key
        if (button != null) {
            activePointers[pointerId] = button
            controllerPort.setButton(button, true)
        } else if (distanceSquared(x, y, stickCenterX, stickCenterY) <= stickRadius * stickRadius * 2.25f) {
            stickPointerId = pointerId
            updateStick(x, y)
        }
    }

    private fun handlePointerMove(pointerId: Int, x: Float, y: Float) {
        if (stickPointerId == pointerId) {
            updateStick(x, y)
            return
        }
        val oldButton = activePointers[pointerId]
        val newButton = buttonRects.entries.firstOrNull { it.value.contains(x, y) }?.key
        if (oldButton != newButton) {
            if (oldButton != null) controllerPort.setButton(oldButton, false)
            if (newButton != null) {
                activePointers[pointerId] = newButton
                controllerPort.setButton(newButton, true)
            } else {
                activePointers.remove(pointerId)
            }
        }
    }

    private fun handlePointerUp(pointerId: Int) {
        activePointers.remove(pointerId)?.let { button ->
            if (activePointers.values.none { it == button }) controllerPort.setButton(button, false)
        }
        if (stickPointerId == pointerId) {
            stickPointerId = null
            controllerPort.setAnalogStick(0, 0)
        }
    }

    private fun updateStick(x: Float, y: Float) {
        val dx = (x - stickCenterX) / stickRadius
        val dy = (stickCenterY - y) / stickRadius
        val magnitude = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val nx = (dx / magnitude).coerceIn(-1f, 1f)
        val ny = (dy / magnitude).coerceIn(-1f, 1f)
        controllerPort.setAnalogStick((nx * 80f).toInt(), (ny * 80f).toInt())
    }

    private fun clearInput() {
        activePointers.clear()
        stickPointerId = null
        controllerPort.releaseAll()
    }

    private fun distanceSquared(x: Float, y: Float, cx: Float, cy: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy
    }

    override fun onDetachedFromWindow() {
        clearInput()
        super.onDetachedFromWindow()
    }
}
