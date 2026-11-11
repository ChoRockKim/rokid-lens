package com.chorockkim.rokidlens.lens

import android.annotation.SuppressLint
import com.chorockkim.rokidlens.camera.LensCamera
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One still (taken when the wearer taps) -> OCR -> paragraphs -> translation.
 *
 * The still is read with every script in [AutoScriptPolicy]'s frozen sweep order until one reads
 * credibly (starting with the script that won last time), cut into paragraphs with
 * [segmentFrozenParagraphs], and translated one by one, so the first ones show up while the rest
 * are still coming. The OCR, layout and script logic is ported from Rokid Nexus' Lens; here it
 * all runs on the glasses instead of the phone.
 */
class LensPipeline(
    private val ocr: OcrEngine,
    private val translator: LensTranslator,
    private val listener: Listener
) {
    data class Item(val source: String, val translated: String?, val from: String?, val to: String?)

    interface Listener {
        fun onProgress(items: List<Item>, total: Int, done: Boolean)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The script that read the last photo best; the next photo tries it first. */
    @Volatile private var lastScript = OcrScript.LATIN

    @SuppressLint("UnsafeOptInUsageError")
    fun read(frame: LensCamera.Frame) {
        val media = frame.proxy.image
        if (media == null) {
            frame.close()
            listener.onError("사진을 못 찍었어요")
            return
        }
        var closed = false
        fun closeFrame() {
            if (!closed) runCatching { frame.close() }
            closed = true
        }
        scope.launch {
            try {
                val image = InputImage.fromMediaImage(media, frame.rotationDegrees)
                var state = FrozenScriptState(primaryScript = lastScript)
                val texts = mutableMapOf<OcrScript, Text>()
                while (true) {
                    val script = AutoScriptPolicy.planFrozenRecognition(state) ?: break
                    val text = ocr.recognize(image, script)
                    texts[script] = text
                    state = AutoScriptPolicy.resolveFrozenResult(state, script, text.text).nextState
                }
                closeFrame()
                val winner = state.winnerScript ?: state.primaryScript
                lastScript = winner
                val paragraphs = texts[winner]?.let { segmentFrozenParagraphs(OcrEngine.layoutBlocks(it)) }.orEmpty()
                    .filter { LanguagePlan.worthTranslating(it.source) }
                if (paragraphs.isEmpty()) {
                    listener.onProgress(emptyList(), 0, done = true)
                    return@launch
                }
                val items = paragraphs.map { Item(it.source, null, null, null) }.toMutableList()
                listener.onProgress(items.toList(), items.size, done = false)
                paragraphs.forEachIndexed { i, p ->
                    val t = translator.translate(p.source, winner)
                    items[i] = Item(p.source, t?.translated, t?.from, t?.to)
                    listener.onProgress(items.toList(), items.size, done = i == paragraphs.lastIndex)
                }
            } catch (e: Exception) {
                closeFrame()
                listener.onError("읽기 실패 · ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
