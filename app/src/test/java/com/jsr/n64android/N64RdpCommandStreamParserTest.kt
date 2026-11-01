package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N64RdpCommandStreamParserTest {
    @Test
    fun preservesAllWordsOfTrianglePacketAndFollowingCommand() {
        val memory = N64Memory(512)
        writeWord(memory, 0x40, command(0x08))
        for (index in 1 until 4) {
            writeWord(memory, 0x40 + index * 8, 0x1111111100000000L + index)
        }
        writeWord(memory, 0x60, command(0x2F))

        val packets = N64RdpCommandStreamParser.parse(memory, 0x40, 0x68, memory.size)

        assertEquals(2, packets.size)
        assertEquals(0x08, packets[0].opcode)
        assertEquals(32, packets[0].byteLength)
        assertEquals(4, packets[0].words.size)
        assertEquals(0x1111111100000001L, packets[0].words[1])
        assertEquals(0x1111111100000002L, packets[0].words[2])
        assertEquals(0x1111111100000003L, packets[0].words[3])
        assertFalse(packets[0].isSingleWord)
        assertEquals(0x2F, packets[1].opcode)
        assertEquals(8, packets[1].byteLength)
        assertTrue(packets[1].isSingleWord)
    }

    @Test
    fun preservesBothWordsOfTextureRectangle() {
        val memory = N64Memory(128)
        writeWord(memory, 0x20, command(0x24))
        writeWord(memory, 0x28, 0x0123456789ABCDEFL)

        val packet = N64RdpCommandStreamParser.parse(memory, 0x20, 0x30, memory.size).single()

        assertEquals(0x24, packet.opcode)
        assertEquals(16, packet.byteLength)
        assertEquals(listOf(command(0x24), 0x123456789ABCDEFL), packet.words)
    }

    @Test
    fun returnsDetachedSnapshotOfSourceWords() {
        val memory = N64Memory(128)
        writeWord(memory, 0x20, command(0x2F))
        val packets = N64RdpCommandStreamParser.parse(memory, 0x20, 0x28, memory.size)
        memory.write32(0x20, 0)
        assertEquals(command(0x2F), packets.single().firstWord)
    }

    @Test
    fun rejectsTruncatedMultiwordPacketBeforeReturningAnyPackets() {
        val memory = N64Memory(128)
        writeWord(memory, 0x20, command(0x24))
        val error = runCatching {
            N64RdpCommandStreamParser.parse(memory, 0x20, 0x28, memory.size)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Truncated RDP command"))
    }

    @Test
    fun rejectsMisalignedAndOutOfRangeStream() {
        val memory = N64Memory(128)
        val misaligned = runCatching {
            N64RdpCommandStreamParser.parse(memory, 0x21, 0x29, memory.size)
        }.exceptionOrNull()
        val outside = runCatching {
            N64RdpCommandStreamParser.parse(memory, 0x78, 0x88, memory.size)
        }.exceptionOrNull()
        assertTrue(misaligned is IllegalArgumentException)
        assertTrue(outside is IllegalArgumentException)
    }

    @Test
    fun acceptsEmptyRange() {
        val memory = N64Memory(128)
        assertTrue(
            N64RdpCommandStreamParser.parse(memory, 0x20, 0x20, memory.size).isEmpty()
        )
    }

    private fun writeWord(memory: N64Memory, address: Int, value: Long) {
        memory.write32(address, (value ushr 32).toInt())
        memory.write32(address + 4, value.toInt())
    }

    private fun command(opcode: Int): Long = opcode.toLong() shl 58
}
