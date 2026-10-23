package com.jovemaprendz55byte.n64android

/**
 * Replacement coordinator for the old N64PifBootSequence.
 *
 * Uses N64BootImageLoader for the actual DMEM copy and byte-for-byte readback
 * verification before changing the CPU handoff state. PIF firmware execution
 * and CIC authentication are still outside this implementation.
 */
internal class N64PifBootCoordinator(
    private val memoryBus: N64MemoryBus,
    private val cpu: R4300Cpu
) {
    fun startCic6102(rom: ByteArray): N64PifBootSequence.BootHandoff =
        start(rom, N64PifBootSequence.CicFamily.CIC_6102)

    fun startCic6105(rom: ByteArray): N64PifBootSequence.BootHandoff =
        start(rom, N64PifBootSequence.CicFamily.CIC_6105)

    private fun start(
        rom: ByteArray,
        cic: N64PifBootSequence.CicFamily
    ): N64PifBootSequence.BootHandoff {
        memoryBus.resetPifRam()
        val image = N64BootImageLoader(memoryBus).load(rom)
        require(image.region != N64BootImageLoader.Region.UNKNOWN) {
            "Cannot select PAL/NTSC " + cic.name + " boot seed: unknown cartridge country code"
        }

        val pal = image.region == N64BootImageLoader.Region.PAL
        when (cic) {
            N64PifBootSequence.CicFamily.CIC_6102 ->
                cpu.resetForCic6102Ipl3Diagnostic(image.entryPoint, pal)
            N64PifBootSequence.CicFamily.CIC_6105 ->
                cpu.resetForCic6105Ipl3Diagnostic(image.entryPoint, pal)
        }

        return N64PifBootSequence.BootHandoff(
            cic = cic,
            entryPoint = image.entryPoint,
            ipl3BytesLoaded = image.payloadBytes,
            region = when (image.region) {
                N64BootImageLoader.Region.NTSC -> N64BootSequence.Region.NTSC
                N64BootImageLoader.Region.PAL -> N64BootSequence.Region.PAL
                N64BootImageLoader.Region.UNKNOWN -> N64BootSequence.Region.UNKNOWN
            },
            countryCode = image.countryCode
        )
    }
}
