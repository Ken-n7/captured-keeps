package com.capturedkeeps.data

import com.capturedkeeps.core.domain.*
import com.capturedkeeps.core.print.StripLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** What the kiosk is showing. Screens only draw this and call [KioskFlow] actions. */
sealed interface KioskState {
    data object Idle : KioskState
    /** Can't take customers — staff needed. Tap retries. */
    data class Blocked(val message: String) : KioskState
    data class ChoosePackage(val event: Event) : KioskState
    /** [unused]: earlier payments for this package whose session never ran (e.g. app restarted) — staff can resume one. */
    data class Payment(val event: Event, val pkg: SessionPackage, val unused: List<LedgerEntry.Sale> = emptyList(), val error: String? = null) : KioskState
    data class Capturing(val event: Event, val session: Session, val layout: StripLayout, val photos: List<String>) : KioskState {
        val shot get() = photos.size + 1
    }
    data class Review(val event: Event, val session: Session, val layout: StripLayout, val photos: List<String>, val number: Int) : KioskState {
        val retakesLeft get() = session.pkg.retakes - session.retakesUsed
    }
    data class Printing(val session: Session, val job: PrintJob) : KioskState
    data class Done(val session: Session, val stripPath: String) : KioskState
}

/**
 * The customer journey: idle → package → (staff confirms payment) → capture → review/retake → print → done.
 * Every step is saved before the screen moves on, so a crash never loses a paid session.
 */
class KioskFlow(
    private val store: Store,
    private val deviceId: String,
    private val freeBytes: () -> Long,
    private val now: () -> Instant = Instant::now,
    private val gate: Gate = Gate(now),
) {
    private val _state = MutableStateFlow<KioskState>(KioskState.Idle)
    val state: StateFlow<KioskState> = _state
    private val lock = Mutex() // double taps can't run two actions at once

    private suspend fun act(block: suspend (KioskState) -> KioskState?) = lock.withLock {
        val next = try {
            block(_state.value)
        } catch (e: RuleBroken) {
            when (val s = _state.value) {
                is KioskState.Payment -> s.copy(error = e.message)
                is KioskState.Capturing -> end(s.session, EndReason.OTHER, e.message!!)
                is KioskState.Review -> end(s.session, EndReason.OTHER, e.message!!)
                else -> KioskState.Blocked(e.message!!)
            }
        }
        if (next != null) _state.value = next
    }

    private suspend fun end(session: Session, reason: EndReason, message: String?): KioskState {
        store.updateSession(if (reason == EndReason.CAMERA_ERROR) session.fail(reason, now()) else session.cancel(reason, now()))
        return message?.let { KioskState.Blocked(it) } ?: KioskState.Idle
    }

    /** Customer tapped the idle screen (or a blocked screen to retry). */
    suspend fun start() = act { s ->
        if (s !is KioskState.Idle && s !is KioskState.Blocked) return@act null
        when {
            storageStatus(freeBytes()) == StorageStatus.BLOCK -> KioskState.Blocked("Storage is almost full. Please call staff.")
            else -> store.liveEvent()?.let { KioskState.ChoosePackage(it) }
                ?: KioskState.Blocked("No event is live. Please call staff.")
        }
    }

    suspend fun choose(packageId: String) = act { s ->
        if (s !is KioskState.ChoosePackage) return@act null
        val pkg = s.event.packages.find { it.id == packageId } ?: throw RuleBroken("That package isn't offered")
        if (pkg.price == Centavos.ZERO) return@act begin(s.event, pkg, sale = null, isNew = false)
        val ledger = store.ledger()
        val unused = ledger.sales.filter { sale ->
            sale.eventId == s.event.id && sale.packageId == pkg.id &&
                runCatching { ledger.authorize(sale.id, store.sessionsForSale(sale.id)) }.isSuccess
        }
        KioskState.Payment(s.event, pkg, unused)
    }

    /** Staff saw the money arrive in the wallet and enters their PIN. */
    suspend fun confirmPayment(path: PaymentPath, pin: String, tendered: String?, reference: String?) = act { s ->
        if (s !is KioskState.Payment) return@act null
        val staff = checkPin(pin)
        val cash = tendered?.takeIf { path == PaymentPath.CASH_VIA_STAFF }?.let(Centavos::parse)
        val sale = store.ledger().sale(s.event, s.pkg.id, path, staff, now(), cash, reference)
        begin(s.event, s.pkg, sale, isNew = true)
    }

    /** Staff resumes a payment whose session was interrupted, so the customer doesn't pay twice. */
    suspend fun resumePayment(saleId: String, pin: String) = act { s ->
        if (s !is KioskState.Payment) return@act null
        checkPin(pin)
        val sale = store.ledger().authorize(saleId, store.sessionsForSale(saleId))
        begin(s.event, s.pkg, sale, isNew = false)
    }

    private suspend fun checkPin(pin: String) = gate.check { store.staffByPin(pin) }

    private suspend fun begin(event: Event, pkg: SessionPackage, sale: LedgerEntry.Sale?, isNew: Boolean): KioskState {
        val all = store.layouts()
        val layout = event.layoutIds.firstNotNullOfOrNull { id -> all.find { it.id == id } }
            ?: throw RuleBroken("No strip layout is enabled. Please call staff.")
        val session = Session.start(event, pkg.id, layout, deviceId, now(), sale)
        store.startSession(session, sale.takeIf { isNew })
        val capturing = session.beginCapture()
        store.updateSession(capturing)
        return KioskState.Capturing(event, capturing, layout, emptyList())
    }

    /** Camera saved a photo to [path]. */
    suspend fun photoTaken(path: String) = act { s ->
        if (s !is KioskState.Capturing) return@act null
        val photos = s.photos + path
        if (photos.size < s.layout.shots) return@act s.copy(photos = photos)
        val review = s.session.review()
        store.updateSession(review)
        val number = store.sessions(s.event.id).count { it.state == SessionState.COMPLETED } + 1
        KioskState.Review(s.event, review, s.layout, photos, number)
    }

    suspend fun cameraFailed(message: String) = act { s ->
        if (s !is KioskState.Capturing) return@act null
        end(s.session, EndReason.CAMERA_ERROR, "Camera problem: $message. Please call staff.")
    }

    suspend fun retake() = act { s ->
        if (s !is KioskState.Review) return@act null
        val again = s.session.retake()
        store.updateSession(again)
        KioskState.Capturing(s.event, again, s.layout, emptyList())
    }

    /** Customer approved; the app saved the final strip image to [stripPath]. */
    suspend fun accept(stripPath: String) = act { s ->
        if (s !is KioskState.Review) return@act null
        val printing = s.session.print()
        val job = PrintJob(sessionId = printing.id, imagePath = stripPath, copies = printing.pkg.copies, createdAt = now()).send()
        store.updateSession(printing)
        store.savePrintJob(job)
        KioskState.Printing(printing, job)
    }

    /** Printer finished: [error] null = printed. */
    suspend fun printResult(error: String?) = act { s ->
        if (s !is KioskState.Printing || s.job.state != PrintState.SENDING) return@act null
        if (error != null) {
            val failed = s.job.failed(error)
            store.savePrintJob(failed)
            return@act s.copy(job = failed)
        }
        val done = s.job.done()
        val complete = s.session.complete(now())
        store.savePrintJob(done)
        store.updateSession(complete)
        KioskState.Done(complete, done.imagePath)
    }

    suspend fun retryPrint() = act { s ->
        if (s !is KioskState.Printing || s.job.state != PrintState.FAILED) return@act null
        val again = s.job.retry().send()
        store.savePrintJob(again)
        s.copy(job = again)
    }

    /** Customer backed out of a free session. Paid sessions are only cancelled by staff. */
    suspend fun cancel() = act { s ->
        when (s) {
            is KioskState.ChoosePackage, is KioskState.Payment -> KioskState.Idle
            is KioskState.Capturing -> if (s.session.saleId == null) end(s.session, EndReason.CUSTOMER_CANCELLED, null) else null
            is KioskState.Review -> if (s.session.saleId == null) end(s.session, EndReason.CUSTOMER_CANCELLED, null) else null
            else -> null
        }
    }

    /** No touches for a while. Paid sessions in progress never time out — the customer already paid. */
    suspend fun timeout() = act { s ->
        when (s) {
            is KioskState.ChoosePackage, is KioskState.Payment, is KioskState.Done, is KioskState.Blocked -> KioskState.Idle
            is KioskState.Capturing -> if (s.session.saleId == null) end(s.session, EndReason.TIMEOUT, null) else null
            is KioskState.Review -> if (s.session.saleId == null) end(s.session, EndReason.TIMEOUT, null) else null
            else -> null
        }
    }
}
