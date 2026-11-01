package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64SpTaskDpCoordinatorTest {
    @Test
    fun dpWaitCompletesWhenFullSyncArrivesAfterTaskBreak() {
        val bus = N64Bus(ram = N64Memory(1024))
        val coordinator = N64SpTaskDpCoordinator(bus)
        coordinator.begin(N64SpTaskLifecycle.OS_TASK_DP_WAIT)
        coordinator.startBootMicrocode()
        coordinator.startTaskMicrocode()
        coordinator.onTaskBreak()
        assertEquals(N64SpTaskLifecycle.State.WAITING_FOR_DP, coordinator.lifecycle.state)

        submitFullSync(bus)

        assertEquals(N64SpTaskLifecycle.State.COMPLETED, coordinator.lifecycle.state)
        assertEquals(N64Bus.MI_INTR_DP, bus.read32(N64Bus.MI_INTR_REG))
    }

    @Test
    fun dpWaitCompletesWhenFullSyncArrivesBeforeTaskBreak() {
        val bus = N64Bus(ram = N64Memory(1024))
        val coordinator = N64SpTaskDpCoordinator(bus)
        coordinator.begin(N64SpTaskLifecycle.OS_TASK_DP_WAIT)
        coordinator.startBootMicrocode()
        coordinator.startTaskMicrocode()

        submitFullSync(bus)
        assertEquals(N64SpTaskLifecycle.State.TASK_MICROCODE_RUNNING, coordinator.lifecycle.state)

        coordinator.onTaskBreak()

        assertEquals(N64SpTaskLifecycle.State.COMPLETED, coordinator.lifecycle.state)
    }

    @Test
    fun taskWithoutDpWaitCompletesAtBreakAndIgnoresLaterFullSync() {
        val bus = N64Bus(ram = N64Memory(1024))
        val coordinator = N64SpTaskDpCoordinator(bus)
        coordinator.begin(0)
        coordinator.startBootMicrocode()
        coordinator.startTaskMicrocode()
        coordinator.onTaskBreak()

        assertEquals(N64SpTaskLifecycle.State.COMPLETED, coordinator.lifecycle.state)
        submitFullSync(bus)
        assertEquals(N64SpTaskLifecycle.State.COMPLETED, coordinator.lifecycle.state)
    }

    @Test
    fun dpWaitCompletesOnlyAfterRdpWorkBeforeFullSyncHasBeenApplied() {
        val bus = N64Bus(ram = N64Memory(4096))
        val coordinator = N64SpTaskDpCoordinator(bus)
        coordinator.begin(N64SpTaskLifecycle.OS_TASK_DP_WAIT)
        coordinator.startBootMicrocode()
        coordinator.startTaskMicrocode()

        val start = 0x40
        val commands = listOf(
            command(N64RdpFillProcessor.CMD_SET_OTHER_MODES, high = 3L shl 20),
            command(N64RdpFillProcessor.CMD_SET_COLOR_IMAGE, high = (2L shl 19) or 3L, low = 0x100L),
            command(N64RdpFillProcessor.CMD_SET_FILL_COLOR, low = 0xF801F801L),
            command(N64RdpFillProcessor.CMD_FILL_RECTANGLE, high = (8L shl 12) or 8L, low = (4L shl 12) or 4L),
            command(N64DpcCommandProcessor.CMD_FULL_SYNC)
        )
        commands.forEachIndexed { index, value ->
            val address = start + index * N64DpcCommandProcessor.COMMAND_BYTES
            bus.write32(address, (value ushr 32).toInt())
            bus.write32(address + 4, value.toInt())
        }

        coordinator.onTaskBreak()
        assertEquals(N64SpTaskLifecycle.State.WAITING_FOR_DP, coordinator.lifecycle.state)
        bus.write32(N64Bus.DPC_START_REG, start)
        bus.write32(N64Bus.DPC_END_REG, start + commands.size * N64DpcCommandProcessor.COMMAND_BYTES)

        // Full Sync is reported only after the preceding supported RDP work has run.
        assertEquals(0xF801, bus.read16(0x100 + (1 * 4 + 1) * 2))
        assertEquals(N64SpTaskLifecycle.State.COMPLETED, coordinator.lifecycle.state)
        assertEquals(N64Bus.MI_INTR_DP, bus.read32(N64Bus.MI_INTR_REG))
    }

    private fun command(opcode: Int, high: Long = 0L, low: Long = 0L): Long =
        (opcode.toLong() shl 58) or (high shl 32) or (low and 0xFFFFFFFFL)

    private fun submitFullSync(bus: N64Bus) {
        val start = 0x40
        bus.write32(start, (N64DpcCommandProcessor.CMD_FULL_SYNC shl 26))
        bus.write32(start + 4, 0)
        bus.write32(N64Bus.DPC_START_REG, start)
        bus.write32(N64Bus.DPC_END_REG, start + N64DpcCommandProcessor.COMMAND_BYTES)
    }
}
