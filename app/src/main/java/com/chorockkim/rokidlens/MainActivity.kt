package com.chorockkim.rokidlens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chorockkim.rokidlens.camera.LensCamera
import com.chorockkim.rokidlens.lens.LensPipeline
import com.chorockkim.rokidlens.lens.LensTranslator
import com.chorockkim.rokidlens.lens.OcrEngine
import com.chorockkim.rokidlens.net.WifiEnabler
import com.chorockkim.rokidlens.ui.GlassesInput
import com.chorockkim.rokidlens.ui.Hud
import com.chorockkim.rokidlens.ui.Hud.dp
import com.chorockkim.rokidlens.ui.LensScreen
import com.chorockkim.rokidlens.ui.RingTarget
import com.chorockkim.rokidlens.ui.StatusIndicator
import com.chorockkim.rokidlens.update.InstallResultReceiver
import com.chorockkim.rokidlens.update.Release
import com.chorockkim.rokidlens.update.Updater
import kotlinx.coroutines.launch

/**
 * Single activity, like rokid-youtube: everything is drawn inside a 480dp x 282dp band glued to
 * the bottom of the glasses display (the rest stays black, i.e. transparent on the waveguide),
 * with clock / battery, Wi-Fi and update pills just above it.
 *
 * Tap: freeze (translate everything in one still) / back to live. Front/back swipes: page
 * through a frozen result. Back: from frozen to live, from live out of the app.
 */
class MainActivity : ComponentActivity(), LensPipeline.Listener {
    private val app get() = application as RokidLensApp
    private lateinit var screen: LensScreen
    private lateinit var ringTarget: RingTarget
    private lateinit var offlineBanner: android.widget.TextView
    private lateinit var updateBanner: android.widget.TextView
    private val input = GlassesInput()

    private lateinit var previewView: PreviewView
    private val camera by lazy { LensCamera(this) }
    private val ocr = OcrEngine()
    private val translator by lazy { LensTranslator { status -> runOnUiThread { screen.setStatus(status) } } }
    private val pipeline by lazy { LensPipeline(ocr, translator, this) }
    private var lastLive: List<LensPipeline.Item> = emptyList()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else screen.setStatus("카메라 권한이 필요해요")
    }

    /** Same as rokid-youtube: fit the 480x282 band to the real display, ignore font scaling. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        val metrics = newBase.resources.displayMetrics
        val (w, h) = realScreenSize(newBase, metrics.widthPixels, metrics.heightPixels)
        val scale = minOf(w / Hud.BAND_WIDTH_DP.toFloat(), h / Hud.BAND_HEIGHT_DP.toFloat())
        applyOverrideConfiguration(android.content.res.Configuration().apply {
            densityDpi = (scale * android.util.DisplayMetrics.DENSITY_DEFAULT).toInt().coerceAtLeast(1)
            fontScale = 1f
        })
    }

    private fun realScreenSize(context: android.content.Context, fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
        val wm = context.getSystemService(android.view.WindowManager::class.java) ?: return fallbackW to fallbackH
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reading signs hands-free: keep the display on while the app is open.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val band = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(band, FrameLayout.LayoutParams(dp(Hud.BAND_WIDTH_DP), dp(Hud.BAND_HEIGHT_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        previewView = PreviewView(this).apply {
            // TextureView, so it sits in the layout like any view; dimmed so the bright camera
            // image leaks less light to the front of the waveguide.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            alpha = 0.8f
        }
        screen = LensScreen(this, previewView)
        val host = FrameLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        }
        band.addView(host, FrameLayout.LayoutParams(-1, -1))
        // R08 ring via R08 Access Bridge: see RingTarget.
        ringTarget = RingTarget(this) { dispatchAction(it) }
        band.addView(ringTarget, FrameLayout.LayoutParams(-1, -1))

        offlineBanner = Hud.offlineNotice(this).apply { visibility = View.GONE }
        root.addView(offlineBanner, Hud.offlineNoticeParams(this))
        root.addView(StatusIndicator(this), StatusIndicator.params(this))
        updateBanner = Hud.updateNotice(this).apply { visibility = View.GONE }
        root.addView(updateBanner, Hud.updateNoticeParams(this))
        setContentView(root)
        ringTarget.requestFocus()
        watchNetwork()
        checkForUpdate()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { dispatchAction(GlassesInput.Action.BACK) }
        })
    }

    /** Waiting for the wearer to allow installs in the system screen opened by [askInstallPermission]. */
    private var waitingForInstallPermission = false
    private var askedInstallPermission = false

    /**
     * True if this app may install APKs. Otherwise opens the system "이 출처 허용" screen for it,
     * once per app start, and the update resumes when the wearer comes back with it allowed.
     */
    private fun askInstallPermission(): Boolean {
        if (app.updater.canInstall()) return true
        if (!askedInstallPermission) {
            askedInstallPermission = true
            waitingForInstallPermission = runCatching {
                startActivity(app.updater.installPermissionIntent())
            }.isSuccess
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        if (waitingForInstallPermission && app.updater.canInstall()) {
            waitingForInstallPermission = false
            checkForUpdate()
        }
        wifiEnabler.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        // The camera and OCR are the big battery cost: nothing runs while the app is not in front.
        camera.stop()
        wifiEnabler.stop()
        super.onStop()
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        pipeline.close()
        camera.release()
        ocr.close()
        translator.close()
        super.onDestroy()
    }

    private fun startCamera() {
        camera.start(this, previewView, pipeline::onLiveFrame) { e ->
            runOnUiThread { screen.setStatus("카메라를 못 열었어요 · ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // region Input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return when (val action = input.map(event)) {
            GlassesInput.Action.NONE, GlassesInput.Action.VOLUME_UP, GlassesInput.Action.VOLUME_DOWN ->
                super.dispatchKeyEvent(event)
            GlassesInput.Action.DUPLICATE -> true
            else -> { dispatchAction(action); true }
        }
    }

    private fun dispatchAction(action: GlassesInput.Action) {
        when (action) {
            GlassesInput.Action.SELECT -> if (screen.mode == LensScreen.Mode.LIVE) freeze() else backToLive()
            GlassesInput.Action.NEXT, GlassesInput.Action.DOWN -> screen.page(1)
            GlassesInput.Action.PREVIOUS, GlassesInput.Action.UP -> screen.page(-1)
            GlassesInput.Action.BACK -> if (screen.mode == LensScreen.Mode.FROZEN) backToLive() else finish()
            else -> Unit
        }
    }

    private fun freeze() {
        pipeline.paused = true
        screen.showFreezing()
        camera.takeStill(pipeline::onStill) { e -> onError("사진을 못 찍었어요 · ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun backToLive() {
        pipeline.paused = false
        screen.showLive(lastLive)
    }

    // endregion

    // region LensPipeline.Listener (called off the main thread)

    override fun onLive(items: List<LensPipeline.Item>) = runOnUiThread {
        lastLive = items
        if (screen.mode == LensScreen.Mode.LIVE) {
            screen.setStatus(null)
            screen.showLive(items)
        }
    }

    override fun onFrozenProgress(items: List<LensPipeline.Item>, total: Int, done: Boolean) = runOnUiThread {
        if (screen.mode == LensScreen.Mode.FROZEN) screen.showFrozen(items, total, done)
    }

    override fun onError(message: String) = runOnUiThread { screen.setStatus(message) }

    // endregion

    // region Update (same as rokid-youtube: automatic at every start)

    private fun checkForUpdate() {
        val updater = app.updater
        lifecycleScope.launch {
            val release = (runCatching { updater.check(force = true) }.getOrNull() as? Updater.Check.Available)?.release
            showUpdate(release)
            if (release == null) return@launch
            if (!askInstallPermission()) {
                updateBanner.text = "새 버전 v${release.versionName} · 설치 허용을 켜면 이어서 해요"
                return@launch
            }
            updateBanner.text = "업데이트 v${release.versionName} 받는 중"
            val apk = runCatching {
                updater.download(release) { percent ->
                    if (percent >= 0) updateBanner.text = "업데이트 v${release.versionName} 받는 중 $percent%"
                }
            }.getOrElse {
                updateBanner.text = "업데이트 실패 · ${it.message ?: "다시 켜면 다시 해요"}"
                return@launch
            }
            InstallResultReceiver.listener = { result ->
                runOnUiThread {
                    updateBanner.text = when (result) {
                        is InstallResultReceiver.Result.Failed -> "업데이트 실패 · ${result.message}"
                        InstallResultReceiver.Result.WaitingForUser -> "업데이트 v${release.versionName} · 설치 확인을 눌러 주세요"
                        else -> "업데이트 v${release.versionName} 설치 완료 · 다시 켜는 중"
                    }
                }
            }
            updateBanner.text = "업데이트 v${release.versionName} 설치 중 · 곧 다시 켜져요"
            runCatching { updater.install(apk) }.onFailure {
                updateBanner.text = "업데이트 실패 · ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun showUpdate(release: Release?) {
        updateBanner.text = release?.let { "새 버전 v${it.versionName}" }
        updateBanner.visibility = if (release == null) View.GONE else View.VISIBLE
    }

    // endregion

    // region Network (same as rokid-youtube)

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var online = true
    private var wifiStatus: WifiEnabler.Status? = null
    private val wifiEnabler by lazy {
        WifiEnabler(this) { status ->
            runOnUiThread {
                wifiStatus = status
                renderNetwork()
            }
        }
    }

    private fun renderNetwork() {
        val text = WifiEnabler.label(wifiStatus, online)
        offlineBanner.text = text
        offlineBanner.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        fun show(isOnline: Boolean) = runOnUiThread {
            online = isOnline
            renderNetwork()
        }
        fun hasInternet(caps: android.net.NetworkCapabilities?) =
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        fun refresh() = show(hasInternet(cm.getNetworkCapabilities(cm.activeNetwork)))
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = refresh()
            override fun onLost(network: android.net.Network) = show(false)
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) =
                show(hasInternet(caps))
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
        refresh()
    }

    // endregion
}
