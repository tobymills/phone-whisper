package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class StreamingTranscriptTest {
    @Test fun `ready update and final`() {
        val state = StreamingTranscript()
        assertEquals(StreamingTranscript.Event.Ready,
            state.receive("""{"type":"ready","version":1}"""))
        val event = state.receive(
            """{"type":"update","revision":1,"stable":"Hello","unstable":" there"}"""
        ) as StreamingTranscript.Event.Update
        assertEquals("Hello", event.stable)
        assertEquals(" there", event.unstable)
        assertEquals(StreamingTranscript.Event.Final("Hello there"),
            state.receive("""{"type":"final","text":"Hello there"}"""))
    }

    @Test fun `rejects regressing confirmed prefix`() {
        val state = StreamingTranscript()
        state.receive("""{"type":"ready","version":1}""")
        state.receive("""{"type":"update","revision":1,"stable":"Hello","unstable":""}""")
        assertThrows(IllegalArgumentException::class.java) {
            state.receive("""{"type":"update","revision":2,"stable":"Hallo","unstable":""}""")
        }
    }

    @Test fun `rejects duplicate revision and final`() {
        val state = StreamingTranscript()
        state.receive("""{"type":"ready","version":1}""")
        state.receive("""{"type":"update","revision":1,"stable":"","unstable":"test"}""")
        assertThrows(IllegalArgumentException::class.java) {
            state.receive("""{"type":"update","revision":1,"stable":"","unstable":"x"}""")
        }
        state.receive("""{"type":"final","text":"test"}""")
        assertThrows(IllegalArgumentException::class.java) {
            state.receive("""{"type":"final","text":"test"}""")
        }
    }

    @Test fun `rejects empty final and update before ready`() {
        val state = StreamingTranscript()
        assertThrows(IllegalArgumentException::class.java) {
            state.receive("""{"type":"update","revision":1,"stable":"","unstable":"x"}""")
        }
        state.receive("""{"type":"ready","version":1}""")
        assertThrows(IllegalArgumentException::class.java) {
            state.receive("""{"type":"final","text":""}""")
        }
    }
}
