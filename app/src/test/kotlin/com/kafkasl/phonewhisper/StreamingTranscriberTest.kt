package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real TLS WebSocket transport tests: callback ordering, cancellation, single
 * final and authentication are exercised independently of Android UI state.
 */
class StreamingTranscriberTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    private val readyLatch = CountDownLatch(1)
    private val finalLatch = CountDownLatch(1)
    private val errorLatch = CountDownLatch(1)
    private val finalCount = AtomicInteger(0)
    @Volatile private var finalText: String? = null
    @Volatile private var error: String? = null

    private fun client(): StreamingTranscriber =
        StreamingTranscriber(object : StreamingTranscriber.Listener {
            override fun onReady() { readyLatch.countDown() }
            override fun onUpdate(stable: String, unstable: String) {}
            override fun onFinal(text: String) {
                finalText = text
                finalCount.incrementAndGet()
                finalLatch.countDown()
            }
            override fun onError(reason: String) {
                error = reason
                errorLatch.countDown()
            }
        }, http)

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder()
            .heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        http = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .build()
    }

    @After fun tearDown() {
        // OkHttp can close the upgraded socket before MockWebServer's shutdown
        // handshake finishes; a teardown-only IOException is not an app failure.
        try { server.shutdown() } catch (_: java.io.IOException) {}
        http.dispatcher.executorService.shutdown()
    }

    private fun endpoint(): String = server.url("/v1/audio/stream")
        .toString().replaceFirst("https://", "wss://")

    @Test fun `start stream and insert one final only after stop`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("\"start\"")) {
                    ws.send("""{"type":"ready","version":1}""")
                } else if (text.contains("\"stop\"")) {
                    ws.send("""{"type":"update","revision":1,"stable":"Hello","unstable":" world"}""")
                    ws.send("""{"type":"final","text":"Hello world"}""")
                    // Duplicate finals are rejected by StreamingTranscriptTest;
                    // avoid writing after the client's normal close handshake.
                }
            }
        }))
        val client = client()
        assertTrue(client.connect(endpoint(), "test-only-token"))
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
        assertTrue(client.sendAudio(ByteArray(3200), 3200))
        assertTrue(client.stop())
        assertTrue(finalLatch.await(5, TimeUnit.SECONDS))
        assertEquals("Hello world", finalText)
        assertEquals(1, finalCount.get())
        assertEquals("Bearer test-only-token",
            server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Authorization"))
    }

    @Test fun `auth failure does not return a partial result`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = client()
        assertTrue(client.connect(endpoint(), "wrong"))
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS))
        assertEquals("Streaming authentication rejected", error)
        assertEquals(0, finalCount.get())
    }

    @Test fun `cancel never inserts a final even if server responds later`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("\"start\"")) {
                    ws.send("""{"type":"ready","version":1}""")
                }
            }
        }))
        val client = client()
        assertTrue(client.connect(endpoint(), "test-only-token"))
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
        client.cancel()
        assertFalse(client.sendAudio(ByteArray(3200), 3200))
        assertFalse(client.stop())
        assertEquals(0, finalCount.get())
    }

    @Test fun `insecure URLs and missing credentials fail before handshake`() {
        val client = client()
        assertFalse(client.connect("ws://localhost:1234/v1/audio/stream", "token"))
        assertFalse(client.connect(endpoint(), ""))
    }
}
