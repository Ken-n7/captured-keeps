package com.capturedkeeps.core.print

/** 8-bit grayscale image, row-major. 0 = black, 255 = white. */
class GrayImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray = IntArray(width * height) { 255 },
) {
    init {
        require(width > 0 && height > 0) { "Image size must be positive: ${width}x$height" }
        require(pixels.size == width * height) { "Pixel count ${pixels.size} != ${width}x$height" }
        require(pixels.all { it in 0..255 }) { "Pixel values must be 0..255" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Center-crop to the target aspect ratio, then scale to exactly [w]×[h] (box average when shrinking). */
    fun fitCrop(w: Int, h: Int): GrayImage {
        require(w > 0 && h > 0) { "Target size must be positive: ${w}x$h" }
        val dstAspect = w.toDouble() / h
        val cropW: Double
        val cropH: Double
        if (width.toDouble() / height > dstAspect) {
            cropH = height.toDouble(); cropW = cropH * dstAspect
        } else {
            cropW = width.toDouble(); cropH = cropW / dstAspect
        }
        val left = (width - cropW) / 2
        val top = (height - cropH) / 2
        val sx = cropW / w
        val sy = cropH / h

        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = (top + y * sy).toInt()
            val y1 = maxOf(y0 + 1, (top + (y + 1) * sy).toInt()).coerceAtMost(height)
            for (x in 0 until w) {
                val x0 = (left + x * sx).toInt()
                val x1 = maxOf(x0 + 1, (left + (x + 1) * sx).toInt()).coerceAtMost(width)
                var sum = 0
                for (yy in y0 until y1) for (xx in x0 until x1) sum += pixels[yy * width + xx]
                out[y * w + x] = sum / ((y1 - y0) * (x1 - x0))
            }
        }
        return GrayImage(w, h, out)
    }

    companion object {
        /** From packed ARGB pixels (Android Bitmap.getPixels / AWT getRGB). Alpha ignored. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage =
            GrayImage(width, height, IntArray(argb.size) {
                val c = argb[it]
                (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            })
    }
}

/** 1-bit image ready for the thermal printer. true = black dot. */
class BitImage(val width: Int, val height: Int, val black: BooleanArray) {
    init {
        require(width > 0 && height > 0) { "Image size must be positive: ${width}x$height" }
        require(black.size == width * height) { "Dot count ${black.size} != ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Boolean = black[y * width + x]
}
