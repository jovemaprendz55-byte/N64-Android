package com.jsr.n64android

/**
 * RSP vector register file and 48-bit accumulator storage.
 *
 * Each vector register contains eight 16-bit elements. Element index zero
 * addresses the most-significant halfword, matching the RSP big-endian view.
 * This class only models storage and lane access; vector opcodes are separate.
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

    fun writeAccumulatorLane(lane: Int, value: Long) {
        validateElement(lane)
        accumulator[lane] = value and ACCUMULATOR_MASK
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
    }
}
