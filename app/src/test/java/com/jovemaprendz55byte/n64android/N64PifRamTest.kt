package com.jovemaprendz55byte.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64PifRamTest {
    @Test fun siDmaReadsControllerButtonsAndAnalogStickFromPifRam() {
        val port = N64ControllerPort().apply {
            setButton(N64ControllerState.A, true)
            setButton(N64ControllerState.START, true)
            setAnalogStick(-12, 34)
        }
        val bus = N64MemoryBus(controllerPort = port)
        val commandBuffer = ByteArray(N64PifRam.SIZE)
        // Joybus: tx length 1, rx length 4, command 1 (read controller).
        commandBuffer[0] = 1
        commandBuffer[1] = 4
        commandBuffer[2] = 1
        commandBuffer[7] = 0xFF.toByte()
        commandBuffer[63] = 1
        bus.loadRdram(0x100L, commandBuffer)

        bus.writeWord(N64MemoryBus.SI_DRAM_ADDR, 0x100)
        bus.writeWord(N64MemoryBus.SI_PIF_ADDR_WR64B, 0)
        assertEquals(0, bus.readWord(N64MemoryBus.SI_STATUS) and 1)

        bus.writeWord(N64MemoryBus.SI_DRAM_ADDR, 0x200)
        bus.writeWord(N64MemoryBus.SI_PIF_ADDR_RD64B, 0)

        assertEquals(0x90, bus.readByte(0x203L).toInt() and 0xFF)
        assertEquals(0x00, bus.readByte(0x204L).toInt() and 0xFF)
        assertEquals((-12).toByte(), bus.readByte(0x205L))
        assertEquals(34.toByte(), bus.readByte(0x206L))
    }

    @Test fun pifControllerStatusCommandReturnsBasicControllerInfo() {
        val bus = N64MemoryBus()
        val commandBuffer = ByteArray(N64PifRam.SIZE)
        commandBuffer[0] = 1
        commandBuffer[1] = 3
        commandBuffer[2] = 0
        commandBuffer[6] = 0xFF.toByte()
        commandBuffer[63] = 1
        bus.loadRdram(0x100L, commandBuffer)

        bus.writeWord(N64MemoryBus.SI_DRAM_ADDR, 0x100)
        bus.writeWord(N64MemoryBus.SI_PIF_ADDR_WR64B, 0)
        bus.writeWord(N64MemoryBus.SI_DRAM_ADDR, 0x200)
        bus.writeWord(N64MemoryBus.SI_PIF_ADDR_RD64B, 0)

        assertEquals(0x05, bus.readByte(0x203L).toInt() and 0xFF)
        assertEquals(0x00, bus.readByte(0x204L).toInt() and 0xFF)
        assertEquals(0x02, bus.readByte(0x205L).toInt() and 0xFF)
    }

    @Test fun rejectsPifDmaWhenRdramRangeIsTooSmallWithoutPartialWrites() {
        val bus = N64MemoryBus(rdramSize = 0x100)
        bus.loadRdram(0xC8, ByteArray(0x38) { 0x5A })
        bus.writeWord(N64MemoryBus.SI_DRAM_ADDR, 0xC8)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            bus.writeWord(N64MemoryBus.SI_PIF_ADDR_RD64B, 0)
        }

        // The invalid 64-byte DMA must fail before modifying its valid prefix.
        assertEquals(0x5A, bus.readByte(0xC8).toInt() and 0xFF)
        assertEquals(0x5A, bus.readByte(0xFF).toInt() and 0xFF)
        assertEquals(0, bus.readWord(N64MemoryBus.SI_STATUS) and 1)
    }
}
