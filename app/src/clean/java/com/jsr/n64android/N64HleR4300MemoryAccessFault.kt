package com.jsr.n64android

/** Direction of the guest CPU memory operation that caused a bus/address fault. */
enum class N64HleR4300MemoryAccessKind {
    LOAD,
    STORE
}

/**
 * Preserves the original typed bus fault while identifying whether the
 * instruction was reading or writing. This is metadata for later AdEL/AdES/
 * DBE classification; it does not deliver a COP0 exception.
 */
class N64HleR4300MemoryAccessFault(
    val accessKind: N64HleR4300MemoryAccessKind,
    val memoryFault: N64HleGuestMemoryFault
) : IllegalArgumentException(
    "Guest ${accessKind.name.lowercase()} fault at 0x${(memoryFault.virtualAddress and 0xFFFF_FFFFL).toString(16)}",
    memoryFault
)
