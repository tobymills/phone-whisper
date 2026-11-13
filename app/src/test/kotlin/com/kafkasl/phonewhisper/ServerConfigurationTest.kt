package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class ServerConfigurationTest {
    @Test fun genericEndpointsAcceptAnySecureServer() {
        assertTrue(AiosBatchFallbackPolicy.configured("https://speech.example.org/v1/audio/transcriptions", "token"))
        assertTrue(AiosBatchFallbackPolicy.configured("https://other.example.org/v1/text/clean", "token"))
        assertFalse(AiosBatchFallbackPolicy.configured("http://other.example.org/v1/text/clean", "token"))
    }
}
