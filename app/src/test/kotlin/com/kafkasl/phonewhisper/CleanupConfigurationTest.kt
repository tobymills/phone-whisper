package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class CleanupConfigurationTest {
    @Test fun secureCleanupEndpointValidation() {
        assertTrue(AiosBatchFallbackPolicy.configured("https://speech.example.org/v1/text/clean", "test-token"))
        assertFalse(AiosBatchFallbackPolicy.configured("http://speech.example.org/v1/text/clean", "test-token"))
        assertFalse(AiosBatchFallbackPolicy.configured("https://speech.example.org/v1/text/clean", ""))
    }
}
