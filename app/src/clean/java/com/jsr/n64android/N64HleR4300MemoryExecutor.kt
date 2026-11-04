package com.jsr.n64android

/**
 * Executes aligned RDRAM integer loads/stores for the initial R4300 subset.
 *
 * This is not a complete bus: only the injected KSEG0/KSEG1 RDRAM address
 * space is accessible. MMIO, TLB translation, doubleword merge operations,
 * cache operations and architectural address exceptions are not implemented.
 */
class N64HleR4300MemoryExecutor(
    private val memory: N64HleGuestAddressSpace
) {
    fun supports(opcode: Int): Boolean = opcode in LOAD_OPCODES || opcode in STORE_OPCODES

    fun execute(instruction: N64HleR4300DecodedInstruction, state: N64HleR4300CpuState) {
        require(!instruction.isSpecial) { "Instruction is SPECIAL, not memory" }
        val opcode = instruction.opcode
        require(supports(opcode)) { "Memory opcode 0x${opcode.toString(16)} is not implemented" }

        val base = state.readGpr(instruction.rs)
        val address = base + instruction.immediateSigned.toLong()

        when (opcode) {
            OP_LB -> state.writeGpr(instruction.rt, memory.read8(address).toByte().toLong())
            OP_LBU -> state.writeGpr(instruction.rt, memory.read8(address).toLong())
            OP_LH -> state.writeGpr(instruction.rt, memory.read16(address).toShort().toLong())
            OP_LHU -> state.writeGpr(instruction.rt, memory.read16(address).toLong())
            OP_LW -> state.writeGpr(instruction.rt, memory.read32(address).toInt().toLong())
            OP_LWU -> state.writeGpr(instruction.rt, memory.read32(address))
            OP_LD -> state.writeGpr(instruction.rt, memory.read64(address))
            OP_LWL -> loadWordLeft(address, instruction.rt, state)
            OP_LWR -> loadWordRight(address, instruction.rt, state)
            OP_SB -> memory.write8(address, state.readGpr(instruction.rt).toInt() and 0xFF)
            OP_SH -> memory.write16(address, state.readGpr(instruction.rt).toInt() and 0xFFFF)
            OP_SW -> memory.write32(address, state.readGpr(instruction.rt) and 0xFFFF_FFFFL)
            OP_SWL -> storeWordLeft(address, instruction.rt, state)
            OP_SWR -> storeWordRight(address, instruction.rt, state)
            OP_SD -> memory.write64(address, state.readGpr(instruction.rt))
        }
    }

    private fun loadWordLeft(address: Long, target: Int, state: N64HleR4300CpuState) {
        val offset = (address and 3L).toInt()
        val aligned = address and -4L
        val shift = offset * 8
        val preserveMask = ((1L shl shift) - 1L).toInt()
        val old = if (preserveMask == 0) 0 else state.readGpr(target).toInt() and preserveMask
        val merged = old or (memory.read32(aligned).toInt() shl shift)
        state.writeGpr(target, merged.toLong())
    }

    private fun loadWordRight(address: Long, target: Int, state: N64HleR4300CpuState) {
        val offset = (address and 3L).toInt()
        val aligned = address and -4L
        val shift = (3 - offset) * 8
        val preserveMask = if (offset == 3) 0 else (-1 shl ((offset + 1) * 8))
        val old = if (preserveMask == 0) 0 else state.readGpr(target).toInt() and preserveMask
        val merged = old or (memory.read32(aligned).toInt() ushr shift)
        state.writeGpr(target, merged.toLong())
    }

    private fun storeWordLeft(address: Long, source: Int, state: N64HleR4300CpuState) {
        val offset = (address and 3L).toInt()
        val aligned = address and -4L
        val shift = offset * 8
        val writeMask = -1 ushr shift
        val old = memory.read32(aligned).toInt()
        val value = state.readGpr(source).toInt() ushr shift
        val merged = (old and writeMask.inv()) or (value and writeMask)
        memory.write32(aligned, merged.toLong() and 0xFFFF_FFFFL)
    }

    private fun storeWordRight(address: Long, source: Int, state: N64HleR4300CpuState) {
        val offset = (address and 3L).toInt()
        val aligned = address and -4L
        val shift = (3 - offset) * 8
        val writeMask = if (offset == 3) -1 else (-1 shl ((3 - offset) * 8))
        val old = memory.read32(aligned).toInt()
        val value = state.readGpr(source).toInt() shl shift
        val merged = (old and writeMask.inv()) or (value and writeMask)
        memory.write32(aligned, merged.toLong() and 0xFFFF_FFFFL)
    }

    private companion object {
        const val OP_LB = 0x20
        const val OP_LH = 0x21
        const val OP_LW = 0x23
        const val OP_LWL = 0x22
        const val OP_LBU = 0x24
        const val OP_LHU = 0x25
        const val OP_LWU = 0x27
        const val OP_LWR = 0x26
        const val OP_SB = 0x28
        const val OP_SH = 0x29
        const val OP_SW = 0x2B
        const val OP_SWL = 0x2A
        const val OP_SWR = 0x2E
        const val OP_LD = 0x37
        const val OP_SD = 0x3F

        val LOAD_OPCODES = setOf(OP_LB, OP_LH, OP_LW, OP_LWL, OP_LWR, OP_LBU, OP_LHU, OP_LWU, OP_LD)
        val STORE_OPCODES = setOf(OP_SB, OP_SH, OP_SW, OP_SWL, OP_SWR, OP_SD)
    }
}
