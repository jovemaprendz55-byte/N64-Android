package com.jsr.n64android

import org.junit.Assert.assertTrue
import org.junit.Test

class N64HleAutoDiagnosticTest {
    @Test fun reportSeparatesObservedStateFromRootCause() {
        val bus = N64Bus()
        val cpu = R4300Cpu(bus)
        val loop = N64InstructionTrace.Loop(
            period = 2,
            repetitions = 3,
            pattern = listOf(
                N64InstructionTrace.Entry(0x7000071CL, 0x1000FFFF),
                N64InstructionTrace.Entry(0x70000720L, 0)
            )
        )

        val report = N64HleAutoDiagnostic.build(
            cpu = cpu,
            bus = bus,
            instructionCount = 24_300_000L,
            rspInstructionCount = 0L,
            frameCount = 0L,
            loop = loop
        )

        assertTrue(report.contains("CONFIRMADO: sequência de instruções repetida"))
        assertTrue(report.contains("SP está em HALT"))
        assertTrue(report.contains("RSP e frames ainda não avançaram"))
        assertTrue(report.contains("CAUSA RAIZ: NÃO DETERMINADA AUTOMATICAMENTE"))
    }
}
