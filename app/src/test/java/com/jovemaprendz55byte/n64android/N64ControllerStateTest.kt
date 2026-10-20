package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64ControllerStateTest {
    @Test fun serializesButtonWordBigEndianAndStickAsSignedBytes() {
        val state = N64ControllerState(
            buttons = N64ControllerState.A or N64ControllerState.START or N64ControllerState.C_RIGHT,
            stickX = -80,
            stickY = 63
        )

        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x01, -80, 63), state.toPakResponse())
    }

    @Test fun reportsIndividualButtons() {
        val state = N64ControllerState(buttons = N64ControllerState.Z or N64ControllerState.D_LEFT)

        assertTrue(state.isPressed(N64ControllerState.Z))
        assertTrue(state.isPressed(N64ControllerState.D_LEFT))
        assertFalse(state.isPressed(N64ControllerState.A))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownButtonBits() {
        N64ControllerState(buttons = 0x0040)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAxisOutsideSignedByteRange() {
        N64ControllerState(stickX = 128)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultipleButtonsWhenCheckingSingleButton() {
        N64ControllerState().isPressed(N64ControllerState.A or N64ControllerState.B)
    }
}
