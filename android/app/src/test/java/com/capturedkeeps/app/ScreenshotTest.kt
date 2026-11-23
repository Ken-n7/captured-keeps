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
import com.capturedkeeps.app.ui.VirtualPrinterScreen
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

    @Test fun virtualPrinterClassic() = screen(StripLayout.CLASSIC_3)
    @Test fun virtualPrinterSquare() = screen(StripLayout.SQUARE_4)
    @Test fun virtualPrinterPortrait() = screen(StripLayout.SINGLE_PORTRAIT)

    private fun screen(layout: StripLayout) =
        paparazzi.snapshot { CapturedKeepsTheme { VirtualPrinterScreen(initialLayout = layout) } }

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

    @Test fun kioskIdle() = kiosk { IdleScreen({}, {}) }
    @Test fun kioskChoosePackage() = kiosk { ChoosePackageScreen(event, {}, {}) }
    @Test fun kioskPaymentCash() = kiosk {
        PaymentScreen(event.packages[0], "Wrong PIN", emptyList(), { _, _, _, _ -> }, { _, _ -> }, {}, PaymentPath.CASH_VIA_STAFF)
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
        EventsPage(listOf(event, event.copy(id = "d", name = "Debut Party", status = EventStatus.DRAFT, packages = emptyList())),
            null, {}, {}, {}, { _, _ -> })
    }
    @Test fun staffEventEditor() = kiosk { EventEditor(event, null, {}, {}) }

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
