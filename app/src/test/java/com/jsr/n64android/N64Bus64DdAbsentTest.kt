package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64Bus64DdAbsentTest {
    @Test fun absent64DdReadsReturnOpenBusOnByteHalfwordAndWordAccesses() {
        val bus = N64Bus()

        assertEquals(0xFF, bus.read8(0x05000508))
        assertEquals(0xFFFF, bus.read16(0x05000508))
        assertEquals(0xFFFFFFFF.toInt(), bus.read32(0x05000508))

        // Domain 1 Address 1 is the absent 64DD IPL ROM window.
        for (address in listOf(0x0700071C, 0x07000720)) {
            assertEquals(0xFF, bus.read8(address))
            assertEquals(0xFFFF, bus.read16(address))
            assertEquals(0xFFFFFFFF.toInt(), bus.read32(address))
        }
    }

    @Test fun writesToAbsent64DdWindowAreIgnored() {
        val bus = N64Bus()

        bus.write8(0x05000508, 0x12)
        bus.write16(0x05000508, 0x1234)
        bus.write32(0x05000508, 0x12345678)
        assertEquals(0xFFFFFFFF.toInt(), bus.read32(0x05000508))

        for (address in listOf(0x0700071C, 0x07000720)) {
            bus.write8(address, 0x12)
            bus.write16(address, 0x1234)
            bus.write32(address, 0x12345678)
            assertEquals(0xFFFFFFFF.toInt(), bus.read32(address))
        }
    }
}
