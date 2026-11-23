package com.capturedkeeps.core.domain

import com.capturedkeeps.core.print.StripLayout
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainTest {
    private val now = Instant.parse("2026-11-23T10:00:00Z")
    private val owner = Staff(name = "Owner", role = Role.OWNER)
    private val ana = Staff(name = "Ana", role = Role.ATTENDANT)
    private val layout = StripLayout.CLASSIC_3
    private val basic = SessionPackage(id = "basic", name = "Basic", price = Centavos.pesos(150))
    private val paid = Event(name = "Wedding", type = EventType.PAID, status = EventStatus.LIVE,
        packages = listOf(basic), layoutIds = listOf(layout.id))
    private val freePkg = SessionPackage(id = "free", name = "Free", price = Centavos.ZERO)
    private val free = Event(name = "Launch", type = EventType.FREE, status = EventStatus.LIVE,
        packages = listOf(freePkg), layoutIds = listOf(layout.id))

    private fun broken(block: () -> Unit) = assertFailsWith<RuleBroken>(block = block).message!!

    // ── Money ──────────────────────────────────────────────
    @Test fun `money parses staff input exactly`() {
        assertEquals(15050, Centavos.parse("150.50").value)
        assertEquals(150000, Centavos.parse("₱1,500").value)
        assertEquals("₱1,500.05", Centavos(150005).toString())
        broken { Centavos.parse("150.555") }
        broken { Centavos.parse("-5") }
        broken { Centavos.parse("abc") }
        broken { Centavos.parse("10000000") }
        broken { Centavos(100) - Centavos(200) }
    }

    // ── Events ─────────────────────────────────────────────
    @Test fun `event validation`() {
        broken { Event(name = " ", type = EventType.FREE) }
        broken { Event(name = "X", type = EventType.FREE, packages = listOf(basic)) }
        broken { Event(name = "X", type = EventType.FREE, startDate = LocalDate.of(2026, 12, 2), endDate = LocalDate.of(2026, 12, 1)) }
        broken { Event(name = "X", type = EventType.FREE, hidden = true) }
    }

    @Test fun `going live needs packages and layouts, and only one live event`() {
        val draft = paid.copy(status = EventStatus.DRAFT, packages = emptyList(), layoutIds = emptyList())
        val msg = broken { draft.moveTo(EventStatus.LIVE, anotherEventLive = false, mediaPlayable = true) }
        assertTrue("package" in msg && "layout" in msg)
        val ready = paid.copy(status = EventStatus.DRAFT)
        broken { ready.moveTo(EventStatus.LIVE, anotherEventLive = true, mediaPlayable = true) }
        assertEquals(EventStatus.LIVE, ready.moveTo(EventStatus.LIVE, false, mediaPlayable = false).status)
        assertEquals(1, ready.goLiveCheck(mediaPlayable = false).warnings.size) // fallback screen, not a blocker
    }

    @Test fun `event status moves`() {
        val p = paid.moveTo(EventStatus.PAUSED, false, true)
        assertEquals(EventStatus.LIVE, p.moveTo(EventStatus.LIVE, false, true).status)
        val done = p.moveTo(EventStatus.COMPLETED, false, true)
        broken { done.moveTo(EventStatus.LIVE, false, true) }
        broken { paid.moveTo(EventStatus.CANCELLED, false, true) }
        assertTrue(done.hide().hidden)
        broken { paid.hide() }
        assertTrue(paid.copy(status = EventStatus.DRAFT, startDate = LocalDate.of(2026, 12, 1)).isScheduled(LocalDate.of(2026, 11, 23)))
    }

    @Test fun `edits are audited and finished events are read-only`() {
        val (edited, log) = paid.edit(paid.copy(name = "Maria & Jose", welcomeText = "Welcome!"), owner, now)
        assertEquals("Maria & Jose", edited.name)
        assertEquals(setOf("name", "welcomeText"), log.map { it.field }.toSet())
        assertEquals("Wedding", log.first { it.field == "name" }.oldValue)
        broken { paid.edit(paid.copy(status = EventStatus.PAUSED), owner, now) }
        val done = paid.moveTo(EventStatus.COMPLETED, false, true)
        broken { done.edit(done.copy(name = "New"), owner, now) }
    }

    // ── Sessions ───────────────────────────────────────────
    @Test fun `free session needs no payment and walks the happy path`() {
        var s = Session.start(free, "free", layout, "tab-1", now, sale = null)
        s = s.beginCapture().review().retake().review().print().complete(now)
        assertEquals(SessionState.COMPLETED, s.state)
        assertEquals(1, s.retakesUsed)
    }

    @Test fun `paid session requires a confirmed payment`() {
        assertTrue("Payment" in broken { Session.start(paid, "basic", layout, "tab-1", now, sale = null) })
        val sale = Ledger().sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now, qrReference = "GC12345")
        assertEquals(sale.id, Session.start(paid, "basic", layout, "tab-1", now, sale).saleId)
    }

    @Test fun `session keeps its package snapshot after the event price changes`() {
        val s = Session.start(free, "free", layout, "tab-1", now, null)
        val (edited, _) = free.edit(free.copy(packages = listOf(freePkg.copy(copies = 3))), owner, now)
        assertEquals(1, s.pkg.copies)
        assertEquals(3, edited.packages.single().copies)
    }

    @Test fun `retake limit and illegal moves`() {
        val s = Session.start(free, "free", layout, "tab-1", now, null).beginCapture().review()
        broken { s.copy(retakesUsed = 2).retake() }
        broken { s.complete(now) }
        val ended = s.cancel(EndReason.TIMEOUT, now)
        broken { ended.fail(EndReason.OTHER, now) }
        assertEquals(EndReason.TIMEOUT, ended.endReason)
    }

    // ── Ledger ─────────────────────────────────────────────
    @Test fun `cash via staff computes change and validates tendered`() {
        val l = Ledger()
        broken { l.sale(paid, "basic", PaymentPath.CASH_VIA_STAFF, ana, now) }
        broken { l.sale(paid, "basic", PaymentPath.CASH_VIA_STAFF, ana, now, tendered = Centavos.pesos(100)) }
        broken { l.sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now, tendered = Centavos.pesos(200)) }
        broken { l.sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now, qrReference = "x") }
        broken { l.sale(free, "free", PaymentPath.CUSTOMER_QR, ana, now) }
        val s = l.sale(paid, "basic", PaymentPath.CASH_VIA_STAFF, ana, now, tendered = Centavos.pesos(200))
        assertEquals(Centavos.pesos(50), s.change)
        assertEquals(ana.id, s.receivedCashBy)
    }

    @Test fun `one payment unlocks one session, cancelled sessions free it again`() {
        var l = Ledger()
        val sale = l.sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now).also { l += it }
        val first = Session.start(paid, "basic", layout, "tab-1", now, l.authorize(sale.id, emptyList()))
        broken { l.authorize(sale.id, listOf(first)) }
        val crashed = first.beginCapture().cancel(EndReason.APP_RESTART, now)
        assertEquals(sale, l.authorize(sale.id, listOf(crashed))) // attendant can resume for the customer
    }

    @Test fun `void only before capture, refund owner only and capped`() {
        var l = Ledger()
        val sale = l.sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now).also { l += it }
        val session = Session.start(paid, "basic", layout, "tab-1", now, l.authorize(sale.id, emptyList())).beginCapture()
        assertTrue("refund" in broken { l.void(sale.id, ana, "mistake", listOf(session), now) })
        broken { l.refund(sale.id, Centavos.pesos(50), ana, "jam", now) }
        broken { l.refund(sale.id, Centavos.pesos(50), owner, " ", now) }
        l += l.refund(sale.id, Centavos.pesos(100), owner, "print jam", now)
        broken { l.refund(sale.id, Centavos.pesos(60), owner, "again", now) }
        l += l.refund(sale.id, Centavos.pesos(50), owner, "rest", now)
        broken { l.void(sale.id, owner, "x", emptyList(), now) }

        var l2 = Ledger()
        val s2 = l2.sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now).also { l2 += it }
        l2 += l2.void(s2.id, ana, "wrong package", emptyList(), now)
        broken { l2.authorize(s2.id, emptyList()) }
    }

    @Test fun `summary totals, cash held and missing references`() {
        var l = Ledger()
        val bob = Staff(name = "Bob", role = Role.ATTENDANT)
        l += l.sale(paid, "basic", PaymentPath.CASH_VIA_STAFF, ana, now, tendered = Centavos.pesos(150), qrReference = "R0001")
        l += l.sale(paid, "basic", PaymentPath.CASH_VIA_STAFF, ana, now, tendered = Centavos.pesos(200), qrReference = "R0002")
        l += l.sale(paid, "basic", PaymentPath.CASH_VIA_STAFF, bob, now, tendered = Centavos.pesos(150))
        val qr = l.sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now, qrReference = "R0004").also { l += it }
        val voided = l.sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now).also { l += it }
        l += l.void(voided.id, ana, "duplicate", emptyList(), now)
        l += l.refund(qr.id, Centavos.pesos(50), owner, "late print", now)

        val sum = l.summary()
        assertEquals(Centavos.pesos(600), sum.gross)
        assertEquals(Centavos.pesos(550), sum.net)
        assertEquals(Centavos.pesos(450), sum.byPath[PaymentPath.CASH_VIA_STAFF])
        assertEquals(mapOf(ana.id to Centavos.pesos(300), bob.id to Centavos.pesos(150)), sum.cashHeldBy)
        assertEquals(1, sum.missingReference.size) // bob's; the voided one doesn't count
        assertEquals(7, l.entries.size) // append-only: nothing removed
    }

    // ── Printing & recovery ────────────────────────────────
    @Test fun `print job retry and reprint rules`() {
        var job = PrintJob(sessionId = "s", imagePath = "/strips/s.png", copies = 1, createdAt = now)
        job = job.send().failed("Printer disconnected").retry().send()
        assertEquals(2, job.attempts)
        broken { job.reprint("damaged", ana, now) }
        job = job.done()
        broken { job.reprint(" ", ana, now) }
        val again = job.reprint("customer dropped it", ana, now)
        assertEquals(job.id, again.reprintOf)
        assertEquals(PrintState.QUEUED, again.state)
    }

    @Test fun `restart cancels interrupted sessions and never auto-resends a print`() {
        val capturing = Session.start(free, "free", layout, "tab-1", now, null).beginCapture()
        val printing = Session.start(free, "free", layout, "tab-1", now, null).beginCapture().review().print()
        val sending = PrintJob(sessionId = printing.id, imagePath = "/p.png", copies = 1, createdAt = now).send()
        val queued = PrintJob(sessionId = "other", imagePath = "/q.png", copies = 1, createdAt = now)

        val r = recoverAfterRestart(listOf(capturing, printing), listOf(sending, queued), now)
        assertEquals(EndReason.APP_RESTART, r.sessions[0].endReason)
        assertEquals(SessionState.PRINTING, r.sessions[1].state)
        assertEquals(listOf(sending.id), r.needsAttendant.map { it.id })
        assertEquals(PrintState.QUEUED, r.jobs[1].state)
        assertEquals(PrintState.DONE, r.needsAttendant.single().resolveUnknown(printed = true).state)
        assertEquals(PrintState.FAILED, r.needsAttendant.single().resolveUnknown(printed = false).state)
    }

    // ── Device ─────────────────────────────────────────────
    @Test fun `storage thresholds and sync deletion rule`() {
        assertEquals(StorageStatus.OK, storageStatus(5L shl 30))
        assertEquals(StorageStatus.WARN, storageStatus(1L shl 30))
        assertEquals(StorageStatus.BLOCK, storageStatus(100L shl 20))
        assertTrue(SyncState.SYNCED.canDeleteLocally)
        assertFalse(SyncState.ERROR.canDeleteLocally)
        assertNull(Ledger().sale(paid, "basic", PaymentPath.CUSTOMER_QR, ana, now).receivedCashBy)
    }
}
