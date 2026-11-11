package com.chorockkim.rokidlens.lens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusTest {
    @Test
    fun readsTheCentredSixtyPercent() {
        assertArrayEquals(intArrayOf(400, 225, 1200, 675), Focus.area(2000, 1125))
        assertArrayEquals(intArrayOf(0, 0, 10, 10), Focus.area(10, 10, 1f))
    }

    @Test
    fun theParagraphAimedAtComesFirst() {
        fun p(text: String, l: Int, t: Int, r: Int, b: Int) = FrozenLayoutParagraph(
            text, FrozenLayoutRect(l, t, r, b), listOf(FrozenLayoutLine(text, FrozenLayoutRect(l, t, r, b))), 0, 0f
        )
        val header = p("Menu Home Search", 0, 0, 1000, 60)
        val body = p("The article text", 200, 450, 800, 560)
        val footer = p("Privacy · Terms", 0, 940, 1000, 1000)
        assertEquals(listOf(body, header, footer), Focus.centerFirst(listOf(header, body, footer), 1000, 1000))
    }
}
