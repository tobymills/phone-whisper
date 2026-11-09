package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One non-resumable v1 streaming session. Callbacks run on OkHttp threads; callers must
 * dispatch to the main thread before touching Android views or Accessibility state.
 * No credential, transcript or audio logging.
 */
class StreamingTranscriber(
    private val listener: Listener,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
) {
    interface Listener {
        fun onReady()
        fun onUpdate(stable: String, unstable: String)
        fun onFinal(text: String)
        fun onError(reason: String)
    }

    private val terminal = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val transcript = StreamingTranscript()
    private var socket: WebSocket? = null

    companion object {
        private const val MAX_QUEUED_AUDIO_BYTES = 2L * 1024 * 1024
    }

    fun connect(url: String, token: String): Boolean {
        if (!url.startsWith("wss://", ignoreCase = true) || token.isBlank()) {
            return false
        }
        val request = try {
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        } catch (_: IllegalArgumentException) {
            return false
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ok = webSocket.send(
                    """{"type":"start","version":1,"sample_rate":16000,"channels":1,"encoding":"pcm_s16le"}"""
                )
                if (!ok) fail("Could not start the streaming session")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (terminal.get()) return
                val message = try {
                    transcript.receive(text)
                } catch (_: Exception) {
                    fail("Invalid streaming server response")
                    return
                }
                // A listener exception is a client fault, not malformed server JSON.
                try {
                    when (message) {
                        is StreamingTranscript.Event.Ready -> {
                            if (ready.compareAndSet(false, true)) listener.onReady()
                        }
                        is StreamingTranscript.Event.Update ->
                            listener.onUpdate(message.stable, message.unstable)
                        is StreamingTranscript.Event.Final -> {
                            if (terminal.compareAndSet(false, true)) {
                                listener.onFinal(message.text)
                                webSocket.close(1000, "final received")
                            }
                        }
                        is StreamingTranscript.Event.Error -> fail(message.code)
                    }
                } catch (_: Exception) {
                    fail("Streaming client callback failed")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(when (response?.code) {
                    401 -> "Streaming authentication rejected"
                    403, 429, 503 -> "Streaming session busy or refused"
                    else -> "Streaming connection failed"
                })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminal.get()) fail("Streaming connection closed before final transcript")
            }
        })
        return true
    }

    fun sendAudio(frame: ByteArray, size: Int): Boolean {
        if (!ready.get() || stopped.get() || terminal.get() || size <= 0) return false
        val current = socket ?: return false
        if (current.queueSize() + size > MAX_QUEUED_AUDIO_BYTES) return false
        return current.send(frame.copyOf(size).toByteString())
    }

    fun stop(): Boolean {
        if (!ready.get() || terminal.get() || !stopped.compareAndSet(false, true))
            return false
        return socket?.send("""{"type":"stop"}""") == true
    }

    fun cancel() {
        if (terminal.compareAndSet(false, true)) {
            socket?.send("""{"type":"cancel"}""")
            socket?.close(1000, "cancelled")
        }
    }

    private fun fail(reason: String) {
        if (terminal.compareAndSet(false, true)) {
            socket?.cancel()
            listener.onError(reason)
        }
    }
}

/** Pure parser/state gate; no access to Android or networks. */
class StreamingTranscript {
    sealed interface Event {
        data object Ready : Event
        data class Update(val stable: String, val unstable: String) : Event
        data class Final(val text: String) : Event
        data class Error(val code: String) : Event
    }

    private var started = false
    private var finished = false
    private var revision = 0
    private var stable = ""

    fun receive(json: String): Event {
        require(json.length <= 65536) { "Oversized transcript event" }
        val msg = JSONObject(json)
        return when (msg.getString("type")) {
            "ready" -> {
                require(!started && !finished && msg.getInt("version") == 1)
                started = true
                Event.Ready
            }
            "update" -> {
                require(started && !finished)
                val nextRevision = msg.getInt("revision")
                val nextStable = msg.getString("stable")
                val unstable = msg.getString("unstable")
                require(nextRevision > revision && nextStable.startsWith(stable))
                revision = nextRevision
                stable = nextStable
                Event.Update(stable, unstable)
            }
            "final" -> {
                require(started && !finished)
                val value = msg.getString("text")
                // Empty is a valid no-speech final, not a retryable network error.
                finished = true
                Event.Final(value)
            }
            "error" -> Event.Error(msg.optString("code", "streaming_error"))
            else -> error("Unknown streaming event")
        }
    }
}
