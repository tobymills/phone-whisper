package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Authenticated text-only cleanup; STT is performed exactly once beforehand. */
internal object LocalCleanupClient {
    data class Result(val text: String?, val usedRaw: Boolean)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(190, TimeUnit.SECONDS)
        .build()
    // The HTTPS cleanup endpoint is configured by the user.
    private const val MAX_CHARS = 100_000

    fun clean(raw: String, url: String, token: String, callback: (Result) -> Unit) {
        if (raw.isBlank() || raw.length > MAX_CHARS ||
            !AiosBatchFallbackPolicy.configured(url, token)) {
            callback(Result(null, true))
            return
        }
        val body = JSONObject().put("text", raw).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result(null, true))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result(null, true))
                        return
                    }
                    val cleaned = try {
                        JSONObject(it.body?.string().orEmpty())
                            .optString("text").takeIf { value -> value.isNotBlank() }
                    } catch (_: Exception) { null }
                    // Compatible servers need not send the private cleanup status header.
                    // Honour explicit raw/fallback status when supplied.
                    val status = it.header("X-STT-Cleanup")
                    val fallback = cleaned == null ||
                        (status != null && status != "local")
                    callback(Result(cleaned, fallback))
                }
            }
        })
    }
}
