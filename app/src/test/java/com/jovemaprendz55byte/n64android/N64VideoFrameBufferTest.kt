package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class N64VideoFrameBufferTest {
    @Test fun convertsRgba5551ToArgb() {
        val frame = N64VideoFrameBuffer(2, 1)
        frame.setRgba5551(0, 0, 0xF801) // red, opaque
        frame.setRgba5551(1, 0, 0x07C0) // green, transparent

        assertEquals(0xFFFF0000.toInt(), frame.pixelAt(0, 0))
        assertEquals(0x0000FF00, frame.pixelAt(1, 0))
    }

    @Test fun copiesRgba8888WithDestinationClipping() {
        val frame = N64VideoFrameBuffer(2, 1)
        val source = byteArrayOf(
            10, 20, 30, -1,
            40, 50, 60, 127,
            70, 80, 90, -1
        )
        frame.copyRgba8888(source, sourceWidth = 3, sourceHeight = 1, destinationX = -1)

        assertEquals(0x7F28323C, frame.pixelAt(0, 0))
        assertEquals(0xFF46505A.toInt(), frame.pixelAt(1, 0))
    }

    @Test fun clearAndSnapshotDoNotExposeInternalStorage() {
        val frame = N64VideoFrameBuffer(2, 2)
        frame.clear(0xFF123456.toInt())
        val snapshot = frame.snapshot()
        snapshot[0] = 0

        assertEquals(0xFF123456.toInt(), frame.pixelAt(0, 0))
        assertNotSame(snapshot, frame.snapshot())
    }

    @Test fun rejectsInvalidDimensionsAndSourceStride() {
        assertThrows(IllegalArgumentException::class.java) { N64VideoFrameBuffer(0, 1) }
        val frame = N64VideoFrameBuffer(1, 1)
        assertThrows(IllegalArgumentException::class.java) {
            frame.copyRgba8888(byteArrayOf(), sourceWidth = 1, sourceHeight = 1, sourceStride = 3)
        }
    }
}
