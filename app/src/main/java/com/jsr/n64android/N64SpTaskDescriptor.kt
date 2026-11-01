package com.jsr.n64android

/**
 * Decoded 64-byte OSTask header stored in SP DMEM.
 *
 * The field order and byte offsets follow the Nintendo 64 OS OSTask ABI.
 * Values are retained as raw 32-bit words: pointers are physical N64
 * addresses and must be range-checked by the component that consumes them.
 *
 * This class only decodes the descriptor. It does not load microcode,
 * start the RSP, or claim that an OSTask is executable.
 */
data class N64SpTaskDescriptor(
    val type: Int,
    val flags: Int,
    val ucodeBoot: Int,
    val ucodeBootSize: Int,
    val ucode: Int,
    val ucodeSize: Int,
    val ucodeData: Int,
    val ucodeDataSize: Int,
    val dramStack: Int,
    val dramStackSize: Int,
    val outputBuffer: Int,
    val outputBufferSize: Int,
    val data: Int,
    val dataSize: Int,
    val yieldData: Int,
    val yieldDataSize: Int
) {
    companion object {
        const val DMEM_SIZE = 0x1000
        const val DEFAULT_DMEM_OFFSET = 0x0FC0
        const val BYTE_SIZE = 0x40
        const val WORD_COUNT = BYTE_SIZE / 4

        /**
         * Reads the ABI-defined 16-word OSTask structure from SP DMEM.
         * The descriptor must fit entirely inside the 4 KiB DMEM window.
         */
        fun readFrom(
            bus: N64Bus,
            dmemOffset: Int = DEFAULT_DMEM_OFFSET
        ): N64SpTaskDescriptor {
            require(dmemOffset >= 0 && dmemOffset <= DMEM_SIZE - BYTE_SIZE) {
                "OSTask descriptor must fit inside the 4 KiB SP DMEM."
            }
            require((dmemOffset and 3) == 0) {
                "OSTask descriptor must be 4-byte aligned."
            }
            val words = IntArray(WORD_COUNT) { index ->
                bus.read32(N64Bus.SP_DMEM_BASE + dmemOffset + index * 4)
            }
            return N64SpTaskDescriptor(
                type = words[0],
                flags = words[1],
                ucodeBoot = words[2],
                ucodeBootSize = words[3],
                ucode = words[4],
                ucodeSize = words[5],
                ucodeData = words[6],
                ucodeDataSize = words[7],
                dramStack = words[8],
                dramStackSize = words[9],
                outputBuffer = words[10],
                outputBufferSize = words[11],
                data = words[12],
                dataSize = words[13],
                yieldData = words[14],
                yieldDataSize = words[15]
            )
        }
    }
}
