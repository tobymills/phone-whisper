package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptPreviewPolicyTest {
    @Test fun keepsPreviousAndCurrentSentence() {
        assertEquals(
            "Second sentence. Third sentence",
            TranscriptPreviewPolicy.lastTwoSentences("First sentence. Second sentence.", "Third sentence")
        )
    }

    @Test fun supportsIncrementalTextWithoutPunctuation() {
        assertEquals("Hello there", TranscriptPreviewPolicy.lastTwoSentences("Hello", "there"))
    }

    @Test fun keepsPreviewBounded() {
        assertEquals(240, TranscriptPreviewPolicy.lastTwoSentences("", "a".repeat(500)).length)
    }
}
