package com.chorockkim.rokidlens.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.chorockkim.rokidlens.lens.LensPipeline.Item
import com.chorockkim.rokidlens.ui.Hud.dp
import org.junit.Rule
import org.junit.Test

/**
 * The glasses display (480x640, the band at the bottom) as Paparazzi draws it from the real
 * layout code. `./gradlew :app:recordPaparazziDebug`; PNGs land in app/src/test/snapshots/images.
 */
class LensSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 480,
            screenHeight = 640,
            xdpi = 160,
            ydpi = 160,
            density = Density.MEDIUM,
            orientation = ScreenOrientation.PORTRAIT,
            softButtons = false
        ),
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false
    )

    private val context get() = paparazzi.context

    /** layoutlib cannot draw the camera; a gradient stands in for the preview. */
    private fun fakePreview() = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3A4A5A.toInt(), 0xFF8A9AA8.toInt()))
    }

    private val sign = listOf(
        Item("Platform 2 · Trains to Shinjuku", "2번 승강장 · 신주쿠행 열차", "en", "ko"),
        Item("Please do not lean on the doors", "문에 기대지 마세요", "en", "ko"),
        Item("Exit A3", "A3 출구", "en", "ko")
    )

    @Test
    fun ready() {
        val screen = LensScreen(context, fakePreview())
        screen.showReady()
        snapshot(screen.view)
    }

    @Test
    fun result_translating() {
        val screen = LensScreen(context, fakePreview())
        screen.showReading()
        val menu = listOf(
            Item("本日のおすすめ", "오늘의 추천", "ja", "ko"),
            Item("味噌ラーメン 950円", "된장 라멘 950엔", "ja", "ko"),
            Item("餃子 (6個) 450円", "교자(6개) 450엔", "ja", "ko"),
            Item("ご飯 大盛り無料", null, null, null)
        )
        screen.showResult(menu, menu.size, done = false)
        screen.page(1)
        snapshot(screen.view)
    }

    @Test
    fun result_done() {
        val screen = LensScreen(context, fakePreview())
        screen.showReading()
        screen.showResult(sign, sign.size, done = true)
        snapshot(screen.view)
    }

    @Test
    fun ready_downloadingModel() {
        val screen = LensScreen(context, fakePreview())
        screen.showReady()
        screen.setStatus("번역 모델 받는 중 · 일본어→한국어")
        snapshot(screen.view)
    }

    private fun snapshot(band: View) {
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(band, FrameLayout.LayoutParams(
            context.dp(Hud.BAND_WIDTH_DP), context.dp(Hud.BAND_HEIGHT_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        root.addView(StatusIndicator(context).apply { show(82, charging = false, time = "14:32") }, StatusIndicator.params(context))
        paparazzi.snapshot(root)
    }
}
