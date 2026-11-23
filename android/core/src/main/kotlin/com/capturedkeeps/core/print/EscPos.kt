package com.capturedkeeps.core.print

import java.io.ByteArrayOutputStream

/** 80mm paper at 203 dpi = 576 printable dots. */
const val PAPER_80MM_DOTS = 576

/** ESC/POS byte encoding. Transport-agnostic: the same bytes go over Bluetooth or USB. */
object EscPos {
    private val INIT = byteArrayOf(0x1B, 0x40)
    private val FEED_AND_PARTIAL_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    /**
     * GS v 0 raster image, split into bands: cheap printers have small receive buffers.
     * [bandHeight] is a calibration knob — lower it if the real printer garbles or skips lines.
     */
    fun raster(img: BitImage, bandHeight: Int = 128): ByteArray {
        require(bandHeight in 1..4095) { "Band height must be 1..4095, was $bandHeight" }
        val bytesPerRow = (img.width + 7) / 8
        val out = ByteArrayOutputStream(bytesPerRow * img.height + 8 * (img.height / bandHeight + 1))
        var top = 0
        while (top < img.height) {
            val h = minOf(bandHeight, img.height - top)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00,
                (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte(),
                (h and 0xFF).toByte(), (h shr 8).toByte()))
            for (y in top until top + h) {
                for (bx in 0 until bytesPerRow) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val x = bx * 8 + bit
                        if (x < img.width && img[x, y]) b = b or (0x80 shr bit)
                    }
                    out.write(b)
                }
            }
            top += h
        }
        return out.toByteArray()
    }

    /** Full job: reset, image, feed past the tear bar, optional cut. */
    fun printJob(
        img: BitImage,
        printerDots: Int = PAPER_80MM_DOTS,
        feedLines: Int = 4,
        cut: Boolean = true,
        bandHeight: Int = 128,
    ): ByteArray {
        require(img.width <= printerDots) { "Image is ${img.width} dots wide; printer fits $printerDots" }
        require(feedLines in 0..255) { "Feed lines must be 0..255, was $feedLines" }
        return INIT + raster(img, bandHeight) + byteArrayOf(0x1B, 0x64, feedLines.toByte()) +
            (if (cut) FEED_AND_PARTIAL_CUT else byteArrayOf())
    }
}
