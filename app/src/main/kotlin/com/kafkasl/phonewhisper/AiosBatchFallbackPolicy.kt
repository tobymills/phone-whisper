package com.kafkasl.phonewhisper

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * server batch is an optional, network-based backup to live streaming.
 * Configured means only that the endpoint and credential are usable as settings;
 * it does not establish server availability or authentication success.
 */
internal object AiosBatchFallbackPolicy {
    fun configured(url: String, token: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && parsed.host.isNotBlank() && token.isNotBlank()
    }

    fun streamingInterrupted(connected: Boolean): String =
        if (connected) "Live transcription interrupted. Will try server batch when you stop."
        else "Live transcription interrupted. No server batch backup is configured."

    const val NO_BACKUP_ERROR =
        "Transcription failed. server streaming stopped and no server batch backup is configured. Check Settings."
}
