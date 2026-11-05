package com.jsr.n64android

/**
 * Resolves the general R4300 exception vector for exceptions that use the
 * general exception entry point (including AdEL, AdES and DBE).
 *
 * This resolver deliberately does not mutate CPU state. EXL controls whether
 * EPC/BD are overwritten by exception entry; it does not select the BEV
 * address. TLB refill and vectored interrupt entries require separate rules.
 */
class N64HleR4300ExceptionVectorResolver {
    fun resolveGeneralVector(status: Long): Long {
        val bevEnabled = (status.toInt() and STATUS_BEV) != 0
        return if (bevEnabled) BOOT_EXCEPTION_VECTOR else GENERAL_EXCEPTION_VECTOR
    }

    companion object {
        const val STATUS_BEV: Int = 1 shl 22
        val GENERAL_EXCEPTION_VECTOR: Long = 0xFFFFFFFF80000180uL.toLong()
        val BOOT_EXCEPTION_VECTOR: Long = 0xFFFFFFFFBFC00380uL.toLong()
    }
}
