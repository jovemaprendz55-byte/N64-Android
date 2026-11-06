package com.jsr.n64android

/**
 * Read-only ROM diagnostics. This class never prepares a boot handoff, starts
 * the CPU, or mutates the normalized cartridge image.
 */
object N64HleRomDiagnostics {
    enum class Status {
        VALID,
        DIVERGENT,
        UNSUPPORTED,
        INSUFFICIENT_DATA,
        READ_ERROR
    }

    enum class CicProfile {
        CIC_6102_7101,
        UNKNOWN,
        INSUFFICIENT_DATA
    }

    data class ChecksumReport(
        val status: Status,
        val headerCrc1: Long,
        val headerCrc2: Long,
        val calculatedCrc1: Long? = null,
        val calculatedCrc2: Long? = null
    )

    data class Report(
        val status: Status,
        val romSizeBytes: Int?,
        val byteOrder: N64HleCartridgeImage.ByteOrder?,
        val title: String?,
        val cartridgeId: String?,
        val countryCode: Int?,
        val entryPoint: Long?,
        val headerCrc1: Long?,
        val headerCrc2: Long?,
        val ipl3WordSum: Long?,
        val cicProfile: CicProfile,
        val cicSeed: Int?,
        val checksum: ChecksumReport?,
        val message: String? = null
    )

    fun diagnose(cartridge: N64HleCartridgeImage): Report {
        return try {
            val ipl3End = N64HleBootProfileFactory.IPL3_CHECKSUM_END
            if (cartridge.sizeBytes < ipl3End) {
                return baseReport(
                    cartridge = cartridge,
                    status = Status.INSUFFICIENT_DATA,
                    sum = null,
                    cicProfile = CicProfile.INSUFFICIENT_DATA,
                    cicSeed = null,
                    checksum = ChecksumReport(
                        status = Status.INSUFFICIENT_DATA,
                        headerCrc1 = cartridge.crc1,
                        headerCrc2 = cartridge.crc2
                    ),
                    message = "ROM não contém o bloco IPL3 completo (0x40..0xFFF)."
                )
            }

            val sum = N64HleBootProfileFactory.calculateIpl3WordSum(cartridge)
            val seed = N64HleBootProfileFactory.detectCicSeed(cartridge)
            val cic = if (seed == 0x3F) CicProfile.CIC_6102_7101 else CicProfile.UNKNOWN

            if (seed == null) {
                return baseReport(
                    cartridge, Status.UNSUPPORTED, sum, cic, null,
                    ChecksumReport(Status.UNSUPPORTED, cartridge.crc1, cartridge.crc2),
                    "Assinatura IPL3 não reconhecida pelo conjunto de perfis implementado."
                )
            }

            val gameOffset = N64HleBootProfileFactory.GAME_IMAGE_ROM_OFFSET
            val gameSize = N64HleBootProfileFactory.GAME_IMAGE_SIZE
            if (cartridge.sizeBytes < gameOffset + gameSize) {
                return baseReport(
                    cartridge, Status.INSUFFICIENT_DATA, sum, cic, seed,
                    ChecksumReport(Status.INSUFFICIENT_DATA, cartridge.crc1, cartridge.crc2),
                    "Dados insuficientes para calcular CRC1/CRC2 do bloco de jogo de 1 MiB."
                )
            }

            val calculated = N64HleBootProfileFactory.calculate6102Checksum(
                cartridge.readBytes(gameOffset, gameSize)
            )
            val calculatedCrc1 = calculated.first.toLong() and 0xFFFF_FFFFL
            val calculatedCrc2 = calculated.second.toLong() and 0xFFFF_FFFFL
            val checksumStatus = if (
                calculatedCrc1 == cartridge.crc1 && calculatedCrc2 == cartridge.crc2
            ) Status.VALID else Status.DIVERGENT
            baseReport(
                cartridge,
                checksumStatus,
                sum,
                cic,
                seed,
                ChecksumReport(
                    checksumStatus,
                    cartridge.crc1,
                    cartridge.crc2,
                    calculatedCrc1,
                    calculatedCrc2
                ),
                if (checksumStatus == Status.VALID) {
                    "Assinatura CIC reconhecida e CRC1/CRC2 correspondem ao cabeçalho."
                } else {
                    "CRC1/CRC2 calculados divergem dos valores gravados no cabeçalho."
                }
            )
        } catch (error: Exception) {
            Report(
                status = Status.READ_ERROR,
                romSizeBytes = cartridge.sizeBytes,
                byteOrder = cartridge.byteOrder,
                title = cartridge.title,
                cartridgeId = cartridge.cartridgeId,
                countryCode = cartridge.countryCode,
                entryPoint = cartridge.entryPoint,
                headerCrc1 = cartridge.crc1,
                headerCrc2 = cartridge.crc2,
                ipl3WordSum = null,
                cicProfile = CicProfile.UNKNOWN,
                cicSeed = null,
                checksum = null,
                message = "Falha ao ler os dados necessários para o diagnóstico: ${error.message ?: "erro desconhecido"}."
            )
        }
    }

    private fun baseReport(
        cartridge: N64HleCartridgeImage,
        status: Status,
        sum: Long?,
        cicProfile: CicProfile,
        cicSeed: Int?,
        checksum: ChecksumReport,
        message: String
    ) = Report(
        status = status,
        romSizeBytes = cartridge.sizeBytes,
        byteOrder = cartridge.byteOrder,
        title = cartridge.title,
        cartridgeId = cartridge.cartridgeId,
        countryCode = cartridge.countryCode,
        entryPoint = cartridge.entryPoint,
        headerCrc1 = cartridge.crc1,
        headerCrc2 = cartridge.crc2,
        ipl3WordSum = sum,
        cicProfile = cicProfile,
        cicSeed = cicSeed,
        checksum = checksum,
        message = message
    )
}
