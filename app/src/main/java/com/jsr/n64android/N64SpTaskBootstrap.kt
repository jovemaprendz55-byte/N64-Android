package com.jsr.n64android

/**
 * CPU-side bootstrap sequence for an OSTask.
 *
 * Mirrors the documented start sequence: halt SP, DMA the 64-byte OSTask
 * into DMEM at 0xFC0, DMA boot microcode into IMEM, set SP PC=0, then
 * release HALT. The boot microcode itself is responsible for loading the
 * task microcode/data and entering the task.
 *
 * SP DMA in N64Bus is currently synchronous and simplified; this helper
 * therefore does not model DMA latency, queueing, or real interrupt timing.
 */
class N64SpTaskBootstrap(
    private val bus: N64Bus,
    private val rdramSize: Int
) {
    data class Result(
        val taskAddress: Int,
        val descriptor: N64SpTaskDescriptor,
        val bootMicrocodeBytes: Int,
        val initialProgramCounter: Int
    )

    init {
        require(rdramSize > 0)
    }

    fun startFromRdram(taskAddress: Int): Result {
        requireRange(taskAddress, N64SpTaskDescriptor.BYTE_SIZE, "OSTask")
        bus.write32(N64Bus.SP_STATUS_REG, N64Bus.SP_STATUS_CMD_SET_HALT)

        // Copy OSTask from RDRAM into the ABI-defined top-of-DMEM location.
        bus.write32(N64Bus.SP_MEM_ADDR_REG, N64SpTaskDescriptor.DEFAULT_DMEM_OFFSET)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, taskAddress)
        bus.write32(N64Bus.SP_RD_LEN_REG, N64SpTaskDescriptor.BYTE_SIZE - 1)

        val descriptor = N64SpTaskDescriptor.readFrom(bus)
        val bootSize = descriptor.ucodeBootSize
        require(bootSize in 1..N64Bus.SP_MEM_SIZE) {
            "Unsupported RSP boot microcode size: $bootSize."
        }
        requireRange(descriptor.ucodeBoot, bootSize, "RSP boot microcode")

        // Select IMEM with bit 12 and transfer the boot program to offset zero.
        bus.write32(N64Bus.SP_MEM_ADDR_REG, 0x1000)
        bus.write32(N64Bus.SP_DRAM_ADDR_REG, descriptor.ucodeBoot)
        bus.write32(N64Bus.SP_RD_LEN_REG, bootSize - 1)

        bus.write32(N64Bus.SP_PC_REG, 0)
        bus.write32(
            N64Bus.SP_STATUS_REG,
            N64Bus.SP_STATUS_CMD_CLR_BROKE or N64Bus.SP_STATUS_CMD_CLR_HALT
        )

        return Result(
            taskAddress = taskAddress,
            descriptor = descriptor,
            bootMicrocodeBytes = bootSize,
            initialProgramCounter = 0
        )
    }

    private fun requireRange(address: Int, length: Int, label: String) {
        require(address >= 0 && length > 0 && address <= rdramSize - length) {
            "$label range is outside RDRAM: address=0x${address.toString(16)}, length=$length."
        }
    }
}
