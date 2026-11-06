package com.jsr.n64android

/**
 * First scalar RSP interpreter slice, implemented independently for the clean HLE runtime.
 * Instructions are fetched from SP IMEM and data accesses use SP DMEM.
 * This is not yet the vector unit or the RSP microcode execution engine.
 */
class N64HleRspInstructionStepper(
    private val memory: N64HleGuestAddressSpace
) {
    fun step(state: N64HleRspCpuState): Int {
        check(!state.halted) { "RSP is halted" }
        val pc = state.programCounter
        val instruction = memory.read32(IMEM_BASE + pc).toInt()
        val opcode = instruction ushr 26
        val rs = (instruction ushr 21) and 31
        val rt = (instruction ushr 16) and 31
        val rd = (instruction ushr 11) and 31
        val shift = (instruction ushr 6) and 31
        val function = instruction and 63
        val immediate = instruction.toShort().toInt()
        val unsignedImmediate = instruction and 0xFFFF
        val branchTarget = (pc + 4 + (immediate shl 2)) and N64HleRspCpuState.PC_MASK
        val pendingTarget = state.pendingBranchTarget()

        when (opcode) {
            0x00 -> executeSpecial(state, instruction, rs, rt, rd, shift, function, pendingTarget)
            0x02, 0x03 -> {
                check(pendingTarget == null) { "RSP control transfer in a delay slot is unsupported" }
                if (opcode == 0x03) state.writeGpr(31, (pc + 8) and N64HleRspCpuState.PC_MASK)
                state.scheduleBranch(((pc + 4) and 0xF000) or ((instruction and 0x03FF_FFFF) shl 2))
            }
            0x04 -> if (state.readGpr(rs) == state.readGpr(rt)) scheduleBranch(state, pendingTarget, branchTarget)
            0x05 -> if (state.readGpr(rs) != state.readGpr(rt)) scheduleBranch(state, pendingTarget, branchTarget)
            0x06 -> if (state.readGpr(rs) <= 0) scheduleBranch(state, pendingTarget, branchTarget)
            0x07 -> if (state.readGpr(rs) > 0) scheduleBranch(state, pendingTarget, branchTarget)
            0x08, 0x09 -> state.writeGpr(rt, state.readGpr(rs) + immediate)
            0x0A -> state.writeGpr(rt, if (state.readGpr(rs) < immediate) 1 else 0)
            0x0B -> state.writeGpr(rt, if (state.readGpr(rs).toUInt() < immediate.toUInt()) 1 else 0)
            0x0C -> state.writeGpr(rt, state.readGpr(rs) and unsignedImmediate)
            0x0D -> state.writeGpr(rt, state.readGpr(rs) or unsignedImmediate)
            0x0E -> state.writeGpr(rt, state.readGpr(rs) xor unsignedImmediate)
            0x0F -> state.writeGpr(rt, unsignedImmediate shl 16)
            0x20 -> state.writeGpr(rt, memory.read8(DMEM_BASE + ((state.readGpr(rs) + immediate) and 0xFFF)).let { if (it and 0x80 != 0) it or -256 else it })
            0x21 -> state.writeGpr(rt, memory.read16(DMEM_BASE + ((state.readGpr(rs) + immediate) and 0xFFE)).let { if (it and 0x8000 != 0) it or -65536 else it })
            0x23 -> state.writeGpr(rt, memory.read32(DMEM_BASE + ((state.readGpr(rs) + immediate) and 0xFFC)).toInt())
            0x24 -> state.writeGpr(rt, memory.read8(DMEM_BASE + ((state.readGpr(rs) + immediate) and 0xFFF)))
            0x25 -> state.writeGpr(rt, memory.read16(DMEM_BASE + ((state.readGpr(rs) + immediate) and 0xFFE)))
            0x28 -> memory.write8(DMEM_BASE + ((state.readGpr(rs) + immediate) and 0xFFF), state.readGpr(rt) and 0xFF)
            0x29 -> memory.write16(DMEM_BASE + ((state.readGpr(rs) + immediate) and 0xFFE), state.readGpr(rt) and 0xFFFF)
            0x2B -> memory.write32(DMEM_BASE + ((state.readGpr(rs) + immediate) and 0xFFC), state.readGpr(rt).toLong() and 0xFFFF_FFFFL)
            else -> throw N64HleRspUnsupportedInstructionException(pc, instruction)
        }

        if (pendingTarget != null) state.clearPendingBranchTarget()
        state.programCounter = pendingTarget ?: ((pc + 4) and N64HleRspCpuState.PC_MASK)
        return instruction
    }

    private fun executeSpecial(
        state: N64HleRspCpuState,
        instruction: Int,
        rs: Int,
        rt: Int,
        rd: Int,
        shift: Int,
        function: Int,
        pendingTarget: Int?
    ) {
        val left = state.readGpr(rs)
        val right = state.readGpr(rt)
        when (function) {
            0x00 -> state.writeGpr(rd, right shl shift)
            0x02 -> state.writeGpr(rd, right ushr shift)
            0x03 -> state.writeGpr(rd, right shr shift)
            0x04 -> state.writeGpr(rd, right shl (left and 31))
            0x06 -> state.writeGpr(rd, right ushr (left and 31))
            0x07 -> state.writeGpr(rd, right shr (left and 31))
            0x10 -> state.writeGpr(rd, state.hi)
            0x11 -> state.writeHi(left)
            0x12 -> state.writeGpr(rd, state.lo)
            0x13 -> state.writeLo(left)
            0x18 -> {
                val product = left.toLong() * right.toLong()
                state.writeLo(product.toInt())
                state.writeHi((product shr 32).toInt())
            }
            0x19 -> {
                val product = left.toUInt().toLong() * right.toUInt().toLong()
                state.writeLo(product.toInt())
                state.writeHi((product ushr 32).toInt())
            }
            0x1A -> {
                if (right == 0) {
                    state.writeLo(if (left < 0) 1 else -1)
                    state.writeHi(left)
                } else {
                    state.writeLo(left / right)
                    state.writeHi(left % right)
                }
            }
            0x1B -> {
                if (right == 0) {
                    state.writeLo(-1)
                    state.writeHi(left)
                } else {
                    state.writeLo((left.toUInt() / right.toUInt()).toInt())
                    state.writeHi((left.toUInt() % right.toUInt()).toInt())
                }
            }
            0x08 -> {
                check(pendingTarget == null) { "RSP control transfer in a delay slot is unsupported" }
                state.scheduleBranch(left)
            }
            0x0D -> {
                state.halted = true
                memory.signalRspBreak()
            }
            0x20, 0x21 -> state.writeGpr(rd, left + right)
            0x22, 0x23 -> state.writeGpr(rd, left - right)
            0x24 -> state.writeGpr(rd, left and right)
            0x25 -> state.writeGpr(rd, left or right)
            0x26 -> state.writeGpr(rd, left xor right)
            0x27 -> state.writeGpr(rd, left.inv() and right.inv())
            0x2A -> state.writeGpr(rd, if (left < right) 1 else 0)
            0x2B -> state.writeGpr(rd, if (left.toUInt() < right.toUInt()) 1 else 0)
            else -> throw N64HleRspUnsupportedInstructionException(state.programCounter, instruction)
        }
    }

    private fun scheduleBranch(state: N64HleRspCpuState, pendingTarget: Int?, target: Int) {
        check(pendingTarget == null) { "RSP control transfer in a delay slot is unsupported" }
        state.scheduleBranch(target)
    }

    companion object {
        private const val IMEM_BASE = 0xA4001000L
        private const val DMEM_BASE = 0xA4000000L
    }
}

class N64HleRspUnsupportedInstructionException(
    val programCounter: Int,
    val instructionWord: Int
) : IllegalArgumentException(
    "Unsupported RSP instruction 0x${instructionWord.toUInt().toString(16)} at PC 0x${programCounter.toString(16)}"
)
