package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64ControllerPortTest {
    @Test fun pressAndReleaseButtonWithoutLosingOtherButtons() {
        val port = N64ControllerPort()
        port.setButton(N64ControllerState.A, true)
        port.setButton(N64ControllerState.START, true)
        port.setButton(N64ControllerState.A, false)

        val state = port.snapshot()
        assertFalse(state.isPressed(N64ControllerState.A))
        assertTrue(state.isPressed(N64ControllerState.START))
    }

    @Test fun clampsAnalogAxesToSignedByteRange() {
        val port = N64ControllerPort()
        port.setAnalogStick(-200, 200)

        assertEquals(-128, port.snapshot().stickX)
        assertEquals(127, port.snapshot().stickY)
    }

    @Test fun releaseAllClearsButtonsAndAxes() {
        val port = N64ControllerPort()
        port.setButton(N64ControllerState.B, true)
        port.setAnalogStick(50, -70)
        port.releaseAll()

        assertEquals(N64ControllerState(), port.snapshot())
    }
}
