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
}
