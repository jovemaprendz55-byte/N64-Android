package com.jsr.n64android

/**
 * Reads standard fields from a normalized, big-endian N64 cartridge image.
 * This is diagnostic metadata only; it does not perform the PIF/CIC boot process.
 */
data class N64RomInfo(
    val title: String,
    val gameCode: String,
    val crc1: String,
    val crc2: String,
    val bootCodePreview: List<String>
) {
    fun toDisplayText(): String = buildString {
        appendLine("Título: ${title.ifBlank { "(sem título no cabeçalho)" }}")
        appendLine("Código do jogo: ${gameCode.ifBlank { "----" }}")
        appendLine("CRC1: $crc1")
        appendLine("CRC2: $crc2")
        appendLine("IPL3 (primeiras 8 palavras):")
        append(bootCodePreview.joinToString(" "))
    }
}

object N64RomInspector {
    private const val HEADER_MIN_SIZE = 0x60
    private const val TITLE_START = 0x20
    private const val TITLE_LENGTH = 20
    private const val GAME_CODE_START = 0x3B
    private const val CRC1_START = 0x10
    private const val CRC2_START = 0x14
    private const val IPL3_START = 0x40
    private const val IPL3_PREVIEW_WORDS = 8

    fun inspect(normalizedRom: ByteArray): N64RomInfo {
        require(normalizedRom.size >= HEADER_MIN_SIZE) {
            "ROM pequena demais para inspecionar cabeçalho e início do IPL3."
        }
        val title = normalizedRom.copyOfRange(TITLE_START, TITLE_START + TITLE_LENGTH)
            .toString(Charsets.US_ASCII)
            .map { if (it.code in 0x20..0x7E) it else ' ' }
            .joinToString("")
            .trim()
        val gameCode = normalizedRom.copyOfRange(GAME_CODE_START, GAME_CODE_START + 4)
            .toString(Charsets.US_ASCII)
            .map { if (it.code in 0x20..0x7E) it else '?' }
            .joinToString("")
        return N64RomInfo(
            title = title,
            gameCode = gameCode,
            crc1 = readHex32(normalizedRom, CRC1_START),
            crc2 = readHex32(normalizedRom, CRC2_START),
            bootCodePreview = (0 until IPL3_PREVIEW_WORDS).map { index ->
                readHex32(normalizedRom, IPL3_START + index * 4)
            }
        )
    }

    private fun readHex32(bytes: ByteArray, offset: Int): String {
        val value = ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
        return "0x" + value.toString(16).uppercase().padStart(8, '0')
    }
}
