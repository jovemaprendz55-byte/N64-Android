package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class N64AddressingTest {
    @Test fun translatesKseg0AndKseg1ToSamePhysicalAddress() {
        assertEquals(0x0000_1234L, N64Addressing.translateDirectMappedAddress(0x8000_1234L))
        assertEquals(0x0000_1234L, N64Addressing.translateDirectMappedAddress(0xA000_1234L))
    }

    @Test fun acceptsSignExtendedR4300VirtualAddresses() {
        assertEquals(0x0000_1234L, N64Addressing.translateDirectMappedAddress(0xFFFF_FFFF_8000_1234UL.toLong()))
        assertEquals(0x0000_1234L, N64Addressing.translateDirectMappedAddress(0xFFFF_FFFF_A000_1234UL.toLong()))
    }

    @Test fun translatesTheTopOfKsegSegments() {
        assertEquals(0x1FFF_FFFFL, N64Addressing.translateDirectMappedAddress(0x9FFF_FFFFL))
        assertEquals(0x1FFF_FFFFL, N64Addressing.translateDirectMappedAddress(0xBFFF_FFFFL))
    }

    @Test fun bootFaultAddressRequiresRealTlbTranslation() {
        assertNull(N64Addressing.translateDirectMappedAddress(0x7F00_0BD0L))
        assertNull(N64Addressing.translateDirectMappedAddress(0xFFFF_FFFF_7F00_0BD0UL.toLong()))
    }

    @Test fun refusesAddressesThatNeedTlbTranslation() {
        assertNull(N64Addressing.translateDirectMappedAddress(0x0000_1234L))
        assertNull(N64Addressing.translateDirectMappedAddress(0xC000_1234L))
        assertNull(N64Addressing.translateDirectMappedAddress(0x1_0000_0000L))
    }
}
