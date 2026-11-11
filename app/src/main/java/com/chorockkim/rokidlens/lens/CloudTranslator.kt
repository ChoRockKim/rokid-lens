package com.chorockkim.rokidlens.lens

import com.chorockkim.rokidlens.BuildConfig
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reads and translates a whole photo in one go on the rokid-conversate server
 * ([BuildConfig.TRANSLATE_URL], Gemini). Far better than on-device ML Kit, but needs the
 * internet; any failure returns null and the caller falls back to ML Kit.
 *
 * The timeout is generous because the server may be asleep (Railway App Sleeping) and the first
 * request wakes it.
 */
class CloudTranslator(http: OkHttpClient, private val url: String = BuildConfig.TRANSLATE_URL) {
    private val client = http.newBuilder()
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    suspend fun translate(jpeg: ByteArray, target: String = LanguagePlan.KOREAN): List<LensPipeline.Item>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$url?target=$target")
                    .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
                    .build()
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    parse(r.body.string(), target)
                }
            }.getOrNull()
        }

    companion object {
        const val TIMEOUT_S = 25L

        /** The server's {"paragraphs": [{"source", "translated", "from"}]}; null if malformed. */
        fun parse(json: String, target: String = LanguagePlan.KOREAN): List<LensPipeline.Item>? = runCatching {
            val o: JsonObject = JsonParser.`object`().from(json)
            if (!o.containsKey("paragraphs")) return null
            val list = o.getArray("paragraphs") ?: return null
            list.filterIsInstance<JsonObject>().mapNotNull { p ->
                val source = p.getString("source", "").trim()
                val translated = p.getString("translated", "").trim()
                if (source.isEmpty() || translated.isEmpty()) return@mapNotNull null
                val from = p.getString("from", "").trim().lowercase().ifEmpty { null }
                val to = from?.let { LanguagePlan.target(it, target) } ?: target
                LensPipeline.Item(source, translated, from, to)
            }
        }.getOrNull()
    }
}
