package com.jsr.n64android

/**
 * Decoded edge-coefficient prefix shared by the eight native RDP triangle commands.
 *
 * Coordinates use signed 12.2 fixed-point Y and signed 16.16 fixed-point X/slope.
 * This is command decoding only; it does not rasterize pixels.
 */
data class N64RdpTriangleSetup(
    val opcode: Int,
    val rightMajor: Boolean,
    val tile: Int,
    val yLow: Int,
    val yMiddle: Int,
    val yHigh: Int,
    val xLow: Int,
    val dxLowDy: Int,
    val xHigh: Int,
    val dxHighDy: Int,
    val xMiddle: Int,
    val dxMiddleDy: Int
) {
    companion object {
        const val FIRST_TRIANGLE_OPCODE = 0x08
        const val LAST_TRIANGLE_OPCODE = 0x0F
        const val EDGE_WORD_COUNT = 4
        const val EDGE_BYTES = EDGE_WORD_COUNT * N64RdpCommandLength.COMMAND_BYTES

        fun decode(packet: N64RdpCommandPacket): N64RdpTriangleSetup {
            require(packet.opcode in FIRST_TRIANGLE_OPCODE..LAST_TRIANGLE_OPCODE) {
                "RDP packet 0x${packet.opcode.toString(16)} is not a triangle command."
            }
            require(packet.byteLength == N64RdpCommandLength.bytesForOpcode(packet.opcode)) {
                "Triangle packet length does not match its opcode."
            }
            require(packet.words.size >= EDGE_WORD_COUNT) {
                "Triangle packet is missing edge-coefficient words."
            }

            val header = packet.words[0]
            val edgeLow = packet.words[1]
            val edgeHigh = packet.words[2]
            val edgeMiddle = packet.words[3]

            return N64RdpTriangleSetup(
                opcode = packet.opcode,
                rightMajor = ((header ushr 55) and 1L) != 0L,
                tile = ((header ushr 48) and 0x7L).toInt(),
                yLow = signExtend14(((header ushr 32) and 0x3FFFL).toInt()),
                yMiddle = signExtend14(((header ushr 16) and 0x3FFFL).toInt()),
                yHigh = signExtend14((header and 0x3FFFL).toInt()),
                xLow = (edgeLow ushr 32).toInt(),
                dxLowDy = edgeLow.toInt(),
                xHigh = (edgeHigh ushr 32).toInt(),
                dxHighDy = edgeHigh.toInt(),
                xMiddle = (edgeMiddle ushr 32).toInt(),
                dxMiddleDy = edgeMiddle.toInt()
            )
        }

        private fun signExtend14(value: Int): Int = (value shl 18) shr 18
    }
}
