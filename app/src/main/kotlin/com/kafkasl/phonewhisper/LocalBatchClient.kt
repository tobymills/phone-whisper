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
        if (!url.startsWith("https://", ignoreCase = true) || token.isBlank()) {
            callback(TranscriberClient.Result(null, "Set the local HTTPS endpoint and token"))
            return
        }
        if (wav.size > MAX_WAV_BYTES) {
            callback(TranscriberClient.Result(null, "Recording exceeds local batch limit"))
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
            callback(TranscriberClient.Result(null, "Invalid local batch URL"))
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(TranscriberClient.Result(null, "Local batch connection failed"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(TranscriberClient.Result(null,
                            "Local batch HTTP ${it.code}"))
                    } else {
                        callback(TranscriberClient.parseResponse(
                            it.body?.string().orEmpty()
                        ))
                    }
                }
            }
        })
    }
}
