package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64VideoScanoutTest {
    @Test fun readsBigEndianRgba5551Rows() {
        val frame = N64VideoFrameBuffer(2, 1)
        val memory = byteArrayOf(0xF8.toByte(), 0x01, 0x07, 0xC0.toByte())
        N64VideoScanout.copyRgba5551(
            source = memory,
            sourceWidth = 2,
            sourceHeight = 1,
            destination = frame
        )

        assertEquals(0xFFFF0000.toInt(), frame.pixelAt(0, 0))
        assertEquals(0x0000FF00, frame.pixelAt(1, 0))
    }

    @Test fun honorsSourceStrideAndDestinationClipping() {
        val frame = N64VideoFrameBuffer(2, 2)
        val memory = byteArrayOf(
            0xF8.toByte(), 0x01, 0, 0,
            0x07, 0xC0.toByte(), 0, 0
        )
        N64VideoScanout.copyRgba5551(
            source = memory,
            sourceWidth = 1,
            sourceHeight = 2,
            sourceStride = 4,
            destination = frame,
            destinationX = 1
        )

        assertEquals(0xFFFF0000.toInt(), frame.pixelAt(1, 0))
        assertEquals(0x0000FF00, frame.pixelAt(1, 1))
    }

    @Test fun rejectsTruncatedSourceBeforeChangingDestination() {
        val frame = N64VideoFrameBuffer(2, 1)
        frame.clear(0xFF112233.toInt())

        assertThrows(IllegalArgumentException::class.java) {
            N64VideoScanout.copyRgba5551(
                source = byteArrayOf(0xF8.toByte(), 0x01, 0x07),
                sourceWidth = 2,
                sourceHeight = 1,
                destination = frame
            )
        }

        assertEquals(0xFF112233.toInt(), frame.pixelAt(0, 0))
        assertEquals(0xFF112233.toInt(), frame.pixelAt(1, 0))
    }

    @Test fun rejectsStrideShorterThanPixelRow() {
        assertThrows(IllegalArgumentException::class.java) {
            N64VideoScanout.copyRgba5551(
                source = byteArrayOf(),
                sourceWidth = 2,
                sourceHeight = 1,
                sourceStride = 2,
                destination = N64VideoFrameBuffer(2, 1)
            )
        }
    }

    @Test fun rejectsSourceWidthThatWouldOverflowRgba5551RowSize() {
        assertThrows(IllegalArgumentException::class.java) {
            N64VideoScanout.copyRgba5551(
                source = byteArrayOf(),
                sourceWidth = Int.MAX_VALUE,
                sourceHeight = 1,
                destination = N64VideoFrameBuffer(1, 1)
            )
        }
    }
}
