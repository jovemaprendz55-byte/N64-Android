package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class N64HleVerifiedSymbolTableTest {
    @Test
    fun registersAndResolvesOnlyEvidenceBackedSymbols() {
        val table = N64HleVerifiedSymbolTable()
        val symbol = table.register(
            "osCreateThread",
            0x80001234L,
            "GoldenEye USA linker map, build revision verified"
        )

        assertEquals("osCreateThread", symbol.name)
        assertEquals(0x80001234L, symbol.address)
        assertEquals(symbol, table.findByName("osCreateThread"))
        assertEquals(symbol, table.findByAddress(0xFFFF_FFFF_8000_1234UL.toLong()))
        assertEquals(1, table.size)
    }

    @Test
    fun rejectsMissingEvidenceInvalidSegmentsAndUnalignedAddresses() {
        val table = N64HleVerifiedSymbolTable()
        assertThrows(IllegalArgumentException::class.java) {
            table.register("missingEvidence", 0x80001000L, " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            table.register("unaligned", 0x80001002L, "verified map line")
        }
        assertThrows(IllegalArgumentException::class.java) {
            table.register("kseg1", 0xA0001000L, "verified map line")
        }
        assertThrows(IllegalArgumentException::class.java) {
            table.register("wide", 0x1_8000_1000L, "verified map line")
        }
        assertEquals(0, table.size)
    }

    @Test
    fun rejectsDuplicateNamesAndAddressesWithoutPartialMutation() {
        val table = N64HleVerifiedSymbolTable()
        table.register("osStartThread", 0x80002000L, "map: symbol A")
        assertThrows(IllegalArgumentException::class.java) {
            table.register("osStartThread", 0x80002004L, "map: symbol B")
        }
        assertThrows(IllegalArgumentException::class.java) {
            table.register("osYieldThread", 0x80002000L, "map: symbol C")
        }

        assertEquals(1, table.size)
        assertNull(table.findByName("osYieldThread"))
        assertEquals("osStartThread", table.findByAddress(0x80002000L)?.name)
    }

    @Test
    fun snapshotIsAddressOrderedAndUnknownSymbolsRemainUnresolved() {
        val table = N64HleVerifiedSymbolTable()
        table.register("second", 0x80003000L, "map line 2")
        table.register("first", 0x80001000L, "map line 1")

        val ordered = table.snapshot()
        assertEquals(listOf("first", "second"), ordered.map { it.name })
        assertNull(table.findByName("notInMap"))
        assertNull(table.findByAddress(0x80004000L))
        assertNotNull(table.findByName("first"))
    }
}