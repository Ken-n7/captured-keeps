package com.capturedkeeps.data

import androidx.room.Room
import com.capturedkeeps.core.domain.*
import com.capturedkeeps.core.print.StripLayout
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

class KioskTest {
    private var clock = Instant.parse("2026-11-23T10:00:00Z")
    private var free = 10L shl 30
    private val db = Room.inMemoryDatabaseBuilder<CkDatabase>().open()
    private val store = Store(db)
    private val kiosk = KioskFlow(store, "tab-1", { free }, { clock })
    private val freePkg = SessionPackage(id = "free", name = "Free", price = Centavos.ZERO, retakes = 1)
    private val freeEvent = Event(name = "Launch", type = EventType.FREE, status = EventStatus.LIVE,
        packages = listOf(freePkg), layoutIds = listOf(StripLayout.CLASSIC_3.id))

    @AfterTest fun close() = db.close()

    private suspend fun Store.seedDemoIfEmpty() {
        saveStaff(Staff(name = "Owner", role = Role.OWNER), pin = "1234")
        saveEvent(Event(name = "Demo Event", type = EventType.PAID, status = EventStatus.LIVE,
            packages = listOf(SessionPackage(name = "Classic", price = Centavos.pesos(150))),
            layoutIds = StripLayout.PRESETS.map { it.id }))
    }

    private fun test(block: suspend () -> Unit) = runBlocking { block() }
    private inline fun <reified T : KioskState> state() = assertIs<T>(kiosk.state.value)

    private suspend fun shoot(n: Int) = repeat(n) { kiosk.photoTaken("/photos/$it.jpg") }

    @Test fun `free session end to end`() = test {
        store.saveEvent(freeEvent)
        kiosk.start()
        kiosk.choose("free")
        shoot(2)
        assertEquals(3, state<KioskState.Capturing>().shot)
        shoot(1)
        assertEquals(1, state<KioskState.Review>().number)

        kiosk.retake()
        shoot(3)
        assertEquals(0, state<KioskState.Review>().retakesLeft)
        kiosk.retake() // none left: the rule is broken and the session ends with a staff message
        state<KioskState.Blocked>()
    }

    @Test fun `print, fail, retry, done`() = test {
        store.saveEvent(freeEvent)
        kiosk.start(); kiosk.choose("free"); shoot(3)
        kiosk.accept("/strips/1.png")
        val sessionId = state<KioskState.Printing>().session.id
        kiosk.printResult("Out of paper")
        assertEquals(PrintState.FAILED, state<KioskState.Printing>().job.state)
        kiosk.retryPrint()
        kiosk.printResult(null)
        state<KioskState.Done>()
        assertEquals(SessionState.COMPLETED, store.session(sessionId)!!.state)
        assertEquals(PrintState.DONE, store.printJobs(sessionId).single().state)
        assertEquals(2, store.printJobs(sessionId).single().attempts)
        kiosk.timeout()
        state<KioskState.Idle>()
    }

    @Test fun `paid session needs staff PIN, records the sale with the session`() = test {
        store.seedDemoIfEmpty()
        val event = store.liveEvent()!!
        val classic = event.packages.first()
        kiosk.start(); kiosk.choose(classic.id)
        state<KioskState.Payment>()

        kiosk.confirmPayment(PaymentPath.CASH_VIA_STAFF, "9999", "200", null)
        assertEquals("Wrong PIN or password", state<KioskState.Payment>().error)
        kiosk.confirmPayment(PaymentPath.CASH_VIA_STAFF, "1234", "100", null)
        assertTrue("less than the price" in state<KioskState.Payment>().error!!)
        assertTrue(store.ledger().entries.isEmpty())

        kiosk.confirmPayment(PaymentPath.CASH_VIA_STAFF, "1234", "200", "REF123")
        val session = state<KioskState.Capturing>().session
        val sale = store.ledger().sales.single()
        assertEquals(session.saleId, sale.id)
        assertEquals(Centavos.pesos(50), sale.change)

        kiosk.timeout(); kiosk.cancel() // paid sessions never time out or get cancelled by the customer
        state<KioskState.Capturing>()
    }

    @Test fun `five wrong PINs lock payment for a minute`() = test {
        store.seedDemoIfEmpty()
        kiosk.start(); kiosk.choose(store.liveEvent()!!.packages.first().id)
        repeat(Gate.MAX_WRONG) { kiosk.confirmPayment(PaymentPath.CUSTOMER_QR, "0000", null, null) }
        kiosk.confirmPayment(PaymentPath.CUSTOMER_QR, "1234", null, null)
        assertTrue("Too many wrong tries" in state<KioskState.Payment>().error!!)
        clock = clock.plusSeconds(61)
        kiosk.confirmPayment(PaymentPath.CUSTOMER_QR, "1234", null, null)
        state<KioskState.Capturing>()
    }

    @Test fun `blocked when no live event or storage full, timeout cancels free session`() = test {
        kiosk.start()
        assertTrue("No event" in state<KioskState.Blocked>().message)
        store.saveEvent(freeEvent)
        free = 100L shl 20
        kiosk.start()
        assertTrue("Storage" in state<KioskState.Blocked>().message)
        free = 10L shl 30
        kiosk.start(); kiosk.choose("free"); shoot(1)
        val id = state<KioskState.Capturing>().session.id
        kiosk.timeout()
        state<KioskState.Idle>()
        assertEquals(EndReason.TIMEOUT, store.session(id)!!.endReason)
    }

    @Test fun `camera failure fails the session and frees a paid sale`() = test {
        store.seedDemoIfEmpty()
        kiosk.start(); kiosk.choose(store.liveEvent()!!.packages.first().id)
        kiosk.confirmPayment(PaymentPath.CUSTOMER_QR, "1234", null, "REF999")
        kiosk.cameraFailed("no front camera")
        assertTrue("Camera problem" in state<KioskState.Blocked>().message)
        val sale = store.ledger().sales.single()
        store.ledger().authorize(sale.id, store.sessionsForSale(sale.id)) // usable again
    }

    @Test fun `payment interrupted by a crash can be resumed once, without paying again`() = test {
        store.seedDemoIfEmpty()
        val pkg = store.liveEvent()!!.packages.first()
        kiosk.start(); kiosk.choose(pkg.id)
        kiosk.confirmPayment(PaymentPath.CUSTOMER_QR, "1234", null, "REF1")
        store.recoverAfterRestart(clock) // app died mid-capture

        val restarted = KioskFlow(store, "tab-1", { free }, { clock })
        restarted.start(); restarted.choose(pkg.id)
        val unused = assertIs<KioskState.Payment>(restarted.state.value).unused.single()
        restarted.resumePayment(unused.id, "9999")
        assertEquals("Wrong PIN or password", assertIs<KioskState.Payment>(restarted.state.value).error)
        restarted.resumePayment(unused.id, "1234")
        assertIs<KioskState.Capturing>(restarted.state.value)
        assertEquals(1, store.ledger().sales.size) // no second charge

        val other = KioskFlow(store, "tab-1", { free }, { clock })
        other.start(); other.choose(pkg.id)
        assertTrue(assertIs<KioskState.Payment>(other.state.value).unused.isEmpty()) // now in use
    }

    @Test fun `sessions use the saved layout and record its version`() = test {
        val edited = store.saveLayout(StripLayout.CLASSIC_3.copy(shots = 2))
        store.saveEvent(freeEvent)
        kiosk.start(); kiosk.choose("free"); shoot(2)
        val review = state<KioskState.Review>()
        assertEquals(edited, review.layout)
        assertEquals(2, review.session.layoutVersion)
    }

    @Test fun `failed print can be left for staff so the booth keeps going`() = test {
        store.saveEvent(freeEvent)
        kiosk.start(); kiosk.choose("free"); shoot(3)
        kiosk.accept("/strips/1.png")
        kiosk.printLater() // only once it has failed
        state<KioskState.Printing>()
        kiosk.printResult("Printer is out of paper")
        kiosk.printLater()
        state<KioskState.Idle>()
        val job = store.failedPrints().single()
        assertEquals(SessionState.PRINTING, store.session(job.sessionId)!!.state)
        store.retryPrint(job.id); store.printFinished(job.id, null, clock)
        assertEquals(SessionState.COMPLETED, store.session(job.sessionId)!!.state)
    }
}
