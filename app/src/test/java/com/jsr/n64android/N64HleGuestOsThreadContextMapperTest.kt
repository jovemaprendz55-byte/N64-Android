package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleGuestOsThreadContextMapperTest {
    private fun templateSnapshot(): R4300Cpu.ContextSnapshot {
        val base = R4300Cpu(N64Memory()).snapshotContext()
        return base.copy(
            gpr = List(32) { index -> 0x1000L + index },
            cop0 = List(32) { index -> 0x2000L + index },
            fpr = List(32) { index -> 0x3000L + index },
            fcr = List(32) { index -> 0x4000L + index },
            pc = 0xFFFFFFFF80000100UL.toLong(),
            delayedTarget = 0xFFFFFFFF80000200UL.toLong(),
            pendingDelaySlotBranchAddress = 0xFFFFFFFF800001FCL.toLong(),
            countCycleRemainder = 1
        )
    }

    private fun thread(fpUsed: Int = 1): N64HleGuestOsThread {
        val gpr = LongArray(32) { index -> 0x100000000L + index }
        val pairs = LongArray(16) { index -> 0x1122334400000000L + index }
        return N64HleGuestOsThread(
            priority = 100,
            fpUsed = fpUsed,
            context = N64HleGuestOsThreadContext(
                gpr = gpr,
                lo = 0x1111222233334444L,
                hi = 0xAAAABBBBCCCCDDDDUL.toLong(),
                status = if (fpUsed != 0) 0x2000FF03 else 0x0000FF03,
                pc = 0x80123450.toInt(),
                cause = 0x80000400.toInt(),
                badVaddr = 0x7F001234,
                rcp = 0x003F0000,
                fpcsr = 0x01000C00,
                fpPairs = pairs
            )
        )
    }

    @Test
    fun mapsGoldenEyeThreadGprCop0AndFrZeroFpPairs() {
        val original = thread()
        val template = templateSnapshot()
        val mapped = N64HleGuestOsThreadContextMapper.toCpuSnapshot(original, template)

        assertEquals(original.context.gpr[1], mapped.gpr[1])
        assertEquals(original.context.gpr[25], mapped.gpr[25])
        assertEquals(original.context.gpr[28], mapped.gpr[28])
        assertEquals(original.context.gpr[29], mapped.gpr[29])
        assertEquals(original.context.gpr[31], mapped.gpr[31])
        assertEquals(0L, mapped.gpr[0])
        assertEquals(0L, mapped.gpr[26])
        assertEquals(0L, mapped.gpr[27])
        assertEquals(original.context.lo, mapped.lo)
        assertEquals(original.context.hi, mapped.hi)
        assertEquals(original.context.badVaddr.toUInt().toLong(), mapped.cop0[8])
        assertEquals(original.context.status.toUInt().toLong(), mapped.cop0[12])
        assertEquals(original.context.cause.toUInt().toLong(), mapped.cop0[13])
        assertEquals(original.context.pc.toLong(), mapped.cop0[14])
        assertEquals(original.context.pc.toLong(), mapped.pc)
        assertEquals(0L, mapped.fpr[0])
        assertEquals(0x11223344L, mapped.fpr[1])
        assertEquals(15L, mapped.fpr[30])
        assertEquals(0x11223344L, mapped.fpr[31])
        assertEquals(original.context.fpcsr.toUInt().toLong(), mapped.fcr[31])
        assertEquals(template.tlb, mapped.tlb)
        assertEquals(template.countCycleRemainder, mapped.countCycleRemainder)
        assertEquals(template.tlbEnabled, mapped.tlbEnabled)
        assertNull(mapped.delayedTarget)
        assertNull(mapped.pendingDelaySlotBranchAddress)
    }

    @Test
    fun mapsCpuSnapshotBackToGuestAndPreservesRcpField() {
        val original = thread()
        val base = templateSnapshot()
        val mapped = N64HleGuestOsThreadContextMapper.toCpuSnapshot(original, base)
        val gpr = mapped.gpr.toMutableList().apply { this[16] = 0x123456789ABCDEFL }
        val fpr = mapped.fpr.toMutableList().apply {
            this[20] = 0x01234567L
            this[21] = 0x89ABCDEFL
        }
        val cop0 = mapped.cop0.toMutableList().apply {
            this[8] = 0x80001234L
            this[12] = 0x2000FF03L
            this[13] = 0x80000400L
        }
        val changed = mapped.copy(
            gpr = gpr,
            fpr = fpr,
            cop0 = cop0,
            hi = 0x1234L,
            lo = 0x5678L,
            fcr = mapped.fcr.toMutableList().apply { this[31] = 0x01000C00L },
            pc = 0xFFFFFFFF80005550UL.toLong()
        )

        val saved = N64HleGuestOsThreadContextMapper.fromCpuSnapshot(changed, original)
        assertEquals(0x123456789ABCDEFL, saved.context.gpr[16])
        assertEquals(0x1234L, saved.context.hi)
        assertEquals(0x5678L, saved.context.lo)
        assertEquals(0x80001234.toInt(), saved.context.badVaddr)
        assertEquals(0x80000400.toInt(), saved.context.cause)
        assertEquals(0x80005550.toInt(), saved.context.pc)
        assertEquals(0x89ABCDEF01234567UL.toLong(), saved.context.fpPairs[10])
        assertEquals(0x003F0000, saved.context.rcp)
        assertEquals(0x01000C00, saved.context.fpcsr)
    }

    @Test
    fun leavesFpStateUntouchedForThreadThatHasNotUsedCoprocessorOne() {
        val original = thread(fpUsed = 0)
        val template = templateSnapshot()
        val mapped = N64HleGuestOsThreadContextMapper.toCpuSnapshot(original, template)
        assertEquals(template.fpr, mapped.fpr)
        assertEquals(template.fcr, mapped.fcr)

        val saved = N64HleGuestOsThreadContextMapper.fromCpuSnapshot(mapped, original)
        assertEquals(original.context.fpPairs.toList(), saved.context.fpPairs.toList())
        assertEquals(original.context.fpcsr, saved.context.fpcsr)
    }

    @Test
    fun mappedContextRestoresIntoCpuAndCanBeSerializedBack() {
        val original = thread()
        val cpu = R4300Cpu(N64Memory())
        val mapped = N64HleGuestOsThreadContextMapper.toCpuSnapshot(original, cpu.snapshotContext())

        cpu.restoreContext(mapped)

        val live = cpu.snapshotContext()
        assertEquals(mapped.gpr, live.gpr)
        assertEquals(mapped.hi, live.hi)
        assertEquals(mapped.lo, live.lo)
        assertEquals(mapped.cop0, live.cop0)
        assertEquals(mapped.fpr, live.fpr)
        assertEquals(mapped.fcr, live.fcr)
        assertEquals(mapped.pc, live.pc)

        val saved = N64HleGuestOsThreadContextMapper.fromCpuSnapshot(live, original)
        assertEquals(original.context.gpr.toList(), saved.context.gpr.toList())
        assertEquals(original.context.fpPairs.toList(), saved.context.fpPairs.toList())
        assertEquals(original.context.fpcsr, saved.context.fpcsr)
    }

    @Test
    fun rejectsFrOneAndInvalidResumePc() {
        val original = thread()
        val template = templateSnapshot()
        val frOne = original.copy(context = original.context.copy(status = 1 shl 26))
        assertThrows(IllegalArgumentException::class.java) {
            N64HleGuestOsThreadContextMapper.toCpuSnapshot(frOne, template)
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleGuestOsThreadContextMapper.fromCpuSnapshot(
                template.copy(pc = 0x0000000180000000L),
                original
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            N64HleGuestOsThreadContextMapper.fromCpuSnapshot(
                template.copy(pc = 0xFFFFFFFF80000002UL.toLong()),
                original
            )
        }
    }
}
