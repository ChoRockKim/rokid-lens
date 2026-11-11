package com.chorockkim.rokidlens.lens

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device translation with ML Kit, like Rokid Nexus' Lens (MlKitTranslationProvider), reduced
 * to what one app on the glasses needs: detect the language, keep up to [MAX_TRANSLATORS]
 * language pairs open, download a pair's model once (about 30 MB, over Wi-Fi or not) and reuse
 * earlier results for the same text, which live mode sees again on every frame.
 */
class LensTranslator(private val onStatus: (String?) -> Unit = {}) {
    data class Result(val source: String, val translated: String, val from: String, val to: String)

    private val identifier = LanguageIdentification.getClient()
    private val translators = LinkedHashMap<Pair<String, String>, Translator>(4, 0.75f, true)
    private val ready = mutableSetOf<Pair<String, String>>()
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, Result>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>) = size > MAX_CACHE
    }

    private val supported = TranslateLanguage.getAllLanguages().toSet()

    internal suspend fun translate(text: String, script: OcrScript): Result? {
        val source = text.replace(Regex("\\s+"), " ").trim()
        if (!LanguagePlan.worthTranslating(source)) return null
        synchronized(cache) { cache[source] }?.let { return it }
        val detected = runCatching { identifier.identifyLanguage(source).await() }.getOrNull()
        val from = LanguagePlan.source(detected, script) { it in supported }
        val to = LanguagePlan.target(from)
        val translator = translator(from, to) ?: return null
        val out = runCatching { translator.translate(source).await() }.getOrNull() ?: return null
        return Result(source, out, from, to).also { synchronized(cache) { cache[source] = it } }
    }

    private suspend fun translator(from: String, to: String): Translator? = lock.withLock {
        val key = from to to
        val t = translators.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }
        while (translators.size > MAX_TRANSLATORS) {
            val eldest = translators.entries.first()
            eldest.value.close()
            ready.remove(eldest.key)
            translators.remove(eldest.key)
        }
        if (key !in ready) {
            onStatus("번역 모델 받는 중 · ${label(from)}→${label(to)}")
            val ok = runCatching { t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }.isSuccess
            onStatus(if (ok) null else "번역 모델을 못 받았어요 · 인터넷 확인")
            if (!ok) return@withLock null
            ready += key
        }
        t
    }

    fun close() {
        identifier.close()
        translators.values.forEach { it.close() }
        translators.clear()
    }

    companion object {
        const val MAX_TRANSLATORS = 3
        const val MAX_CACHE = 300

        fun label(lang: String): String = when (lang) {
            "ko" -> "한국어"
            "en" -> "영어"
            "ja" -> "일본어"
            "zh" -> "중국어"
            "fr" -> "프랑스어"
            "de" -> "독일어"
            "es" -> "스페인어"
            "hi" -> "힌디어"
            else -> lang
        }
    }
}
