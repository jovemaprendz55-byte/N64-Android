package com.jsr.n64android

/**
 * UI-independent output of one bounded HLE diagnostic run.
 *
 * The frame contains decoded pixels only; Android Bitmap creation and view
 * presentation remain the responsibility of the Activity on the main thread.
 */
data class N64HleDiagnosticResult(
    val reportLines: List<String>,
    val videoFrame: N64VideoFrame?,
    val executedInstructions: Int,
    val stopReason: String,
    val failureMessage: String? = null
) {
    init {
        require(executedInstructions >= 0) { "Instruction count cannot be negative." }
        require(stopReason.isNotBlank()) { "A diagnostic stop reason is required." }
    }
}
