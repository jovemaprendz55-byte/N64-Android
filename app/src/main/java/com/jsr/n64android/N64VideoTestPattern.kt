package com.jsr.n64android

/**
 * Generates a deterministic color-bar frame in synthetic RDRAM and reads it
 * back through the VI registers and the same framebuffer reader used by ROM diagnostics.
 *
 * This validates memory layout, VI register configuration, pixel conversion and
 * frame extraction. It does not emulate RSP/RDP or render a game.
 */
object N64VideoTestPattern {
    const val WIDTH = 320
    const val HEIGHT = 240
    const val ORIGIN = 0x1000

    private val colorBars5551 = intArrayOf(
        0xF801, // red
        0xFFC1, // yellow
        0x07C1, // green
        0x07FF, // cyan
        0x003F, // blue
        0xF83F, // magenta
        0xFFFF, // white
        0x0001  // black
    )

    fun createFrame(): N64VideoFrame {
        val memory = N64Memory()
        val bus = N64Bus(ram = memory)

        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val bar = x / (WIDTH / colorBars5551.size)
                val address = ORIGIN + (y * WIDTH + x) * 2
                memory.write16(address, colorBars5551[bar])
            }
        }

        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_STATUS_OFFSET,
            N64ViFramebufferReader.VI_STATUS_PIXEL_SIZE_16)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_ORIGIN_OFFSET, ORIGIN)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_WIDTH_OFFSET, WIDTH)
        // 480 half-lines at a 0.5 scale produce a 240-line output frame.
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_V_START_OFFSET, (0x20 shl 16) or 0x200)
        bus.write32(N64Bus.VI_REG_BASE + N64Bus.VI_Y_SCALE_OFFSET, 0x400)

        return checkNotNull(bus.captureViFrameFromRegisters()) {
            "VI did not produce a valid synthetic framebuffer."
        }
    }
}
