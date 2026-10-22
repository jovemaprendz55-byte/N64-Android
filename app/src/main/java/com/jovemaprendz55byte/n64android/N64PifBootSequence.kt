package com.jovemaprendz55byte.n64android

/**
 * Clean-room coordinator for the documented CIC-6105 PIF-to-IPL3 handoff.
 *
 * This is deliberately not a replacement PIF boot-ROM firmware image. It
 * models the hardware-visible handoff that this emulator currently supports:
 * copy the cartridge IPL3 into SP DMEM, seed the CPU/CP0 state for CIC-6105,
 * install the documented IPL2 IMEM stub, then enter IPL3 at 0xA4000040.
 *
 * CIC identification/authentication and other CIC families are not implemented.
 */
internal class N64PifBootSequence(
    private val memoryBus: N64MemoryBus,
    private val cpu: R4300Cpu
) {
    data class BootHandoff(
        val cic: CicFamily,
        val entryPoint: Long,
        val ipl3BytesLoaded: Int,
        val region: N64BootSequence.Region,
        val countryCode: Int
    )

    enum class CicFamily { CIC_6105 }

    fun startCic6105(rom: ByteArray): BootHandoff {
        val image = N64BootSequence(memoryBus).loadIpl3(rom)
        require(image.region != N64BootSequence.Region.UNKNOWN) {
            "Cannot select PAL/NTSC CIC-6105 boot seed: unknown cartridge country code"
        }

        cpu.resetForCic6105Ipl3Diagnostic(
            entryPoint = image.entryPoint,
            pal = image.region == N64BootSequence.Region.PAL
        )

        return BootHandoff(
            cic = CicFamily.CIC_6105,
            entryPoint = image.entryPoint,
            ipl3BytesLoaded = image.byteCount,
            region = image.region,
            countryCode = image.countryCode
        )
    }
}
