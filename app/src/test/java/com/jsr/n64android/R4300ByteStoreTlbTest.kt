package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class R4300ByteStoreTlbTest {
    @Test fun unalignedSbAddressRaisesTlbStoreMissNotAddressError() {
        val memory = N64Memory(64)
        // sb $v0, 0x59F($at): byte stores may use unaligned addresses.
        memory.write32(0, (0x28 shl 26) or (1 shl 21) or (2 shl 16) or 0x059F)
        val cpu = R4300Cpu(memory)
        cpu.tlbEnabled = true
        cpu.gpr[1] = 0x70201000L
        cpu.gpr[2] = 0x5AL
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())

        cpu.step()

        assertEquals(3, cpu.lastExceptionCode) // TLB Store Miss, not AdES.
        assertEquals(0x7020159FL, cpu.cop0[8]) // Exact byte address in BadVAddr.
        assertEquals((0x28 shl 26) or (1 shl 21) or (2 shl 16) or 0x059F, cpu.lastExceptionInstructionWord)
    }
}
