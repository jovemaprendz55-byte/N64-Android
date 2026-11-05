package com.jsr.n64android

/** Typed guest-memory faults so the CPU can distinguish address errors from bus errors. */
sealed class N64HleGuestMemoryFault(
    message: String,
    val virtualAddress: Long
) : IllegalArgumentException(message)

/** AdEL/AdES candidate: the guest virtual address violates an access alignment. */
class N64HleGuestAddressAlignmentFault(
    address: Long,
    val requiredAlignment: Int
) : N64HleGuestMemoryFault(
    "Guest address 0x${(address and 0xFFFF_FFFFL).toString(16)} must be aligned to $requiredAlignment bytes",
    address
)

/** IBE/DBE candidate: address cannot be translated by the currently mapped HLE bus. */
class N64HleGuestAddressMappingFault(
    address: Long,
    detail: String
) : N64HleGuestMemoryFault(
    "Guest address 0x${(address and 0xFFFF_FFFFL).toString(16)} is not mapped: $detail",
    address
)
