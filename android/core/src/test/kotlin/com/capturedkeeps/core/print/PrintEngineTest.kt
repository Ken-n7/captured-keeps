package com.capturedkeeps.core.print

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrintEngineTest {
    private fun solid(w: Int, h: Int, v: Int) = GrayImage(w, h, IntArray(w * h) { v })

    @Test fun `rejects invalid images`() {
        assertFailsWith<IllegalArgumentException> { GrayImage(0, 5) }
        assertFailsWith<IllegalArgumentException> { GrayImage(2, 2, IntArray(3)) }
        assertFailsWith<IllegalArgumentException> { GrayImage(1, 1, intArrayOf(300)) }
    }

    @Test fun `fitCrop takes the center`() {
        // 200x100: left half black, right half white. Square center crop = x 50..150.
        val src = GrayImage(200, 100, IntArray(200 * 100) { if (it % 200 < 100) 0 else 255 })
        val out = src.fitCrop(50, 50)
        assertEquals(0, out[0, 25])
        assertEquals(255, out[49, 25])
    }

    @Test fun `dither keeps solids solid and grey half black`() {
        for (style in DitherStyle.entries) {
            assertTrue(solid(40, 40, 255).dither(style).black.none { it }, "$style white")
            assertTrue(solid(40, 40, 0).dither(style).black.all { it }, "$style black")
        }
        val grey = solid(100, 100, 128).dither(DitherStyle.FLOYD_STEINBERG).black.count { it }
        assertTrue(grey in 4500..5500, "50% grey gave $grey/10000 black dots")
    }

    @Test fun `tuning lifts midtones and validates ranges`() {
        val mid = solid(4, 4, 128).tuned(PrintTuning(autoLevels = false))
        assertTrue(mid[0, 0] > 128)
        assertFailsWith<IllegalArgumentException> { PrintTuning(brightness = 101) }
        assertFailsWith<IllegalArgumentException> { PrintTuning(gamma = 0.0) }
    }

    @Test fun `raster encodes MSB-first rows with band headers`() {
        // 10x1: dots at x=0 and x=9 → bytes 0b1000_0000, 0b0100_0000
        val img = BitImage(10, 1, BooleanArray(10) { it == 0 || it == 9 })
        assertContentEquals(
            byteArrayOf(0x1D, 0x76, 0x30, 0x00, 2, 0, 1, 0, 0x80.toByte(), 0x40),
            EscPos.raster(img),
        )
        val tall = BitImage(8, 300, BooleanArray(8 * 300))
        assertEquals(300 + 3 * 8, EscPos.raster(tall, bandHeight = 128).size) // 3 bands
    }

    @Test fun `print job rejects images wider than the paper`() {
        assertFailsWith<IllegalArgumentException> { EscPos.printJob(BitImage(577, 1, BooleanArray(577))) }
        val job = EscPos.printJob(BitImage(576, 1, BooleanArray(576)))
        assertContentEquals(byteArrayOf(0x1B, 0x40), job.copyOfRange(0, 2))
    }

    @Test fun `layout validates settings`() {
        assertFailsWith<IllegalArgumentException> { StripLayout.CLASSIC_3.copy(shots = 5) }
        assertFailsWith<IllegalArgumentException> { StripLayout.CLASSIC_3.copy(footerText = "x".repeat(41)) }
        assertFailsWith<IllegalArgumentException> { StripLayout.CLASSIC_3.copy(name = " ") }
    }

    @Test fun `every preset plans photos inside the paper with no overlaps`() {
        val info = StripInfo("Maria & Jose's Wedding", LocalDateTime.of(2026, 11, 23, 18, 42), 42)
        for (layout in StripLayout.PRESETS) {
            val plan = layout.plan(info)
            assertEquals(layout.shots, plan.photos.size, layout.name)
            plan.photos.forEach { assertTrue(it.left >= 0 && it.left + it.width <= plan.width, layout.name) }
            // Elements sharing a line (date left / number right) start at the same top; otherwise stacked.
            plan.elements.groupBy { it.top }.values.map { g -> g.maxBy { it.height } }.sortedBy { it.top }
                .zipWithNext().forEach { (a, b) -> assertTrue(b.top >= a.top + a.height, "${layout.name}: $a overlaps $b") }
            assertTrue(plan.elements.all { it.top + it.height <= plan.height }, layout.name)
        }
    }

    @Test fun `hidden parts are left out`() {
        val bare = StripLayout.CLASSIC_3.copy(showHeader = false, showEventName = false, showDate = false,
            showTime = false, showNumber = false, footerText = "")
        val plan = bare.plan(StripInfo("x", LocalDateTime.now(), 1))
        assertTrue(plan.elements.all { it is StripElement.Photo })
    }
}
