package com.jsr.n64android

/**
 * HLE replacement for the cartridge IPL3 loading handoff.
 *
 * Nintendo IPL3 initializes RDRAM and copies the first 1 MiB of the game
 * image (ROM offset 0x1000) to RDRAM 0x400 before entering the header's
 * entry point. This HLE path reproduces that visible memory handoff without
 * copying IPL3 into SP DMEM or executing the original IPL3/PIF ROM.
 *
 * RDRAM calibration, cache initialization, full CIC/PIF behavior, and
 * hardware timing are not modeled here.
 */
object N64HleBoot {
    const val BOOT_IMAGE_ROM_OFFSET = 0x1000
    const val BOOT_IMAGE_RAM_ADDRESS = 0x400
    const val BOOT_IMAGE_SIZE = 0x100000
    const val BOOTCODE_ROM_OFFSET = BOOT_IMAGE_ROM_OFFSET
    const val BOOTCODE_RAM_ADDRESS = BOOT_IMAGE_RAM_ADDRESS
    const val BOOTCODE_SIZE = BOOT_IMAGE_SIZE

    const val ROM_HEADER_ENTRY_OFFSET = 0x08
    const val ROM_HEADER_REGION_OFFSET = 0x3E
    const val BOOT_FLAGS_ADDRESS = 0x300
    const val BOOT_FLAG_TV_TYPE = BOOT_FLAGS_ADDRESS
    const val BOOT_FLAG_ROM_TYPE = BOOT_FLAGS_ADDRESS + 0x04
    const val BOOT_FLAG_ROM_BASE = BOOT_FLAGS_ADDRESS + 0x08
    const val BOOT_FLAG_RESET_TYPE = BOOT_FLAGS_ADDRESS + 0x0C
    const val BOOT_FLAG_IPL2_VERSION = BOOT_FLAGS_ADDRESS + 0x14
    const val BOOT_FLAG_RDRAM_SIZE = BOOT_FLAGS_ADDRESS + 0x18

    val ENTRY_PC: Long = 0xFFFFFFFF80000400UL.toLong()
    val INITIAL_STACK: Long = 0xFFFFFFFFA07FFFE0UL.toLong()

    data class Result(
        val cicSeed: Int,
        val copiedBytes: Int,
        val romOffset: Int,
        val ramAddress: Int,
        val entryPc: Long,
        val pifRomUsed: Boolean,
        val tvType: Int
    )

    /**
     * Stages the standard first-megabyte game image and IPL3 handoff fields.
     * IPL3 at ROM 0x40 is only inspected by detectCicSeed(); it is never
     * copied to SP DMEM and is never executed.
     */
    fun prepare(bus: N64Bus, cpu: R4300Cpu): Result {
        val seed = requireNotNull(bus.detectCicSeed()) {
            "CIC/seed do IPL3 não reconhecido; HLE atual aceita CIC 6102/7101 e 6105/7105."
        }

        val entryPc = readRomWord(bus, ROM_HEADER_ENTRY_OFFSET).toLong()
        require((entryPc.toInt() and 0xE0000000.toInt()) == 0x80000000.toInt()) {
            "Entry point da ROM não está no segmento KSEG0: 0x${entryPc.toULong().toString(16)}."
        }
        val entryPhysical = entryPc.toInt() and 0x1FFFFFFF
        require(entryPhysical < N64Memory.DEFAULT_RAM_SIZE) {
            "Entry point fora da RDRAM instalada: 0x${entryPc.toULong().toString(16)}."
        }
        require(BOOT_IMAGE_RAM_ADDRESS + BOOT_IMAGE_SIZE <= N64Memory.DEFAULT_RAM_SIZE) {
            "RDRAM insuficiente para receber a imagem inicial de 1 MiB."
        }

        // Read and validate the whole image before mutating RDRAM or CPU state.
        val payload = ByteArray(BOOT_IMAGE_SIZE) { index ->
            bus.read8(N64Bus.CARTRIDGE_ROM_BASE + BOOT_IMAGE_ROM_OFFSET + index).toByte()
        }
        val tvType = detectTvType(bus.read8(N64Bus.CARTRIDGE_ROM_BASE + ROM_HEADER_REGION_OFFSET))

        for (index in payload.indices) {
            bus.write8(BOOT_IMAGE_RAM_ADDRESS + index, payload[index].toInt() and 0xFF)
        }

        // IPL3's low-memory parameters consumed by libultra/game startup code.
        bus.write32(BOOT_FLAG_TV_TYPE, tvType)
        bus.write32(BOOT_FLAG_ROM_TYPE, 0) // Cartridge, not 64DD.
        bus.write32(BOOT_FLAG_ROM_BASE, 0xB0000000.toInt()) // KSEG1 cartridge window.
        bus.write32(BOOT_FLAG_RESET_TYPE, 0) // Cold boot.
        bus.write32(BOOT_FLAG_IPL2_VERSION, 0)
        bus.write32(BOOT_FLAG_RDRAM_SIZE, N64Memory.DEFAULT_RAM_SIZE)

        cpu.reset()
        // KUSEG/KSEG2/KSEG3 accesses must use the architectural TLB.
        // The game entry remains a direct-mapped KSEG0 address.
        cpu.tlbEnabled = true
        cpu.gpr[19] = 0L // $s3: cartridge ROM type.
        cpu.gpr[20] = tvType.toLong() // $s4: TV type.
        cpu.gpr[21] = 0L // $s5: cold reset.
        cpu.gpr[22] = seed.toLong() // $s6: CIC seed.
        cpu.gpr[23] = 0L // $s7: IPL2 version.
        cpu.gpr[29] = INITIAL_STACK
        cpu.setProgramCounter(entryPc)

        return Result(
            cicSeed = seed,
            copiedBytes = payload.size,
            romOffset = BOOT_IMAGE_ROM_OFFSET,
            ramAddress = BOOT_IMAGE_RAM_ADDRESS,
            entryPc = entryPc,
            pifRomUsed = false,
            tvType = tvType
        )
    }

    private fun readRomWord(bus: N64Bus, offset: Int): Int {
        val address = N64Bus.CARTRIDGE_ROM_BASE + offset
        return (bus.read8(address) shl 24) or
            (bus.read8(address + 1) shl 16) or
            (bus.read8(address + 2) shl 8) or
            bus.read8(address + 3)
    }

    private fun detectTvType(regionByte: Int): Int {
        return when (regionByte.toChar().uppercaseChar()) {
            'P', 'D', 'F', 'I', 'S', 'U', 'X', 'Y' -> 0 // PAL.
            'B' -> 2 // MPAL.
            else -> 1 // NTSC; includes GoldenEye's 'E' region.
        }
    }
}
