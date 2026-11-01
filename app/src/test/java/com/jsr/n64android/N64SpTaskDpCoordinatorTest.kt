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

    private fun submitFullSync(bus: N64Bus) {
        val start = 0x40
        bus.write32(start, (N64DpcCommandProcessor.CMD_FULL_SYNC shl 26))
        bus.write32(start + 4, 0)
        bus.write32(N64Bus.DPC_START_REG, start)
        bus.write32(N64Bus.DPC_END_REG, start + N64DpcCommandProcessor.COMMAND_BYTES)
    }
}
