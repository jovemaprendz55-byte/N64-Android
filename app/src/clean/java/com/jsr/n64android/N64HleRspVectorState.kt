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
