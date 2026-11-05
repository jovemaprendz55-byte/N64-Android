package com.jsr.n64android

/**
 * Builds the first clean HLE handoff profile supported by verified project
 * evidence: CIC 6102/7101 only.
 *
 * This stages the 1 MiB game image at the cartridge header entry point and
 * supplies the documented cold-boot libultra parameters. It does not execute
 * IPL3/PIF, initialize RDRAM hardware registers, or guarantee game execution.
 * CIC 6105/7105 is deliberately rejected until its separate checksum path is
 * implemented and validated in this clean source set.
 */
object N64HleBootProfileFactory {
    private const val IPL3_CHECKSUM_OFFSET = 0x40
    private const val IPL3_CHECKSUM_END = 0x1000
    private const val CIC_6102_IPL3_SUM = 0xD057C85244L
    private const val GAME_IMAGE_ROM_OFFSET = 0x1000
    private const val GAME_IMAGE_SIZE = 0x100000
    private const val BOOT_FLAGS = 0x80000300L
    private val ENTRY_STACK = 0xFFFFFFFFA4001FF0UL.toLong()
    private const val INITIAL_STATUS = 0x34000000L
    private const val INITIAL_CONFIG = 0x0005E463L

    fun createCic6102Profile(cartridge: N64HleCartridgeImage): N64HleBootProfile {
        require(cartridge.sizeBytes >= GAME_IMAGE_ROM_OFFSET + GAME_IMAGE_SIZE) {
            "ROM is too short for the 1 MiB CIC 6102 game image."
        }
        require(detectCicSeed(cartridge) == 0x3F) {
            "Clean HLE profile currently supports only recognized CIC 6102/7101 images."
        }

        val entryPc = cartridge.entryPoint
        require((entryPc and 3L) == 0L) { "Cartridge entry point is not word-aligned." }
        val normalizedPc = entryPc and 0xFFFF_FFFFL
        require((normalizedPc ushr 29) == 4L) {
            "Cartridge entry point is not in KSEG0: 0x${normalizedPc.toString(16)}."
        }
        val physicalEntry = (normalizedPc and 0x1FFF_FFFFL).toInt()
        require(physicalEntry >= 0x320) {
            "Cartridge entry point overlaps the HLE low-memory boot parameters."
        }
        require(physicalEntry <= N64HleGuestAddressSpace.DEFAULT_RDRAM_SIZE - GAME_IMAGE_SIZE) {
            "Cartridge entry point leaves insufficient RDRAM for the 1 MiB game image."
        }

        val gameImage = cartridge.readBytes(GAME_IMAGE_ROM_OFFSET, GAME_IMAGE_SIZE)
        val computed = calculate6102Checksum(gameImage)
        require((computed.first.toLong() and 0xFFFF_FFFFL) == cartridge.crc1 &&
            (computed.second.toLong() and 0xFFFF_FFFFL) == cartridge.crc2) {
            "CIC 6102 game-image checksum mismatch: header=" +
                "0x${cartridge.crc1.toString(16)}:0x${cartridge.crc2.toString(16)}, " +
                "computed=0x${computed.first.toString(16)}:0x${computed.second.toString(16)}."
        }

        val tvType = detectTvType(cartridge.countryCode)
        val writes = listOf(
            N64HleBootProfile.MemoryWrite(BOOT_FLAGS, 4, tvType.toLong()),
            N64HleBootProfile.MemoryWrite(BOOT_FLAGS + 0x04, 4, 0L),
            N64HleBootProfile.MemoryWrite(BOOT_FLAGS + 0x08, 4, 0xB0000000L),
            N64HleBootProfile.MemoryWrite(BOOT_FLAGS + 0x0C, 4, 0L),
            N64HleBootProfile.MemoryWrite(BOOT_FLAGS + 0x10, 4, 0L),
            N64HleBootProfile.MemoryWrite(BOOT_FLAGS + 0x14, 4, 0L),
            N64HleBootProfile.MemoryWrite(BOOT_FLAGS + 0x18, 4, 8L * 1024L * 1024L)
        )
        val registers = mapOf(
            19 to 0L,                 // $s3: cartridge ROM type
            20 to tvType.toLong(),    // $s4: TV type
            21 to 0L,                 // $s5: cold reset
            22 to 0x3FL,              // $s6: CIC seed, not osCicId
            23 to 0L,                 // $s7: IPL2 version
            29 to ENTRY_STACK
        )

        return N64HleBootProfile(
            entryPc = entryPc,
            registerValues = registers,
            romCopies = listOf(
                N64HleBootProfile.RomCopy(GAME_IMAGE_ROM_OFFSET, entryPc, GAME_IMAGE_SIZE)
            ),
            memoryWrites = writes,
            evidence = "CIC 6102/7101 HLE post-IPL3 profile; IPL3 sum 0xD057C85244; " +
                "validated 1 MiB game-image CRC1/CRC2; boot parameters documented in " +
                "docs/CORRECAO_HLE_OSCICID_2026-11-02.md",
            cop0RegisterValues = mapOf(12 to INITIAL_STATUS, 16 to INITIAL_CONFIG),
            cop0Evidence = "Legacy HLE handoff constants and project boot tests: " +
                "N64HleBoot.INITIAL_STATUS=0x34000000 and INITIAL_CONFIG=0x0005E463"
        )
    }

    private fun detectCicSeed(cartridge: N64HleCartridgeImage): Int? {
        if (cartridge.sizeBytes < IPL3_CHECKSUM_END) return null
        var sum = 0L
        val bytes = cartridge.readBytes(IPL3_CHECKSUM_OFFSET, IPL3_CHECKSUM_END - IPL3_CHECKSUM_OFFSET)
        var offset = 0
        while (offset < bytes.size) {
            val word = ((bytes[offset].toLong() and 0xFFL) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
                (bytes[offset + 3].toLong() and 0xFFL)
            sum += word
            offset += 4
        }
        return if (sum == CIC_6102_IPL3_SUM) 0x3F else null
    }

    private fun detectTvType(region: Int): Int = when (region.toChar().uppercaseChar()) {
        'P', 'D', 'F', 'I', 'S', 'U', 'X', 'Y' -> 0
        'B' -> 2
        else -> 1
    }

    private fun calculate6102Checksum(image: ByteArray): kotlin.Pair<Int, Int> {
        require(image.size == GAME_IMAGE_SIZE)
        val mask = 0xFFFF_FFFFL
        val initial = (0x3FL * 0x5D588B65L + 1L) and mask
        var a3 = initial
        var t2 = initial
        var t3 = initial
        var s0 = initial
        var a2 = initial
        var t4 = initial
        var offset = 0
        while (offset < image.size) {
            val word = ((image[offset].toLong() and 0xFFL) shl 24) or
                ((image[offset + 1].toLong() and 0xFFL) shl 16) or
                ((image[offset + 2].toLong() and 0xFFL) shl 8) or
                (image[offset + 3].toLong() and 0xFFL)
            val sum = (a3 + word) and mask
            if (sum < a3) t2 = (t2 + 1L) and mask
            a3 = sum
            val rotate = Integer.rotateLeft(word.toInt(), (word and 0x1FL).toInt()).toLong() and mask
            t3 = (t3 xor word) and mask
            s0 = (s0 + rotate) and mask
            val mixed = if (a2 < word) (a3 xor word) else rotate
            a2 = (a2 xor mixed) and mask
            t4 = (t4 + (word xor s0)) and mask
            offset += 4
        }
        return kotlin.Pair(((a3 xor t2) xor t3).toInt(), ((s0 xor a2) xor t4).toInt())
    }
}
