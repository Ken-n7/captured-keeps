package com.capturedkeeps.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.capturedkeeps.core.domain.*
import java.time.Instant

/** The app's only way to read/write records. Domain rules run first, then rows are saved. */
class Store(private val db: CkDatabase) {
    private val dao = db.dao()

    private suspend fun <T> tx(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }

    suspend fun saveStaff(staff: Staff) = dao.saveStaff(staff.row())
    suspend fun staff() = dao.staff().map { it.domain() }

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

    suspend fun audit(entityId: String) = dao.audit(entityId).map { it.domain() }

    // ponytail: loads the whole ledger each time; fine for thousands of sales, query per event if it grows.
    suspend fun ledger() = Ledger(dao.ledger().map { it.domain() })

    /** Sale, void or refund — created by [Ledger.sale]/[Ledger.void]/[Ledger.refund]. Never edited afterwards. */
    suspend fun append(entry: LedgerEntry) = dao.appendLedger(entry.row())

    /** Paid sessions re-check the sale inside the same transaction; the DB index backs it up. */
    suspend fun startSession(session: Session) = tx {
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
