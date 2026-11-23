package com.capturedkeeps.core.print

import com.capturedkeeps.core.domain.Centavos
import com.capturedkeeps.core.domain.LedgerEntry
import com.capturedkeeps.core.domain.PaymentPath
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the printer reported. Null fields = printer didn't answer that question (many cheap ones don't). */
data class PrinterStatus(val paperOut: Boolean, val paperLow: Boolean, val coverOpen: Boolean, val error: Boolean) {
    /** The one message staff need, or null if it can print. */
    val problem get() = when {
        coverOpen -> "Printer cover is open"
        paperOut -> "Printer is out of paper"
        error -> "Printer reports an error — turn it off and on"
        else -> null
    }
}

/** ESC/POS real-time status (DLE EOT). Transport sends [OFFLINE_QUERY] then [PAPER_QUERY], one reply byte each. */
object PrinterStatusQuery {
    val OFFLINE_QUERY = byteArrayOf(0x10, 0x04, 0x02)
    val PAPER_QUERY = byteArrayOf(0x10, 0x04, 0x04)

    /** Status bytes always have bit1 and bit4 set, bit0 and bit7 clear — anything else isn't a status reply. */
    private fun valid(b: Int) = b and 0x93 == 0x12

    fun parse(offline: Int?, paper: Int?): PrinterStatus? {
        val o = offline?.takeIf(::valid)
        val p = paper?.takeIf(::valid)
        if (o == null && p == null) return null
        return PrinterStatus(
            paperOut = (p != null && p and 0x60 != 0) || (o != null && o and 0x20 != 0),
            paperLow = p != null && p and 0x0C != 0,
            coverOpen = o != null && o and 0x04 != 0,
            error = o != null && o and 0x40 != 0,
        )
    }
}

/** Text-mode printouts (fast and crisp; no image needed). Font A on 80mm = 48 characters per line. */
class TextPrintout(private val width: Int = 48) {
    private val out = ByteArrayOutputStream().apply { write(byteArrayOf(0x1B, 0x40, 0x1B, 0x74, 0x00)) } // init, code page PC437

    fun center(on: Boolean = true) = apply { out.write(byteArrayOf(0x1B, 0x61, if (on) 1 else 0)) }
    fun bold(on: Boolean = true) = apply { out.write(byteArrayOf(0x1B, 0x45, if (on) 1 else 0)) }
    fun big(on: Boolean = true) = apply { out.write(byteArrayOf(0x1D, 0x21, if (on) 0x11 else 0x00)) }
    fun line(text: String = "") = apply { out.write(encode(text)); out.write(0x0A) }
    fun rule() = line("-".repeat(width))

    /** Label on the left, value on the right; long values wrap under the label. */
    fun pair(label: String, value: String) = apply {
        val gap = width - label.length - value.length
        if (gap >= 1) line(label + " ".repeat(gap) + value) else { line(label); line(value.padStart(width)) }
    }

    fun finish(feedLines: Int = 4, cut: Boolean = true): ByteArray {
        out.write(byteArrayOf(0x1B, 0x64, feedLines.toByte()))
        if (cut) out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))
        return out.toByteArray()
    }

    private fun encode(text: String): ByteArray {
        // ₱ isn't in the printer's character set; ñ/é etc. are (PC437). Anything else prints as '?'.
        val encoder = Charset.forName("IBM437").newEncoder()
            .onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith(byteArrayOf('?'.code.toByte()))
        val buf: ByteBuffer = encoder.encode(CharBuffer.wrap(text.replace("₱", "PHP ")))
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }
}

object Printouts {
    private val WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a")

    private fun money(c: Centavos) = "PHP %,d.%02d".format(c.value / 100, c.value % 100)

    /** Given on request after a payment. Never looks like a tax receipt (PROJECT_NOTES §2). */
    fun acknowledgment(
        sale: LedgerEntry.Sale,
        eventName: String,
        packageName: String,
        staffName: String,
        zone: ZoneId,
        footer: String = "Little moments. Lifelong keeps.",
        feedLines: Int = 4,
        cut: Boolean = true,
    ): ByteArray = TextPrintout().run {
        center().big().line("CAPTURED KEEPS").big(false).line("RECEIPT PHOTO STUDIO").line()
        bold().line("PAYMENT ACKNOWLEDGMENT").bold(false)
        line("NOT AN OFFICIAL RECEIPT/INVOICE").center(false).rule()
        pair("Event", eventName)
        pair("Date", WHEN.format(sale.at.atZone(zone)))
        pair("Package", packageName)
        bold().pair("Amount", money(sale.amount)).bold(false)
        pair("Paid via", if (sale.path == PaymentPath.CUSTOMER_QR) "E-wallet QR" else "Cash")
        sale.tendered?.let { pair("Cash received", money(it)) }
        sale.change?.let { pair("Change", money(it)) }
        sale.qrReference?.let { pair("Wallet ref.", it) }
        pair("Staff", staffName)
        pair("Ack. no.", sale.id.take(8).uppercase())
        rule().center().line(footer).line("NOT AN OFFICIAL RECEIPT/INVOICE")
        finish(feedLines, cut)
    }

    /** For setting up a printer: text, a gray ramp to judge darkness, and a ruler to check the width. */
    fun testPage(printerDots: Int, bandHeight: Int, feedLines: Int, cut: Boolean, tuning: PrintTuning): ByteArray {
        val text = TextPrintout().center().big().line("CAPTURED KEEPS").big(false)
            .line("Printer test").line("Width $printerDots dots, band $bandHeight").line("Ramp: white -> black, then a sample photo")
            .center(false).finish(feedLines = 1, cut = false)
        val ramp = GrayImage(printerDots, 96, IntArray(printerDots * 96) { i ->
            val x = i % printerDots; val y = i / printerDots
            if (y < 8) (if ((x / 8) % 2 == 0) 0 else 255) else 255 - x * 255 / (printerDots - 1)
        }).dither(tuning.dither)
        val photo = SamplePhotos.synthetic(1).fitCrop(printerDots, printerDots * 3 / 4).tuned(tuning).dither(tuning.dither)
        return text + EscPos.raster(ramp, bandHeight) + EscPos.printJob(photo, printerDots, feedLines, cut, bandHeight)
    }
}
