package com.jsr.n64android

data class N64Rom(val bytes: ByteArray, val byteSize: Int, val format: RomFormat)

enum class RomFormat { Z64_BIG_ENDIAN, V64_HALFWORD_SWAPPED, N64_WORD_SWAPPED }

sealed interface RomLoadResult {
    data class Success(val rom: N64Rom) : RomLoadResult
    data class Failure(val reason: String) : RomLoadResult
}

object N64RomLoader {
    private const val MIN_HEADER_SIZE = 0x40

    fun load(source: ByteArray): RomLoadResult {
        if (source.size < MIN_HEADER_SIZE) {
            return RomLoadResult.Failure("Arquivo pequeno demais para uma ROM N64 válida.")
        }

        val format = when {
            source[0] == 0x80.toByte() && source[1] == 0x37.toByte() &&
                source[2] == 0x12.toByte() && source[3] == 0x40.toByte() ->
                RomFormat.Z64_BIG_ENDIAN
            source[0] == 0x37.toByte() && source[1] == 0x80.toByte() &&
                source[2] == 0x40.toByte() && source[3] == 0x12.toByte() ->
                RomFormat.V64_HALFWORD_SWAPPED
            source[0] == 0x40.toByte() && source[1] == 0x12.toByte() &&
                source[2] == 0x37.toByte() && source[3] == 0x80.toByte() ->
                RomFormat.N64_WORD_SWAPPED
            else -> return RomLoadResult.Failure("Cabeçalho desconhecido: o arquivo não parece ser uma ROM N64 válida.")
        }

        val normalized = source.copyOf()
        when (format) {
            RomFormat.Z64_BIG_ENDIAN -> Unit
            RomFormat.V64_HALFWORD_SWAPPED -> {
                var i = 0
                while (i + 1 < normalized.size) {
                    val tmp = normalized[i]
                    normalized[i] = normalized[i + 1]
                    normalized[i + 1] = tmp
                    i += 2
                }
            }
            RomFormat.N64_WORD_SWAPPED -> {
                var i = 0
                while (i + 3 < normalized.size) {
                    val a = normalized[i]
                    val b = normalized[i + 1]
                    normalized[i] = normalized[i + 3]
                    normalized[i + 1] = normalized[i + 2]
                    normalized[i + 2] = b
                    normalized[i + 3] = a
                    i += 4
                }
            }
        }
        return RomLoadResult.Success(N64Rom(normalized, normalized.size, format))
    }
}
