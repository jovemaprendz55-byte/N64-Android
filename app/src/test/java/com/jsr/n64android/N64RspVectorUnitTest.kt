package com.jsr.n64android

import org.junit.Assert.assertEquals
import org.junit.Test

class N64RspVectorUnitTest {
    @Test fun storesThirtyTwoVectorsOfEightSixteenBitLanes() {
        val unit = N64RspVectorUnit()
        unit.writeLane(31, 7, 0x1_2345)
        assertEquals(0x2345, unit.readLane(31, 7))
    }

    @Test fun addUsesSignedLanesAndSaturates() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, 32760)
        unit.writeLane(2, 0, 20)
        unit.writeLane(1, 1, -32768)
        unit.writeLane(2, 1, 1)
        unit.add(3, 1, 2)
        assertEquals(32767, unit.readLane(3, 0).toShort().toInt())
        assertEquals(-32767, unit.readLane(3, 1).toShort().toInt())
        assertEquals(32780L, unit.readAccumulator(0))
        assertEquals(0, unit.vcoLow)
        assertEquals(0, unit.vcoHigh)
    }

    @Test fun addConsumesCarryInAndRecordsAccumulator() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, 10)
        unit.writeLane(2, 0, 20)
        unit.setVco(1)
        unit.add(3, 1, 2)
        assertEquals(31, unit.readLane(3, 0))
        assertEquals(31L, unit.readAccumulator(0))
    }

    @Test fun subtractConsumesBorrowInAndSaturates() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, -32768)
        unit.writeLane(2, 0, 1)
        unit.setVco(1)
        unit.subtract(3, 1, 2)
        assertEquals(-32768, unit.readLane(3, 0).toShort().toInt())
        assertEquals(-32770L, unit.readAccumulator(0))
    }

    @Test fun logicalOperationsWorkLaneByLane() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 4, 0x0FF0)
        unit.writeLane(2, 4, 0x00FF)
        unit.and(3, 1, 2)
        assertEquals(0x00F0, unit.readLane(3, 4))
        unit.or(3, 1, 2)
        assertEquals(0x0FFF, unit.readLane(3, 4))
        unit.xor(3, 1, 2)
        assertEquals(0x0F0F, unit.readLane(3, 4))
        unit.nor(3, 1, 2)
        assertEquals(0xF000, unit.readLane(3, 4))
    }

    @Test fun accumulatorIsSignExtendedToFortyEightBits() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, -32768)
        unit.writeLane(2, 0, 0)
        unit.subtract(3, 1, 2)
        assertEquals(-32768L, unit.readAccumulator(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLane() {
        N64RspVectorUnit().readLane(0, 8)
    }

    @Test fun addCarryWritesLowHalfAndSetsPerLaneCarryFlags() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, 0xFFFF)
        unit.writeLane(2, 0, 1)
        unit.writeLane(1, 1, 0x1234)
        unit.writeLane(2, 1, 1)

        unit.addCarry(3, 1, 2)

        assertEquals(0, unit.readLane(3, 0))
        assertEquals(0x1235, unit.readLane(3, 1))
        assertEquals(1, unit.vcoLow)
        assertEquals(0, unit.vcoHigh)
    }

    @Test fun subtractCarryTracksBorrowAndUnequalLanesSeparately() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, 0)
        unit.writeLane(2, 0, 1)
        unit.writeLane(1, 1, 5)
        unit.writeLane(2, 1, 5)
        unit.writeLane(1, 2, 9)
        unit.writeLane(2, 2, 4)

        unit.subtractCarry(3, 1, 2)

        assertEquals(0xFFFF, unit.readLane(3, 0))
        assertEquals(0, unit.readLane(3, 1))
        assertEquals(5, unit.readLane(3, 2))
        assertEquals(1, unit.vcoLow)
        assertEquals(5, unit.vcoHigh)
    }

    @Test fun absoluteUsesSignSourceAndSaturatesNegative32768Magnitude() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, -1)
        unit.writeLane(2, 0, -32768)
        unit.writeLane(1, 1, 1)
        unit.writeLane(2, 1, -12)
        unit.writeLane(1, 2, 0)
        unit.writeLane(2, 2, 123)

        unit.absolute(3, 1, 2)

        assertEquals(32767, unit.readLane(3, 0).toShort().toInt())
        assertEquals(-12, unit.readLane(3, 1).toShort().toInt())
        assertEquals(0, unit.readLane(3, 2))
    }

}
