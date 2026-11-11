package com.chorockkim.rokidlens.lens

import android.graphics.Bitmap
import com.chorockkim.rokidlens.camera.LensCamera
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One photo (taken when the wearer taps) -> text read and translated:
 *
 * 1. AI: the photo goes to the server ([CloudTranslator], Gemini), which reads and translates it
 *    in one go — much better than on-device ML Kit.
 * 2. Offline fallback (no internet, server or quota trouble): ML Kit on the glasses. The photo is
 *    read with every script in [AutoScriptPolicy]'s frozen sweep order until one reads credibly
 *    (starting with the script that won last time), cut into paragraphs with
 *    [segmentFrozenParagraphs] and translated one by one, so the first ones show up first.
 *
 * The photo is decoded once at a modest size ([Photo]) and shared by both paths. Nothing here
 * may take the app down: every failure, out-of-memory included, ends as [Listener.onError].
 * The OCR, layout and script logic is ported from Rokid Nexus' Lens.
 */
class LensPipeline(
    private val ocr: OcrEngine,
    private val translator: LensTranslator,
    private val cloud: CloudTranslator?,
    private val listener: Listener
) {
    data class Item(val source: String, val translated: String?, val from: String?, val to: String?)

    enum class Engine { AI, OFFLINE }

    interface Listener {
        fun onProgress(items: List<Item>, total: Int, done: Boolean, engine: Engine)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> listener.onError(errorText(e)) }
    )
    /** The script that read the last photo best; the next offline read tries it first. */
    @Volatile private var lastScript = OcrScript.LATIN

    fun read(frame: LensCamera.Frame) {
        scope.launch {
            val bitmap: Bitmap
            try {
                bitmap = Photo.upright(Photo.jpegBytes(frame.proxy), frame.rotationDegrees)
            } catch (e: Throwable) {
                listener.onError(errorText(e))
                return@launch
            } finally {
                runCatching { frame.close() }
            }
            try {
                val ai = cloud?.translate(Photo.jpeg(bitmap))
                if (ai != null) {
                    listener.onProgress(ai, ai.size, done = true, engine = Engine.AI)
                } else {
                    readOffline(bitmap)
                }
            } catch (e: Throwable) {
                listener.onError(errorText(e))
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun readOffline(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        var state = FrozenScriptState(primaryScript = lastScript)
        val texts = mutableMapOf<OcrScript, Text>()
        while (true) {
            val script = AutoScriptPolicy.planFrozenRecognition(state) ?: break
            val text = ocr.recognize(image, script)
            texts[script] = text
            state = AutoScriptPolicy.resolveFrozenResult(state, script, text.text).nextState
        }
        val winner = state.winnerScript ?: state.primaryScript
        lastScript = winner
        val paragraphs = texts[winner]?.let { segmentFrozenParagraphs(OcrEngine.layoutBlocks(it)) }.orEmpty()
            .filter { LanguagePlan.worthTranslating(it.source) }
        if (paragraphs.isEmpty()) {
            listener.onProgress(emptyList(), 0, done = true, engine = Engine.OFFLINE)
            return
        }
        val items = paragraphs.map { Item(it.source, null, null, null) }.toMutableList()
        listener.onProgress(items.toList(), items.size, done = false, engine = Engine.OFFLINE)
        paragraphs.forEachIndexed { i, p ->
            val t = translator.translate(p.source, winner)
            items[i] = Item(p.source, t?.translated, t?.from, t?.to)
            listener.onProgress(items.toList(), items.size, done = i == paragraphs.lastIndex, engine = Engine.OFFLINE)
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun errorText(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "메모리가 부족해요 · 다시 탭해 보세요"
        else -> "읽기 실패 · ${e.message ?: e.javaClass.simpleName}"
    }
}
