package com.capturedkeeps.data

import androidx.room.Room
import androidx.sqlite.SQLiteException
import androidx.room.useWriterConnection
import com.capturedkeeps.core.domain.*
import com.capturedkeeps.core.print.StripLayout
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class StoreTest {
    private val now = Instant.parse("2026-11-23T10:00:00Z")
    private val owner = Staff(name = "Owner", role = Role.OWNER)
    private val ana = Staff(name = "Ana", role = Role.ATTENDANT)
    private val layout = StripLayout.CLASSIC_3
    private val basic = SessionPackage(id = "basic", name = "Basic", price = Centavos.pesos(150))
    private val deluxe = SessionPackage(id = "deluxe", name = "Deluxe", price = Centavos.pesos(250), copies = 2)
    private val event = Event(name = "Wedding", type = EventType.PAID, status = EventStatus.LIVE, venue = "Hall A",
        startDate = LocalDate.of(2026, 12, 1), packages = listOf(basic, deluxe), layoutIds = listOf(layout.id, "square-4"))

    private val db = Room.inMemoryDatabaseBuilder<CkDatabase>().open()
    private val store = Store(db)

    @AfterTest fun close() = db.close()

    private fun test(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun paidSale(): LedgerEntry.Sale =
        store.ledger().sale(event, "basic", PaymentPath.CASH_VIA_STAFF, ana, now, Centavos.pesos(200), "REF1234")
            .also { store.append(it) }

    private fun session(sale: LedgerEntry.Sale?) = Session.start(event, "basic", layout, "tab-1", now, sale)

    @Test fun `event with packages round-trips in order`() = test {
        store.saveEvent(event)
        assertEquals(event, store.event(event.id))
        assertEquals(listOf(event), store.events())
    }

    @Test fun `event edit saves and writes audit lines`() = test {
        store.saveEvent(event)
        val edited = store.editEvent(event.copy(welcomeText = "Smile!", packages = listOf(basic)), owner, now)
        assertEquals(edited, store.event(event.id))
        assertEquals(setOf("welcomeText", "packages"), store.audit(event.id).map { it.field }.toSet())
        assertFailsWith<RuleBroken> { store.editEvent(event.copy(id = "nope"), owner, now) }
    }

    @Test fun `ledger round-trips all entry kinds and stays append-only`() = test {
        store.saveEvent(event)
        val sale = paidSale()
        val refund = store.ledger().refund(sale.id, Centavos.pesos(50), owner, "Blurry", now)
        store.append(refund)
        store.editEvent(event.copy(venue = "Hall B"), owner, now) // one audit row to try deleting
        val ledger = store.ledger()
        assertEquals(listOf(sale, refund), ledger.entries)
        assertEquals(Centavos.pesos(100), ledger.summary().net)
        assertEquals(Centavos.pesos(50), (ledger.entries[0] as LedgerEntry.Sale).change)

        for (sql in listOf("UPDATE ledger SET amount = 1", "DELETE FROM ledger", "DELETE FROM audit")) {
            assertFailsWith<SQLiteException>(sql) { db.useWriterConnection { it.usePrepared(sql) { s -> s.step() } } }
        }
        assertEquals(2, store.ledger().entries.size)
    }

    @Test fun `one payment unlocks one session, cancelled session frees it`() = test {
        store.saveEvent(event)
        val sale = paidSale()
        val first = session(sale)
        store.startSession(first)
        assertFailsWith<RuleBroken> { store.startSession(session(sale)) }
        // DB backs it up even if the rule check were skipped
        assertFailsWith<SQLiteException> { db.dao().insertSession(session(sale).row()) }

        store.updateSession(first.cancel(EndReason.CUSTOMER_CANCELLED, now))
        store.startSession(session(sale)) // allowed again
        assertEquals(2, store.sessionsForSale(sale.id).size)
    }

    @Test fun `restart recovery cancels interrupted sessions and flags mid-send prints`() = test {
        store.saveEvent(event)
        val s = session(paidSale())
        store.startSession(s)
        val printing = s.beginCapture().review().print()
        store.updateSession(printing)
        val job = PrintJob(sessionId = s.id, imagePath = "/strips/1.png", copies = 1, createdAt = now).send()
        store.savePrintJob(job)

        val r = store.recoverAfterRestart(now)
        assertEquals(PrintState.UNKNOWN, store.printJobs(s.id).single().state)
        assertEquals(listOf(job.id), r.needsAttendant.map { it.id })
        assertEquals(SessionState.PRINTING, store.session(s.id)!!.state) // printing isn't cancelled

        val other = session(paidSale())
        store.startSession(other.beginCapture())
        store.recoverAfterRestart(now)
        assertEquals(SessionState.CANCELLED, store.session(other.id)!!.state)
    }

    @Test fun `records survive closing and reopening the file`() = test {
        val file = File.createTempFile("ckdb", ".db").also { it.delete(); it.deleteOnExit() }
        Room.databaseBuilder<CkDatabase>(name = file.path).open().also { d ->
            Store(d).run { saveStaff(ana); saveEvent(event) }
            d.close()
        }
        Room.databaseBuilder<CkDatabase>(name = file.path).open().also { d ->
            assertEquals(listOf(ana), Store(d).staff())
            assertEquals(event, Store(d).event(event.id))
            d.close()
        }
    }

    @Test fun `owner setup, password and PIN login`() = test {
        assertTrue(store.needsSetup())
        store.saveStaff(owner, pin = "4321", password = "correct horse")
        store.saveStaff(ana, pin = "1111")
        assertFalse(store.needsSetup())
        assertFailsWith<RuleBroken> { store.saveStaff(ana, password = "attendants have none") }
        assertEquals(owner, store.ownerByPassword("correct horse"))
        assertNull(store.ownerByPassword("4321"))
        assertEquals(ana, store.staffByPin("1111"))
        store.saveStaff(ana.copy(name = "Ana R.")) // rename keeps the PIN
        assertEquals("Ana R.", store.staffByPin("1111")!!.name)
    }

    @Test fun `staff resolve prints left unknown by a crash`() = test {
        store.saveEvent(event)
        val sale1 = paidSale(); val sale2 = paidSale()
        val a = session(sale1).beginCapture().review().print()
        val b = session(sale2).beginCapture().review().print()
        for (s in listOf(a, b)) { store.startSession(s.copy(state = SessionState.STARTED)); store.updateSession(s) }
        val ja = PrintJob(sessionId = a.id, imagePath = "/a.png", copies = 1, createdAt = now).send()
        val jb = PrintJob(sessionId = b.id, imagePath = "/b.png", copies = 1, createdAt = now).send()
        store.savePrintJob(ja); store.savePrintJob(jb)
        store.recoverAfterRestart(now)
        assertEquals(setOf(ja.id, jb.id), store.printsToConfirm().map { it.id }.toSet())

        store.resolvePrint(ja.id, printed = true, now)
        assertEquals(SessionState.COMPLETED, store.session(a.id)!!.state)
        store.resolvePrint(jb.id, printed = false, now)
        assertEquals(listOf(jb.id), store.failedPrints().map { it.id })
        assertEquals(SessionState.PRINTING, store.session(b.id)!!.state)
        store.retryPrint(jb.id, now)
        assertEquals(SessionState.COMPLETED, store.session(b.id)!!.state)
        assertTrue(store.printsToConfirm().isEmpty() && store.failedPrints().isEmpty())
    }

    @Test fun `login unlocks owner tools only with the password`() = test {
        store.saveStaff(owner, pin = "4321", password = "correct horse")
        store.saveStaff(ana, pin = "1111")
        val gate = Gate { now }
        assertEquals(Login(owner, ownerTools = true), gate.login(store, "correct horse"))
        assertEquals(Login(owner, ownerTools = false), gate.login(store, "4321"))
        assertEquals(Login(ana, ownerTools = false), gate.login(store, "1111"))
        assertFailsWith<RuleBroken> { gate.login(store, "nope") }
    }

    @Test fun `event moves are checked and audited`() = test {
        val draft = event.copy(status = EventStatus.DRAFT)
        val other = event.copy(id = "other", name = "Other", status = EventStatus.DRAFT, packages = listOf(SessionPackage(name = "X", price = Centavos.pesos(99))))
        store.saveEvent(draft); store.saveEvent(other)
        store.moveEvent(draft.id, EventStatus.LIVE, owner, now)
        assertTrue("already live" in assertFailsWith<RuleBroken> { store.moveEvent(other.id, EventStatus.LIVE, owner, now) }.message!!)
        store.moveEvent(draft.id, EventStatus.COMPLETED, owner, now)
        store.moveEvent(draft.id, null, owner, now)
        assertTrue(store.event(draft.id)!!.hidden)
        assertEquals(listOf("draft→live", "live→completed", "false→true"), store.audit(draft.id).map { "${it.oldValue}→${it.newValue}" })
    }

    @Test fun `disabled staff can't log in, the last owner can't be disabled`() = test {
        store.saveStaff(owner, pin = "4321", password = "correct horse")
        store.saveStaff(ana, pin = "1111")
        store.saveStaff(ana.copy(active = false))
        assertNull(store.staffByPin("1111"))
        assertEquals(false, store.staff().single { it.id == ana.id }.active)
        assertTrue("only owner" in assertFailsWith<RuleBroken> { store.saveStaff(owner.copy(active = false)) }.message!!)
        store.saveStaff(ana.copy(active = true))
        assertEquals(ana, store.staffByPin("1111")) // PIN kept while disabled
    }

    @Test fun `payments list, void unused, refund used`() = test {
        store.saveEvent(event)
        val used = paidSale()
        store.startSession(session(used).copy(state = SessionState.STARTED))
        store.updateSession(store.sessionsForSale(used.id).single().beginCapture())
        val unused = paidSale()

        val before = store.payments(event.id).associateBy { it.sale.id }
        assertTrue(before[unused.id]!!.unused && before[unused.id]!!.session == null)
        assertEquals(SessionState.CAPTURING, before[used.id]!!.session)

        assertFailsWith<RuleBroken> { store.void(used.id, ana, "oops", now) } // session started → refund instead
        store.void(unused.id, ana, "Customer left after the crash", now)
        assertFailsWith<RuleBroken> { store.refund(used.id, Centavos.pesos(50), ana, "jam", now) } // owner only
        store.refund(used.id, Centavos.pesos(50), owner, "Smudged print", now)

        val after = store.payments(event.id).associateBy { it.sale.id }
        assertTrue(after[unused.id]!!.voided && !after[unused.id]!!.unused)
        assertEquals(Centavos.pesos(50), after[used.id]!!.refunded)
        assertEquals(Centavos.pesos(100), store.ledger().forEvent(event.id).summary().net)
    }

    @Test fun `layouts save as new versions, presets can be reset, used ones can't be deleted`() = test {
        assertEquals(StripLayout.PRESETS, store.layouts())
        val darker = store.saveLayout(layout.copy(tuning = layout.tuning.copy(brightness = -20)))
        assertEquals(2, darker.version)
        assertEquals(darker, store.layouts().first())
        assertEquals(darker, store.saveLayout(darker)) // unchanged → same version

        val mine = store.saveLayout(StripLayout(id = "mine", name = "Wedding 2", shots = 2, footerText = "Maria & Jose"))
        assertEquals(1, mine.version)
        assertEquals(listOf("classic-3", "square-4", "single-portrait", "mine"), store.layouts().map { it.id })

        store.saveEvent(event.copy(layoutIds = listOf("mine")))
        assertFailsWith<RuleBroken> { store.deleteLayout("mine") }
        store.saveEvent(event.copy(layoutIds = listOf(layout.id)))
        store.deleteLayout("mine")
        assertEquals(3, store.layouts().size)

        val reset = store.resetPreset(layout.id)
        assertEquals(3, reset.version)
        assertEquals(layout.tuning, reset.tuning)
    }
}
