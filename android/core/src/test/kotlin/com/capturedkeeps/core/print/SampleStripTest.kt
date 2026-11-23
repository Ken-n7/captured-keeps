package com.capturedkeeps.core.print

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Laptop preview of real strips: renders every preset with the photos in <repo>/samples/photos
 * (synthetic test images if empty) and writes PNGs to core/build/samples.
 * Fonts differ slightly from the tablet; the photo processing is identical.
 */
class SampleStripTest {
    @Test fun `render sample strips`() {
        val outDir = File(System.getProperty("sampleOutDir") ?: "build/samples").apply { mkdirs() }
        val photos = File(System.getProperty("samplePhotosDir") ?: "")
            .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sorted()?.mapNotNull { ImageIO.read(it)?.toGray() }
            .orEmpty()
            .ifEmpty { (1..4).map { synthetic(it) } }

        val info = StripInfo("Maria & Jose's Wedding", LocalDateTime.of(2026, 11, 23, 18, 42), 42)
        for (layout in StripLayout.PRESETS) {
            val plan = layout.plan(info)
            val dots = draw(plan, layout, photos).dither(layout.tuning.dither)
            assertEquals(PAPER_80MM_DOTS, dots.width)
            EscPos.printJob(dots) // must encode without error
            ImageIO.write(dots.toPng(), "png", File(outDir, "${layout.id}.png"))
        }
        println("Sample strips written to ${outDir.absolutePath}")
    }

    private fun draw(plan: StripPlan, layout: StripLayout, photos: List<GrayImage>): GrayImage {
        val img = BufferedImage(plan.width, plan.height, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE; g.fillRect(0, 0, plan.width, plan.height)
        g.color = Color.BLACK
        for (e in plan.elements) when (e) {
            is StripElement.Rule -> g.fillRect(plan.margin, e.top, plan.width - 2 * plan.margin, e.thickness)
            is StripElement.Photo -> {
                val p = photos[e.index % photos.size].forSlot(e, layout.tuning)
                val raster = img.raster
                for (y in 0 until p.height) for (x in 0 until p.width) raster.setSample(e.left + x, e.top + y, 0, p[x, y])
            }
            is StripElement.Text -> {
                g.font = when (e.style) {
                    TextStyle.WORDMARK, TextStyle.FOOTER -> Font(Font.SERIF, Font.BOLD, e.size)
                    TextStyle.SPACED_CAPS -> Font(Font.SANS_SERIF, Font.PLAIN, e.size)
                        .deriveFont(mapOf(TextAttribute.TRACKING to 0.35))
                    TextStyle.BODY -> Font(Font.SANS_SERIF, Font.PLAIN, e.size)
                    TextStyle.MONO -> Font(Font.MONOSPACED, Font.PLAIN, e.size)
                }
                val w = g.fontMetrics.stringWidth(e.text)
                val x = when (e.align) {
                    Align.LEFT -> plan.margin
                    Align.CENTER -> (plan.width - w) / 2
                    Align.RIGHT -> plan.width - plan.margin - w
                }
                g.drawString(e.text, x, e.top + g.fontMetrics.ascent)
            }
        }
        g.dispose()
        val px = IntArray(plan.width * plan.height)
        img.raster.getSamples(0, 0, plan.width, plan.height, 0, px)
        return GrayImage(plan.width, plan.height, px)
    }

    private fun BufferedImage.toGray(): GrayImage =
        GrayImage.fromArgb(width, height, getRGB(0, 0, width, height, null, 0, width))

    private fun BitImage.toPng(): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until height) for (x in 0 until width) out.setRGB(x, y, if (this[x, y]) 0 else 0xFFFFFF)
        return out
    }

    /** Smooth gradients and shaded circles — exercises the full tone range without needing real photos. */
    private fun synthetic(seed: Int): GrayImage {
        val w = 800; val h = 600
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
}
