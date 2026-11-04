package com.jsr.n64android

/**
 * Explicit address-to-handler table for verified guest HLE entry points.
 *
 * This class intentionally contains no built-in ROM addresses. A caller must
 * register each function using an address resolved from a trusted symbol/map
 * source for the exact game revision being executed.
 */
class N64HleGuestCallDispatcher(
    private val cpu: R4300Cpu
) {
    fun interface Handler {
        /** Returns modeled CPU cycles consumed by this HLE call. */
        fun invoke(cpu: R4300Cpu): Int
    }

    data class Registration(
        val symbol: String,
        val guestAddress: Long,
        val evidence: String
    )

    private data class Entry(
        val registration: Registration,
        val handler: Handler
    )

    private val entriesByAddress = LinkedHashMap<Long, Entry>()
    private val addressesBySymbol = HashMap<String, Long>()

    fun register(symbol: String, guestAddress: Long, evidence: String, handler: Handler) {
        require(symbol.isNotBlank()) { "Guest call symbol cannot be blank." }
        require(evidence.isNotBlank()) { "A verified address evidence reference is required." }
        require(guestAddress and 3L == 0L) { "Guest call entry must be word-aligned." }
        val low = guestAddress.toInt()
        require(guestAddress == low.toLong() || guestAddress == (low.toLong() and 0xFFFFFFFFL)) {
            "Guest call entry must fit in a 32-bit address."
        }
        check(!entriesByAddress.containsKey(guestAddress)) {
            "Guest call address is already registered: 0x${guestAddress.toULong().toString(16)}"
        }
        check(!addressesBySymbol.containsKey(symbol)) {
            "Guest call symbol is already registered: $symbol"
        }
        val registration = Registration(symbol, guestAddress, evidence)
        entriesByAddress[guestAddress] = Entry(registration, handler)
        addressesBySymbol[symbol] = guestAddress
    }

    /** Installs the table as the CPU's pre-fetch boundary hook. */
    fun install() {
        cpu.setInstructionBoundaryHook(::dispatchAt)
    }

    fun uninstall() {
        cpu.setInstructionBoundaryHook(null)
    }

    /** Returns null when no verified call gate matches this exact guest PC. */
    fun dispatchAt(guestPc: Long): Int? {
        val entry = entriesByAddress[guestPc] ?: return null
        val cycles = entry.handler.invoke(cpu)
        require(cycles >= 0) {
            "HLE handler ${entry.registration.symbol} returned a negative cycle cost."
        }
        return cycles
    }

    fun registrationForAddress(guestAddress: Long): Registration? =
        entriesByAddress[guestAddress]?.registration

    fun addressForSymbol(symbol: String): Long? = addressesBySymbol[symbol]

    fun registrations(): List<Registration> = entriesByAddress.values.map { it.registration }
}
