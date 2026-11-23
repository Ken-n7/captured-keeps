package com.capturedkeeps.core.print

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PhotoShape(val ratioW: Int, val ratioH: Int) {
    LANDSCAPE(4, 3), SQUARE(1, 1), PORTRAIT(3, 4)
}

/** Customizable strip design (PROJECT_NOTES §5.1). Saved as data; sessions record [id] + [version]. */
data class StripLayout(
    val id: String,
    val name: String,
    val version: Int = 1,
    val shots: Int = 3,
    val shape: PhotoShape = PhotoShape.LANDSCAPE,
    val showHeader: Boolean = true,
    val headerText: String = "CAPTURED KEEPS",
    val subheaderText: String = "RECEIPT PHOTO STUDIO",
    val showEventName: Boolean = true,
    val showDate: Boolean = true,
    val showTime: Boolean = true,
    val showNumber: Boolean = true,
    val footerText: String = "Little moments. Lifelong keeps.",
    val margin: Int = 24,
    val gap: Int = 18,
    val tuning: PrintTuning = PrintTuning(),
    // ponytail: logo image + PNG overlay not yet supported; add when the real logo/overlay files exist.
) {
    init {
        require(id.isNotBlank()) { "Layout id is required" }
        require(name.isNotBlank()) { "Layout name is required" }
        require(version >= 1) { "Version must be >= 1" }
        require(shots in 1..4) { "Shots per strip must be 1..4, was $shots" }
        require(margin in 0..120) { "Margin must be 0..120 dots, was $margin" }
        require(gap in 0..120) { "Gap must be 0..120 dots, was $gap" }
        require(headerText.length <= MAX_HEADER) { "Header text max $MAX_HEADER characters" }
        require(subheaderText.length <= MAX_LINE) { "Subheader text max $MAX_LINE characters" }
        require(footerText.length <= MAX_LINE) { "Footer text max $MAX_LINE characters" }
    }

    companion object {
        const val MAX_HEADER = 16
        const val MAX_LINE = 40

        val CLASSIC_3 = StripLayout(id = "classic-3", name = "Classic 3")
        val SQUARE_4 = StripLayout(id = "square-4", name = "Square 4", shots = 4, shape = PhotoShape.SQUARE)
        val SINGLE_PORTRAIT = StripLayout(id = "single-portrait", name = "Single portrait", shots = 1, shape = PhotoShape.PORTRAIT)
        val PRESETS = listOf(CLASSIC_3, SQUARE_4, SINGLE_PORTRAIT)
    }
}

/** What changes per print. */
data class StripInfo(val eventName: String, val time: LocalDateTime, val number: Int)

enum class TextStyle { WORDMARK, SPACED_CAPS, BODY, MONO, FOOTER }
enum class Align { LEFT, CENTER, RIGHT }

/** Positioned pieces of a strip. Platform drawers (Android Canvas, JVM preview) just paint these. */
sealed interface StripElement {
    val top: Int
    val height: Int

    data class Rule(override val top: Int, val thickness: Int) : StripElement {
        override val height get() = thickness
    }

    data class Text(
        override val top: Int,
        val text: String,
        val style: TextStyle,
        val size: Int,
        val align: Align = Align.CENTER,
    ) : StripElement {
        override val height get() = (size * 1.3).toInt()
    }

    data class Photo(override val top: Int, val index: Int, val left: Int, val width: Int, override val height: Int) : StripElement
}

data class StripPlan(val width: Int, val height: Int, val margin: Int, val elements: List<StripElement>) {
    val photos get() = elements.filterIsInstance<StripElement.Photo>()
}

private val DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** Lays the strip out top to bottom. Pure math — no fonts or pixels involved. */
fun StripLayout.plan(info: StripInfo, width: Int = PAPER_80MM_DOTS): StripPlan {
    val photoWidth = width - 2 * margin
    require(photoWidth > 0) { "Margins leave no room for photos" }
    val els = mutableListOf<StripElement>()
    var y = 30

    fun rule(thickness: Int, after: Int) {
        els += StripElement.Rule(y, thickness); y += thickness + after
    }
    fun text(t: String, style: TextStyle, size: Int, after: Int) {
        val e = StripElement.Text(y, t, style, size); els += e; y += e.height + after
    }

    if (showHeader) {
        rule(2, 14)
        if (headerText.isNotBlank()) text(headerText, TextStyle.WORDMARK, 46, 2)
        if (subheaderText.isNotBlank()) text(subheaderText, TextStyle.SPACED_CAPS, 16, 10)
        rule(2, 22)
    }
    if (showEventName && info.eventName.isNotBlank()) text(info.eventName.take(MAX_EVENT_NAME), TextStyle.BODY, 24, 14)

    val photoHeight = photoWidth * shape.ratioH / shape.ratioW
    repeat(shots) {
        els += StripElement.Photo(y, it, margin, photoWidth, photoHeight)
        y += photoHeight + gap
    }

    val meta = listOfNotNull(
        info.time.format(DATE).uppercase().takeIf { showDate },
        info.time.format(TIME).takeIf { showTime },
    ).joinToString("   ")
    if (meta.isNotEmpty() || showNumber) {
        if (meta.isNotEmpty()) els += StripElement.Text(y, meta, TextStyle.MONO, 20, Align.LEFT)
        if (showNumber) els += StripElement.Text(y, "No. %04d".format(info.number), TextStyle.MONO, 20, Align.RIGHT)
        y += StripElement.Text(y, "", TextStyle.MONO, 20).height + 10
    }
    if (footerText.isNotBlank()) {
        rule(1, 18)
        text(footerText, TextStyle.FOOTER, 22, 0)
    }
    return StripPlan(width, y + 24, margin, els)
}

const val MAX_EVENT_NAME = 32

/** Crop/scale a captured photo into its slot and apply print tuning. Text stays crisp because only photos are tuned. */
fun GrayImage.forSlot(slot: StripElement.Photo, tuning: PrintTuning): GrayImage =
    fitCrop(slot.width, slot.height).tuned(tuning)
