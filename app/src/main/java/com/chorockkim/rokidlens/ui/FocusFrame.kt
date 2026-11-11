package com.chorockkim.rokidlens.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import com.chorockkim.rokidlens.lens.Focus
import com.chorockkim.rokidlens.ui.Hud.dp

/** The read area ([Focus]) drawn over the preview, so aiming shows what will be translated. */
class FocusFrame(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f).toFloat()
        color = Hud.GREEN
        pathEffect = DashPathEffect(floatArrayOf(context.dp(4).toFloat(), context.dp(3).toFloat()), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val (l, t, w, h) = Focus.area(width, height).toList()
        canvas.drawRect(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat(), paint)
    }
}
