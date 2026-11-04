package com.jsr.n64android

/**
 * 32-bit libultra OSThread / __OSThreadContext memory layout.
 *
 * Offsets follow the public libultra os_thread.h ABI. This object only defines
 * the guest-memory contract; it does not yet dispatch ROM calls or switch CPU
 * contexts. Keep pointer fields 32-bit and register/context fields at their
 * declared widths.
 *
 * Reference: https://harbourmasters.github.io/SpaghettiKart/os__thread_8h_source.html
 */
object N64HleGuestOsThreadLayout {
    const val POINTER_SIZE = 4
    const val THREAD_NEXT = 0x00
    const val THREAD_PRIORITY = 0x04
    const val THREAD_QUEUE = 0x08
    const val THREAD_TL_NEXT = 0x0C
    const val THREAD_STATE = 0x10
    const val THREAD_FLAGS = 0x12
    const val THREAD_ID = 0x14
    const val THREAD_FP_USED = 0x18
    const val THREAD_PROFILE = 0x1C
    const val THREAD_CONTEXT = 0x20

    const val CONTEXT_AT = 0x00
    const val CONTEXT_V0 = 0x08
    const val CONTEXT_V1 = 0x10
    const val CONTEXT_A0 = 0x18
    const val CONTEXT_A1 = 0x20
    const val CONTEXT_A2 = 0x28
    const val CONTEXT_A3 = 0x30
    const val CONTEXT_T0 = 0x38
    const val CONTEXT_T7 = 0x70
    const val CONTEXT_S0 = 0x78
    const val CONTEXT_S7 = 0xB0
    const val CONTEXT_T8 = 0xB8
    const val CONTEXT_T9 = 0xC0
    const val CONTEXT_GP = 0xC8
    const val CONTEXT_SP = 0xD0
    const val CONTEXT_S8 = 0xD8
    const val CONTEXT_RA = 0xE0
    const val CONTEXT_LO = 0xE8
    const val CONTEXT_HI = 0xF0
    const val CONTEXT_SR = 0xF8
    const val CONTEXT_PC = 0xFC
    const val CONTEXT_CAUSE = 0x100
    const val CONTEXT_BAD_VADDR = 0x104
    const val CONTEXT_RCP = 0x108
    const val CONTEXT_FPCSR = 0x10C
    const val CONTEXT_FP0 = 0x110
    const val CONTEXT_FP30 = 0x188
    const val CONTEXT_SIZE = 0x190
    const val THREAD_SIZE = THREAD_CONTEXT + CONTEXT_SIZE

    const val STATE_STOPPED = 1
    const val STATE_RUNNABLE = 2
    const val STATE_RUNNING = 4
    const val STATE_WAITING = 8

    const val PRIORITY_IDLE = 0
    const val PRIORITY_APP_MAX = 127
    const val PRIORITY_MAX = 255
}
