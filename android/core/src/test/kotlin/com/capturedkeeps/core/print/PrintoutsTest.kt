package com.capturedkeeps.core.print

import com.capturedkeeps.core.domain.*
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class PrintoutsTest {
    @Test fun `status bytes are validated and decoded`() {
        assertNull(PrinterStatusQuery.parse(null, null))
        assertNull(PrinterStatusQuery.parse(0xFF, 0x00)) // garbage, not status replies
        assertEquals(PrinterStatus(false, false, false, false), PrinterStatusQuery.parse(0x12, 0x12))
        assertEquals("Printer is out of paper", PrinterStatusQuery.parse(0x12, 0x12 or 0x60)!!.problem)
        assertEquals("Printer cover is open", PrinterStatusQuery.parse(0x12 or 0x04, null)!!.problem)
        val low = PrinterStatusQuery.parse(null, 0x12 or 0x0C)!!
        assertTrue(low.paperLow && low.problem == null) // low paper warns but still prints
    }

    @Test fun `acknowledgment slip says it is not a receipt and fits 48 columns`() {
        val sale = LedgerEntry.Sale(id = "abcdef12-3456", at = Instant.parse("2026-11-23T10:30:00Z"), byStaffId = "s",
            eventId = "e", packageId = "p", amount = Centavos.pesos(150), path = PaymentPath.CASH_VIA_STAFF,
            receivedCashBy = "s", tendered = Centavos.pesos(200), qrReference = "REF-1")
        val bytes = Printouts.acknowledgment(sale, "Maria & Jose's Wedding", "Classic", "Niño", ZoneOffset.UTC)
        val text = String(bytes, charset("IBM437"))
        assertEquals(2, Regex("NOT AN OFFICIAL RECEIPT/INVOICE").findAll(text).count())
        for (want in listOf("PAYMENT ACKNOWLEDGMENT", "Nov 23, 2026  10:30 AM", "PHP 150.00", "PHP 200.00", "PHP 50.00", "REF-1", "ABCDEF12", "Niño"))
            assertTrue(want in text, want)
        assertFalse("₱" in text)
        val lines = text.split('\n').map { it.filter { c -> c.code >= 0x20 } }
        assertTrue(lines.all { it.length <= 48 + 4 }, lines.maxBy { it.length }) // + stray control-byte chars
        assertTrue(bytes.toList().windowed(4).any { it == listOf<Byte>(0x1D, 0x56, 0x42, 0x00) }) // cut
    }

    @Test fun `test page is a valid job at the configured width`() {
        val bytes = Printouts.testPage(512, 64, 3, cut = false, tuning = PrintTuning())
        assertEquals(listOf<Byte>(0x1B, 0x40), bytes.take(2))
        assertFalse(bytes.toList().windowed(4).any { it == listOf<Byte>(0x1D, 0x56, 0x42, 0x00) })
        assertTrue(bytes.toList().windowed(6).any { it == listOf<Byte>(0x1D, 0x76, 0x30, 0x00, 64, 0) }) // 512/8 bytes per row
    }
}
