package com.chorockkim.rokidlens.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * The glasses camera through CameraX: a small preview to aim with, and a full-resolution still
 * when the wearer taps. Nothing reads the video itself (no continuous OCR, which is what made the
 * glasses warm). CameraX handles the sensor-to-display rotation (the glasses screen is portrait);
 * [Frame.rotationDegrees] is passed on to ML Kit.
 *
 * To read better and run cooler on the glasses:
 * - [ZOOM]x zoom on the camera itself: the wide-angle edges, where text is too small and
 *   distorted to read anyway, are left out and the middle fills the frame (the preview shows the
 *   same, so aiming matches what is read). The zoom is done by the camera, not the CPU.
 * - The sensor runs at the lowest frame rate it supports at or above [MIN_FPS] instead of 30 fps.
 */
class LensCamera(private val context: Context) {
    /** One camera frame; [close] it when done (CameraX waits for that before the next one). */
    class Frame(val proxy: ImageProxy) {
        val rotationDegrees: Int get() = proxy.imageInfo.rotationDegrees
        fun close() = proxy.close()
    }

    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null

    @OptIn(ExperimentalCamera2Interop::class)
    fun start(owner: LifecycleOwner, previewView: PreviewView?, onError: (Throwable) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val fps = lowFpsRange()
                val preview = Preview.Builder().apply { fps?.let { Camera2Interop.Extender(this).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) } }.build().also { pv ->
                    previewView?.let { pv.surfaceProvider = it.surfaceProvider }
                }
                // About 1920x1080: with the 2x zoom that is plenty for reading, and a full-sensor
                // still was big enough to run the glasses out of memory.
                val still = ImageCapture.Builder()
                    // Quality first (sharper small text); the size cap above keeps memory in check.
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build()
                    )
                    .build()
                capture = still
                p.unbindAll()
                val useCases = listOfNotNull(preview.takeIf { previewView != null }, still)
                val camera = p.bindToLifecycle(owner, glassesCamera(), *useCases.toTypedArray())
                val zoom = camera.cameraInfo.zoomState.value
                val ratio = ZOOM.coerceIn(zoom?.minZoomRatio ?: 1f, zoom?.maxZoomRatio ?: 1f)
                camera.cameraControl.setZoomRatio(ratio)
            }.onFailure(onError)
        }, ContextCompat.getMainExecutor(context))
    }

    /** Takes a full-resolution still for freeze mode. */
    fun takeStill(onStill: (Frame) -> Unit, onError: (Throwable) -> Unit) {
        val c = capture ?: return onError(IllegalStateException("camera not started"))
        c.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    onStill(Frame(image))
                } catch (e: Throwable) {
                    runCatching { image.close() }
                    onError(e)
                }
            }

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
        captureExecutor.shutdown()
    }

    /**
     * The glasses have one camera (id "0" in Rokid Nexus), which may not report itself as a back
     * camera: take a back-facing one if there is any, else the first camera there is.
     */
    companion object {
        /** Camera zoom: the middle half of the wide-angle view, magnified. */
        const val ZOOM = 2.0f
        const val MIN_FPS = 10

        /** Lowest top frame rate >= [MIN_FPS]; among those, the widest (lowest bottom) range. */
        fun pickFpsRange(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? =
            ranges.filter { it.second >= MIN_FPS }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .firstOrNull()
    }

    private fun glassesCamera(): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos ->
            val back = infos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            (back.ifEmpty { infos }).take(1)
        }
        .build()

    /**
     * The lowest AE frame-rate range the glasses camera offers whose top is at least [MIN_FPS]
     * (e.g. [10, 15] rather than [30, 30]); null keeps the camera's default.
     */
    private fun lowFpsRange(): Range<Int>? = runCatching {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val ids = manager.cameraIdList
        val id = ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull() ?: return null
        val ranges = manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        pickFpsRange(ranges?.map { it.lower to it.upper }.orEmpty())?.let { (lo, hi) -> Range(lo, hi) }
    }.getOrNull()
}
