package com.jsr.n64android

/**
 * Byte lengths of standard RDP commands, indexed by the six-bit command opcode.
 * Multiword commands are recognized here so the DPC reader can reject them safely
 * until their full payload decoders and execution paths are implemented.
 */
object N64RdpCommandLength {
    fun bytesForOpcode(opcode: Int): Int {
        require(opcode in 0..MAX_OPCODE) { "RDP opcode must be in 0..63." }
        return when (opcode) {
            0x08 -> 32
            0x09 -> 48
            0x0A -> 96
            0x0B -> 112
            0x0C -> 96
            0x0D -> 112
            0x0E -> 160
            0x0F -> 176
            0x24, 0x25 -> 16
            else -> COMMAND_BYTES
        }
    }

    const val COMMAND_BYTES = 8
    private const val MAX_OPCODE = 0x3F
}
