package com.chorockkim.rokidlens.lens

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device text recognition with ML Kit's bundled models (no Google Play services needed),
 * one recognizer per script, created on first use. Which script to run is decided by
 * [AutoScriptPolicy] (ported from Rokid Nexus' Lens): Latin by default, other scripts probed
 * now and then and switched to when they read more.
 */
class OcrEngine {
    private val recognizers = mutableMapOf<OcrScript, TextRecognizer>()

    private fun recognizer(script: OcrScript): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(script) {
            when (script) {
                OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            }
        }
    }

    internal suspend fun recognize(image: InputImage, script: OcrScript): Text =
        recognizer(script).process(image).await()

    fun close() = synchronized(recognizers) {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    companion object {
        /** ML Kit result -> the layout model of [segmentFrozenParagraphs]. */
        internal fun layoutBlocks(text: Text): List<FrozenLayoutBlock> =
            text.textBlocks.mapNotNull { block ->
                val lines = block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    FrozenLayoutLine(line.text, FrozenLayoutRect(box.left, box.top, box.right, box.bottom))
                }
                lines.takeIf { it.isNotEmpty() }?.let(::FrozenLayoutBlock)
            }
    }
}
