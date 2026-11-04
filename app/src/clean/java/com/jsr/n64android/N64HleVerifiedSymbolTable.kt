package com.jsr.n64android

/**
 * Address table populated only from a verified symbol/map source.
 *
 * No built-in libultra or game addresses are assumed. A symbol can be
 * registered only with a non-empty evidence reference and an aligned KSEG0
 * code address. This table resolves names; it does not intercept CPU calls.
 */
class N64HleVerifiedSymbolTable {
    data class Symbol(
        val name: String,
        val address: Long,
        val evidence: String
    )

    private val byName = LinkedHashMap<String, Symbol>()
    private val byAddress = LinkedHashMap<Long, Symbol>()

    val size: Int get() = byName.size

    fun register(name: String, guestAddress: Long, evidence: String): Symbol {
        val normalized = normalizeAddress(guestAddress)
        require(name.isNotBlank()) { "Symbol name must not be blank" }
        require(evidence.isNotBlank()) { "A source/map evidence reference is required" }
        require(normalized in KSEG0_START..KSEG0_END) {
            "HLE function symbols must resolve to KSEG0"
        }
        require((normalized and 3L) == 0L) { "HLE function address must be word-aligned" }
        require(!byName.containsKey(name)) { "Symbol name is already registered: $name" }
        require(!byAddress.containsKey(normalized)) {
            "Guest address is already assigned to " + byAddress[normalized]?.name
        }

        val symbol = Symbol(name, normalized, evidence.trim())
        byName[name] = symbol
        byAddress[normalized] = symbol
        return symbol
    }

    fun findByName(name: String): Symbol? = byName[name]

    fun findByAddress(guestAddress: Long): Symbol? {
        val normalized = normalizeAddress(guestAddress)
        return byAddress[normalized]
    }

    fun snapshot(): List<Symbol> = byName.values.sortedBy { it.address }

    private fun normalizeAddress(address: Long): Long {
        val upper = address ushr 32
        require(upper == 0L || upper == 0xFFFF_FFFFL) {
            "Guest symbol address must be a 32-bit zero-extended or sign-extended value"
        }
        return address and 0xFFFF_FFFFL
    }

    companion object {
        private const val KSEG0_START = 0x8000_0000L
        private const val KSEG0_END = 0x9FFF_FFFFL
    }
}