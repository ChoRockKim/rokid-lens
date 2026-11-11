package com.chorockkim.rokidlens.lens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * One photo as a modest, upright bitmap, shared by the cloud and the ML Kit path. The camera's
 * JPEG is decoded at a reduced size (never the full sensor: that ran the glasses out of memory)
 * and turned the right way up, so nothing downstream has to handle rotation.
 */
object Photo {
    /** Enough detail for small print; the model scales it down itself if it needs to. */
    const val MAX_SIDE = 2048
    const val JPEG_QUALITY = 90

    fun jpegBytes(proxy: ImageProxy): ByteArray {
        val buffer = proxy.planes[0].buffer
        buffer.rewind()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** Power-of-two sample size that brings the longer side to at most [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    fun upright(jpeg: ByteArray, rotationDegrees: Int, maxSide: Int = MAX_SIDE): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IllegalStateException("사진을 읽지 못했어요")
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (rotationDegrees % 360 != 0 || scale < 1f) {
            val m = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                postRotate(rotationDegrees.toFloat())
            }
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (turned !== bitmap) bitmap.recycle()
            bitmap = turned
        }
        return bitmap
    }

    /** The centred [Focus] area of [bitmap] (a new bitmap; [bitmap] is recycled). */
    fun focus(bitmap: Bitmap, fraction: Float = Focus.FRACTION): Bitmap {
        val (left, top, w, h) = Focus.area(bitmap.width, bitmap.height, fraction).toList()
        val cropped = Bitmap.createBitmap(bitmap, left, top, w, h)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    fun jpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
