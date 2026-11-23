package com.capturedkeeps.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.capturedkeeps.core.domain.*
import com.capturedkeeps.core.print.StripLayout
import java.time.Instant

/** The app's only way to read/write records. Domain rules run first, then rows are saved. */
class Store(private val db: CkDatabase) {
    private val dao = db.dao()

    private suspend fun <T> tx(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }

    /** [pin] null keeps the current PIN. */
    suspend fun saveStaff(staff: Staff, pin: String? = null) =
        dao.saveStaff(staff.row(pin?.let(Pin::hash) ?: dao.staffRow(staff.id)?.pinHash))
    suspend fun staff() = dao.staff().map { it.domain() }

    // ponytail: checks every staff hash (~20 ms each); fine for a handful of staff.
    suspend fun staffByPin(pin: String): Staff? =
        dao.staff().firstOrNull { it.pinHash != null && Pin.matches(pin, it.pinHash) }?.domain()

    suspend fun liveEvent() = events().firstOrNull { it.status == EventStatus.LIVE }

    suspend fun event(id: String) = dao.eventRow(id)?.domain(dao.packages(id))
    suspend fun events() = dao.eventRows().map { it.domain(dao.packages(it.id)) }

    /** New events and status moves ([moveTo], [hide]). Field edits go through [editEvent] so they're audited. */
    suspend fun saveEvent(event: Event) = dao.saveEvent(event.row(), event.packageRows())

    suspend fun editEvent(updated: Event, by: Staff, at: Instant): Event = tx {
        val current = event(updated.id) ?: throw RuleBroken("Event not found")
        val (saved, log) = current.edit(updated, by, at)
        dao.saveEvent(saved.row(), saved.packageRows())
        dao.appendAudit(log.map { it.row() })
        saved
    }

    /** ponytail: demo data (owner PIN 1234 + a live paid event) until the admin screens exist — delete then. */
    suspend fun seedDemoIfEmpty() {
        if (dao.eventRows().isNotEmpty()) return
        saveStaff(Staff(name = "Owner", role = Role.OWNER), pin = "1234")
        saveEvent(Event(name = "Demo Event", type = EventType.PAID, status = EventStatus.LIVE,
            packages = listOf(
                SessionPackage(name = "Classic", price = Centavos.pesos(150), copies = 1, retakes = 2),
                SessionPackage(name = "Duo", price = Centavos.pesos(250), copies = 2, retakes = 2),
            ),
            layoutIds = StripLayout.PRESETS.map { it.id }))
    }

    suspend fun audit(entityId: String) = dao.audit(entityId).map { it.domain() }

    // ponytail: loads the whole ledger each time; fine for thousands of sales, query per event if it grows.
    suspend fun ledger() = Ledger(dao.ledger().map { it.domain() })

    /** Sale, void or refund — created by [Ledger.sale]/[Ledger.void]/[Ledger.refund]. Never edited afterwards. */
    suspend fun append(entry: LedgerEntry) = dao.appendLedger(entry.row())

    /**
     * Paid sessions re-check the sale inside the same transaction; the DB index backs it up.
     * Pass [newSale] to record a just-confirmed payment together with its session (both or neither).
     */
    suspend fun startSession(session: Session, newSale: LedgerEntry.Sale? = null) = tx {
        newSale?.let { dao.appendLedger(it.row()) }
        session.saleId?.let { ledger().authorize(it, dao.sessionsForSale(it).map { r -> r.domain() }) }
        dao.insertSession(session.row())
    }

    suspend fun updateSession(session: Session) = dao.updateSession(session.row())
    suspend fun session(id: String) = dao.session(id)?.domain()
    suspend fun sessions(eventId: String) = dao.sessions(eventId).map { it.domain() }
    suspend fun sessionsForSale(saleId: String) = dao.sessionsForSale(saleId).map { it.domain() }

    suspend fun savePrintJob(job: PrintJob) = dao.savePrintJob(job.row())
    suspend fun printJobs(sessionId: String) = dao.printJobs(sessionId).map { it.domain() }
    suspend fun openPrintJobs() = dao.openPrintJobs().map { it.domain() }

    /** Call once at app start. Returns prints the attendant must confirm ("Did it print?"). */
    suspend fun recoverAfterRestart(at: Instant): Recovery = tx {
        val sessions = dao.unfinishedSessions().map { it.domain() }
        val jobs = dao.openPrintJobs().map { it.domain() }
        val r = recoverAfterRestart(sessions, jobs, at)
        r.sessions.filter { it !in sessions }.forEach { dao.updateSession(it.row()) }
        r.jobs.filter { it !in jobs }.forEach { dao.savePrintJob(it.row()) }
        r
    }
}
