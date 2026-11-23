package com.capturedkeeps.app

import android.graphics.Bitmap
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.capturedkeeps.app.print.AndroidStrip
import com.capturedkeeps.app.print.toBitmap
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
