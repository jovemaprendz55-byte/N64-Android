package com.jsr.n64android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on Android so System.loadLibrary and the real JNI bridge are exercised.
 * These tests validate native memory plumbing only, not N64 game compatibility.
 */
@RunWith(AndroidJUnit4::class)
class N64NativeEngineInstrumentedTest {
    @Test
    fun nativeLibraryStartsAndReportsExpectedApi() {
        N64NativeEngine().use { engine ->
            assertEquals(4, engine.apiVersion())
            assertEquals(N64NativeEngine.State.CREATED, engine.state())
        }
    }

    @Test
    fun nativeRdramPreservesBigEndianWordsAndBulkBytes() {
        N64NativeEngine().use { engine ->
            engine.writeRdramWord(0x100, 0x12345678)
            assertEquals(0x12345678, engine.readRdramWord(0x100))

            val payload = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x7F, 0x80.toByte(), 0xFE.toByte())
            engine.writeRdramBytes(0x200, payload)
            assertArrayEquals(payload, engine.readRdramBytes(0x200, payload.size))
        }
    }

    @Test
    fun nativeRdramRejectsMisalignedAndOutOfRangeAccess() {
        N64NativeEngine().use { engine ->
            assertThrows(IllegalArgumentException::class.java) {
                engine.readRdramWord(0x101)
            }
            assertThrows(IllegalArgumentException::class.java) {
                engine.readRdramBytes(8 * 1024 * 1024, 1)
            }
        }
    }

    @Test
    fun nativeEngineRejectsUseAfterClose() {
        val engine = N64NativeEngine()
        engine.close()
        engine.close()
        assertThrows(IllegalStateException::class.java) {
            engine.apiVersion()
        }
    }
}
