package com.chorockkim.rokidlens.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensLogicTest {
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
