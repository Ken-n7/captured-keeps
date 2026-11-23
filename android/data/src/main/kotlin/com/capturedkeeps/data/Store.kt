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

    /** Null [pin]/[password] keeps the current one. Only owners have a password. */
    suspend fun saveStaff(staff: Staff, pin: String? = null, password: String? = null) {
        if (password != null && staff.role != Role.OWNER) throw RuleBroken("Only the owner has a password")
        val old = dao.staffRow(staff.id)
        if (old != null && old.active && !staff.active) {
            if (staff.role == Role.OWNER && dao.staff().none { it.id != staff.id && it.role == Role.OWNER.name && it.active && it.passwordHash != null })
                throw RuleBroken("Can't disable the only owner")
        }
        dao.saveStaff(StaffRow(staff.id, staff.name, staff.role.name,
            pin?.let(Pin::hash) ?: old?.pinHash, password?.let(Pin::hashPassword) ?: old?.passwordHash, staff.active))
    }
    suspend fun staff() = dao.staff().map { it.domain() }

    /** First run: no owner can log in yet. */
    suspend fun needsSetup() = dao.staff().none { it.role == Role.OWNER.name && it.passwordHash != null && it.active }

    // ponytail: checks every staff hash (~20 ms each); fine for a handful of staff.
    suspend fun staffByPin(pin: String): Staff? =
        dao.staff().firstOrNull { it.active && it.pinHash != null && Pin.matches(pin, it.pinHash) }?.domain()
    suspend fun ownerByPassword(password: String): Staff? =
        dao.staff().firstOrNull { it.active && it.passwordHash != null && Pin.matches(password, it.passwordHash) }?.domain()

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

    /** Go live / pause / finish / cancel / archive, checked against the other events and audited. */
    suspend fun moveEvent(id: String, to: EventStatus?, by: Staff, at: Instant, mediaPlayable: Boolean = false): Event = tx {
        val e = event(id) ?: throw RuleBroken("Event not found")
        val anotherLive = dao.eventRows().any { it.id != id && it.status == EventStatus.LIVE.name }
        val moved = if (to == null) e.hide() else e.moveTo(to, anotherLive, mediaPlayable)
        dao.saveEventRow(moved.row())
        val field = if (to == null) "hidden" else "status"
        val (old, new) = if (to == null) "false" to "true" else e.status.label to to.label
        dao.appendAudit(listOf(AuditEntry(at = at, byStaffId = by.id, entity = "event", entityId = id, field = field, oldValue = old, newValue = new).row()))
        moved
    }

    /** Presets (with the owner's saved edits) first, then the owner's own layouts. */
    suspend fun layouts(): List<StripLayout> {
        val saved = dao.layouts().map { it.domain() }.associateBy { it.id }
        return StripLayout.PRESETS.map { saved[it.id] ?: it } + saved.values.filter { s -> StripLayout.PRESETS.none { it.id == s.id } }.sortedBy { it.name }
    }

    /** Saves an edit as a new version, so sessions can tell which version they printed with. */
    suspend fun saveLayout(layout: StripLayout): StripLayout = tx {
        val current = layouts().find { it.id == layout.id }
        val saved = when {
            current == null -> layout.copy(version = 1)
            current == layout.copy(version = current.version) -> current // nothing changed
            else -> layout.copy(version = current.version + 1)
        }
        dao.saveLayout(saved.row())
        saved
    }

    /** Own layouts only, and only when no unfinished event uses them. Presets can be reset instead. */
    suspend fun deleteLayout(id: String) = tx {
        val inUse = events().filter { !it.status.isFinished && id in it.layoutIds }
        if (inUse.isNotEmpty()) throw RuleBroken("Used by ${inUse.joinToString { it.name }} — remove it from the event first")
        dao.deleteLayout(id)
    }

    /** Back to the built-in preset (a new version, so it isn't confused with the edited one). */
    suspend fun resetPreset(id: String): StripLayout {
        val preset = StripLayout.PRESETS.find { it.id == id } ?: throw RuleBroken("Not a built-in layout")
        return saveLayout(preset)
    }

    suspend fun audit(entityId: String) = dao.audit(entityId).map { it.domain() }

    // ponytail: loads the whole ledger each time; fine for thousands of sales, query per event if it grows.
    suspend fun ledger() = Ledger(dao.ledger().map { it.domain() })

    /** Cancel a payment whose session never got past the start (e.g. customer left after a crash). */
    suspend fun void(saleId: String, by: Staff, reason: String, at: Instant) = tx {
        dao.appendLedger(ledger().void(saleId, by, reason, sessionsForSale(saleId), at).row())
    }

    /** Owner only (the screen also requires the owner password login). */
    suspend fun refund(saleId: String, amount: Centavos, by: Staff, reason: String, at: Instant) = tx {
        dao.appendLedger(ledger().refund(saleId, amount, by, reason, at).row())
    }

    /** One line per sale of an event: what happened to the money and the session it paid for. */
    suspend fun payments(eventId: String): List<PaymentInfo> {
        val ledger = ledger().forEvent(eventId)
        return ledger.sales.sortedByDescending { it.at }.map { sale ->
            val sessions = sessionsForSale(sale.id)
            PaymentInfo(sale, ledger.isVoided(sale.id), ledger.refunded(sale.id),
                sessions.maxByOrNull { it.startedAt }?.state,
                runCatching { ledger.authorize(sale.id, sessions) }.isSuccess)
        }
    }

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

    /** Prints that were mid-send when the app died: staff must say whether they came out. */
    suspend fun printsToConfirm() = dao.openPrintJobs().map { it.domain() }.filter { it.state == PrintState.UNKNOWN }

    /** Staff answered "Did it print?". Yes completes the session; no leaves it failed for a retry. */
    suspend fun resolvePrint(jobId: String, printed: Boolean, at: Instant) = tx {
        val job = dao.printJob(jobId)?.domain() ?: throw RuleBroken("Print not found")
        dao.savePrintJob(job.resolveUnknown(printed).row())
        if (printed) completeSession(job.sessionId, at)
    }

    /** Failed prints still waiting for a retry. */
    suspend fun failedPrints() = dao.openPrintJobs().map { it.domain() }.filter { it.state == PrintState.FAILED }

    /**
     * Retry a failed print. ponytail: virtual printer — marks it printed at once;
     * the real printer step sends it and reports back instead.
     */
    suspend fun retryPrint(jobId: String, at: Instant) = tx {
        val job = dao.printJob(jobId)?.domain() ?: throw RuleBroken("Print not found")
        dao.savePrintJob(job.retry().send().done().row())
        completeSession(job.sessionId, at)
    }

    private suspend fun completeSession(sessionId: String, at: Instant) {
        val session = dao.session(sessionId)?.domain() ?: return
        if (session.state == SessionState.PRINTING) dao.updateSession(session.complete(at).row())
    }

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

data class PaymentInfo(
    val sale: LedgerEntry.Sale,
    val voided: Boolean,
    val refunded: Centavos,
    /** Latest session this payment unlocked; null = never used. */
    val session: SessionState?,
    /** Paid but no session is using it — can be resumed at the booth or voided. */
    val unused: Boolean,
)
