package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class N64SpTaskLifecycleTest {
    @Test
    fun graphicsTaskWaitsForDpDrainAfterTaskBreak() {
        val lifecycle = N64SpTaskLifecycle()

        lifecycle.begin(N64SpTaskLifecycle.OS_TASK_DP_WAIT)
        assertEquals(N64SpTaskLifecycle.State.PREPARING, lifecycle.state)
        lifecycle.startBootMicrocode()
        assertEquals(N64SpTaskLifecycle.State.BOOT_MICROCODE_RUNNING, lifecycle.state)
        lifecycle.startTaskMicrocode()
        assertEquals(N64SpTaskLifecycle.State.TASK_MICROCODE_RUNNING, lifecycle.state)

        lifecycle.onTaskBreak()
        assertEquals(N64SpTaskLifecycle.State.WAITING_FOR_DP, lifecycle.state)

        lifecycle.onDpDrainCompleted()
        assertEquals(N64SpTaskLifecycle.State.COMPLETED, lifecycle.state)
    }

    @Test
    fun taskWithoutDpWaitCompletesAtBreak() {
        val lifecycle = N64SpTaskLifecycle()
        lifecycle.begin(0)
        lifecycle.startBootMicrocode()
        lifecycle.startTaskMicrocode()

        lifecycle.onTaskBreak()

        assertEquals(N64SpTaskLifecycle.State.COMPLETED, lifecycle.state)
        assertTrue(!lifecycle.requiresDpWait)
    }

    @Test
    fun failedTaskCanBeResetAndReused() {
        val lifecycle = N64SpTaskLifecycle()
        lifecycle.begin(0)
        lifecycle.fail("synthetic DMA failure")

        assertEquals(N64SpTaskLifecycle.State.FAILED, lifecycle.state)
        assertEquals("synthetic DMA failure", lifecycle.failureReason)

        lifecycle.reset()

        assertEquals(N64SpTaskLifecycle.State.IDLE, lifecycle.state)
        assertNull(lifecycle.failureReason)
        assertEquals(0, lifecycle.taskFlags)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsTaskMicrocodeBeforeBootMicrocode() {
        val lifecycle = N64SpTaskLifecycle()
        lifecycle.begin(0)
        lifecycle.startTaskMicrocode()
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsDpDrainWhenTaskDidNotWait() {
        val lifecycle = N64SpTaskLifecycle()
        lifecycle.begin(0)
        lifecycle.startBootMicrocode()
        lifecycle.startTaskMicrocode()
        lifecycle.onTaskBreak()
        lifecycle.onDpDrainCompleted()
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsSecondTaskWhileOneIsActive() {
        val lifecycle = N64SpTaskLifecycle()
        lifecycle.begin(0)
        lifecycle.begin(0)
    }
}
