package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Local AIOS batch fallback. Never uses the legacy OpenAI URL or key.
 */
object LocalBatchClient {
    private const val MAX_WAV_BYTES = 50 * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun transcribe(
        wav: ByteArray, url: String, token: String,
        callback: (TranscriberClient.Result) -> Unit
    ) {
        if (!AiosBatchFallbackPolicy.configured(url, token)) {
            callback(TranscriberClient.Result(null, "Check the AIOS batch URL and access token in Settings."))
            return
        }
        if (wav.size > MAX_WAV_BYTES) {
            callback(TranscriberClient.Result(null, "Recording is too long for AIOS batch transcription."))
            return
        }
        val request = try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "large-v3-turbo-q5_0")
                .addFormDataPart("file", "recording.wav",
                    wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .post(body).build()
        } catch (_: IllegalArgumentException) {
            callback(TranscriberClient.Result(null, "Invalid AIOS batch URL. Check Settings."))
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(TranscriberClient.Result(null, "Could not connect to AIOS batch. Check your connection."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val explanation = when (it.code) {
                            401 -> "AIOS authentication failed. Check your access token."
                            403 -> "AIOS denied this transcription request."
                            408, 504 -> "AIOS batch transcription timed out."
                            413 -> "Recording is too large for the AIOS server."
                            429, 503 -> "AIOS batch is temporarily unavailable. Try again."
                            else -> "AIOS batch request failed (HTTP ${it.code})."
                        }
                        callback(TranscriberClient.Result(null, explanation))
                    } else {
                        val parsed = TranscriberClient.parseResponse(
                            it.body?.string().orEmpty()
                        )
                        callback(if (parsed.text.isNullOrBlank())
                            TranscriberClient.Result(null, "AIOS returned no usable transcript.")
                            else parsed)
                    }
                }
            }
        })
    }
}
