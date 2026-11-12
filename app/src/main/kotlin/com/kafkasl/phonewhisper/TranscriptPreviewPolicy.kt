package com.kafkasl.phonewhisper

/** Preview only: never modifies recorded audio or the final transcript. */
internal object TranscriptPreviewPolicy {
    fun lastTwoSentences(stable: String, unstable: String): String {
        val text = listOf(stable.trim(), unstable.trim())
            .filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isBlank()) return ""
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
        return sentences.takeLast(2).joinToString(" ").takeLast(240)
    }
}
