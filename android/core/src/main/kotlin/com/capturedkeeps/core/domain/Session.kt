package com.capturedkeeps.core.domain

import com.capturedkeeps.core.print.StripLayout
import java.time.Instant

enum class SessionState {
    STARTED, CAPTURING, REVIEWING, PRINTING, COMPLETED, FAILED, CANCELLED;

    val isFinal get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

enum class EndReason { CAMERA_ERROR, PRINT_ERROR, STORAGE_FULL, APP_RESTART, TIMEOUT, CUSTOMER_CANCELLED, OTHER }

/** One customer's booth visit. Package and layout are snapshots: event edits never change a running session. */
data class Session(
    val id: String = newId(),
    val eventId: String,
    val deviceId: String,
    val pkg: SessionPackage,
    val layoutId: String,
    val layoutVersion: Int,
    /** The sale that paid for it; null for free sessions. */
    val saleId: String? = null,
    val state: SessionState = SessionState.STARTED,
    val retakesUsed: Int = 0,
    val endReason: EndReason? = null,
    val startedAt: Instant,
    val endedAt: Instant? = null,
) {
    companion object {
        /** [sale] must come from [Ledger.authorize] — that's what stops one payment unlocking two sessions. */
        fun start(event: Event, packageId: String, layout: StripLayout, deviceId: String, at: Instant, sale: LedgerEntry.Sale?): Session {
            rule(event.status == EventStatus.LIVE) { "This event isn't live" }
            val pkg = event.packages.find { it.id == packageId }
            rule(pkg != null) { "That package isn't offered at this event" }
            pkg!!
            rule(layout.id in event.layoutIds) { "That layout isn't enabled for this event" }
            if (pkg.price > Centavos.ZERO) {
                rule(sale != null) { "Payment must be confirmed before the session starts" }
                rule(sale!!.eventId == event.id && sale.packageId == pkg.id) { "That payment is for a different package" }
            } else {
                rule(sale == null) { "Free sessions don't take a payment" }
            }
            return Session(eventId = event.id, deviceId = deviceId, pkg = pkg, layoutId = layout.id,
                layoutVersion = layout.version, saleId = sale?.id, startedAt = at)
        }
    }
}

private fun Session.move(to: SessionState, vararg from: SessionState): Session {
    rule(state in from) { "Session is ${state.name.lowercase()}, can't go to ${to.name.lowercase()}" }
    return copy(state = to)
}

fun Session.beginCapture() = move(SessionState.CAPTURING, SessionState.STARTED)
fun Session.review() = move(SessionState.REVIEWING, SessionState.CAPTURING)
fun Session.print() = move(SessionState.PRINTING, SessionState.REVIEWING)
fun Session.complete(at: Instant) = move(SessionState.COMPLETED, SessionState.PRINTING).copy(endedAt = at)

fun Session.retake(): Session {
    rule(retakesUsed < pkg.retakes) { "No retakes left in this package" }
    return move(SessionState.CAPTURING, SessionState.REVIEWING).copy(retakesUsed = retakesUsed + 1)
}

fun Session.fail(reason: EndReason, at: Instant) = end(SessionState.FAILED, reason, at)
fun Session.cancel(reason: EndReason, at: Instant) = end(SessionState.CANCELLED, reason, at)

private fun Session.end(to: SessionState, reason: EndReason, at: Instant): Session {
    rule(!state.isFinal) { "Session already ended" }
    return copy(state = to, endReason = reason, endedAt = at)
}

enum class PrintState { QUEUED, SENDING, DONE, FAILED, UNKNOWN }

/**
 * One request to print a session's final image (already saved to disk, so reprints never need the camera).
 * Retries reuse the job; a reprint is a new job with a reason and operator. Neither ever charges again.
 */
data class PrintJob(
    val id: String = newId(),
    val sessionId: String,
    val imagePath: String,
    val copies: Int,
    val state: PrintState = PrintState.QUEUED,
    val attempts: Int = 0,
    val lastError: String? = null,
    val reprintOf: String? = null,
    val reason: String? = null,
    val operatorId: String? = null,
    val createdAt: Instant,
) {
    init {
        rule(copies in 1..5) { "Copies must be 1 to 5" }
        rule(imagePath.isNotBlank()) { "Print image is missing" }
        if (reprintOf != null) {
            rule(!reason.isNullOrBlank()) { "Enter a reason for the reprint" }
            rule(operatorId != null) { "Reprints need a staff member" }
        }
    }
}

private fun PrintJob.move(to: PrintState, vararg from: PrintState): PrintJob {
    rule(state in from) { "Print is ${state.name.lowercase()}, can't go to ${to.name.lowercase()}" }
    return copy(state = to)
}

fun PrintJob.send() = move(PrintState.SENDING, PrintState.QUEUED).copy(attempts = attempts + 1)
fun PrintJob.done() = move(PrintState.DONE, PrintState.SENDING)
fun PrintJob.failed(error: String) = move(PrintState.FAILED, PrintState.SENDING).copy(lastError = error)
fun PrintJob.retry() = move(PrintState.QUEUED, PrintState.FAILED)

/** App died while sending: only a person can tell whether it came out. "No" → failed → retry. */
fun PrintJob.resolveUnknown(printed: Boolean) =
    if (printed) move(PrintState.DONE, PrintState.UNKNOWN)
    else move(PrintState.FAILED, PrintState.UNKNOWN).copy(lastError = "Did not print (confirmed by attendant)")

fun PrintJob.reprint(reason: String, by: Staff, at: Instant): PrintJob {
    rule(state == PrintState.DONE) { "Only finished prints can be reprinted — retry the failed one instead" }
    return PrintJob(sessionId = sessionId, imagePath = imagePath, copies = 1, reprintOf = id,
        reason = reason.trim(), operatorId = by.id, createdAt = at)
}

data class Recovery(val sessions: List<Session>, val jobs: List<PrintJob>) {
    /** Prints the attendant must confirm: "Did it print?" */
    val needsAttendant get() = jobs.filter { it.state == PrintState.UNKNOWN }
}

/**
 * Run on app start. Interrupted sessions are cancelled (a paid one's sale becomes usable again via
 * [Ledger.authorize]); a print that was mid-send is never re-sent automatically.
 */
fun recoverAfterRestart(sessions: List<Session>, jobs: List<PrintJob>, at: Instant) = Recovery(
    sessions = sessions.map {
        if (it.state in setOf(SessionState.STARTED, SessionState.CAPTURING, SessionState.REVIEWING))
            it.cancel(EndReason.APP_RESTART, at) else it
    },
    jobs = jobs.map { if (it.state == PrintState.SENDING) it.copy(state = PrintState.UNKNOWN) else it },
)
