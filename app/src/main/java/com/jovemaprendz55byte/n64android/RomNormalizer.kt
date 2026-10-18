package com.jovemaprendz55byte.n64android

/**
 * Converts supported N64 ROM byte orders to canonical big-endian (.z64) order.
 *
 * Returns null for an unrecognized header or a ROM whose length is not a
 * multiple of four bytes. The caller owns the returned byte array.
 */
object RomNormalizer {
    fun normalize(bytes: ByteArray): ByteArray? {
        val header = RomHeader.parse(bytes) ?: return null
        if (bytes.size % 4 != 0) return null

        return when (header.format) {
            RomHeader.Format.Z64_BIG_ENDIAN -> bytes.copyOf()
            RomHeader.Format.V64_BYTE_SWAPPED -> ByteArray(bytes.size).also { output ->
                var i = 0
                while (i < bytes.size) {
                    output[i] = bytes[i + 1]
                    output[i + 1] = bytes[i]
                    i += 2
                }
            }
            RomHeader.Format.N64_LITTLE_ENDIAN -> ByteArray(bytes.size).also { output ->
                var i = 0
                while (i < bytes.size) {
                    output[i] = bytes[i + 3]
                    output[i + 1] = bytes[i + 2]
                    output[i + 2] = bytes[i + 1]
                    output[i + 3] = bytes[i]
                    i += 4
                }
            }
        }
    }
}
