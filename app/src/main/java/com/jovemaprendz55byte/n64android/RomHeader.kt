package com.jovemaprendz55byte.n64android

/** Basic Nintendo 64 ROM header detection; this is not an emulator core. */
data class RomHeader(
    val format: Format,
    val title: String,
    val countryCode: Char?
) {
    enum class Format { Z64_BIG_ENDIAN, V64_BYTE_SWAPPED, N64_LITTLE_ENDIAN }

    companion object {
        private const val MIN_HEADER_SIZE = 0x40

        fun parse(bytes: ByteArray): RomHeader? {
            if (bytes.size < MIN_HEADER_SIZE) return null
            val format = when {
                matches(bytes, 0x80, 0x37, 0x12, 0x40) -> Format.Z64_BIG_ENDIAN
                matches(bytes, 0x37, 0x80, 0x40, 0x12) -> Format.V64_BYTE_SWAPPED
                matches(bytes, 0x40, 0x12, 0x37, 0x80) -> Format.N64_LITTLE_ENDIAN
                else -> return null
            }
            val title = bytes.copyOfRange(0x20, 0x34)
                .map { (it.toInt() and 0xFF).toChar() }
                .joinToString("")
                .trim { it <= ' ' || it == '\u0000' }
                .ifBlank { "Sem título no cabeçalho" }
            val rawCountry = bytes[0x3E].toInt() and 0xFF
            val country = rawCountry.takeIf { it in 0x20..0x7E }?.toChar()
            return RomHeader(format, title, country)
        }

        private fun matches(bytes: ByteArray, vararg expected: Int): Boolean =
            expected.indices.all { (bytes[it].toInt() and 0xFF) == expected[it] }
    }
}
