package com.capturedkeeps.app.print

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.capturedkeeps.core.print.BitImage
import com.capturedkeeps.core.print.GrayImage
import java.io.File

/** Photos and strips live in app storage: filesDir/sessions/<sessionId>/. */
object Photos {
    fun dir(filesDir: File, sessionId: String) = File(filesDir, "sessions/$sessionId").apply { mkdirs() }

    /** Rotates upright and saves as JPEG. */
    fun save(bitmap: Bitmap, rotationDegrees: Int, file: File) {
        val upright = if (rotationDegrees == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        file.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /** Decodes at roughly 2× the strip's photo width — plenty for 576 dots, light on memory. */
    fun loadGray(path: String, minWidth: Int = 1100): GrayImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= minWidth) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Can't read photo $path")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return GrayImage.fromArgb(bmp.width, bmp.height, px).also { bmp.recycle() }
    }

    /** Final strip as printed (1-bit PNG-sized). Reprints use this file, never the camera. */
    fun saveStrip(dots: BitImage, file: File): File =
        file.also { f -> f.outputStream().use { dots.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } }
}
