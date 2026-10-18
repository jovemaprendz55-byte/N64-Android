package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RomNormalizerTest {
    @Test fun keepsBigEndianRomBytesUnchanged() {
        val canonical = sampleRom(0x80, 0x37, 0x12, 0x40)
        assertArrayEquals(canonical, RomNormalizer.normalize(canonical))
    }

    @Test fun convertsByteSwappedV64ToBigEndian() {
        val canonical = sampleRom(0x80, 0x37, 0x12, 0x40)
        val v64 = ByteArray(canonical.size) { index ->
            canonical[index xor 1]
        }
        assertArrayEquals(canonical, RomNormalizer.normalize(v64))
    }

    @Test fun convertsWordSwappedN64ToBigEndian() {
        val canonical = sampleRom(0x80, 0x37, 0x12, 0x40)
        val n64 = ByteArray(canonical.size) { index ->
            canonical[(index and -4) + (3 - (index and 3))]
        }
        assertArrayEquals(canonical, RomNormalizer.normalize(n64))
    }

    @Test fun rejectsUnknownAndMisalignedRoms() {
        assertNull(RomNormalizer.normalize(ByteArray(0x40)))
        val misaligned = sampleRom(0x80, 0x37, 0x12, 0x40).copyOf(0x41)
        assertNull(RomNormalizer.normalize(misaligned))
    }

    @Test fun doesNotModifyTheInputArray() {
        val input = sampleRom(0x37, 0x80, 0x40, 0x12)
        val before = input.copyOf()
        val normalized = RomNormalizer.normalize(input)
        assertNotNull(normalized)
        assertArrayEquals(before, input)
    }

    private fun sampleRom(a: Int, b: Int, c: Int, d: Int): ByteArray =
        ByteArray(0x80) { index -> (index * 7).toByte() }.apply {
            this[0] = a.toByte()
            this[1] = b.toByte()
            this[2] = c.toByte()
            this[3] = d.toByte()
        }
}
