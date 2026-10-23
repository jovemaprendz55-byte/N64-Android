package com.jovemaprendz55byte.n64android

/**
 * Clean-room IPL3/PIF boot API retained only so callers can compile while the
 * boot pipeline is rebuilt from a blank implementation.
 *
 * No ROM copy, CIC seed, PIF firmware, or CPU handoff is performed here.
 */
internal class N64PifBootSequence {
    data class BootHandoff(
        val cic: CicFamily,
        val entryPoint: Long,
        val ipl3BytesLoaded: Int,
        val region: Region,
        val countryCode: Int
    )

    enum class CicFamily { CIC_6102, CIC_6105 }
    enum class Region { NTSC, PAL, UNKNOWN }

    fun startCic6102(rom: ByteArray): BootHandoff = notImplemented()
    fun startCic6105(rom: ByteArray): BootHandoff = notImplemented()

    private fun notImplemented(): Nothing =
        throw UnsupportedOperationException("PIF/ IPL3 boot pipeline is intentionally empty pending clean rebuild.")
}
