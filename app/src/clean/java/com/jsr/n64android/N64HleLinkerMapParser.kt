package com.jsr.n64android

/**
 * Imports function symbols from a GNU ld-style linker map.
 *
 * Accepted rows must contain exactly an address followed by one symbol name.
 * Section rows, wildcards, assignments and other map syntax are ignored.
 * Every accepted address is still validated by N64HleVerifiedSymbolTable.
 *
 * This parser does not prove that a map belongs to a ROM. The caller must
 * supply a source label identifying the exact build/revision that produced it.
 */
class N64HleLinkerMapParser {
    data class ParseResult(
        val symbols: List<N64HleVerifiedSymbolTable.Symbol>,
        val ignoredLineCount: Int
    )

    fun parse(
        mapText: String,
        sourceEvidence: String,
        symbolTable: N64HleVerifiedSymbolTable
    ): ParseResult {
        require(sourceEvidence.isNotBlank()) { "Map source evidence is required" }

        val rows = mapText.lineSequence().mapIndexedNotNull { index, raw ->
            val match = SYMBOL_ROW.matchEntire(raw) ?: return@mapIndexedNotNull null
            val address = match.groupValues[1].toULong(16).toLong()
            val name = match.groupValues[2]
            Triple(index + 1, address, name)
        }.toList()

        // Validate the complete candidate set before mutating the caller's table.
        val candidateNames = HashSet<String>()
        val candidateAddresses = HashSet<Long>()
        rows.forEach { (lineNumber, address, name) ->
            require(candidateNames.add(name)) {
                "Duplicate symbol '$name' in linker map at line $lineNumber"
            }
            require(candidateAddresses.add(address)) {
                "Duplicate symbol address 0x${address.toULong().toString(16)} in linker map at line $lineNumber"
            }
            val probe = N64HleVerifiedSymbolTable()
            probe.register(name, address, "$sourceEvidence:line $lineNumber")
        }

        val registered = rows.map { (lineNumber, address, name) ->
            symbolTable.register(name, address, "$sourceEvidence:line $lineNumber")
        }
        return ParseResult(registered, mapText.lineSequence().count() - rows.size)
    }

    companion object {
        // GNU ld map output: one 32/64-bit hexadecimal address and one symbol.
        private val SYMBOL_ROW = Regex(
            """^\\s*(?:0x)?([0-9a-fA-F]{8}|[0-9a-fA-F]{16})\\s+([A-Za-z_.$][A-Za-z0-9_.$@]*)\\s*$"""
        )
    }
}
