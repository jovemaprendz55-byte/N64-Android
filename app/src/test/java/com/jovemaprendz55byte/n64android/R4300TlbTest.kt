package com.jovemaprendz55byte.n64android

import org.junit.Assert.*
import org.junit.Test

class R4300TlbTest {
    @Test fun translatesEvenAndOddPagesAndKeepsPageOffset() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            entryHi = R4300Tlb.entryHi(0x0040_0000L, 7),
            entryLo0 = R4300Tlb.entryLo(0x123, cacheAttribute = 3),
            entryLo1 = R4300Tlb.entryLo(0x456, cacheAttribute = 2)
        ))

        assertEquals(0x0012_3ABCL, tlb.translate(0x0040_0ABCL, 7).physicalAddress)
        assertEquals(0x0045_6ABCL, tlb.translate(0x0040_1ABCL, 7).physicalAddress)
        assertEquals(0, tlb.translate(0x0040_0ABCL, 7).entryIndex)
    }

    @Test fun differentAsidDoesNotMatchUnlessBothPagesAreGlobal() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            R4300Tlb.entryHi(0x1000_0000L, 4),
            R4300Tlb.entryLo(1), R4300Tlb.entryLo(2)
        ))
        assertEquals(-1, tlb.probe(R4300Tlb.entryHi(0x1000_0000L, 5)))
        tlb.write(1, R4300Tlb.Entry(
            R4300Tlb.entryHi(0x1000_0000L, 4),
            R4300Tlb.entryLo(1, global = true),
            R4300Tlb.entryLo(2, global = true)
        ))
        assertEquals(1, tlb.probe(R4300Tlb.entryHi(0x1000_0000L, 5)))
    }

    @Test fun invalidPageRaisesInvalidFault() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            R4300Tlb.entryHi(0x2000_0000L, 1),
            R4300Tlb.entryLo(0x10, valid = false),
            R4300Tlb.entryLo(0x11)
        ))
        assertFault(R4300Tlb.Fault.INVALID) { tlb.translate(0x2000_0000L, 1) }
    }

    @Test fun storeToCleanPageRaisesModificationFault() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            R4300Tlb.entryHi(0x3000_0000L, 1),
            R4300Tlb.entryLo(0x20, dirty = false),
            R4300Tlb.entryLo(0x21)
        ))
        assertFault(R4300Tlb.Fault.MODIFICATION) { tlb.translate(0x3000_0000L, 1, store = true) }
        assertEquals(0x0002_0000L, tlb.translate(0x3000_0000L, 1).physicalAddress)
    }

    @Test fun absentEntryRaisesMissAndResetClearsEntries() {
        val tlb = R4300Tlb()
        assertFault(R4300Tlb.Fault.MISS) { tlb.translate(0x4000_0000L, 0) }
        tlb.write(2, R4300Tlb.Entry(
            R4300Tlb.entryHi(0x4000_0000L, 0),
            R4300Tlb.entryLo(0x30), R4300Tlb.entryLo(0x31)
        ))
        assertEquals(2, tlb.probe(R4300Tlb.entryHi(0x4000_0000L, 0)))
        tlb.reset()
        assertFault(R4300Tlb.Fault.MISS) { tlb.translate(0x4000_0000L, 0) }
    }

    @Test fun rejectsUnsupportedPageMaskAndInvalidIndex() {
        val tlb = R4300Tlb()
        try {
            tlb.write(0, R4300Tlb.Entry(0, 0, 0, pageMask = 0x6000L))
            fail("Unsupported PageMask should be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            tlb.read(32)
            fail("Out-of-range index should be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    private fun assertFault(expected: R4300Tlb.Fault, action: () -> Unit) {
        try {
            action()
            fail("Expected TLB fault $expected")
        } catch (error: R4300Tlb.TranslationException) {
            assertEquals(expected, error.fault)
        }
    }
}
