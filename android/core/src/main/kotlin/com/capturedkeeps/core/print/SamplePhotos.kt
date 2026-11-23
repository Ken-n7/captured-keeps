package com.capturedkeeps.core.print

/** Stand-in photos (gradients + shaded circles, full tone range) until the camera exists. */
object SamplePhotos {
    fun synthetic(seed: Int, w: Int = 800, h: Int = 600): GrayImage {
        val circles = List(3) { i -> Triple(150 + (seed * 97 + i * 211) % 500, 150 + (seed * 53 + i * 137) % 300, 80 + (seed * 31 + i * 41) % 70) }
        return GrayImage(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            var v = 220.0 - 140.0 * y / h
            for ((cx, cy, r) in circles) {
                val dx = (x - cx).toDouble(); val dy = (y - cy).toDouble()
                if (dx * dx + dy * dy < r * r) {
                    val lx = x - (cx - r * 0.4); val ly = y - (cy - r * 0.4)
                    v = 255 - Math.sqrt(lx * lx + ly * ly) / (1.6 * r) * 230
                }
            }
            v.toInt().coerceIn(0, 255)
        })
    }

    val DEFAULT = (1..4).map { synthetic(it) }
}
