package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R4300TlbTest {
    @Test fun translatesEvenAndOddFourKiBPages() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 3,
            evenPage = R4300Tlb.Page(0x123, valid = true, dirty = true),
            oddPage = R4300Tlb.Page(0x456, valid = true, dirty = true)
        ))

        assertEquals(0x00123034, tlb.translate(0x00400034L, 3, R4300Tlb.Access.READ))
        assertEquals(0x00456078, tlb.translate(0x00401078L, 3, R4300Tlb.Access.FETCH))
    }

    @Test fun mismatchedAsidCausesMissUnlessEntryIsGlobal() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            0x10000L, 7,
            R4300Tlb.Page(1, true, true),
            R4300Tlb.Page(2, true, true)
        ))
        assertThrows(R4300Tlb.TlbFault::class.java) {
            tlb.translate(0x10000L, 8, R4300Tlb.Access.READ)
        }

        tlb.write(0, R4300Tlb.Entry(
            0x10000L, 7,
            R4300Tlb.Page(1, true, true, global = true),
            R4300Tlb.Page(2, true, true, global = true)
        ))
        assertEquals(0x1000, tlb.translate(0x10000L, 8, R4300Tlb.Access.READ))
    }

    @Test fun invalidPageAndReadOnlyPageRaiseDistinctFaults() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            0x20000L, 1,
            R4300Tlb.Page(3, valid = false, dirty = true),
            R4300Tlb.Page(4, valid = true, dirty = false)
        ))

        val invalid = assertThrows(R4300Tlb.TlbFault::class.java) {
            tlb.translate(0x20010L, 1, R4300Tlb.Access.READ)
        }
        assertEquals(R4300Tlb.FaultKind.INVALID, invalid.kind)

        val modified = assertThrows(R4300Tlb.TlbFault::class.java) {
            tlb.translate(0x21010L, 1, R4300Tlb.Access.WRITE)
        }
        assertEquals(R4300Tlb.FaultKind.MODIFIED, modified.kind)
        assertEquals(0x21010L, modified.virtualAddress)
    }

    @Test fun preservesPageOffsetsAtBothFourKiBBoundaries() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x12000L,
            asid = 2,
            evenPage = R4300Tlb.Page(0xABCDE, valid = true, dirty = true),
            oddPage = R4300Tlb.Page(0x12345, valid = true, dirty = true)
        ))

        assertEquals(0xABCDE000.toInt(), tlb.translate(0x12000L, 2, R4300Tlb.Access.READ))
        assertEquals(0xABCDEFFF.toInt(), tlb.translate(0x12FFFL, 2, R4300Tlb.Access.READ))
        assertEquals(0x12345000, tlb.translate(0x13000L, 2, R4300Tlb.Access.READ))
        assertEquals(0x12345FFF, tlb.translate(0x13FFFL, 2, R4300Tlb.Access.READ))
    }

    @Test fun highestPhysicalFramePreservesUnsignedAddressBits() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x20000L,
            asid = 0,
            evenPage = R4300Tlb.Page(0xFFFFF, valid = true, dirty = true),
            oddPage = R4300Tlb.Page(0, valid = true, dirty = true)
        ))

        assertEquals(-1, tlb.translate(0x20FFFL, 0, R4300Tlb.Access.READ))
    }

    @Test fun clearRemovesEntriesAndSubsequentTranslationMisses() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x40000L,
            asid = 1,
            evenPage = R4300Tlb.Page(1, valid = true, dirty = true),
            oddPage = R4300Tlb.Page(2, valid = true, dirty = true)
        ))
        assertEquals(0x1000, tlb.translate(0x40000L, 1, R4300Tlb.Access.READ))

        tlb.clear()

        val fault = assertThrows(R4300Tlb.TlbFault::class.java) {
            tlb.translate(0x40000L, 1, R4300Tlb.Access.READ)
        }
        assertEquals(R4300Tlb.FaultKind.MISS, fault.kind)
        assertEquals(0x40000L, fault.virtualAddress)
        assertEquals(R4300Tlb.Access.READ, fault.access)
    }

    @Test fun validatesIndexAndPagePairAlignment() {
        val tlb = R4300Tlb()
        assertThrows(IllegalArgumentException::class.java) { tlb.read(32) }
        assertThrows(IllegalArgumentException::class.java) {
            tlb.write(0, R4300Tlb.Entry(
                0x1000L, 0,
                R4300Tlb.Page(0, true, true),
                R4300Tlb.Page(1, true, true)
            ))
        }
    }

    @Test fun probeRequiresMatchingAsidUnlessBothPagesAreGlobal() {
        val tlb = R4300Tlb()
        tlb.write(4, R4300Tlb.Entry(
            0x00400000L, 7,
            R4300Tlb.Page(1, valid = true, dirty = true, global = true),
            R4300Tlb.Page(2, valid = true, dirty = true, global = false)
        ))

        // One G bit is insufficient: ASID must still match.
        assertEquals(null, tlb.probe(0x00400000L, 8))
        assertEquals(4, tlb.probe(0x00400000L, 7))

        tlb.write(4, R4300Tlb.Entry(
            0x00400000L, 7,
            R4300Tlb.Page(1, valid = true, dirty = true, global = true),
            R4300Tlb.Page(2, valid = true, dirty = true, global = true)
        ))
        assertEquals(4, tlb.probe(0x00401000L, 8))
        assertEquals(null, tlb.probe(0x00600000L, 7))
    }

    @Test fun normalizesSignExtendedVirtualPairBase() {
        val tlb = R4300Tlb()
        val signExtendedBase = 0xFFFFFFFF80000000UL.toLong()
        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = signExtendedBase,
            asid = 7,
            evenPage = R4300Tlb.Page(0x12345, valid = true, dirty = true),
            oddPage = R4300Tlb.Page(0x23456, valid = true, dirty = true)
        ))

        assertEquals(0, tlb.probe(signExtendedBase, 7))
        assertEquals(0x12345000, tlb.translate(signExtendedBase, 7, R4300Tlb.Access.READ))
        assertEquals(0x23456000, tlb.translate(signExtendedBase + 0x1000L, 7, R4300Tlb.Access.READ))
    }


    @Test fun translationRequiresBothGlobalBitsWhenAsidDiffers() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 7,
            evenPage = R4300Tlb.Page(0x123, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(0x456, valid = true, dirty = true, global = false)
        ))

        val fault = assertThrows(R4300Tlb.TlbFault::class.java) {
            tlb.translate(0x00400000L, 8, R4300Tlb.Access.READ)
        }
        assertEquals(R4300Tlb.FaultKind.MISS, fault.kind)

        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 7,
            evenPage = R4300Tlb.Page(0x123, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(0x456, valid = true, dirty = true, global = true)
        ))

        assertEquals(0x00123000, tlb.translate(0x00400000L, 8, R4300Tlb.Access.READ))
        assertEquals(0x00456000, tlb.translate(0x00401000L, 8, R4300Tlb.Access.READ))
    }


    @Test fun translatesEvenAndOddSixteenKiBPagesAndMasksLowPfnBits() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x00400000L,
            asid = 3,
            evenPage = R4300Tlb.Page(0x123, valid = true, dirty = true),
            oddPage = R4300Tlb.Page(0x456, valid = true, dirty = true),
            pageMask = 0x6000L
        ))

        // A 16 KiB page uses address bit 14 to select the odd half;
        // low two PFN bits are ignored, so PFN 0x123 aligns to 0x120.
        assertEquals(0x00120034, tlb.translate(0x00400034L, 3, R4300Tlb.Access.READ))
        assertEquals(0x00454078, tlb.translate(0x00404078L, 3, R4300Tlb.Access.READ))
        assertEquals(0, tlb.probe(0x00407000L, 3))
    }

    @Test fun largerPageMaskMatchesWholeVirtualPairAndHonorsAsidGlobalRules() {
        val tlb = R4300Tlb()
        tlb.write(0, R4300Tlb.Entry(
            virtualPagePairBase = 0x01000000L,
            asid = 4,
            evenPage = R4300Tlb.Page(0x200, valid = true, dirty = true, global = true),
            oddPage = R4300Tlb.Page(0x300, valid = true, dirty = true, global = true),
            pageMask = 0x1E000L
        ))

        assertEquals(0, tlb.probe(0x0100FFFFL, 9))
        assertEquals(0x0020FFFF, tlb.translate(0x0100FFFFL, 9, R4300Tlb.Access.READ))
        assertEquals(0x00300000, tlb.translate(0x01010000L, 9, R4300Tlb.Access.READ))
        assertEquals(null, tlb.probe(0x01020000L, 9))
    }

    @Test fun rejectsUnsupportedPageMaskAndMisalignedLargePairBase() {
        assertThrows(IllegalArgumentException::class.java) {
            R4300Tlb.normalizePageMask(0x2000L)
        }
        val tlb = R4300Tlb()
        assertThrows(IllegalArgumentException::class.java) {
            tlb.write(0, R4300Tlb.Entry(
                virtualPagePairBase = 0x00402000L,
                asid = 0,
                evenPage = R4300Tlb.Page(1, true, true),
                oddPage = R4300Tlb.Page(2, true, true),
                pageMask = 0x6000L
            ))
        }
    }

}
