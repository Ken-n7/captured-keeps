package com.capturedkeeps.core.print

import kotlin.math.pow
import kotlin.math.roundToInt

enum class DitherStyle { FLOYD_STEINBERG, ATKINSON, THRESHOLD }

/**
 * Per printer + paper calibration. Thermal paper prints darker than a screen shows,
 * so defaults lift mid-tones; final values get tuned on real prints.
 */
data class PrintTuning(
    val autoLevels: Boolean = true,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val gamma: Double = 0.8,
    val dither: DitherStyle = DitherStyle.FLOYD_STEINBERG,
) {
    init {
        require(brightness in -100..100) { "Brightness must be -100..100, was $brightness" }
        require(contrast in -100..100) { "Contrast must be -100..100, was $contrast" }
        require(gamma in 0.2..3.0) { "Gamma must be 0.2..3.0, was $gamma" }
    }
}

/** Applies levels → gamma → contrast → brightness through one lookup table. */
fun GrayImage.tuned(t: PrintTuning): GrayImage {
    var lo = 0
    var hi = 255
    if (t.autoLevels) {
        val hist = IntArray(256).also { h -> pixels.forEach { h[it]++ } }
        val cut = pixels.size / 100 // ignore the darkest/brightest 1%
        var acc = 0
        lo = (0..255).first { acc += hist[it]; acc > cut }
        acc = 0
        hi = (255 downTo 0).first { acc += hist[it]; acc > cut }
        if (hi <= lo) { lo = 0; hi = 255 } // flat image: leave alone
    }
    val c = (100 + t.contrast) / 100.0
    val lut = IntArray(256) { v ->
        var f = ((v - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
        f = f.pow(t.gamma)
        f = (f - 0.5) * c + 0.5 + t.brightness / 200.0
        (f.coerceIn(0.0, 1.0) * 255).roundToInt()
    }
    return GrayImage(width, height, IntArray(pixels.size) { lut[pixels[it]] })
}

private class Tap(val dx: Int, val dy: Int, val weight: Float)

private val FLOYD_STEINBERG = listOf(Tap(1, 0, 7 / 16f), Tap(-1, 1, 3 / 16f), Tap(0, 1, 5 / 16f), Tap(1, 1, 1 / 16f))

// Atkinson spreads only 6/8 of the error: punchier contrast, cleaner highlights.
private val ATKINSON = listOf(
    Tap(1, 0, 1 / 8f), Tap(2, 0, 1 / 8f), Tap(-1, 1, 1 / 8f),
    Tap(0, 1, 1 / 8f), Tap(1, 1, 1 / 8f), Tap(0, 2, 1 / 8f),
)

/** Error-diffusion dither to black/white dots. */
fun GrayImage.dither(style: DitherStyle): BitImage {
    val taps = when (style) {
        DitherStyle.FLOYD_STEINBERG -> FLOYD_STEINBERG
        DitherStyle.ATKINSON -> ATKINSON
        DitherStyle.THRESHOLD -> emptyList()
    }
    val value = FloatArray(pixels.size) { pixels[it].toFloat() }
    val black = BooleanArray(pixels.size)
    for (y in 0 until height) for (x in 0 until width) {
        val i = y * width + x
        val isBlack = value[i] < 128f
        black[i] = isBlack
        val error = value[i] - if (isBlack) 0f else 255f
        for (t in taps) {
            val nx = x + t.dx
            val ny = y + t.dy
            if (nx in 0 until width && ny < height) value[ny * width + nx] += error * t.weight
        }
    }
    return BitImage(width, height, black)
}
