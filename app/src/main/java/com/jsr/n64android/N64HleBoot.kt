package com.jsr.n64android

/**
 * Minimal cartridge boot handoff that replaces IPL3 execution.
 *
 * This prepares the first game bootcode block at RDRAM 0x400 and starts
 * the R4300 at KSEG0 0x80000400. It does not emulate the complete PIF/CIC
 * protocol or claim that the game is fully bootable.
 */
object N64HleBoot {
    const val BOOTCODE_ROM_OFFSET = 0x1000
    const val BOOTCODE_RAM_ADDRESS = 0x400
    const val BOOTCODE_SIZE = 0xFC0
    val ENTRY_PC: Long = 0xFFFFFFFF80000400UL.toLong()
    val INITIAL_STACK: Long = 0xFFFFFFFFA07FFFE0UL.toLong()

    data class Result(
        val cicSeed: Int,
        val copiedBytes: Int,
        val romOffset: Int,
        val ramAddress: Int,
        val entryPc: Long,
        val pifRomUsed: Boolean
    )

    /**
     * Stages the cartridge's game bootcode and initializes a conservative
     * post-IPL3 handoff state. The original IPL3 at ROM 0x40 is never copied
     * to SP DMEM and is never executed by this routine.
     */
    fun prepare(bus: N64Bus, cpu: R4300Cpu): Result {
        val seed = requireNotNull(bus.detectCicSeed()) {
            "CIC/seed do IPL3 não reconhecido; HLE atual aceita CIC 6102/7101 e 6105/7105."
        }

        require(BOOTCODE_RAM_ADDRESS + BOOTCODE_SIZE <= N64Memory.DEFAULT_RAM_SIZE) {
            "RDRAM insuficiente para receber o bootcode do jogo."
        }

        // Validate/read the complete source before mutating CPU state.
        val payload = ByteArray(BOOTCODE_SIZE) { index ->
            bus.read8(N64Bus.CARTRIDGE_ROM_BASE + BOOTCODE_ROM_OFFSET + index).toByte()
        }

        for (index in payload.indices) {
            bus.write8(BOOTCODE_RAM_ADDRESS + index, payload[index].toInt() and 0xFF)
        }

        cpu.reset()
        // KUSEG/KSEG2/KSEG3 accesses must use the architectural TLB.
        // The bootcode entry at 0x80000400 remains direct-mapped through KSEG0.
        cpu.tlbEnabled = true
        cpu.gpr[22] = seed.toLong() // CIC seed expected in $s6 by supported IPL3 families.
        cpu.gpr[29] = INITIAL_STACK
        cpu.setProgramCounter(ENTRY_PC)

        return Result(
            cicSeed = seed,
            copiedBytes = payload.size,
            romOffset = BOOTCODE_ROM_OFFSET,
            ramAddress = BOOTCODE_RAM_ADDRESS,
            entryPc = ENTRY_PC,
            pifRomUsed = false
        )
    }
}
