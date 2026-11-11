package com.chorockkim.rokidlens.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.chorockkim.rokidlens.lens.LensPipeline
import com.chorockkim.rokidlens.lens.LensTranslator
import com.chorockkim.rokidlens.ui.Hud.dp

/**
 * The one screen, inside the 480x282 band: a small portrait camera preview on the left to aim
 * with, translations on the right (translation big and white, the original small and grey under
 * it), the mode on top and the controls at the bottom.
 *
 * Live: the few paragraphs nearest the middle of the view, updated as the camera moves.
 * Frozen: every paragraph of one still, two at a time, swiped through.
 */
class LensScreen(context: Context, private val preview: View?) {
    enum class Mode { LIVE, FROZEN }

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(6))
    }

    private val previewFrame = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(context.dp(1), Hud.DIM_GREEN)
            cornerRadius = context.dp(4).toFloat()
        }
        setPadding(context.dp(1), context.dp(1), context.dp(1), context.dp(1))
    }
    private val modeLabel = Hud.text(context, 12f, Hud.GREEN, bold = true).apply { gravity = Gravity.CENTER }

    private val header = Hud.text(context, 12f, Hud.GREY).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val status = Hud.text(context, 11f, Hud.RED).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val items = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val empty = Hud.text(context, 14f, Hud.GREY).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.2f)
    }
    private val hint = Hud.text(context, 11f, Hud.GREY).apply { maxLines = 1 }

    var mode = Mode.LIVE
        private set
    private var frozen: List<LensPipeline.Item> = emptyList()
    private var frozenTotal = 0
    private var frozenDone = false
    var frozenIndex = 0
        private set

    init {
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(previewFrame, LinearLayout.LayoutParams(context.dp(PREVIEW_W), context.dp(PREVIEW_H)))
            addView(modeLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
        }
        preview?.let { previewFrame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        view.addView(left, LinearLayout.LayoutParams(-2, -1))

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(2) })
            val body = FrameLayout(context).apply {
                addView(items, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
                addView(empty, FrameLayout.LayoutParams(-1, -1))
            }
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(6) })
            addView(hint, LinearLayout.LayoutParams(-1, -2))
        }
        view.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = context.dp(12) })
        showLive(emptyList())
    }

    /** Model downloads and errors, in red under the header; null hides it. */
    fun setStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun showLive(list: List<LensPipeline.Item>) {
        mode = Mode.LIVE
        modeLabel.text = "실시간"
        header.text = "보이는 글자를 번역해요" + pairLabel(list)
        hint.text = "탭: 멈추고 전체 번역 · 뒤로: 나가기"
        render(list, if (list.isEmpty()) "글자 쪽을 바라봐 주세요" else null)
    }

    fun showFreezing() {
        mode = Mode.FROZEN
        frozen = emptyList()
        frozenIndex = 0
        frozenDone = false
        modeLabel.text = "멈춤"
        header.text = "사진을 찍어 읽는 중…"
        hint.text = "탭 · 뒤로: 실시간으로"
        render(emptyList(), "읽는 중…")
    }

    fun showFrozen(list: List<LensPipeline.Item>, total: Int, done: Boolean) {
        mode = Mode.FROZEN
        frozen = list
        frozenTotal = total
        frozenDone = done
        frozenIndex = frozenIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        renderFrozen()
    }

    /** Frozen mode: move by one paragraph; returns false at the ends. */
    fun page(delta: Int): Boolean {
        if (mode != Mode.FROZEN || frozen.isEmpty()) return false
        val next = (frozenIndex + delta).coerceIn(0, frozen.size - 1)
        if (next == frozenIndex) return false
        frozenIndex = next
        renderFrozen()
        return true
    }

    private fun renderFrozen() {
        modeLabel.text = "멈춤"
        val translatedCount = frozen.count { it.translated != null }
        header.text = when {
            frozen.isEmpty() && frozenDone -> "글자를 못 찾았어요"
            !frozenDone -> "번역 중 $translatedCount/$frozenTotal" + pairLabel(frozen)
            else -> "${frozenIndex + 1} / ${frozen.size}" + pairLabel(frozen)
        }
        hint.text = "앞뒤로: 넘기기 · 탭 · 뒤로: 실시간으로"
        render(frozen.drop(frozenIndex).take(FROZEN_PER_PAGE), if (frozen.isEmpty()) "탭해서 다시 시도" else null)
    }

    private fun render(list: List<LensPipeline.Item>, emptyText: String?) {
        items.removeAllViews()
        list.forEachIndexed { i, item -> items.addView(itemView(item), LinearLayout.LayoutParams(-1, -2).apply {
            if (i > 0) topMargin = view.context.dp(7)
        }) }
        empty.text = emptyText
        empty.visibility = if (list.isEmpty() && emptyText != null) View.VISIBLE else View.GONE
    }

    private fun itemView(item: LensPipeline.Item): View {
        val c = view.context
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Hud.text(c, 13f, Hud.WHITE, bold = true).apply {
                text = item.translated ?: "…"
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.1f)
            })
            addView(Hud.text(c, 10f, Hud.GREY).apply {
                text = item.source
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(2) })
        }
    }

    private fun pairLabel(list: List<LensPipeline.Item>): String {
        val first = list.firstOrNull { it.from != null && it.to != null } ?: return ""
        return " · ${LensTranslator.label(first.from!!)}→${LensTranslator.label(first.to!!)}"
    }

    companion object {
        /** Portrait, like the glasses camera (the display is portrait). */
        const val PREVIEW_W = 120
        const val PREVIEW_H = 213
        const val FROZEN_PER_PAGE = 2
    }
}
