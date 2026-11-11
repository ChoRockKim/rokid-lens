package com.chorockkim.rokidlens.lens

import android.annotation.SuppressLint
import android.os.SystemClock
import com.chorockkim.rokidlens.camera.LensCamera
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Camera frame -> OCR -> paragraphs -> translation, for both modes:
 *
 * - Live: at most one frame every [LIVE_INTERVAL_MS] ([OcrCadencePolicy]), read with the script
 *   [AutoScriptPolicy] picks (Latin, probing the others now and then), the [LiveSelection]
 *   paragraphs translated and shown.
 * - Frozen: one full-resolution still, read with every script in [AutoScriptPolicy]'s frozen sweep
 *   order until one reads credibly, all its paragraphs ([segmentFrozenParagraphs]) translated one
 *   by one, so the first ones show up while the rest are still coming.
 *
 * The OCR, layout and script logic is ported from Rokid Nexus' Lens; here it all runs on the
 * glasses instead of the phone.
 */
class LensPipeline(
    private val ocr: OcrEngine,
    private val translator: LensTranslator,
    private val listener: Listener
) {
    data class Item(val source: String, val translated: String?, val from: String?, val to: String?)

    interface Listener {
        fun onLive(items: List<Item>)
        fun onFrozenProgress(items: List<Item>, total: Int, done: Boolean)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val busy = AtomicBoolean(false)
    @Volatile var paused = false
    @Volatile private var cadence = OcrCadenceState()
    @Volatile private var scriptState = AutoScriptState()
    @Volatile private var emptyFrames = 0

    /** Called for every analysis frame; closes it when done. */
    @SuppressLint("UnsafeOptInUsageError")
    fun onLiveFrame(frame: LensCamera.Frame) {
        val now = SystemClock.elapsedRealtime()
        val decision = OcrCadencePolicy.evaluate(cadence, now, LIVE_INTERVAL_MS)
        val media = frame.proxy.image
        if (paused || media == null || !decision.shouldStart || !busy.compareAndSet(false, true)) {
            frame.close()
            return
        }
        cadence = decision.nextState
        val plan = AutoScriptPolicy.planLiveFrame(scriptState, now)
        scriptState = plan.nextState
        scope.launch {
            try {
                val image = InputImage.fromMediaImage(media, frame.rotationDegrees)
                val text = ocr.recognize(image, plan.script)
                val observation = AutoScriptPolicy.observeLiveResult(
                    scriptState, SystemClock.elapsedRealtime(), plan.script, plan.isProbe, plan.isBurstProbe, text.text
                )
                scriptState = observation.nextState
                if (!observation.acceptResult || paused) return@launch
                val (w, h) = uprightSize(frame)
                val picked = LiveSelection.pick(segmentFrozenParagraphs(OcrEngine.layoutBlocks(text)), w, h)
                // A frame or two without text happens while moving; only clear after a few.
                if (picked.isEmpty()) {
                    if (++emptyFrames >= EMPTY_FRAMES_TO_CLEAR) listener.onLive(emptyList())
                    return@launch
                }
                emptyFrames = 0
                val items = picked.map { p ->
                    val t = translator.translate(p.source, observation.effectiveScript)
                    Item(p.source, t?.translated, t?.from, t?.to)
                }
                if (!paused) listener.onLive(items)
            } catch (e: Exception) {
                listener.onError("읽기 실패 · ${e.message ?: e.javaClass.simpleName}")
            } finally {
                frame.close()
                busy.set(false)
            }
        }
    }

    /** Reads and translates one still; reports progress as paragraphs get translated. */
    @SuppressLint("UnsafeOptInUsageError")
    fun onStill(frame: LensCamera.Frame) {
        val media = frame.proxy.image
        if (media == null) {
            frame.close()
            listener.onError("사진을 못 찍었어요")
            return
        }
        scope.launch {
            try {
                val image = InputImage.fromMediaImage(media, frame.rotationDegrees)
                var state = FrozenScriptState(primaryScript = scriptState.effectiveScript)
                val texts = mutableMapOf<OcrScript, Text>()
                while (true) {
                    val script = AutoScriptPolicy.planFrozenRecognition(state) ?: break
                    val text = ocr.recognize(image, script)
                    texts[script] = text
                    state = AutoScriptPolicy.resolveFrozenResult(state, script, text.text).nextState
                }
                frame.close()
                val winner = state.winnerScript ?: state.primaryScript
                val text = texts[winner]
                val paragraphs = text?.let { segmentFrozenParagraphs(OcrEngine.layoutBlocks(it)) }.orEmpty()
                    .filter { LanguagePlan.worthTranslating(it.source) }
                if (paragraphs.isEmpty()) {
                    listener.onFrozenProgress(emptyList(), 0, done = true)
                    return@launch
                }
                val items = paragraphs.map { Item(it.source, null, null, null) }.toMutableList()
                listener.onFrozenProgress(items.toList(), items.size, done = false)
                paragraphs.forEachIndexed { i, p ->
                    val t = translator.translate(p.source, winner)
                    items[i] = Item(p.source, t?.translated, t?.from, t?.to)
                    listener.onFrozenProgress(items.toList(), items.size, done = i == paragraphs.lastIndex)
                }
            } catch (e: Exception) {
                frame.close()
                listener.onError("읽기 실패 · ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun uprightSize(frame: LensCamera.Frame): Pair<Int, Int> {
        val w = frame.proxy.width
        val h = frame.proxy.height
        return if (frame.rotationDegrees % 180 == 0) w to h else h to w
    }

    companion object {
        const val LIVE_INTERVAL_MS = 1_500L
        const val EMPTY_FRAMES_TO_CLEAR = 2
    }
}
