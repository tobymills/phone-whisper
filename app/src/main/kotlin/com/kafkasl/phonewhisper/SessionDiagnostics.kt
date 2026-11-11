package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log

/**
 * Minimal app-private last-session diagnostics. Only transport state, session ID,
 * timings/counts and fixed outcome labels belong here. Never store speech, URLs,
 * clipboard content, credentials, accessibility node labels or free-form exceptions.
 *
 * Each event overwrites the previous summary: this is not a background recorder.
 */
object SessionDiagnostics {
    private const val TAG = "PhoneWhisperSession"
    private const val PREFS = "phonewhisper"
    const val KEY = "last_session_summary"

    @Synchronized
    fun record(context: Context, sessionId: String, stage: String, source: String) {
        val safeId = sessionId.take(32).filter { it in '0'..'9' || it in 'a'..'f' }
        val allowedStages = setOf(
            "started", "capturing", "connected", "streaming", "finalising",
            "stream_failed", "batch_fallback", "batch_success", "batch_failed",
            "stream_success", "legacy_success", "injected", "clipboard",
            "cancelled", "failed", "cleanup_success", "cleanup_failed",
            "cleanup_unavailable"
        )
        val allowedSources = setOf("streaming", "local_batch", "batch_fallback", "legacy")
        if (stage !in allowedStages || source !in allowedSources) return
        val summary = "$source · $stage · ${safeId.take(8)}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, summary).apply()
        Log.i(TAG, "session=$safeId source=$source stage=$stage")
    }
}
