package com.chorockkim.rokidlens.lens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusTest {
    @Test
    fun readsAWideBandInTheMiddle() {
        // Portrait photo from the glasses: 90% of the width, 16:9.
        assertArrayEquals(intArrayOf(54, 687, 972, 546), Focus.area(1080, 1920))
        // On the 16:9 preview the same area is 90% x 90%.
        val p = Focus.area(160, 90)
        assertArrayEquals(intArrayOf(8, 4, 144, 81), p)
        // Never taller than the picture.
        assertArrayEquals(intArrayOf(0, 0, 100, 10), Focus.area(100, 10, 1f))
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
