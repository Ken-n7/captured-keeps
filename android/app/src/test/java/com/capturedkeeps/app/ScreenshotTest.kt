package com.capturedkeeps.app

import android.graphics.Bitmap
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.capturedkeeps.app.print.AndroidStrip
import com.capturedkeeps.app.print.toBitmap
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import com.capturedkeeps.app.ui.*
import com.capturedkeeps.core.domain.*
import com.capturedkeeps.app.ui.CapturedKeepsTheme
import com.capturedkeeps.core.print.PAPER_80MM_DOTS
import com.capturedkeeps.core.print.SamplePhotos
import com.capturedkeeps.core.print.StripInfo
import com.capturedkeeps.core.print.StripLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Screens rendered by Android's real layout engine on the laptop (no emulator).
 * `./gradlew :app:recordPaparazziDebug` saves PNGs to app/src/test/snapshots; `verifyPaparazziDebug` catches changes.
 */
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10) // 1280 × 800 dp landscape tablet

    @Test fun layoutClassic() = screen(StripLayout.CLASSIC_3)
    @Test fun layoutSquare() = screen(StripLayout.SQUARE_4)
    @Test fun layoutPortrait() = screen(StripLayout.SINGLE_PORTRAIT)

    private fun screen(layout: StripLayout) = paparazzi.snapshot {
        val info = StripInfo("Maria & Jose's Wedding", LocalDateTime.of(2026, 11, 23, 18, 42), 42)
        CapturedKeepsTheme { LayoutEditor(layout, null, {}, {}, onReset = {}, initialPreview = renderPreview(layout, info, SamplePhotos.DEFAULT)) }
    }

    @Test fun layoutsList() = kiosk {
        LayoutsPage(StripLayout.PRESETS + StripLayout(id = "w", name = "Wedding — 2 photos", version = 3, shots = 2), {}, {}, {})
    }

    private val event = Event(name = "Maria & Jose's Wedding", type = EventType.PAID, status = EventStatus.LIVE,
        packages = listOf(SessionPackage(name = "Classic", price = Centavos.pesos(150)),
            SessionPackage(name = "Duo", price = Centavos.pesos(250), copies = 2)),
        layoutIds = listOf(StripLayout.CLASSIC_3.id))
    private val strip by lazy {
        AndroidStrip.render(StripLayout.CLASSIC_3, StripInfo(event.name, LocalDateTime.of(2026, 11, 23, 18, 42), 42), SamplePhotos.DEFAULT)
            .toBitmap().asImageBitmap()
    }

    private fun kiosk(content: @Composable () -> Unit) = paparazzi.snapshot {
        CapturedKeepsTheme { Surface(color = MaterialTheme.colorScheme.background) { content() } }
    }

    @Test fun kioskIdle() = kiosk { IdleScreen("Welcome to Maria & Jose's Wedding! Tap to start", null, {}, {}) }
    @Test fun kioskChoosePackage() = kiosk { ChoosePackageScreen(event, {}, {}) }
    @Test fun kioskPaymentCash() = kiosk {
        PaymentScreen(event.packages[0], "Wrong PIN", emptyList(), null, { _, _, _, _ -> }, { _, _ -> }, {}, PaymentPath.CASH_VIA_STAFF)
    }
    @Test fun kioskReview() = kiosk { ReviewScreen(strip, 2, false, {}, {}, null) }
    @Test fun kioskPrintFailed() = kiosk { PrintingScreen(strip, "Printer is out of paper", {}) }
    @Test fun kioskDone() = kiosk { DoneScreen(strip, 2, {}) }
    @Test fun kioskBlocked() = kiosk { Message("Please call staff", "No event is live. Please call staff.") { Button({}) { Text("Try again") } } }
    @Test fun kioskCameraPermission() = kiosk { CameraPermissionNeeded(asked = true, {}, {}, {}) }

    // ── Staff ──
    private val ana = Staff(name = "Ana", role = Role.ATTENDANT)
    private val owner = Staff(name = "Owner", role = Role.OWNER)

    @Test fun staffSetup() = kiosk { SetupScreen("PIN must be 4 to 6 digits") { _, _, _ -> } }
    @Test fun staffLogin() = kiosk { LoginScreen("Wrong PIN or password", {}, {}) }
    @Test fun staffTotals() = kiosk {
        val now = java.time.Instant.parse("2026-11-23T10:00:00Z")
        var l = Ledger()
        l += l.sale(event, event.packages[0].id, PaymentPath.CASH_VIA_STAFF, ana, now, Centavos.pesos(200), "REF-1")
        l += l.sale(event, event.packages[1].id, PaymentPath.CUSTOMER_QR, owner, now)
        TotalsPage(listOf(event), EventTotals(event, l.summary(), 2, 3, mapOf(ana.id to "Ana", owner.id to "Owner")), {}, {}, {}, null)
    }
    @Test fun staffEvents() = kiosk {
        EventsPage(listOf(event.copy(startDate = java.time.LocalDate.of(2026, 12, 12)),
            event.copy(id = "d", name = "Debut Party", status = EventStatus.DRAFT, packages = emptyList(), startDate = java.time.LocalDate.of(2099, 1, 9))),
            null, {}, {}, {}, { _, _ -> })
    }
    @Test fun staffPayments() = kiosk {
        val now = java.time.Instant.parse("2026-11-23T10:00:00Z")
        var l = Ledger()
        val a = l.sale(event, event.packages[0].id, PaymentPath.CASH_VIA_STAFF, ana, now, Centavos.pesos(200), "REF-1").also { l += it }
        val b = l.sale(event, event.packages[1].id, PaymentPath.CUSTOMER_QR, owner, now.plusSeconds(600)).also { l += it }
        val c = l.sale(event, event.packages[0].id, PaymentPath.CUSTOMER_QR, ana, now.plusSeconds(900), qrReference = "REF-3").also { l += it }
        PaymentsPage(event, listOf(
            com.capturedkeeps.data.PaymentInfo(c, false, Centavos.ZERO, null, unused = true),
            com.capturedkeeps.data.PaymentInfo(b, false, Centavos.pesos(100), SessionState.COMPLETED, unused = false),
            com.capturedkeeps.data.PaymentInfo(a, false, Centavos.ZERO, SessionState.COMPLETED, unused = false),
        ), ownerTools = true, error = null, onBack = {}, onVoid = { _, _ -> }, onRefund = { _, _, _ -> })
    }
    @Test fun staffPaymentQrEmpty() = kiosk { PaymentQrPage(null, null, {}, {}, {}) }
    @Test fun staffEventEditor() = kiosk { EventEditor(event.copy(startDate = java.time.LocalDate.of(2026, 12, 12), idleMedia = "/x.mp4"), null, {}, {}) }

    /** Full-size strips drawn with Android Canvas → app/build/samples, to compare with core/build/samples. */
    @Test fun androidStrips() {
        val out = File("build/samples").apply { mkdirs() }
        val info = StripInfo("Maria & Jose's Wedding", LocalDateTime.of(2026, 11, 23, 18, 42), 42)
        for (layout in StripLayout.PRESETS) {
            val dots = AndroidStrip.render(layout, info, SamplePhotos.DEFAULT)
            assertEquals(PAPER_80MM_DOTS, dots.width)
            File(out, "${layout.id}.png").outputStream().use { dots.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
