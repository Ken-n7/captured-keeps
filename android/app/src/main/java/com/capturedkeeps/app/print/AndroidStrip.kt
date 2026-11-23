package com.capturedkeeps.app.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.capturedkeeps.core.print.*

/**
 * Paints a [StripPlan] with Android Canvas, then dithers the whole strip to printer dots.
 * Same plan + photo processing as the laptop preview (SampleStripTest); only fonts differ.
 */
object AndroidStrip {
    fun render(layout: StripLayout, info: StripInfo, photos: List<GrayImage>): BitImage {
        val plan = layout.plan(info)
        val bmp = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        for (e in plan.elements) when (e) {
            is StripElement.Rule -> canvas.drawRect(plan.margin.toFloat(), e.top.toFloat(),
                (plan.width - plan.margin).toFloat(), (e.top + e.thickness).toFloat(), paint)

            is StripElement.Photo -> {
                val p = photos[e.index % photos.size].forSlot(e, layout.tuning)
                bmp.setPixels(p.toArgb(), 0, p.width, e.left, e.top, p.width, p.height)
            }

            is StripElement.Text -> {
                paint.textSize = e.size.toFloat()
                paint.letterSpacing = if (e.style == TextStyle.SPACED_CAPS) 0.35f else 0f
                paint.typeface = when (e.style) {
                    TextStyle.WORDMARK, TextStyle.FOOTER -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    TextStyle.SPACED_CAPS, TextStyle.BODY -> Typeface.SANS_SERIF
                    TextStyle.MONO -> Typeface.MONOSPACE
                }
                val (align, x) = when (e.align) {
                    Align.LEFT -> Paint.Align.LEFT to plan.margin
                    Align.CENTER -> Paint.Align.CENTER to plan.width / 2
                    Align.RIGHT -> Paint.Align.RIGHT to plan.width - plan.margin
                }
                paint.textAlign = align
                canvas.drawText(e.text, x.toFloat(), e.top - paint.fontMetrics.ascent, paint)
            }
        }

        val argb = IntArray(plan.width * plan.height)
        bmp.getPixels(argb, 0, plan.width, 0, 0, plan.width, plan.height)
        bmp.recycle()
        return GrayImage.fromArgb(plan.width, plan.height, argb).dither(layout.tuning.dither)
    }

    private fun GrayImage.toArgb() = IntArray(pixels.size) { val v = pixels[it]; Color.rgb(v, v, v) }
}

/** Printer dots → bitmap for the virtual printer screen. */
fun BitImage.toBitmap(): Bitmap {
    val px = IntArray(black.size) { if (black[it]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
}
