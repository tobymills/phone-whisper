package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class AiosBatchFallbackPolicyTest {
    @Test fun onlyHttpsAndTokenCountAsConfigured() {
        val valid = "https://stt.example.test/v1/audio/transcriptions"
        assertTrue(AiosBatchFallbackPolicy.configured(valid, "token"))
        assertFalse(AiosBatchFallbackPolicy.configured(valid, ""))
        assertFalse(AiosBatchFallbackPolicy.configured("", "token"))
        assertFalse(AiosBatchFallbackPolicy.configured("https://", "token"))
        assertFalse(AiosBatchFallbackPolicy.configured("http://stt.example.test/endpoint", "token"))
        assertFalse(AiosBatchFallbackPolicy.configured("not-a-url", "token"))
    }

    @Test fun warningDoesNotPromiseUnavailableFallback() {
        assertTrue(AiosBatchFallbackPolicy.streamingInterrupted(true).contains("Will try AIOS batch"))
        assertTrue(AiosBatchFallbackPolicy.streamingInterrupted(false).contains("No AIOS batch backup"))
        assertTrue(AiosBatchFallbackPolicy.NO_BACKUP_ERROR.contains("Transcription failed"))
    }
}
