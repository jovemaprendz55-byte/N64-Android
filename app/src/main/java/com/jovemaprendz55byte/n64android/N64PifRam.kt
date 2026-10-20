package com.jovemaprendz55byte.n64android

/**
 * Minimal 64-byte PIF RAM and Joybus controller-command handler.
 *
 * Supports controller 0 status (0x00) and button/stick read (0x01). Other
 * channels and accessory commands are reported as unavailable; this is not a
 * complete PIF ROM or authentication implementation.
 */
internal class N64PifRam(
    private val controllerPort: N64ControllerPort = N64ControllerPort()
) {
    private val ram = ByteArray(SIZE)

    fun readByte(physicalAddress: Long): Byte = ram[index(physicalAddress)]

    fun writeByte(physicalAddress: Long, value: Byte) {
        ram[index(physicalAddress)] = value
        if (index(physicalAddress) == CONTROL_INDEX && value.toInt() and 0x01 != 0) {
            executeJoybusCommands()
        }
    }

    fun read64(): ByteArray = ram.copyOf()

    fun write64(bytes: ByteArray) {
        require(bytes.size == SIZE) { "PIF DMA must transfer exactly 64 bytes" }
        bytes.copyInto(ram)
        // Bit 0 of the final control byte requests Joybus command execution.
        if (ram[CONTROL_INDEX].toInt() and 0x01 != 0) executeJoybusCommands()
    }

    private fun executeJoybusCommands() {
        var cursor = 0
        var commandCount = 0
        while (cursor < CONTROL_INDEX && commandCount++ < MAX_COMMANDS) {
            val txLengthByte = ram[cursor].toInt() and 0xFF
            if (txLengthByte == 0xFE) { // channel reset marker
                cursor++
                continue
            }
            if (txLengthByte == 0xFF) break // end of command list
            if (txLengthByte == 0x00) { // unused channel
                cursor++
                continue
            }

            val rxLengthByte = ram.getOrNull(cursor + 1)?.toInt()?.and(0xFF) ?: break
            val txLength = txLengthByte and 0x3F
            val rxLength = rxLengthByte and 0x3F
            if (txLength == 0 || cursor + 2 + txLength > CONTROL_INDEX) break

            val commandOffset = cursor + 2
            val responseOffset = commandOffset + txLength
            if (responseOffset + rxLength > CONTROL_INDEX) break
            val channel = commandCount - 1

            // This implementation has one physical controller on channel 0.
            if (channel == 0 && txLength >= 1) {
                when (ram[commandOffset].toInt() and 0xFF) {
                    CMD_STATUS -> {
                        if (rxLength >= 3) {
                            ram[responseOffset] = 0x05
                            ram[responseOffset + 1] = 0x00
                            ram[responseOffset + 2] = 0x02
                        } else markError(cursor)
                    }
                    CMD_READ_CONTROLLER -> {
                        if (rxLength >= 4) {
                            val payload = controllerPort.snapshot().toPakResponse()
                            payload.copyInto(ram, responseOffset)
                        } else markError(cursor)
                    }
                    else -> markError(cursor)
                }
            } else {
                markError(cursor)
            }
            cursor = responseOffset + rxLength
        }
        // Clear the execute bit after processing; preserve other control bits.
        ram[CONTROL_INDEX] = (ram[CONTROL_INDEX].toInt() and 0x01.inv()).toByte()
    }

    private fun markError(commandStart: Int) {
        ram[commandStart + 1] = ((ram[commandStart + 1].toInt() and 0xFF) or 0x80).toByte()
    }

    private fun index(address: Long): Int {
        require(address in PIF_RAM_BASE until PIF_RAM_END) {
            "Address 0x${address.toString(16)} is outside PIF RAM"
        }
        return (address - PIF_RAM_BASE).toInt()
    }

    companion object {
        const val PIF_RAM_BASE = 0x1FC0_07C0L
        const val PIF_RAM_END = 0x1FC0_0800L
        const val SIZE = 64
        private const val CONTROL_INDEX = 63
        private const val MAX_COMMANDS = 6
        private const val CMD_STATUS = 0x00
        private const val CMD_READ_CONTROLLER = 0x01
    }
}
