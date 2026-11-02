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

    @Test fun fractionalMultiplyRoundsAccumulatesAndSaturates() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, 0x4000)
        unit.writeLane(2, 0, 0x4000)
        unit.writeLane(1, 1, -0x4000)
        unit.writeLane(2, 1, 0x4000)
        unit.writeLane(1, 2, -32768)
        unit.writeLane(2, 2, -32768)

        unit.multiplyFractional(3, 1, 2)

        assertEquals(8192, unit.readLane(3, 0).toShort().toInt())
        assertEquals(-8192, unit.readLane(3, 1).toShort().toInt())
        assertEquals(32767, unit.readLane(3, 2).toShort().toInt())
        assertEquals(536903680L, unit.readAccumulator(0))
        assertEquals(0, unit.vcoLow)
        assertEquals(0, unit.vcoHigh)
    }

    @Test fun fractionalMultiplyUnsignedClampsNegativeAndPreservesAccumulator() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, 0x4000)
        unit.writeLane(2, 0, 0x4000)
        unit.writeLane(1, 1, -0x4000)
        unit.writeLane(2, 1, 0x4000)
        unit.writeLane(1, 2, -32768)
        unit.writeLane(2, 2, -32768)

        unit.multiplyFractionalUnsigned(3, 1, 2)

        assertEquals(8192, unit.readLane(3, 0))
        assertEquals(0, unit.readLane(3, 1))
        assertEquals(32768, unit.readLane(3, 2))
        assertEquals(536903680L, unit.readAccumulator(0))
        assertEquals(-536838144L, unit.readAccumulator(1))
        assertEquals(0, unit.vcoLow)
        assertEquals(0, unit.vcoHigh)
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


    @Test fun snapshotRestoresVectorRegistersAccumulatorAndFlags() {
        val source = N64RspVectorUnit()
        source.writeLane(7, 3, 0xABCD)
        source.writeLane(8, 3, 0x1234)
        source.addCarry(9, 7, 8)
        source.setCompareFlags(0x12, 0x34, 0x56)
        val saved = source.snapshot()

        val resumed = N64RspVectorUnit()
        resumed.restore(saved)

        assertEquals(saved, resumed.snapshot())
        assertEquals(0xBE01, resumed.readLane(9, 3))
        assertEquals(0xABCD + 0x1234, resumed.readAccumulator(3).toInt())
    }

    @Test fun invalidSnapshotDoesNotPartiallyMutateVectorState() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 2, 0xCAFE)
        val before = unit.snapshot()
        val invalid = before.copy(
            registers = before.registers.mapIndexed { index, lanes ->
                if (index == 31) lanes.drop(1) else lanes
            }
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            unit.restore(invalid)
        }
        assertEquals(before, unit.snapshot())
    }


    @Test fun accumulatorSliceSelectsHighMiddleAndLowSixteenBitWords() {
        val unit = N64RspVectorUnit()
        unit.writeLane(1, 0, 0x4000)
        unit.writeLane(2, 0, 0x4000)
        unit.multiplyFractional(3, 1, 2)
        unit.accumulatorSlice(4, 8)
        unit.accumulatorSlice(5, 9)
        unit.accumulatorSlice(6, 10)
        assertEquals(0x0000, unit.readLane(4, 0))
        assertEquals(0x2000, unit.readLane(5, 0))
        assertEquals(0x0000, unit.readLane(6, 0))
    }
\
}
