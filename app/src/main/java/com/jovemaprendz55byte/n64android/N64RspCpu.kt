package com.jovemaprendz55byte.n64android

/**
 * Minimal scalar RSP interpreter foundation.
 *
 * Implements a deliberately small MIPS-I integer subset for smoke tests.
 * Vector instructions, DMA scheduling, interrupts, and cycle timing are not
 * implemented. COP0 access is limited to the eight SP interface registers.
 * Branches use a one-instruction delay slot.
 */
internal class N64RspCpu(private val bus: N64MemoryBus) {
    private val gpr = IntArray(32)
    var pc: Int = 0
        private set
    private var pendingTarget: Int? = null

    fun register(index: Int): Int {
        require(index in 0..31)
        return if (index == 0) 0 else gpr[index]
    }

    fun setPc(address: Int) {
        require(address in 0 until N64SpMemory.MEMORY_SIZE && address % 4 == 0)
        pc = address
        pendingTarget = null
    }

    /**
     * Runs until the SP HALT flag is set again or the instruction budget is exhausted.
     * Returns the number of instructions executed. SP status command bit 0 must clear
     * HALT before this method starts executing.
     */
    fun run(maxInstructions: Int): Int {
        require(maxInstructions > 0) { "Instruction budget must be positive" }
        var executed = 0
        while (executed < maxInstructions && !bus.isSpHalted()) {
            step()
            executed++
        }
        return executed
    }

    /** Fetches and executes one instruction, then advances through delay-slot semantics. */
    fun step() {
        require(pc in 0 until N64SpMemory.MEMORY_SIZE && pc % 4 == 0) {
            "RSP PC is outside IMEM or unaligned: 0x${pc.toString(16)}"
        }
        val instruction = bus.readWord(N64SpMemory.IMEM_BASE + pc)
        val oldTarget = pendingTarget
        pendingTarget = null
        val sequentialPc = (pc + 4) and 0xFFC
        pc = sequentialPc
        execute(instruction, sequentialPc)
        if (oldTarget != null) pc = oldTarget and 0xFFC
        gpr[0] = 0
    }

    private fun execute(instruction: Int, pcPlus4: Int) {
        val opcode = instruction ushr 26
        val rs = (instruction ushr 21) and 31
        val rt = (instruction ushr 16) and 31
        val rd = (instruction ushr 11) and 31
        val shamt = (instruction ushr 6) and 31
        val immediate = instruction.toShort().toInt()
        val unsignedImmediate = instruction and 0xFFFF
        val a = register(rs)
        val b = register(rt)

        when (opcode) {
            0 -> when (instruction and 63) {
                0 -> writeRegister(rd, b shl shamt) // SLL / NOP
                2 -> writeRegister(rd, b ushr shamt) // SRL
                3 -> writeRegister(rd, b shr shamt) // SRA
                4 -> writeRegister(rd, b shl (a and 31)) // SLLV
                6 -> writeRegister(rd, b ushr (a and 31)) // SRLV
                7 -> writeRegister(rd, b shr (a and 31)) // SRAV
                8 -> pendingTarget = a // JR
                13 -> bus.markSpBroke() // BREAK
                33 -> writeRegister(rd, a + b) // ADDU
                35 -> writeRegister(rd, a - b) // SUBU
                36 -> writeRegister(rd, a and b) // AND
                37 -> writeRegister(rd, a or b) // OR
                38 -> writeRegister(rd, a xor b) // XOR
                39 -> writeRegister(rd, (a or b).inv()) // NOR
                42 -> writeRegister(rd, if (a < b) 1 else 0) // SLT
                43 -> writeRegister(rd, if (a.toUInt() < b.toUInt()) 1 else 0) // SLTU
                else -> unsupported(instruction)
            }
            16 -> when (rs) {
                0 -> writeRegister(rt, readSpCop0(rd)) // MFC0
                4 -> writeSpCop0(rd, b) // MTC0
                else -> unsupported(instruction)
            }
            1 -> when (rt) {
                0 -> if (a < 0) pendingTarget = pcPlus4 + (immediate shl 2) // BLTZ
                1 -> if (a >= 0) pendingTarget = pcPlus4 + (immediate shl 2) // BGEZ
                else -> unsupported(instruction)
            }
            2 -> pendingTarget = (pcPlus4 and 0xF0000000.toInt()) or
                ((instruction and 0x03FFFFFF) shl 2) // J
            3 -> {
                writeRegister(31, (pcPlus4 + 4) and 0xFFC)
                pendingTarget = (pcPlus4 and 0xF0000000.toInt()) or
                    ((instruction and 0x03FFFFFF) shl 2) // JAL
            }
            4 -> if (a == b) pendingTarget = pcPlus4 + (immediate shl 2) // BEQ
            5 -> if (a != b) pendingTarget = pcPlus4 + (immediate shl 2) // BNE
            6 -> if (a <= 0) pendingTarget = pcPlus4 + (immediate shl 2) // BLEZ
            7 -> if (a > 0) pendingTarget = pcPlus4 + (immediate shl 2) // BGTZ
            9 -> writeRegister(rt, a + immediate) // ADDIU
            12 -> writeRegister(rt, a and unsignedImmediate) // ANDI
            13 -> writeRegister(rt, a or unsignedImmediate) // ORI
            14 -> writeRegister(rt, a xor unsignedImmediate) // XORI
            15 -> writeRegister(rt, unsignedImmediate shl 16) // LUI
            32 -> writeRegister(rt, bus.readByte(dmemAddress(a + immediate)).toInt()) // LB
            33 -> writeRegister(rt, bus.readHalf(dmemAddress(a + immediate)).toInt()) // LH
            35 -> writeRegister(rt, bus.readWord(dmemAddress(a + immediate))) // LW
            36 -> writeRegister(rt, bus.readByte(dmemAddress(a + immediate)).toInt() and 0xFF) // LBU
            37 -> writeRegister(rt, bus.readHalf(dmemAddress(a + immediate)).toInt() and 0xFFFF) // LHU
            40 -> bus.writeByte(dmemAddress(a + immediate), b.toByte()) // SB
            41 -> bus.writeHalf(dmemAddress(a + immediate), b.toShort()) // SH
            43 -> bus.writeWord(dmemAddress(a + immediate), b) // SW
            else -> unsupported(instruction)
        }
    }

    private fun readSpCop0(index: Int): Int {
        require(index in 0..7) { "Unsupported RSP COP0 register: $index" }
        return bus.readWord(N64MemoryBus.SP_MEM_ADDR + index * 4L)
    }

    private fun writeSpCop0(index: Int, value: Int) {
        require(index in 0..7) { "Unsupported RSP COP0 register: $index" }
        bus.writeWord(N64MemoryBus.SP_MEM_ADDR + index * 4L, value)
    }

    private fun dmemAddress(address: Int): Long {
        require(address in 0 until N64SpMemory.MEMORY_SIZE) {
            "RSP data address is outside DMEM: 0x${address.toString(16)}"
        }
        return N64SpMemory.DMEM_BASE + address
    }

    private fun writeRegister(index: Int, value: Int) {
        if (index != 0) gpr[index] = value
    }

    private fun unsupported(instruction: Int): Nothing =
        throw IllegalArgumentException("Unsupported scalar RSP instruction 0x${instruction.toString(16)}")
}
