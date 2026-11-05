package com.jsr.n64android

/**
 * In-memory, validated cartridge held by the clean application session.
 *
 * This is deliberately not an execution session: it does not create a bus,
 * prepare HLE boot state, start the CPU, or schedule RSP/RDP work.
 * The cartridge image owns normalized bytes and exposes only defensive copies.
 */
class N64HleCartridgeSession private constructor(
    val cartridge: N64HleCartridgeImage
) {
    val romSizeBytes: Int
        get() = cartridge.sizeBytes

    fun readRomBytes(offset: Int, length: Int): ByteArray =
        cartridge.readBytes(offset, length)

    companion object {
        fun fromValidatedImage(cartridge: N64HleCartridgeImage): N64HleCartridgeSession =
            N64HleCartridgeSession(cartridge)
    }
}
