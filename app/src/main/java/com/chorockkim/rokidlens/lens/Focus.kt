package com.chorockkim.rokidlens.lens

import kotlin.math.hypot

/**
 * Only the middle of the photo is read: a phone or browser screen, a poster wall or a shop
 * front has text everywhere, and the wearer aims at one part. The preview shows the same area
 * as a frame ([FocusFrame][com.chorockkim.rokidlens.ui.FocusFrame]).
 */
object Focus {
    /** Width of the read area, as a share of the photo's width. */
    const val FRACTION = 0.9f
    /** The read area is wide, not tall: 16:9, like a line of text or a sign. */
    const val ASPECT = 9f / 16f

    /**
     * (left, top, width, height) of the centred, landscape read area of a [w] x [h] picture:
     * [fraction] of the width, [ASPECT] as tall as it is wide (at most the whole height).
     * On the 16:9 preview, which shows the photo's full width, this is the same area.
     */
    fun area(w: Int, h: Int, fraction: Float = FRACTION): IntArray {
        val cw = (w * fraction).toInt().coerceIn(1, w)
        val ch = (cw * ASPECT).toInt().coerceIn(1, h)
        return intArrayOf((w - cw) / 2, (h - ch) / 2, cw, ch)
    }

    /** The paragraph nearest the centre first (what the wearer aims at), the rest in reading order. */
    internal fun centerFirst(paragraphs: List<FrozenLayoutParagraph>, w: Int, h: Int): List<FrozenLayoutParagraph> {
        if (paragraphs.size < 2) return paragraphs
        val nearest = paragraphs.minBy { p ->
            hypot((p.bounds.left + p.bounds.right) / 2.0 - w / 2.0, (p.bounds.top + p.bounds.bottom) / 2.0 - h / 2.0)
        }
        return listOf(nearest) + (paragraphs - nearest)
    }
}
