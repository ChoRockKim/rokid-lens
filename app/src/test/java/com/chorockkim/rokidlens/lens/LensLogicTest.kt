package com.chorockkim.rokidlens.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensLogicTest {
    private fun para(text: String, l: Int, t: Int, r: Int, b: Int) = FrozenLayoutParagraph(
        source = text,
        bounds = FrozenLayoutRect(l, t, r, b),
        lines = listOf(FrozenLayoutLine(text, FrozenLayoutRect(l, t, r, b))),
        columnIndex = 0,
        gapBelow = 0f
    )

    @Test
    fun liveModePicksBigCentralTextAndShowsItTopToBottom() {
        val frameW = 720
        val frameH = 1280
        val title = para("EXIT ONLY", 160, 500, 560, 600)          // big, central
        val sign = para("Platform 2", 200, 700, 520, 760)          // central
        val corner = para("tiny corner note", 0, 0, 120, 20)       // small, far
        val noise = para("1", 300, 300, 320, 330)                  // not words
        val bottom = para("Mind the gap please", 100, 1100, 620, 1180)

        val picked = LiveSelection.pick(listOf(corner, bottom, sign, noise, title), frameW, frameH)

        assertEquals(listOf("EXIT ONLY", "Platform 2", "Mind the gap please"), picked.map { it.source })
    }

    @Test
    fun koreanGoesToEnglishEverythingElseToKorean() {
        assertEquals("ko", LanguagePlan.target("en"))
        assertEquals("ko", LanguagePlan.target("ja"))
        assertEquals("en", LanguagePlan.target("ko"))
    }

    @Test
    fun sourceFallsBackToTheScriptWhenDetectionFails() {
        val all = setOf("en", "ja", "zh", "ko", "fr", "hi")
        assertEquals("fr", LanguagePlan.source("fr", OcrScript.LATIN) { it in all })
        assertEquals("zh", LanguagePlan.source("zh-Latn", OcrScript.LATIN) { it in all })
        assertEquals("en", LanguagePlan.source("und", OcrScript.LATIN) { it in all })
        assertEquals("ja", LanguagePlan.source(null, OcrScript.JAPANESE) { it in all })
        assertEquals("ko", LanguagePlan.source("xx", OcrScript.KOREAN) { it in all })
    }

    @Test
    fun strayCharactersAreNotTranslated() {
        assertFalse(LanguagePlan.worthTranslating("1"))
        assertFalse(LanguagePlan.worthTranslating("- %"))
        assertTrue(LanguagePlan.worthTranslating("No"))
        assertTrue(LanguagePlan.worthTranslating("出口"))
    }

    @Test
    fun paragraphsComeOutOfMlKitStyleBlocks() {
        val blocks = listOf(
            FrozenLayoutBlock(
                listOf(
                    FrozenLayoutLine("Welcome to the", FrozenLayoutRect(100, 100, 600, 140)),
                    FrozenLayoutLine("city museum", FrozenLayoutRect(100, 150, 500, 190))
                )
            ),
            FrozenLayoutBlock(listOf(FrozenLayoutLine("Open 9-18", FrozenLayoutRect(100, 400, 400, 440))))
        )
        val paragraphs = segmentFrozenParagraphs(blocks)
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs[0].source.contains("Welcome") && paragraphs[0].source.contains("museum"))
        assertEquals("Open 9-18", paragraphs[1].source)
    }
}
