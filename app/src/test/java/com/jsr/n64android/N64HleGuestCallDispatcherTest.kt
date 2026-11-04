package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleGuestCallDispatcherTest {
    @Test
    fun dispatchesOnlyExactRegisteredAddressAndConsumesTheFetchBoundary() {
        val memory = N64Memory(8 * 1024 * 1024)
        memory.write32(0, -1)
        val cpu = R4300Cpu(memory)
        val entry = 0xFFFFFFFF80000000UL.toLong()
        val resume = 0xFFFFFFFF80000004UL.toLong()
        cpu.setProgramCounter(entry)
        val dispatcher = N64HleGuestCallDispatcher(cpu)
        dispatcher.register("osStartThread", entry, "GoldenEye USA symbol map fixture") { liveCpu ->
            assertEquals(entry, liveCpu.pc)
            liveCpu.setProgramCounter(resume)
            4
        }
        dispatcher.install()

        cpu.step()

        assertEquals(resume, cpu.pc)
        assertEquals(2L, cpu.cop0[9])
        assertEquals(entry, dispatcher.registrationForAddress(entry)?.guestAddress)
    }

    @Test
    fun unknownAddressFallsThroughToNormalCpuFetch() {
        val memory = N64Memory(8 * 1024 * 1024)
        memory.write32(4, 0) // NOP at the current PC.
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0xFFFFFFFF80000004UL.toLong())
        val dispatcher = N64HleGuestCallDispatcher(cpu)
        dispatcher.register(
            "osYieldThread",
            0xFFFFFFFF80000000UL.toLong(),
            "test-only address"
        ) { 0 }
        dispatcher.install()

        cpu.step()

        assertEquals(0xFFFFFFFF80000008UL.toLong(), cpu.pc)
        assertEquals(1L, cpu.cop0[9])
    }

    @Test
    fun rejectsDuplicateAddressesSymbolsAndMissingEvidence() {
        val cpu = R4300Cpu(N64Memory(8 * 1024 * 1024))
        val dispatcher = N64HleGuestCallDispatcher(cpu)
        val address = 0xFFFFFFFF80000000UL.toLong()
        dispatcher.register("osStartThread", address, "verified map") { 1 }

        assertThrows(IllegalStateException::class.java) {
            dispatcher.register("otherSymbol", address, "verified map") { 1 }
        }
        assertThrows(IllegalStateException::class.java) {
            dispatcher.register("osStartThread", address + 4, "verified map") { 1 }
        }
        assertThrows(IllegalArgumentException::class.java) {
            dispatcher.register("osStopThread", address + 8, "") { 1 }
        }
        assertNull(dispatcher.addressForSymbol("unknown"))
    }

    @Test
    fun uninstallRemovesCpuBoundaryHook() {
        val memory = N64Memory(8 * 1024 * 1024)
        memory.write32(0, 0)
        val cpu = R4300Cpu(memory)
        cpu.setProgramCounter(0xFFFFFFFF80000000UL.toLong())
        val dispatcher = N64HleGuestCallDispatcher(cpu)
        dispatcher.register("osCreateThread", 0xFFFFFFFF80000000UL.toLong(), "test map") { 0 }
        dispatcher.install()
        dispatcher.uninstall()

        cpu.step()

        assertEquals(0xFFFFFFFF80000004UL.toLong(), cpu.pc)
        assertEquals(1L, cpu.cop0[9])
    }
}
