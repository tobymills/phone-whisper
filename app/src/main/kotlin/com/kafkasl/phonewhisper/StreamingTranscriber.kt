package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.util.Log
import java.util.UUID
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One non-resumable v1 streaming session. Callbacks run on OkHttp threads; callers must
 * dispatch to the main thread before touching Android views or Accessibility state.
 * Logs lifecycle and protocol status only; never credentials, audio or transcript content.
 */
class StreamingTranscriber(
    private val listener: Listener,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
    val sessionId: String = UUID.randomUUID().toString().replace("-", "")
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
    private var bytesSent = 0L
    private var framesSent = 0L

    companion object {
        private const val TAG = "PhoneWhisperStream"
        // Plain JVM unit tests do not have a real android.util.Log implementation.
        private fun info(message: String) { runCatching { Log.i(TAG, message) } }
        private fun warn(message: String) { runCatching { Log.w(TAG, message) } }
        private const val MAX_QUEUED_AUDIO_BYTES = 2L * 1024 * 1024
    }

    fun connect(url: String, token: String): Boolean {
        if (!url.startsWith("wss://", ignoreCase = true) || token.isBlank()) {
            return false
        }
        val request = try {
            Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("X-Session-ID", sessionId)
                .build()
        } catch (_: IllegalArgumentException) {
            return false
        }
        info("session=$sessionId connecting")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                info("session=$sessionId socket_open")
                val ok = webSocket.send(
                    """{"type":"start","version":1,"sample_rate":16000,"channels":1,"encoding":"pcm_s16le"}"""
                )
                if (!ok) fail("Could not start the Server streaming session.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (terminal.get()) return
                val message = try {
                    transcript.receive(text)
                } catch (_: Exception) {
                    fail("Server streaming returned an invalid response.")
                    return
                }
                // A listener exception is a client fault, not malformed server JSON.
                try {
                    when (message) {
                        is StreamingTranscript.Event.Ready -> {
                            if (ready.compareAndSet(false, true)) {
                                info("session=$sessionId ready")
                                listener.onReady()
                            }
                        }
                        is StreamingTranscript.Event.Update ->
                            listener.onUpdate(message.stable, message.unstable)
                        is StreamingTranscript.Event.Final -> {
                            if (terminal.compareAndSet(false, true)) {
                                info("session=$sessionId final chars=${message.text.length} bytes=$bytesSent frames=$framesSent")
                                listener.onFinal(message.text)
                                webSocket.close(1000, "final received")
                            }
                        }
                        is StreamingTranscript.Event.Error -> fail(message.code)
                    }
                } catch (_: Exception) {
                    fail("The app could not process the streaming response.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                warn("session=$sessionId socket_failure http=${response?.code ?: 0} cause=${t.javaClass.simpleName}")
                fail(when (response?.code) {
                    401 -> "Server authentication failed. Check your access token."
                    403, 429, 503 -> "Server streaming is busy or unavailable."
                    else -> "Could not connect to Server streaming."
                })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                info("session=$sessionId closed code=$code")
                if (!terminal.get()) fail("Server streaming closed before transcription finished.")
            }
        })
        return true
    }

    fun sendAudio(frame: ByteArray, size: Int): Boolean {
        if (!ready.get() || stopped.get() || terminal.get() || size <= 0) return false
        val current = socket ?: return false
        if (current.queueSize() + size > MAX_QUEUED_AUDIO_BYTES) return false
        val sent = current.send(frame.copyOf(size).toByteString())
        if (sent) {
            bytesSent += size
            framesSent++
            if (framesSent == 1L) info("session=$sessionId first_audio_frame bytes=$size")
        }
        return sent
    }

    fun stop(): Boolean {
        if (!ready.get() || terminal.get() || !stopped.compareAndSet(false, true))
            return false
        info("session=$sessionId stop bytes=$bytesSent frames=$framesSent")
        return socket?.send("""{"type":"stop"}""") == true
    }

    fun cancel() {
        if (terminal.compareAndSet(false, true)) {
            info("session=$sessionId cancel")
            socket?.send("""{"type":"cancel"}""")
            socket?.close(1000, "cancelled")
        }
    }

    private fun fail(reason: String) {
        if (terminal.compareAndSet(false, true)) {
            warn("session=$sessionId failed reason=$reason bytes=$bytesSent frames=$framesSent")
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
            "error" -> {
                // Only fixed protocol identifiers may reach diagnostics or UI.
                // Untrusted server strings must never enter Logcat verbatim.
                val code = msg.optString("code", "streaming_error")
                Event.Error(if (code.matches(Regex("[a-z0-9_]{1,64}")))
                    code else "streaming_error")
            }
            else -> error("Unknown streaming event")
        }
    }
}
