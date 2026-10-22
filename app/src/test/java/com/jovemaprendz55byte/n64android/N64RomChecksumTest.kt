package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class N64RomChecksumTest {
    @Test fun calculatesCic6105ChecksumIndependentlyOfTheCpu() {
        val rom = ByteArray(0x101000) { offset -> ((offset * 31 + 7) and 0xFF).toByte() }

        val checksum = N64RomChecksum.calculateCic6105(rom)

        assertEquals(Pair(0xDD10FA38u, 0x7B639060u), checksum)
    }

    @Test fun returnsNullWhenTheChecksumRegionIsIncomplete() {
        assertNull(N64RomChecksum.calculateCic6105(ByteArray(0x100FFF)))
    }
}
