package com.jovemaprendz55byte.n64android

/**
 * Immutable snapshot of one standard N64 controller's digital buttons and
 * signed analog stick axes. This models the controller data format only;
 * SI/PIF serial protocol integration is a separate subsystem.
 */
data class N64ControllerState(
    val buttons: Int = 0,
    val stickX: Int = 0,
    val stickY: Int = 0
) {
    init {
        require(buttons and BUTTON_MASK.inv() == 0) {
            "Unknown N64 controller button bits: 0x${buttons.toString(16)}"
        }
        require(stickX in -128..127) { "stickX must fit a signed byte" }
        require(stickY in -128..127) { "stickY must fit a signed byte" }
    }

    fun isPressed(button: Int): Boolean {
        require(button != 0 && button and BUTTON_MASK == button && button.countOneBits() == 1) {
            "button must be one known N64 button bit"
        }
        return buttons and button != 0
    }

    /** Four-byte controller payload: buttons high byte, buttons low byte, X, Y. */
    fun toPakResponse(): ByteArray = byteArrayOf(
        (buttons ushr 8).toByte(),
        buttons.toByte(),
        stickX.toByte(),
        stickY.toByte()
    )

    companion object {
        const val A = 0x8000
        const val B = 0x4000
        const val Z = 0x2000
        const val START = 0x1000
        const val D_UP = 0x0800
        const val D_DOWN = 0x0400
        const val D_LEFT = 0x0200
        const val D_RIGHT = 0x0100
        const val L = 0x0020
        const val R = 0x0010
        const val C_UP = 0x0008
        const val C_DOWN = 0x0004
        const val C_LEFT = 0x0002
        const val C_RIGHT = 0x0001

        const val BUTTON_MASK = A or B or Z or START or D_UP or D_DOWN or
            D_LEFT or D_RIGHT or L or R or C_UP or C_DOWN or C_LEFT or C_RIGHT
    }
}
