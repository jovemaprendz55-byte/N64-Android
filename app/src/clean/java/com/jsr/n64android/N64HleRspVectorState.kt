package com.jsr.n64android

/**
 * RSP vector register file, accumulator, flags and initial arithmetic operations.
 * Element zero is the most-significant halfword (big-endian RSP lane order).
 */
class N64HleRspVectorState {
    private val registers = Array(VECTOR_REGISTER_COUNT) { ShortArray(VECTOR_LANES) }
    private val accumulator = LongArray(VECTOR_LANES)

    var vco: Int = 0
        private set
    var vcc: Int = 0
        private set
    var vce: Int = 0
        private set

    fun readElement(register: Int, element: Int): Int {
        validateRegister(register)
        validateElement(element)
        return registers[register][element].toInt() and 0xFFFF
    }

    fun readSignedElement(register: Int, element: Int): Int =
        readElement(register, element).toShort().toInt()

    fun writeElement(register: Int, element: Int, value: Int) {
        validateRegister(register)
        validateElement(element)
        require(value in -0x8000..0xFFFF) { "Vector element must fit in 16 bits" }
        registers[register][element] = value.toShort()
    }


    /** Read one byte from the architectural big-endian 16-byte vector view. */
    fun readByte(register: Int, byteIndex: Int): Int {
        validateRegister(register)
        require(byteIndex in 0..15)
        val lane = byteIndex ushr 1
        return if ((byteIndex and 1) == 0) readElement(register, lane) ushr 8
        else readElement(register, lane) and 0xFF
    }

    /** Write one byte in the architectural big-endian 16-byte vector view. */
    fun writeByte(register: Int, byteIndex: Int, value: Int) {
        validateRegister(register)
        require(byteIndex in 0..15 && value in 0..0xFF)
        val lane = byteIndex ushr 1
        val old = readElement(register, lane)
        val next = if ((byteIndex and 1) == 0) ((value shl 8) or (old and 0xFF))
            else ((old and 0xFF00) or value)
        writeElement(register, lane, next)
    }

    fun readControlRegister(register: Int): Int = when (register) {
        0 -> vco
        1 -> vcc
        2 -> vce
        else -> throw IllegalArgumentException("Unsupported RSP vector control register")
    }

    fun writeControlRegister(register: Int, value: Int) {
        when (register) {
            0 -> setFlags(value and 0xFFFF, vcc, vce)
            1 -> setFlags(vco, value and 0xFFFF, vce)
            2 -> setFlags(vco, vcc, value and 0xFF)
            else -> throw IllegalArgumentException("Unsupported RSP vector control register")
        }
    }

    fun readAccumulatorLane(lane: Int): Long {
        validateElement(lane)
        return accumulator[lane]
    }

    fun readSignedAccumulatorLane(lane: Int): Long {
        val value = readAccumulatorLane(lane)
        return if (value and ACCUMULATOR_SIGN_BIT != 0L) value or ACCUMULATOR_SIGN_EXTENSION else value
    }

    fun writeAccumulatorLane(lane: Int, value: Long) {
        validateElement(lane)
        accumulator[lane] = value and ACCUMULATOR_MASK
    }

    /** RSP VADD: signed lane addition, VCO carry-in, signed saturation, then clear VCO. */
    fun add(destination: Int, left: Int, right: Int, rightElement: Int? = null) {
        validateRegister(destination)
        validateRegister(left)
        validateRegister(right)
        for (lane in 0 until VECTOR_LANES) {
            val carry = (vco ushr lane) and 1
            val sum = readSignedElement(left, lane) + readSignedElement(right, rightElement ?: lane) + carry
            val result = saturateSigned16(sum.toLong())
            writeElement(destination, lane, result)
            writeAccumulatorLane(lane, result.toLong() shl 16)
        }
        vco = 0
    }

    /** RSP VSUB: signed lane subtraction, VCO borrow-in, signed saturation, then clear VCO. */
    fun subtract(destination: Int, left: Int, right: Int, rightElement: Int? = null) {
        validateRegister(destination)
        validateRegister(left)
        validateRegister(right)
        for (lane in 0 until VECTOR_LANES) {
            val borrow = (vco ushr lane) and 1
            val difference = readSignedElement(left, lane) - readSignedElement(right, rightElement ?: lane) - borrow
            val result = saturateSigned16(difference.toLong())
            writeElement(destination, lane, result)
            writeAccumulatorLane(lane, result.toLong() shl 16)
        }
        vco = 0
    }

    /** RSP VMULF: signed fractional multiply with the architectural rounding bias. */
    fun multiplyFractional(destination: Int, left: Int, right: Int, rightElement: Int? = null) {
        validateRegister(destination)
        validateRegister(left)
        validateRegister(right)
        for (lane in 0 until VECTOR_LANES) {
            val product = readSignedElement(left, lane).toLong() *
                readSignedElement(right, rightElement ?: lane).toLong()
            val accumulated = (product shl 1) + 0x8000L
            writeAccumulatorLane(lane, accumulated)
            writeElement(destination, lane, saturateSigned16(accumulated shr 16))
        }
        vco = 0
    }


    /** Bitwise vector operations preserve all 16 result bits and do not alter flags. */
    fun bitwiseAnd(destination: Int, left: Int, right: Int, rightElement: Int? = null) =
        bitwise(destination, left, right, rightElement) { a, b -> a and b }

    fun bitwiseOr(destination: Int, left: Int, right: Int, rightElement: Int? = null) =
        bitwise(destination, left, right, rightElement) { a, b -> a or b }

    fun bitwiseXor(destination: Int, left: Int, right: Int, rightElement: Int? = null) =
        bitwise(destination, left, right, rightElement) { a, b -> a xor b }

    fun bitwiseNor(destination: Int, left: Int, right: Int, rightElement: Int? = null) =
        bitwise(destination, left, right, rightElement) { a, b -> (a or b).inv() }

    private inline fun bitwise(
        destination: Int,
        left: Int,
        right: Int,
        rightElement: Int?,
        operation: (Int, Int) -> Int
    ) {
        validateRegister(destination)
        validateRegister(left)
        validateRegister(right)
        if (rightElement != null) validateElement(rightElement)
        for (lane in 0 until VECTOR_LANES) {
            val rhs = readElement(right, rightElement ?: lane)
            writeElement(destination, lane, operation(readElement(left, lane), rhs) and 0xFFFF)
        }
    }

    fun setFlags(vco: Int, vcc: Int, vce: Int) {
        require(vco in 0..0xFFFF && vcc in 0..0xFFFF && vce in 0..0xFF)
        this.vco = vco
        this.vcc = vcc
        this.vce = vce
    }

    fun clearFlags() {
        vco = 0
        vcc = 0
        vce = 0
    }

    fun copyRegister(source: Int, destination: Int) {
        validateRegister(source)
        validateRegister(destination)
        registers[source].copyInto(registers[destination])
    }

    private fun saturateSigned16(value: Long): Int =
        value.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()

    private fun validateRegister(register: Int) {
        require(register in 0 until VECTOR_REGISTER_COUNT) { "Vector register must be in 0..31" }
    }

    private fun validateElement(element: Int) {
        require(element in 0 until VECTOR_LANES) { "Vector element must be in 0..7" }
    }

    companion object {
        const val VECTOR_REGISTER_COUNT = 32
        const val VECTOR_LANES = 8
        private const val ACCUMULATOR_MASK = 0xFFFF_FFFF_FFFFL
        private const val ACCUMULATOR_SIGN_BIT = 0x8000_0000_0000L
        private const val ACCUMULATOR_SIGN_EXTENSION = -0x1_0000_0000_0000L
    }
}
