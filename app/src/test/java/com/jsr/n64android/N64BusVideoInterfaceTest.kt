package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64BusVideoInterfaceTest {
    @Test fun viCurrentRegisterIsReadableInsteadOfThrowingUnmappedRead() {
        val bus = N64Bus()

        assertEquals(0, bus.read32(N64Bus.VI_CURRENT_REG))
    }

    @Test fun viConfigurationRegistersCanBeWrittenAndReadBack() {
        val bus = N64Bus()
        val viStatus = N64Bus.VI_REG_BASE
        val viWidth = N64Bus.VI_REG_BASE + 0x08

        bus.write32(viStatus, 0x00000003)
        bus.write32(viWidth, 320)

        assertEquals(0x00000003, bus.read32(viStatus))
        assertEquals(320, bus.read32(viWidth))
    }


    @Test fun capturesFramebufferFromProgrammedViRegistersWithoutAddingMmioReads() {
        val memory = N64Memory(1024)
        val bus = N64Bus(ram = memory)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET, 0x00000002)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, 0x00000020)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, 2)
        memory.write16(0x20, 0xF801)
        memory.write16(0x22, 0x07C1)
        val eventCountBeforeCapture = bus.diagnosticMmioHistory.size

        val frame = bus.captureViFrame(height = 1)

        assertEquals(2, frame?.width)
        assertEquals(1, frame?.height)
        assertEquals(0x20, frame?.origin)
        assertEquals(0xFFFF0000.toInt(), frame?.argbPixels?.get(0))
        assertEquals(0xFF00FF00.toInt(), frame?.argbPixels?.get(1))
        assertEquals(eventCountBeforeCapture, bus.diagnosticMmioHistory.size)
    }


    @Test fun capturesVisibleWidthWithLargerViStride() {
        val memory = N64Memory(128)
        val bus = N64Bus(ram = memory)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET, 2)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, 0x20)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, 4)
        memory.write16(0x20, 0xF801)
        memory.write16(0x22, 0x07C1)
        memory.write16(0x24, 0xFFFF)
        memory.write16(0x26, 0xFFFF)
        memory.write16(0x28, 0x003F)
        memory.write16(0x2A, 0xF83F)

        val frame = bus.captureViFrame(height = 2, outputWidth = 2)

        assertEquals(2, frame?.width)
        assertEquals(2, frame?.height)
        assertEquals(0xFF0000FF.toInt(), frame?.argbPixels?.get(2))
        assertEquals(0xFFFF00FF.toInt(), frame?.argbPixels?.get(3))
    }

    @Test fun derivesActiveHeightFromViVerticalWindowAndScale() {
        val memory = N64Memory(1024 * 1024)
        val bus = N64Bus(ram = memory)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET, 2)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, 0x100)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, 2)
        // 480 half-line units scaled by 0.5 produce 240 output lines.
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_H_START_OFFSET, (0x20 shl 16) or 0x22)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_X_SCALE_OFFSET, 0x400)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (0x20 shl 16) or 0x200)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0x400)
        for (line in 0 until 240) {
            memory.write16(0x100 + line * 4, 0xF801)
            memory.write16(0x102 + line * 4, 0x07C1)
        }

        val frame = bus.captureViFrameFromRegisters()

        assertEquals(2, frame?.width)
        assertEquals(240, frame?.height)
        assertEquals(0xFFFF0000.toInt(), frame?.argbPixels?.get(0))
        assertEquals(0xFF00FF00.toInt(), frame?.argbPixels?.get(1))
    }

    @Test fun derivesVisibleWidthFromHorizontalWindowAndScale() {
        val memory = N64Memory(1024)
        val bus = N64Bus(ram = memory)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET, 2)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, 0x100)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, 4)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_H_START_OFFSET, (0x20 shl 16) or 0x22)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_X_SCALE_OFFSET, 0x400)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (0x20 shl 16) or 0x24)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0x400)
        for (y in 0 until 2) {
            memory.write16(0x100 + y * 8, 0xF801)
            memory.write16(0x102 + y * 8, 0x07C1)
            memory.write16(0x104 + y * 8, 0xFFFF)
            memory.write16(0x106 + y * 8, 0xFFFF)
        }

        val frame = bus.captureViFrameFromRegisters()

        assertEquals(2, frame?.width)
        assertEquals(2, frame?.height)
        assertEquals(0xFFFF0000.toInt(), frame?.argbPixels?.get(0))
        assertEquals(0xFF00FF00.toInt(), frame?.argbPixels?.get(1))
        assertEquals(0xFFFF0000.toInt(), frame?.argbPixels?.get(2))
    }

    @Test fun returnsNoAutomaticFrameWhenHorizontalWindowIsEmpty() {
        val bus = N64Bus()
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_H_START_OFFSET, (20 shl 16) or 20)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_X_SCALE_OFFSET, 0x400)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (0x20 shl 16) or 0x200)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0x400)
        assertEquals(null, bus.captureViFrameFromRegisters())
    }

    @Test fun returnsNoAutomaticFrameWhenVerticalWindowIsEmpty() {
        val bus = N64Bus()
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (100 shl 16) or 100)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0x400)
        assertEquals(null, bus.captureViFrameFromRegisters())
    }

    @Test fun returnsNoAutomaticFrameWhenVerticalScaleIsZero() {
        val bus = N64Bus()
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (0x20 shl 16) or 0x200)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0)
        assertEquals(null, bus.captureViFrameFromRegisters())
    }

    @Test fun returnsNoFrameWhenViWidthIsZero() {
        val bus = N64Bus()
        assertEquals(null, bus.captureViFrame(height = 240))
    }

    @Test fun writingViCurrentRegisterAcknowledgesCurrentLineState() {
        val bus = N64Bus()

        bus.write32(N64Bus.VI_CURRENT_REG, 1)

        assertEquals(0, bus.read32(N64Bus.VI_CURRENT_REG))
    }

    @Test fun viTimingRaisesMaskedMiInterruptAtConfiguredScanline() {
        val bus = N64Bus()
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 2)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 525)

        bus.advanceViInstructions(2L * N64Bus.VI_INSTRUCTIONS_PER_SCANLINE - 1L)
        assertEquals(0, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)
        assertEquals(1, bus.read32(N64Bus.VI_CURRENT_REG))

        bus.advanceViInstructions(1L)

        assertEquals(N64Bus.MI_INTR_VI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)
        assertEquals(true, bus.isMiInterruptPending())
    }

    @Test fun writingViCurrentAcknowledgesViInterruptWithoutResettingScanline() {
        val bus = N64Bus()
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 1)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 525)
        bus.advanceViInstructions(N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)

        assertEquals(N64Bus.MI_INTR_VI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)
        bus.write32(N64Bus.VI_CURRENT_REG, 0)

        assertEquals(1, bus.read32(N64Bus.VI_CURRENT_REG))
        assertEquals(0, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)
        assertEquals(false, bus.isMiInterruptPending())
    }

    @Test fun viAcknowledgeDoesNotClearUnrelatedSpInterruptSource() {
        val bus = N64Bus()
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.SP_STATUS_REG, N64Bus.SP_STATUS_CMD_SET_INTR)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 1)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 525)
        bus.advanceViInstructions(N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)

        assertEquals(N64Bus.MI_INTR_SP or N64Bus.MI_INTR_VI, bus.read32(N64Bus.MI_INTR_REG) and (N64Bus.MI_INTR_SP or N64Bus.MI_INTR_VI))
        bus.write32(N64Bus.VI_CURRENT_REG, 0)

        assertEquals(N64Bus.MI_INTR_SP, bus.read32(N64Bus.MI_INTR_REG) and (N64Bus.MI_INTR_SP or N64Bus.MI_INTR_VI))
    }

    @Test fun viInterruptCompareUsesMostRecentlyProgrammedViIntr() {
        val bus = N64Bus()
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 3)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 525)
        bus.advanceViInstructions(N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 2)
        bus.advanceViInstructions(N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)

        assertEquals(2, bus.read32(N64Bus.VI_CURRENT_REG))
        assertEquals(N64Bus.MI_INTR_VI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)
    }

    @Test fun viCurrentWrapUsesMostRecentlyProgrammedVSync() {
        val bus = N64Bus()
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 5)
        bus.advanceViInstructions(4L * N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)
        assertEquals(4, bus.read32(N64Bus.VI_CURRENT_REG))

        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 3)
        bus.advanceViInstructions(N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)

        assertEquals(2, bus.read32(N64Bus.VI_CURRENT_REG))
    }


    @Test fun viOriginWriteUsesOnlyTheDocumentedTwentyFourBitRdramAddress() {
        val bus = N64Bus()
        val viOrigin = N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET

        bus.write32(viOrigin, 0xAB000120.toInt())

        assertEquals(0x00000120, bus.read32(viOrigin))
    }

    @Test fun viStatusSerrateBitCanBeProgrammedForProgressiveAndInterlacedModes() {
        val bus = N64Bus()
        val viStatus = N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET
        val serrate = 1 shl 6

        bus.write32(viStatus, 0)
        assertEquals(0, bus.read32(viStatus) and serrate)

        bus.write32(viStatus, serrate)
        assertEquals(serrate, bus.read32(viStatus) and serrate)
    }


    @Test fun viTimingUsesOnlyTenBitValuesForSyncAndInterruptComparison() {
        val bus = N64Bus()
        bus.write32(N64Bus.MI_INTR_MASK_REG, N64Bus.MI_INTR_MASK_VI_SET)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_INTR_OFFSET, 0xABCD0002.toInt())
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_SYNC_OFFSET, 0xFFFF0005.toInt())

        bus.advanceViInstructions(2L * N64Bus.VI_INSTRUCTIONS_PER_SCANLINE)

        assertEquals(2, bus.read32(N64Bus.VI_CURRENT_REG))
        assertEquals(N64Bus.MI_INTR_VI, bus.read32(N64Bus.MI_INTR_REG) and N64Bus.MI_INTR_VI)
    }

}
