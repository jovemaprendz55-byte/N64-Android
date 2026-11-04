package com.jsr.n64android

/**
 * Guest-memory representation of libultra OSThread and __OSThreadContext.
 *
 * The floating-point slots are deliberately kept as opaque 64-bit pairs.
 * Their mapping to the emulator's FR=0/FR=1 FPR representation is a separate
 * CPU-context task and must not be guessed here.
 */
data class N64HleGuestOsThreadContext(
    val gpr: LongArray = LongArray(32),
    val lo: Long = 0L,
    val hi: Long = 0L,
    val status: Int = 0,
    val pc: Int = 0,
    val cause: Int = 0,
    val badVaddr: Int = 0,
    val rcp: Int = 0,
    val fpcsr: Int = 0,
    val fpPairs: LongArray = LongArray(16)
) {
    init {
        require(gpr.size == 32) { "Guest GPR array must contain 32 entries." }
        require(fpPairs.size == 16) { "Guest FP pair array must contain 16 entries." }
    }
}

data class N64HleGuestOsThread(
    val next: Long = 0L,
    val priority: Int = 0,
    val queue: Long = 0L,
    val threadListNext: Long = 0L,
    val state: Int = N64HleGuestOsThreadLayout.STATE_STOPPED,
    val flags: Int = 0,
    val id: Int = 0,
    val fpUsed: Int = 0,
    val profile: Long = 0L,
    val context: N64HleGuestOsThreadContext = N64HleGuestOsThreadContext()
)

/**
 * Reads/writes one 32-bit libultra OSThread using the checked guest RDRAM bridge.
 * The caller owns thread-list policy and CPU context switching.
 */
class N64HleGuestOsThreadCodec(private val memory: N64HleGuestMemory) {
    fun read(address: Long): N64HleGuestOsThread {
        // Validate the complete structure before issuing any field reads.
        memory.translateRdramAddress(
            address,
            width = N64HleGuestOsThreadLayout.THREAD_SIZE,
            alignment = 4
        )
        val l = N64HleGuestOsThreadLayout
        val contextAddress = address + l.THREAD_CONTEXT
        val gpr = LongArray(32)
        GPR_OFFSETS.forEachIndexed { register, offset ->
            if (offset >= 0) gpr[register] = memory.read64(contextAddress + offset)
        }
        val fpPairs = LongArray(16) { index ->
            memory.read64(contextAddress + l.CONTEXT_FP0 + index * 8)
        }
        val context = N64HleGuestOsThreadContext(
            gpr = gpr,
            lo = memory.read64(contextAddress + l.CONTEXT_LO),
            hi = memory.read64(contextAddress + l.CONTEXT_HI),
            status = memory.read32(contextAddress + l.CONTEXT_SR),
            pc = memory.read32(contextAddress + l.CONTEXT_PC),
            cause = memory.read32(contextAddress + l.CONTEXT_CAUSE),
            badVaddr = memory.read32(contextAddress + l.CONTEXT_BAD_VADDR),
            rcp = memory.read32(contextAddress + l.CONTEXT_RCP),
            fpcsr = memory.read32(contextAddress + l.CONTEXT_FPCSR),
            fpPairs = fpPairs
        )
        return N64HleGuestOsThread(
            next = memory.read32(address + l.THREAD_NEXT).toUnsignedLong(),
            priority = memory.read32(address + l.THREAD_PRIORITY),
            queue = memory.read32(address + l.THREAD_QUEUE).toUnsignedLong(),
            threadListNext = memory.read32(address + l.THREAD_TL_NEXT).toUnsignedLong(),
            state = memory.read16(address + l.THREAD_STATE) and 0xFFFF,
            flags = memory.read16(address + l.THREAD_FLAGS) and 0xFFFF,
            id = memory.read32(address + l.THREAD_ID),
            fpUsed = memory.read32(address + l.THREAD_FP_USED),
            profile = memory.read32(address + l.THREAD_PROFILE).toUnsignedLong(),
            context = context
        )
    }

    fun write(address: Long, thread: N64HleGuestOsThread) {
        // Validate the complete destination before changing any guest bytes.
        memory.translateRdramAddress(
            address,
            width = N64HleGuestOsThreadLayout.THREAD_SIZE,
            alignment = 4
        )
        require(thread.state in VALID_STATES) { "Unknown libultra OSThread state: ${thread.state}" }
        require(thread.flags in 0..0xFFFF) { "OSThread flags must fit in u16." }
        val nextPointer = thread.next.toGuestPointer()
        val queuePointer = thread.queue.toGuestPointer()
        val threadListNextPointer = thread.threadListNext.toGuestPointer()
        val profilePointer = thread.profile.toGuestPointer()
        val l = N64HleGuestOsThreadLayout
        val contextAddress = address + l.THREAD_CONTEXT
        memory.write32(address + l.THREAD_NEXT, nextPointer)
        memory.write32(address + l.THREAD_PRIORITY, thread.priority)
        memory.write32(address + l.THREAD_QUEUE, queuePointer)
        memory.write32(address + l.THREAD_TL_NEXT, threadListNextPointer)
        memory.write16(address + l.THREAD_STATE, thread.state)
        memory.write16(address + l.THREAD_FLAGS, thread.flags)
        memory.write32(address + l.THREAD_ID, thread.id)
        memory.write32(address + l.THREAD_FP_USED, thread.fpUsed)
        memory.write32(address + l.THREAD_PROFILE, profilePointer)

        GPR_OFFSETS.forEachIndexed { register, offset ->
            if (offset >= 0) memory.write64(contextAddress + offset, thread.context.gpr[register])
        }
        memory.write64(contextAddress + l.CONTEXT_LO, thread.context.lo)
        memory.write64(contextAddress + l.CONTEXT_HI, thread.context.hi)
        memory.write32(contextAddress + l.CONTEXT_SR, thread.context.status)
        memory.write32(contextAddress + l.CONTEXT_PC, thread.context.pc)
        memory.write32(contextAddress + l.CONTEXT_CAUSE, thread.context.cause)
        memory.write32(contextAddress + l.CONTEXT_BAD_VADDR, thread.context.badVaddr)
        memory.write32(contextAddress + l.CONTEXT_RCP, thread.context.rcp)
        memory.write32(contextAddress + l.CONTEXT_FPCSR, thread.context.fpcsr)
        thread.context.fpPairs.forEachIndexed { index, pair ->
            memory.write64(contextAddress + l.CONTEXT_FP0 + index * 8, pair)
        }
    }

    private fun Int.toUnsignedLong(): Long = toUInt().toLong()

    private fun Long.toGuestPointer(): Int {
        val low = toInt()
        require(this == (low.toLong() and 0xFFFFFFFFL) || this == low.toLong()) {
            "Guest pointer must fit in 32 bits."
        }
        return low
    }

    companion object {
        private val VALID_STATES = setOf(
            N64HleGuestOsThreadLayout.STATE_STOPPED,
            N64HleGuestOsThreadLayout.STATE_RUNNABLE,
            N64HleGuestOsThreadLayout.STATE_RUNNING,
            N64HleGuestOsThreadLayout.STATE_WAITING
        )

        /** Guest ABI saves all GPRs except zero, k0 and k1. */
        private val GPR_OFFSETS = IntArray(32) { -1 }.apply {
            this[1] = 0x00  // at
            this[2] = 0x08  // v0
            this[3] = 0x10  // v1
            for (register in 4..7) this[register] = 0x18 + (register - 4) * 8
            for (register in 8..15) this[register] = 0x38 + (register - 8) * 8
            for (register in 16..23) this[register] = 0x78 + (register - 16) * 8
            this[24] = 0xB8 // t8
            this[25] = 0xC0 // t9
            this[28] = 0xC8 // gp
            this[29] = 0xD0 // sp
            this[30] = 0xD8 // s8
            this[31] = 0xE0 // ra
        }
    }
}

/** Big-endian 64-bit accessors kept here so the guest codec has one clear ABI path. */
private fun N64HleGuestMemory.read64(address: Long): Long {
    val high = read32(address).toLong()
    val low = read32(address + 4).toLong() and 0xFFFFFFFFL
    return (high shl 32) or low
}

private fun N64HleGuestMemory.write64(address: Long, value: Long) {
    write32(address, (value ushr 32).toInt())
    write32(address + 4, value.toInt())
}
