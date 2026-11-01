package com.jsr.n64android

import java.util.Collections

/**
 * A complete command packet read from an end-exclusive RDRAM display-list range.
 *
 * [words] contains every 64-bit word in big-endian stream order, including
 * payload words. The list is detached from memory and cannot be used to mutate
 * the source address space.
 */
class N64RdpCommandPacket(
    val opcode: Int,
    val byteLength: Int,
    words: List<Long>
) {
    /** A defensive, unmodifiable copy of all words in this packet. */
    val words: List<Long> = Collections.unmodifiableList(ArrayList(words))
    init {
        require(opcode in 0..0x3F) { "RDP opcode must be in 0..63." }
        require(byteLength > 0 && byteLength % N64RdpCommandLength.COMMAND_BYTES == 0) {
            "RDP packet length must be a positive multiple of 8 bytes."
        }
        require(words.size == byteLength / N64RdpCommandLength.COMMAND_BYTES) {
            "RDP packet word count does not match its byte length."
        }
    }

    val isSingleWord: Boolean
        get() = byteLength == N64RdpCommandLength.COMMAND_BYTES

    val firstWord: Long
        get() = words.first()
}

/**
 * Parses a complete DPC display-list range before any command is dispatched.
 * This separates stream framing from the currently limited RDP execution core.
 */
object N64RdpCommandStreamParser {
    fun parse(
        memory: N64AddressSpace,
        startAddress: Int,
        endAddressExclusive: Int,
        rdramSize: Int,
        maxCommandBytes: Int = DEFAULT_MAX_COMMAND_BYTES
    ): List<N64RdpCommandPacket> {
        require(rdramSize > 0) { "RDRAM size must be positive." }
        require(maxCommandBytes > 0 && maxCommandBytes % WORD_BYTES == 0) {
            "Maximum command bytes must be a positive multiple of $WORD_BYTES."
        }
        require(startAddress >= 0) { "DPC start address cannot be negative." }
        require(endAddressExclusive >= startAddress) {
            "DPC end address must not precede start address."
        }
        require((startAddress and (WORD_BYTES - 1)) == 0) {
            "DPC start address must be $WORD_BYTES-byte aligned."
        }
        require((endAddressExclusive and (WORD_BYTES - 1)) == 0) {
            "DPC end address must be $WORD_BYTES-byte aligned."
        }
        require(endAddressExclusive <= rdramSize) {
            "DPC command range exceeds RDRAM: end=0x${endAddressExclusive.toString(16)}, RDRAM=$rdramSize."
        }

        val byteCount = endAddressExclusive - startAddress
        require(byteCount <= maxCommandBytes) {
            "DPC command range exceeds processing limit: bytes=$byteCount, limit=$maxCommandBytes."
        }

        val packets = ArrayList<N64RdpCommandPacket>()
        var address = startAddress
        while (address < endAddressExclusive) {
            val high = memory.read32(address).toLong() and 0xFFFFFFFFL
            val low = memory.read32(address + 4).toLong() and 0xFFFFFFFFL
            val firstWord = (high shl 32) or low
            val opcode = ((firstWord ushr 58) and 0x3FL).toInt()
            val packetBytes = N64RdpCommandLength.bytesForOpcode(opcode)
            val remainingBytes = endAddressExclusive - address
            require(packetBytes <= remainingBytes) {
                "Truncated RDP command 0x${opcode.toString(16)} at 0x${address.toString(16)}: " +
                    "requires $packetBytes bytes, only $remainingBytes remain."
            }

            val words = ArrayList<Long>(packetBytes / WORD_BYTES)
            words.add(firstWord)
            var wordAddress = address + WORD_BYTES
            while (wordAddress < address + packetBytes) {
                val wordHigh = memory.read32(wordAddress).toLong() and 0xFFFFFFFFL
                val wordLow = memory.read32(wordAddress + 4).toLong() and 0xFFFFFFFFL
                words.add((wordHigh shl 32) or wordLow)
                wordAddress += WORD_BYTES
            }
            packets.add(
                N64RdpCommandPacket(
                    opcode = opcode,
                    byteLength = packetBytes,
                    words = words.toList()
                )
            )
            address += packetBytes
        }
        return Collections.unmodifiableList(ArrayList(packets))
    }

    const val WORD_BYTES = 8
    const val DEFAULT_MAX_COMMAND_BYTES = 1 shl 20
}
