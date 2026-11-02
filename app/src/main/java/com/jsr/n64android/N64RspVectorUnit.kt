package com.jsr.n64android

/**
 * Own state and first arithmetic subset for the N64 Signal Processor vector unit.
 *
 * Lanes are numbered 0..7 in architectural element order. Values are stored as
 * unsigned 16-bit bit patterns; signed interpretation is applied by arithmetic.
 * This unit is intentionally separate until the RSP instruction decoder can
 * dispatch COP2 vector formats without conflating scalar and vector fields.
 */
class N64RspVectorUnit {
    /** Complete architectural vector state, suitable for a task/context save. */
    data class Snapshot(
        val registers: List<List<Int>>,
        val accumulator: List<Long>,
        val vcoLow: Int,
        val vcoHigh: Int,
        val vccLow: Int,
        val vccHigh: Int,
        val vce: Int
    )

    fun snapshot(): Snapshot = Snapshot(
        registers = registers.map { it.toList() },
        accumulator = accumulator.toList(),
        vcoLow = vcoLow,
        vcoHigh = vcoHigh,
        vccLow = vccLow,
        vccHigh = vccHigh,
        vce = vce
    )

    /** Validates the entire snapshot before changing any live vector state. */
    fun restore(snapshot: Snapshot) {
        require(snapshot.registers.size == 32) { "RSP snapshot must contain 32 vector registers." }
        require(snapshot.registers.all { it.size == 8 }) { "Each RSP vector register must contain 8 lanes." }
        require(snapshot.registers.all { lanes -> lanes.all { it in 0..0xFFFF } }) {
            "RSP vector lanes must be unsigned 16-bit values."
        }
        require(snapshot.accumulator.size == 8) { "RSP accumulator snapshot must contain 8 lanes." }
        val minAccumulator = -(1L shl 47)
        val maxAccumulator = (1L shl 47) - 1L
        require(snapshot.accumulator.all { it in minAccumulator..maxAccumulator }) {
            "RSP accumulator values must fit the signed 48-bit architectural range."
        }
        require(snapshot.vcoLow in 0..0xFF && snapshot.vcoHigh in 0..0xFF &&
            snapshot.vccLow in 0..0xFF && snapshot.vccHigh in 0..0xFF &&
            snapshot.vce in 0..0xFF) { "RSP vector flags must be 8-bit lane masks." }

        snapshot.registers.forEachIndexed { register, lanes ->
            lanes.forEachIndexed { lane, value -> registers[register][lane] = value }
        }
        snapshot.accumulator.forEachIndexed { lane, value -> accumulator[lane] = value }
        vcoLow = snapshot.vcoLow
        vcoHigh = snapshot.vcoHigh
        vccLow = snapshot.vccLow
        vccHigh = snapshot.vccHigh
        vce = snapshot.vce
    }
    private val registers = Array(32) { IntArray(8) }
    private val accumulator = LongArray(8)

    /** VCO low/high, VCC low/high and VCE are represented as eight-bit lane masks. */
    var vcoLow: Int = 0
        private set
    var vcoHigh: Int = 0
        private set
    var vccLow: Int = 0
        private set
    var vccHigh: Int = 0
        private set
    var vce: Int = 0
        private set

    fun readLane(register: Int, lane: Int): Int {
        checkRegister(register, lane)
        return registers[register][lane]
    }

    fun writeLane(register: Int, lane: Int, value: Int) {
        checkRegister(register, lane)
        registers[register][lane] = value and 0xFFFF
    }

    /** Read/write the architectural byte view used by MFC2/MTC2. */
    fun readByte(register: Int, byteIndex: Int): Int {
        checkVector(register)
        require(byteIndex in 0..15)
        val lane = byteIndex ushr 1
        return if ((byteIndex and 1) == 0) {
            (registers[register][lane] ushr 8) and 0xFF
        } else {
            registers[register][lane] and 0xFF
        }
    }

    fun writeByte(register: Int, byteIndex: Int, value: Int) {
        checkVector(register)
        require(byteIndex in 0..15)
        val lane = byteIndex ushr 1
        val current = registers[register][lane]
        registers[register][lane] = if ((byteIndex and 1) == 0) {
            ((current and 0x00FF) or ((value and 0xFF) shl 8))
        } else {
            (current and 0xFF00) or (value and 0xFF)
        }
    }

    /** Packed control-register view: VCO=0, VCC=1, VCE=2. */
    fun readControl(register: Int): Int = when (register) {
        0 -> (vcoHigh shl 8) or vcoLow
        1 -> (vccHigh shl 8) or vccLow
        2 -> vce
        else -> throw UnsupportedOperationException("Unsupported RSP vector control register $register.")
    }

    fun writeControl(register: Int, value: Int) {
        when (register) {
            0 -> setVco(value and 0xFF, (value ushr 8) and 0xFF)
            1 -> setCompareFlags(value and 0xFF, (value ushr 8) and 0xFF, vce)
            2 -> setCompareFlags(vccLow, vccHigh, value and 0xFF)
            else -> throw UnsupportedOperationException("Unsupported RSP vector control register $register.")
        }
    }

    fun readAccumulator(lane: Int): Long {
        require(lane in 0..7)
        return accumulator[lane]
    }

    /** Signed saturated lane addition; consumes VCO-low carry-in and clears VCO. */
    fun add(destination: Int, left: Int, right: Int, element: Int = 0) {
        checkVector(destination); checkVector(left); checkVector(right)
        require(element in 0..15)
        for (lane in 0..7) {
            val carry = (vcoLow ushr lane) and 1
            val selectedRight = selectedLane(right, lane, element)
            val sum = signed(registers[left][lane]) + signed(selectedRight) + carry
            accumulator[lane] = signExtend48(sum.toLong())
            registers[destination][lane] = saturateSigned16(sum)
        }
        vcoLow = 0
        vcoHigh = 0
    }

    /** Signed saturated lane subtraction; consumes VCO-low borrow-in and clears VCO. */
    fun subtract(destination: Int, left: Int, right: Int, element: Int = 0) {
        checkVector(destination); checkVector(left); checkVector(right)
        require(element in 0..15)
        for (lane in 0..7) {
            val borrow = (vcoLow ushr lane) and 1
            val selectedRight = selectedLane(right, lane, element)
            val difference = signed(registers[left][lane]) - signed(selectedRight) - borrow
            accumulator[lane] = signExtend48(difference.toLong())
            registers[destination][lane] = saturateSigned16(difference)
        }
        vcoLow = 0
        vcoHigh = 0
    }

    fun and(destination: Int, left: Int, right: Int, element: Int = 0) =
        logical(destination, left, right, element) { a, b -> a and b }
    fun or(destination: Int, left: Int, right: Int, element: Int = 0) =
        logical(destination, left, right, element) { a, b -> a or b }
    fun xor(destination: Int, left: Int, right: Int, element: Int = 0) =
        logical(destination, left, right, element) { a, b -> a xor b }
    fun nor(destination: Int, left: Int, right: Int, element: Int = 0) =
        logical(destination, left, right, element) { a, b -> (a or b).inv() }

    /**
     * Signed fractional multiply (VMULF): doubles the signed product, adds
     * the architectural rounding bias, stores the signed 48-bit accumulator,
     * and saturates the high accumulator half into the destination lane.
     */
    fun multiplyFractional(destination: Int, left: Int, right: Int, element: Int = 0) {
        checkVector(destination); checkVector(left); checkVector(right)
        require(element in 0..15)
        for (lane in 0..7) {
            val product = signed(registers[left][lane]).toLong() *
                signed(selectedLane(right, lane, element)).toLong()
            val accumulated = signExtend48((product shl 1) + 0x8000L)
            accumulator[lane] = accumulated
            val rounded = (accumulated shr 16).coerceIn(
                Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()
            )
            registers[destination][lane] = rounded.toInt() and 0xFFFF
        }
        vcoLow = 0
        vcoHigh = 0
    }

    /**
     * Signed fractional multiply with unsigned destination saturation (VMULU).
     * The product and 48-bit accumulator match VMULF; only the destination
     * interpretation differs: the rounded high half is clamped to 0..65535.
     */
    fun multiplyFractionalUnsigned(destination: Int, left: Int, right: Int, element: Int = 0) {
        checkVector(destination); checkVector(left); checkVector(right)
        require(element in 0..15)
        for (lane in 0..7) {
            val product = signed(registers[left][lane]).toLong() *
                signed(selectedLane(right, lane, element)).toLong()
            val accumulated = signExtend48((product shl 1) + 0x8000L)
            accumulator[lane] = accumulated
            val rounded = (accumulated shr 16).coerceIn(0L, 0xFFFFL)
            registers[destination][lane] = rounded.toInt()
        }
        vcoLow = 0
        vcoHigh = 0
    }

    /** Unsigned lane addition: stores low 16 bits and records carry-out in VCO low. */
    fun addCarry(destination: Int, left: Int, right: Int, element: Int = 0) {
        checkVector(destination); checkVector(left); checkVector(right)
        require(element in 0..15)
        var carries = 0
        for (lane in 0..7) {
            val a = registers[left][lane]
            val b = selectedLane(right, lane, element)
            val sum = a + b
            registers[destination][lane] = sum and 0xFFFF
            accumulator[lane] = signExtend48(sum.toLong())
            if (sum > 0xFFFF) carries = carries or (1 shl lane)
        }
        vcoLow = carries
        vcoHigh = 0
    }

    /** Unsigned lane subtraction: VCO low records borrow and VCO high records inequality. */
    fun subtractCarry(destination: Int, left: Int, right: Int, element: Int = 0) {
        checkVector(destination); checkVector(left); checkVector(right)
        require(element in 0..15)
        var borrows = 0
        var nonZero = 0
        for (lane in 0..7) {
            val a = registers[left][lane]
            val b = selectedLane(right, lane, element)
            val difference = a - b
            registers[destination][lane] = difference and 0xFFFF
            accumulator[lane] = signExtend48(difference.toLong())
            if (a < b) borrows = borrows or (1 shl lane)
            if (a != b) nonZero = nonZero or (1 shl lane)
        }
        vcoLow = borrows
        vcoHigh = nonZero
    }

    /** Signed absolute/select operation used by the RSP VABS instruction. */
    fun absolute(destination: Int, signSource: Int, valueSource: Int, element: Int = 0) {
        checkVector(destination); checkVector(signSource); checkVector(valueSource)
        require(element in 0..15)
        for (lane in 0..7) {
            val sign = signed(registers[signSource][lane])
            val value = signed(selectedLane(valueSource, lane, element))
            val result = when {
                sign < 0 -> -value
                sign > 0 -> value
                else -> 0
            }
            val saturated = result.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            registers[destination][lane] = saturated and 0xFFFF
            accumulator[lane] = signExtend48(result.toLong())
        }
    }

    /** Explicit flag injection is useful for testing carry/borrow data paths. */
    fun setVco(low: Int, high: Int = 0) {
        vcoLow = low and 0xFF
        vcoHigh = high and 0xFF
    }

    fun setCompareFlags(low: Int, high: Int, extension: Int = 0) {
        vccLow = low and 0xFF
        vccHigh = high and 0xFF
        vce = extension and 0xFF
    }

    private inline fun logical(
        destination: Int,
        left: Int,
        right: Int,
        element: Int,
        operation: (Int, Int) -> Int
    ) {
        checkVector(destination); checkVector(left); checkVector(right)
        require(element in 0..15)
        for (lane in 0..7) {
            val result = operation(registers[left][lane], selectedLane(right, lane, element)) and 0xFFFF
            registers[destination][lane] = result
            accumulator[lane] = signExtend48(result.toLong())
        }
    }

    /**
     * RSP element selector: 0..7 selects a sliding window ending at lane 7;
     * 8..15 broadcasts lane 0..7 respectively.
     */
    private fun selectedLane(register: Int, destinationLane: Int, element: Int): Int {
        val sourceLane = if (element < 8) minOf(destinationLane + element, 7) else element - 8
        return registers[register][sourceLane]
    }

    /** Full bounded textual snapshot for a user-requested diagnostic export. */
    fun diagnosticSnapshot(): String = buildString {
        for (register in 0 until 32) {
            append("V").append(register).append("=")
            append((0..7).joinToString(",") { lane ->
                "0x" + registers[register][lane].toString(16).uppercase().padStart(4, '0')
            })
            appendLine()
        }
        appendLine("ACC=" + (0..7).joinToString(" ") { lane ->
            "0x" + accumulator[lane].toULong().toString(16).uppercase().padStart(16, '0')
        })
        appendLine("VCO_LOW=0x${vcoLow.toString(16)} VCO_HIGH=0x${vcoHigh.toString(16)} VCC_LOW=0x${vccLow.toString(16)} VCC_HIGH=0x${vccHigh.toString(16)} VCE=0x${vce.toString(16)}")
    }

    private fun checkVector(register: Int) = require(register in 0..31)
    private fun checkRegister(register: Int, lane: Int) {
        checkVector(register)
        require(lane in 0..7)
    }

    private fun signed(value: Int): Int = value.toShort().toInt()
    private fun saturateSigned16(value: Int): Int = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) and 0xFFFF
    private fun signExtend48(value: Long): Long {
        val masked = value and 0x0000_FFFF_FFFF_FFFFL
        return if ((masked and (1L shl 47)) != 0L) masked or -0x0001_0000_0000_0000L else masked
    }
}
