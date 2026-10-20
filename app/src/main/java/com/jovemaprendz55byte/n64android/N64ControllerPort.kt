package com.jovemaprendz55byte.n64android

/**
 * Small frontend-neutral controller input state.
 *
 * Android touch controls can update this object, and a future SI/PIF emulation
 * layer can serialize it. It intentionally does not pretend that the N64
 * serial interface or game input loop is already implemented.
 */
class N64ControllerPort {
    private val lock = Any()
    private var current = N64ControllerState()

    fun snapshot(): N64ControllerState = synchronized(lock) { current }

    fun setButton(button: Int, pressed: Boolean) {
        require(button != 0 && button and N64ControllerState.BUTTON_MASK == button &&
            button.countOneBits() == 1) { "Expected exactly one valid N64 button" }
        synchronized(lock) {
            val nextButtons = if (pressed) {
                current.buttons or button
            } else {
                current.buttons and button.inv()
            }
            current = current.copy(buttons = nextButtons)
        }
    }

    fun setAnalogStick(x: Int, y: Int) {
        synchronized(lock) {
            current = current.copy(stickX = x.coerceIn(-128, 127), stickY = y.coerceIn(-128, 127))
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            current = N64ControllerState()
        }
    }
}
