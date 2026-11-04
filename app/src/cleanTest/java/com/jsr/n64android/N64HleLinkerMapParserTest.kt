package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleLinkerMapParserTest {
    @Test
    fun importsOnlyStrictAddressAndSymbolRowsWithLineEvidence() {
        val map = """
            Linker script and memory map
            .text           0x0000000080001000      0x100
            0x0000000080001010                osCreateThread
            0x80001020                      osStartThread
            *(.text*)
            0x0000000080001030                __osEnqueueAndYield
        """.trimIndent()
        val table = N64HleVerifiedSymbolTable()

        val result = N64HleLinkerMapParser().parse(
            map,
            "GoldenEye USA exact-build map SHA256:example",
            table
        )

        assertEquals(
            listOf("osCreateThread", "osStartThread", "__osEnqueueAndYield"),
            result.symbols.map { it.name }
        )
        assertEquals(3, table.size)
        assertEquals(
            "GoldenEye USA exact-build map SHA256:example:line 3",
            table.findByName("osCreateThread")?.evidence
        )
        assertEquals(3, result.ignoredLineCount)
    }

    @Test
    fun rejectsWrongSegmentsAndDoesNotPartiallyRegister() {
        val table = N64HleVerifiedSymbolTable()
        val map = """
            0x80001000 validSymbol
            0xA0001000 kseg1Symbol
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            N64HleLinkerMapParser().parse(map, "verified map", table)
        }
        assertEquals(0, table.size)
    }

    @Test
    fun rejectsDuplicateNamesAndAddressesBeforeMutation() {
        val table = N64HleVerifiedSymbolTable()
        val duplicateName = """
            0x80001000 same
            0x80001004 same
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            N64HleLinkerMapParser().parse(duplicateName, "map", table)
        }

        val duplicateAddress = """
            0x80001000 first
            0x80001000 second
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            N64HleLinkerMapParser().parse(duplicateAddress, "map", table)
        }
        assertEquals(0, table.size)
    }

    @Test
    fun requiresMapEvidenceAndRejectsMalformedAddressRows() {
        val table = N64HleVerifiedSymbolTable()
        assertThrows(IllegalArgumentException::class.java) {
            N64HleLinkerMapParser().parse("0x80001000 symbol", " ", table)
        }
        val result = N64HleLinkerMapParser().parse(
            "0x80001000 symbol extra\nnot-a-symbol-row",
            "map revision",
            table
        )
        assertEquals(0, result.symbols.size)
        assertEquals(2, result.ignoredLineCount)
    }
    @Test
    fun existingTableConflictDoesNotPartiallyRegisterNewRows() {
        val table = N64HleVerifiedSymbolTable()
        table.register("alreadyThere", 0x80001000L, "prior verified map")
        val map = """
            0x80001004 newSymbol
            0x80001000 collidingAddress
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            N64HleLinkerMapParser().parse(map, "second map", table)
        }
        assertEquals(1, table.size)
        assertEquals(null, table.findByName("newSymbol"))
    }

}
