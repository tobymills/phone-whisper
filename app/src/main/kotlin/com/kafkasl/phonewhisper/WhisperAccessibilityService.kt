package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.text.TextUtils
import java.util.UUID
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, CONNECTING, RECORDING, TRANSCRIBING }

    @Volatile private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var docked = false
    private var diagnosticId = ""
    @Volatile private var fallbackNotified = false
    private var transcriptSource = "legacy"
    private val dockIdle = Runnable { dockAtEdge() }
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val pcmLock = Any()
    @Volatile private var streamReady = false
    private var cancelArmedUntil = 0L
    private val handshakeTimeout = Runnable {
        if (state == State.RECORDING && recordingTransport == "streaming"
            && !streamReady && !streamingFailed) {
            streamingFailed = true
            streamingSession?.cancel()
            showFeedback("AIOS connection timed out — local batch on stop", 4000)
        }
    }
    private var recordingThread: Thread? = null
    private var streamingSession: StreamingTranscriber? = null
    @Volatile private var streamingFailed = false
    private var recordingTransport = "legacy"
    private var sessionId = 0
    private var finalInserted = false
    private var pendingPcm: ByteArray? = null
    private var startingFocus: FocusTarget? = null
    private val finalTimeout = Runnable {
        if (state == State.TRANSCRIBING && recordingTransport == "streaming") {
            streamingSession?.cancel()
            fallBackToLocalBatch("Streaming finalisation timed out")
        }
    }

    private data class FocusTarget(
        val packageName: String, val windowId: Int, val viewId: String?,
        val className: String, val bounds: android.graphics.Rect,
        val isPassword: Boolean
    )
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Local transcription engine (loaded lazily)
    private var localTranscriber: LocalTranscriber? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        updateOverlayVisibility()
        // Try to load local model in background
        thread { initLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Focus and window changes both affect whether an editor is active.
        // These events are declared in accessibility_service_config.xml.
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> updateOverlayVisibility()
        }
    }

    /** Show only when an editable, non-password input owns focus, unless overridden. */
    fun updateOverlayVisibility() {
        handler.post {
            val shouldShow = state != State.IDLE ||
                prefs().getString("overlay_visibility", "focused") == "always" ||
                hasEditableFocus()
            overlayView?.visibility = if (shouldShow) View.VISIBLE else View.GONE
            if (shouldShow && state == State.IDLE) scheduleIdleDock()
            else handler.removeCallbacks(dockIdle)
        }
    }

    private fun hasEditableFocus(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return try {
            focused?.isEditable == true && focused.isFocused && !focused.isPassword
        } finally {
            focused?.recycle()
        }
    }

    /** Recess only an idle button; keep a usable edge handle for a tap or drag. */
    private fun dockAtEdge() {
        if (state != State.IDLE || !prefs().getBoolean("overlay_recess", true) ||
            overlayView?.visibility != View.VISIBLE || docked) return
        val params = layoutParams ?: return
        val view = overlayView ?: return
        val ringSize = (RING_DP * dp).toInt()
        val visible = (28 * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        val rightSide = params.x + ringSize / 2 > screenW / 2
        params.x = if (rightSide) screenW - visible else -(ringSize - visible)
        params.y = params.y.coerceIn(margin, (screenH - ringSize - margin).coerceAtLeast(margin))
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        docked = true
    }

    private fun undock() {
        if (!docked) return
        val params = layoutParams ?: return
        val view = overlayView ?: return
        val ringSize = (RING_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        params.x = if (params.x + ringSize / 2 >= screenW / 2)
            screenW - ringSize - margin else margin
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        docked = false
    }

    private fun scheduleIdleDock() {
        handler.removeCallbacks(dockIdle)
        if (state == State.IDLE && prefs().getBoolean("overlay_recess", true))
            handler.postDelayed(dockIdle, 2500)
        else undock()
    }

    private fun recordStage(stage: String, source: String = recordingTransport) {
        if (diagnosticId.isNotBlank()) SessionDiagnostics.record(this, diagnosticId, stage, source)
    }
    override fun onInterrupt() { handler.post { if (state != State.IDLE) cancelDictation() } }

    override fun onDestroy() {
        ++sessionId
        streamingSession?.cancel()
        streamingSession = null
        handler.removeCallbacks(finalTimeout)
        handler.removeCallbacks(handshakeTimeout)
        handler.removeCallbacks(dockIdle)
        if (state != State.IDLE) recordStage("cancelled",
            if (recordingTransport == "local_batch_fallback") "batch_fallback" else recordingTransport)
        state = State.IDLE
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    private fun initLocalModel() {
        val modelName = prefs().getString("model_name", "") ?: ""
        if (modelName.isBlank()) {
            // Auto-detect first available model
            val models = LocalTranscriber.availableModels(this)
            if (models.isNotEmpty()) {
                Log.i(TAG, "Auto-detected model: ${models.first()}")
                localTranscriber = LocalTranscriber.create(this, models.first())
            }
        } else {
            localTranscriber = LocalTranscriber.create(this, modelName)
        }
        if (localTranscriber != null) {
            Log.i(TAG, "Local transcription ready")
        } else {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var touchDownAt = 0L

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(dockIdle)
                    undock()
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    touchDownAt = ev.eventTime
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        if (ev.eventTime - touchDownAt >= 1500
                            && (state == State.CONNECTING || state == State.RECORDING
                                || (state == State.TRANSCRIBING &&
                                    recordingTransport != "legacy"))) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now < cancelArmedUntil) {
                                cancelDictation()
                            } else {
                                cancelArmedUntil = now + 5000
                                showFeedback("Hold again to discard dictation", 4000)
                            }
                        } else {
                            cancelArmedUntil = 0L
                            onTap()
                        }
                    } else {
                        docked = false
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                        scheduleIdleDock()
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.START
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            screenW - margin * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        // Feedback is touch-through at the top, never adjacent to the stop button.
        feedbackParams.x = margin
        feedbackParams.y = (64 * dp).toInt()
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.CONNECTING -> {
                ++sessionId
                streamingSession?.cancel()
                streamingSession = null
                reset("Streaming cancelled")
            }
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    /** Long-press to cancel; no partial or final result is inserted. */
    private fun cancelDictation() {
        ++sessionId
        streamingFailed = true
        streamReady = false
        cancelArmedUntil = 0L
        handler.removeCallbacks(finalTimeout)
        handler.removeCallbacks(handshakeTimeout)
        streamingSession?.cancel()
        streamingSession = null
        recordStage("cancelled",
            if (recordingTransport == "local_batch_fallback") "batch_fallback" else recordingTransport)
        state = State.IDLE
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { recordingThread?.join(800) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
        pcmStream = null
        pendingPcm = null
        stopPulse()
        setBusy(false)
        setAppearance(COLOR_IDLE)
        showFeedback("Dictation cancelled", 1600)
        updateOverlayVisibility()
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }
        sessionId++
        diagnosticId = UUID.randomUUID().toString().replace("-", "")
        fallbackNotified = false
        finalInserted = false
        cancelArmedUntil = 0L
        startingFocus = captureFocus()
        if (startingFocus?.isPassword == true) {
            reset("Dictation into password fields is disabled")
            return
        }
        recordingTransport = prefs().getString("transcription_mode", "legacy") ?: "legacy"
        transcriptSource = recordingTransport
        recordStage("started")
        handler.removeCallbacks(dockIdle)
        undock()
        updateOverlayVisibility()
        if (recordingTransport == "streaming") startStreaming()
        else startCapture()
    }

    /** Surface one warning per session; sendAudio failure may be recoverable via batch. */
    private fun recordStreamFailure(message: String) {
        if (fallbackNotified) return
        fallbackNotified = true
        recordStage("stream_failed", "streaming")
        showFeedback(message, 3500)
    }

    /** Open one WSS session while buffering the microphone from the first tap. */
    private fun startStreaming() {
        val url = prefs().getString("stream_url", "wss://stt.millsho.me/v1/audio/stream")
            ?: ""
        val token = LocalSecrets.read(this)
        val current = sessionId
        streamingFailed = false
        streamReady = false
        state = State.CONNECTING
        setBusy(true)
        setAppearance(COLOR_BUSY)
        showFeedback("Connecting to AIOS", 3000)
        val stream = StreamingTranscriber(object : StreamingTranscriber.Listener {
            override fun onReady() {
                handler.post {
                    if (current != sessionId || state != State.RECORDING || streamingFailed)
                        return@post
                    handler.removeCallbacks(handshakeTimeout)
                    // Sending the buffered pre-roll under the same lock used by
                    // AudioRecord preserves sample order without truncating speech.
                    synchronized(pcmLock) {
                        val buffered = pcmStream?.toByteArray() ?: ByteArray(0)
                        // The server limits burst ingress. A handshake longer
                        // than two seconds cannot replay an unbounded queue:
                        // preserve the whole recording for local batch instead.
                        if (buffered.size > SAMPLE_RATE * 2 * 2) {
                            streamingFailed = true
                        }
                        var offset = 0
                        while (offset < buffered.size && !streamingFailed) {
                            val length = minOf(8192, buffered.size - offset)
                            if (streamingSession?.sendAudio(buffered.copyOfRange(offset, offset + length), length) != true) {
                                streamingFailed = true
                            }
                            offset += length
                        }
                        if (!streamingFailed) streamReady = true
                    }
                    if (streamingFailed) {
                        recordStreamFailure("Streaming interrupted — local batch on stop")
                    } else {
                        recordStage("connected")
                        showFeedback("AIOS streaming connected", 1200)
                    }
                }
            }

            override fun onUpdate(stable: String, unstable: String) {
                handler.post {
                    if (current == sessionId && state == State.RECORDING) {
                        val displayed = (stable + " " + unstable).trim().takeLast(150)
                        if (displayed.isNotBlank()) showFeedback(displayed, 3000)
                    }
                }
            }

            override fun onFinal(text: String) {
                handler.post {
                    if (current != sessionId || state != State.TRANSCRIBING
                        || finalInserted) return@post
                    finalInserted = true
                    handler.removeCallbacks(finalTimeout)
                    transcriptSource = "streaming"
                    recordStage("stream_success")
                    if (text.isBlank()) showFeedback("No speech detected", 2000)
                    else injectTextToOriginalFocus(text)
                    completeSession()
                }
            }

            override fun onError(reason: String) {
                handler.post {
                    if (current != sessionId) return@post
                    when (state) {
                        State.RECORDING -> {
                            streamingFailed = true
                            recordStreamFailure("Stream lost — local batch on stop")
                        }
                        State.CONNECTING -> reset(reason)
                        State.TRANSCRIBING -> {
                            handler.removeCallbacks(finalTimeout)
                            fallBackToLocalBatch(reason)
                        }
                        else -> {}
                    }
                }
            }
        }, sessionId = diagnosticId)
        streamingSession = stream
        if (!stream.connect(url, token)) {
            streamingSession = null
            reset("Set a secure WSS endpoint and AIOS token")
            return
        }
        // Capture begins immediately; audio recorded before 'ready' is queued
        // for ordered delivery to prevent clipping the opening words.
        startCapture()
        if (state == State.RECORDING) handler.postDelayed(handshakeTimeout, 8000)
    }

    /** Record chronological PCM16 frames; send them only after ordered pre-roll flush. */
    private fun startCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            streamingSession?.cancel()
            reset("Microphone permission unavailable")
            return
        }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) {
            streamingSession?.cancel()
            reset("Microphone format unavailable")
            return
        }
        val bufSize = maxOf(minimum, 4096).let { if (it % 2 == 0) it else it + 1 }
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) {
            streamingSession?.cancel()
            reset("Microphone permission revoked")
            return
        } catch (_: IllegalArgumentException) {
            streamingSession?.cancel()
            reset("Microphone initialisation failed")
            return
        }

        pcmStream = ByteArrayOutputStream()
        try {
            audioRecord!!.startRecording()
        } catch (_: Exception) {
            audioRecord?.release()
            audioRecord = null
            reset("Could not start microphone")
            return
        }
        state = State.RECORDING
        recordStage("capturing")
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()

        recordingThread = thread {
            val buf = ByteArray(minOf(bufSize, 8192))
            while (state == State.RECORDING) {
                val n = try { audioRecord?.read(buf, 0, buf.size) ?: break }
                        catch (_: Exception) { break }
                if (n > 0) {
                    // Capture and any pre-roll flush share a monitor, so frames
                    // reach the server in sample order with no duplicate send.
                    val accepted = synchronized(pcmLock) {
                        val output = pcmStream ?: return@synchronized false
                        if (output.size() + n > 16000 * 2 * 600) {
                            return@synchronized false
                        }
                        output.write(buf, 0, n)
                        if (recordingTransport == "streaming" && streamReady &&
                            !streamingFailed && streamingSession?.sendAudio(buf, n) != true) {
                            streamingFailed = true
                        }
                        true
                    }
                    if (!accepted) {
                        handler.post { if (state == State.RECORDING) stopAndTranscribe() }
                        break
                    }
                    if (streamingFailed && !fallbackNotified &&
                        recordingTransport == "streaming") {
                        handler.post {
                            recordStreamFailure("Streaming interrupted — local batch on stop")
                        }
                    }
                } else if (n < 0) {
                    streamingFailed = true
                    handler.post { if (state == State.RECORDING) stopAndTranscribe() }
                    break
                }
            }
        }
    }

    /** Stop and join capture before sending stop or the complete local batch fallback. */
    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        recordStage("finalising")
        stopPulse()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { recordingThread?.join(1500) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (recordingThread?.isAlive == true) {
            streamingSession?.cancel()
            audioRecord?.release()
            audioRecord = null
            reset("Microphone capture did not stop safely")
            return
        }
        audioRecord?.release()
        audioRecord = null
        recordingThread = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }
        handler.removeCallbacks(handshakeTimeout)
        if (recordingTransport == "streaming") {
            pendingPcm = pcm
            if (streamingFailed || !streamReady || streamingSession?.stop() != true) {
                fallBackToLocalBatch("Streaming interrupted")
            } else {
                handler.postDelayed(finalTimeout, 150000)
                showFeedback("Finalising on AIOS", 3000)
            }
            return
        }
        if (recordingTransport == "local_batch") {
            sendLocalBatch(pcm)
            return
        }

        val useLocal = prefs().getBoolean("use_local", true)
        val local = localTranscriber

        if (useLocal && local != null) {
            transcribeLocal(pcm, local)
        } else {
            transcribeApi(pcm)
        }
    }

    /** Send complete retained PCM to the configured AIOS HTTPS batch endpoint only. */
    private fun sendLocalBatch(pcm: ByteArray) {
        val url = prefs().getString("batch_url",
            "https://stt.millsho.me/v1/audio/transcriptions") ?: ""
        val token = LocalSecrets.read(this)
        val current = sessionId
        LocalBatchClient.transcribe(WavWriter.encode(pcm), url, token) { result ->
            handler.post {
                if (current != sessionId || state != State.TRANSCRIBING
                    || finalInserted) return@post
                if (!result.text.isNullOrBlank()) {
                    finalInserted = true
                    transcriptSource = if (recordingTransport == "local_batch_fallback")
                        "batch_fallback" else "local_batch"
                    recordStage("batch_success", transcriptSource)
                    injectTextToOriginalFocus(result.text)
                    completeSession()
                } else {
                    recordStage("batch_failed",
                        if (recordingTransport == "local_batch_fallback") "batch_fallback" else "local_batch")
                    reset(result.error ?: "Local batch transcription failed")
                }
            }
        }
    }

    /** Make one deliberate fallback attempt; never paste a partial stream result. */
    private fun fallBackToLocalBatch(reason: String) {
        handler.removeCallbacks(handshakeTimeout)
        streamingSession?.cancel()
        streamingSession = null
        handler.removeCallbacks(finalTimeout)
        if (recordingTransport == "local_batch_fallback") return
        recordingTransport = "local_batch_fallback"
        recordStage("batch_fallback", "batch_fallback")
        val pcm = pendingPcm
        if (pcm == null) {
            reset(reason)
            return
        }
        showFeedback("Using local batch fallback", 3000)
        sendLocalBatch(pcm)
    }

    /** Release session buffers and timers after one final success or no-speech result. */
    private fun completeSession() {
        handler.removeCallbacks(finalTimeout)
        handler.removeCallbacks(handshakeTimeout)
        streamReady = false
        streamingSession?.cancel()
        streamingSession = null
        pendingPcm = null
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
        updateOverlayVisibility()
    }

    /** Snapshot a focused editor's identity without retaining stale accessibility nodes. */
    private fun captureFocus(): FocusTarget? {
        val nodes = findInjectionCandidates()
        try {
            val node = nodes.firstOrNull { it.isFocused && it.isEditable }
                ?: return null
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            return FocusTarget(
                node.packageName?.toString().orEmpty(),
                node.windowId,
                node.viewIdResourceName,
                node.className?.toString().orEmpty(),
                rect,
                node.isPassword
            )
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    /**
     * Paste once only into the original focused editable node. Identity is
     * conservative: if a view ID is unavailable, require matching class and
     * screen bounds; otherwise leave the transcript on the sensitive clipboard.
     */
    private fun injectTextToOriginalFocus(text: String) {
        val target = startingFocus
        setSensitiveClipboard(text)
        val nodes = findInjectionCandidates()
        var injected = false
        try {
            if (target != null) {
                for (node in nodes) {
                    if (node.isFocused && node.isEditable
                        && node.packageName?.toString() == target.packageName
                        && node.windowId == target.windowId
                        && !node.isPassword
                        && (if (target.viewId != null)
                            node.viewIdResourceName == target.viewId
                        else {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            node.className?.toString().orEmpty() == target.className &&
                                bounds == target.bounds
                        })
                    ) {
                        if (tryInjectIntoNode(node, text)) {
                            injected = true
                            break
                        }
                    }
                }
            }
        } finally {
            nodes.forEach { it.recycle() }
        }
        recordStage(if (injected) "injected" else "clipboard", transcriptSource)
        showFeedback(if (injected) "Inserted" else "Focus changed — copied to clipboard", 3000)
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber) {
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")

                handleTranscriptionResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                handler.post {
                    toast("Local error: ${e.message}")
                    recordStage("failed", "legacy")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                    updateOverlayVisibility()
                }
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) { reset("Set API key in Phone Whisper app"); return }

        TranscriberClient.transcribe(wav, apiKey) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text)
            } else {
                handler.post {
                    toast("Error: ${result.error ?: "empty transcript"}")
                    recordStage("failed", "legacy")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                    updateOverlayVisibility()
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?) {
        if (text.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                recordStage("failed", "legacy")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
                updateOverlayVisibility()
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = prefs().getString("api_key", "") ?: ""

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    recordStage("failed", "legacy")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                    updateOverlayVisibility()
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            
            PostProcessor.process(text, prompt, apiKey) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        injectText(result.text)
                    } else {
                        injectText(text, feedback = "Cleanup failed — raw copied to clipboard", feedbackDurationMs = 3000)
                    }
                    recordStage("failed", "legacy")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                    updateOverlayVisibility()
                }
            }
        } else {
            handler.post {
                injectText(text)
                recordStage("legacy_success", "legacy")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
                updateOverlayVisibility()
            }
        }
    }

    /** Report a terminal local error; future callbacks are ignored by session ID. */
    private fun reset(msg: String) {
        handler.removeCallbacks(finalTimeout)
        handler.removeCallbacks(handshakeTimeout)
        streamReady = false
        stopPulse()
        streamingSession?.cancel()
        streamingSession = null
        pendingPcm = null
        toast(msg)
        recordStage("failed", if (recordingTransport == "local_batch_fallback")
            "batch_fallback" else recordingTransport)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
        updateOverlayVisibility()
    }

    /**
     * Preserve the existing clipboard fallback but hide transcript previews
     * from Android's clipboard UI on API 33+. The clipboard remains a shared
     * OS facility; this is not equivalent to clearing or encrypting its data.
     */
    private fun setSensitiveClipboard(value: String) {
        val clip = ClipData.newPlainText("phonewhisper", value)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(clip)
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        setSensitiveClipboard(text)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.isPassword) return false
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
