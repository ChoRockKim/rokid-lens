package com.chorockkim.rokidlens.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * The glasses camera through CameraX: a small preview to aim with, a 1280x720 analysis stream
 * for live mode (the same size Rokid Nexus streams from these glasses) and full-resolution stills
 * for freeze mode. CameraX handles the sensor-to-display rotation (the glasses screen is
 * portrait); [Frame.rotationDegrees] is passed on to ML Kit.
 *
 * Analysis keeps only the latest frame: while one frame is being read the next ones are dropped,
 * so live mode never falls behind the camera.
 */
class LensCamera(private val context: Context) {
    /** One camera frame; [close] it when done (CameraX waits for that before the next one). */
    class Frame(val proxy: ImageProxy) {
        val rotationDegrees: Int get() = proxy.imageInfo.rotationDegrees
        fun close() = proxy.close()
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null

    fun start(owner: LifecycleOwner, previewView: PreviewView?, onFrame: (Frame) -> Unit, onError: (Throwable) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { pv ->
                    previewView?.let { pv.surfaceProvider = it.surfaceProvider }
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector(Size(1280, 720)))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor) { proxy -> onFrame(Frame(proxy)) } }
                val still = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                capture = still
                p.unbindAll()
                val useCases = listOfNotNull(preview.takeIf { previewView != null }, analysis, still)
                p.bindToLifecycle(owner, glassesCamera(), *useCases.toTypedArray())
            }.onFailure(onError)
        }, ContextCompat.getMainExecutor(context))
    }

    /** Takes a full-resolution still for freeze mode. */
    fun takeStill(onStill: (Frame) -> Unit, onError: (Throwable) -> Unit) {
        val c = capture ?: return onError(IllegalStateException("camera not started"))
        c.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) = onStill(Frame(image))
            override fun onError(exception: ImageCaptureException) = onError(exception)
        })
    }

    fun stop() {
        provider?.unbindAll()
        provider = null
        capture = null
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
    }

    /**
     * The glasses have one camera (id "0" in Rokid Nexus), which may not report itself as a back
     * camera: take a back-facing one if there is any, else the first camera there is.
     */
    private fun glassesCamera(): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos ->
            val back = infos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            (back.ifEmpty { infos }).take(1)
        }
        .build()

    private fun selector(size: Size) = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()
}
