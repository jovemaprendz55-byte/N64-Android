package com.jsr.n64android

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Bounded document-stream reader for the Android Storage Access Framework.
 * The caller owns and closes the supplied stream.
 *
 * Reading and cartridge inspection are deliberately separate from boot or execution.
 */
object N64RomDocumentReader {
    const val MAX_ROM_SIZE_BYTES = 64 * 1024 * 1024
    private const val BUFFER_SIZE = 8192

    sealed interface Result {
        data class Success(
            val cartridge: N64HleCartridgeImage,
            val sourceSizeBytes: Int
        ) : Result

        data class Failure(val reason: String) : Result
    }

    fun read(
        input: InputStream,
        maxBytes: Int = MAX_ROM_SIZE_BYTES
    ): Result {
        if (maxBytes < 0) return Result.Failure("Limite de leitura inválido.")
        if (maxBytes < 0x40) return Result.Failure("Limite menor que o cabeçalho mínimo de uma ROM N64.")

        val output = ByteArrayOutputStream(minOf(maxBytes, 1024 * 1024))
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0

        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val single = input.read()
                    if (single < 0) break
                    if (total >= maxBytes) {
                        return Result.Failure("Arquivo excede o limite de 64 MiB.")
                    }
                    output.write(single)
                    total++
                    continue
                }
                if (count > maxBytes - total) {
                    return Result.Failure("Arquivo excede o limite de 64 MiB.")
                }
                output.write(buffer, 0, count)
                total += count
            }
        } catch (error: IOException) {
            return Result.Failure("Não foi possível ler o arquivo: ${error.message ?: "erro de entrada/saída"}.")
        }

        val bytes = output.toByteArray()
        return try {
            Result.Success(N64HleCartridgeImage.parse(bytes), bytes.size)
        } catch (error: IllegalArgumentException) {
            Result.Failure(error.message ?: "Arquivo não reconhecido como ROM N64 válida.")
        }
    }
}
