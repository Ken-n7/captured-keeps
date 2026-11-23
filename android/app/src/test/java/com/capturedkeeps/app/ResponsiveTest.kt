package com.capturedkeeps.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.capturedkeeps.app.print.AndroidStrip
import com.capturedkeeps.app.print.toBitmap
import com.capturedkeeps.app.ui.*
import com.capturedkeeps.core.domain.*
import com.capturedkeeps.core.print.SamplePhotos
import com.capturedkeeps.core.print.StripInfo
import com.capturedkeeps.core.print.StripLayout
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/** The customer screens on other screen sizes: they should adapt, not just scroll. */
class ResponsiveTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10)

    private val sizes = mapOf(
        "tabletPortrait" to DeviceConfig.NEXUS_10.copy(screenWidth = 1600, screenHeight = 2560, orientation = ScreenOrientation.PORTRAIT),
        // 8.7" tablet class (e.g. 1340 × 800 px at ~213 dpi ≈ 1005 × 600 dp)
        "tablet8inch" to DeviceConfig.NEXUS_10.copy(screenWidth = 1340, screenHeight = 800, xdpi = 213, ydpi = 213, density = Density.TV),
        // Realme 3 Pro: 1080 × 2340 px at density 408
        "phoneLandscape" to DeviceConfig.PIXEL_5.copy(screenWidth = 2340, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        "phonePortrait" to DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2340, orientation = ScreenOrientation.PORTRAIT),
    )

    private val event = Event(name = "Maria & Jose's Wedding", type = EventType.PAID, status = EventStatus.LIVE,
        packages = listOf(SessionPackage(name = "Classic", price = Centavos.pesos(150)),
            SessionPackage(name = "Duo", price = Centavos.pesos(250), copies = 2),
            SessionPackage(name = "Barkada", price = Centavos.pesos(400), copies = 4)),
        layoutIds = listOf(StripLayout.CLASSIC_3.id))

    private fun each(content: @Composable () -> Unit) = sizes.forEach { (name, config) ->
        paparazzi.unsafeUpdateConfig(deviceConfig = config)
        paparazzi.snapshot(name) { CapturedKeepsTheme { Surface(color = MaterialTheme.colorScheme.background) { content() } } }
    }

    @Test fun choosePackage() = each { ChoosePackageScreen(event, {}, {}) }
    @Test fun payment() = each {
        PaymentScreen(event.packages[0], null, emptyList(), null, { _, _, _, _ -> }, { _, _ -> }, {}, PaymentPath.CASH_VIA_STAFF)
    }
    @Test fun review() = each {
        val strip = AndroidStrip.render(StripLayout.CLASSIC_3, StripInfo(event.name, LocalDateTime.of(2026, 11, 23, 18, 42), 42),
            SamplePhotos.DEFAULT).toBitmap().asImageBitmap()
        ReviewScreen(strip, 2, false, {}, {}, null)
    }
}
