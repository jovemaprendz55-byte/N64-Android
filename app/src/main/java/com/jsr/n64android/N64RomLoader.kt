package com.jsr.n64android

data class N64Rom(val bytes: ByteArray, val byteSize: Int, val format: RomFormat)

enum class RomFormat { Z64_BIG_ENDIAN, V64_HALFWORD_SWAPPED, N64_WORD_SWAPPED }

sealed interface RomLoadResult {
    data class Success(val rom: N64Rom) : RomLoadResult
    data class Failure(val reason: String) : RomLoadResult
}

object N64RomLoader {
    private const val HEADER_SIZE = 0x40
    const val MAX_ROM_SIZE_BYTES = 64 * 1024 * 1024

    fun load(source: ByteArray): RomLoadResult {
        if (source.size < HEADER_SIZE) {
            return RomLoadResult.Failure("Arquivo pequeno demais para conter um cabeçalho N64 completo.")
        }
        if (source.size > MAX_ROM_SIZE_BYTES) {
            return RomLoadResult.Failure("ROM maior que o limite suportado de 64 MiB.")
        }
        if (source.size % 4 != 0) {
            return RomLoadResult.Failure("Tamanho inválido: a ROM deve conter um número inteiro de palavras de 32 bits.")
        }

        val format = when {
            matches(source, 0x80, 0x37, 0x12, 0x40) -> RomFormat.Z64_BIG_ENDIAN
            matches(source, 0x37, 0x80, 0x40, 0x12) -> RomFormat.V64_HALFWORD_SWAPPED
            matches(source, 0x40, 0x12, 0x37, 0x80) -> RomFormat.N64_WORD_SWAPPED
            else -> return RomLoadResult.Failure("Cabeçalho desconhecido: o arquivo não parece ser uma ROM N64.")
        }
        val normalized = source.copyOf()
        when (format) {
            RomFormat.Z64_BIG_ENDIAN -> Unit
            RomFormat.V64_HALFWORD_SWAPPED -> {
                for (i in normalized.indices step 2) {
                    val first = normalized[i]
                    normalized[i] = normalized[i + 1]
                    normalized[i + 1] = first
                }
            }
            RomFormat.N64_WORD_SWAPPED -> {
                for (i in normalized.indices step 4) {
                    val first = normalized[i]
                    val second = normalized[i + 1]
                    normalized[i] = normalized[i + 3]
                    normalized[i + 1] = normalized[i + 2]
                    normalized[i + 2] = second
                    normalized[i + 3] = first
                }
            }
        }
        return RomLoadResult.Success(N64Rom(normalized, normalized.size, format))
    }

    private fun matches(bytes: ByteArray, a: Int, b: Int, c: Int, d: Int): Boolean =
        (bytes[0].toInt() and 0xFF) == a &&
            (bytes[1].toInt() and 0xFF) == b &&
            (bytes[2].toInt() and 0xFF) == c &&
            (bytes[3].toInt() and 0xFF) == d
}
