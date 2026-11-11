package com.chorockkim.rokidlens.lens

import kotlin.math.abs

/**
 * Live mode shows only a few paragraphs on the HUD band, so it picks the ones the wearer is most
 * likely looking at: big text near the middle of the camera frame. The chosen ones are shown top
 * to bottom, in reading order, so they do not jump around between frames.
 */
object LiveSelection {
    const val MAX_LIVE = 3

    internal fun pick(
        paragraphs: List<FrozenLayoutParagraph>,
        frameWidth: Int,
        frameHeight: Int,
        max: Int = MAX_LIVE
    ): List<FrozenLayoutParagraph> {
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()
        return paragraphs
            .filter { LanguagePlan.worthTranslating(it.source) }
            .sortedByDescending { score(it.bounds, frameWidth, frameHeight) }
            .take(max)
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /** Area (how big the text looks) times how close its center is to the frame's center. */
    internal fun score(r: FrozenLayoutRect, frameWidth: Int, frameHeight: Int): Double {
        val area = r.width.toDouble().coerceAtLeast(0.0) * r.height.toDouble().coerceAtLeast(0.0)
        val dx = abs((r.left + r.right) / 2.0 - frameWidth / 2.0) / (frameWidth / 2.0)
        val dy = abs((r.top + r.bottom) / 2.0 - frameHeight / 2.0) / (frameHeight / 2.0)
        val centrality = (1.0 - 0.5 * (dx + dy)).coerceIn(0.1, 1.0)
        return area * centrality
    }
}
