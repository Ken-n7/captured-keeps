package com.capturedkeeps.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.capturedkeeps.app.print.AndroidStrip
import com.capturedkeeps.app.print.toBitmap
import com.capturedkeeps.core.print.*
import java.time.LocalDateTime

private const val DOTS_PER_MM = 8 // 203 dpi

/**
 * Stand-in for the thermal printer until it arrives: shows exactly the dots that would be sent,
 * and lets the owner try layouts and print tuning on screen.
 */
@Composable
fun VirtualPrinterScreen(
    photos: List<GrayImage> = SamplePhotos.DEFAULT,
    info: StripInfo = StripInfo("Maria & Jose's Wedding", LocalDateTime.of(2026, 11, 23, 18, 42), 42),
    initialLayout: StripLayout = StripLayout.CLASSIC_3,
) {
    var preset by remember { mutableStateOf(initialLayout) }
    var tuning by remember { mutableStateOf(initialLayout.tuning) }
    val layout = preset.copy(tuning = tuning)
    // ponytail: renders on the main thread (~100 ms); move to a background coroutine if sliders feel laggy on the tablet.
    val dots = remember(layout, info, photos) { AndroidStrip.render(layout, info, photos) }
    val bitmap = remember(dots) { dots.toBitmap().asImageBitmap() }
    val bytes = remember(dots) { EscPos.printJob(dots).size }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        // One printer dot = one screen pixel, so nothing is blurred or resampled.
        val paperWidth = with(LocalDensity.current) { dots.width.toDp() }
        Box(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
            Image(bitmap, contentDescription = "Strip as it will print",
                filterQuality = FilterQuality.None, contentScale = ContentScale.FillWidth,
                modifier = Modifier.width(paperWidth).border(1.dp, MaterialTheme.colorScheme.outline))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Virtual printer", style = MaterialTheme.typography.headlineMedium)
            Text("${dots.width} × ${dots.height} dots · ${dots.height / DOTS_PER_MM} mm of paper · ${bytes / 1024} KB to send",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Label("Layout")
            Choices(StripLayout.PRESETS, preset, { it.name }) { preset = it; tuning = it.tuning }

            Label("Dithering")
            Choices(DitherStyle.entries, tuning.dither, { it.label }) { tuning = tuning.copy(dither = it) }

            TuningSlider("Brightness", tuning.brightness.toFloat(), -100f..100f, tuning.brightness.toString()) {
                tuning = tuning.copy(brightness = it.toInt())
            }
            TuningSlider("Contrast", tuning.contrast.toFloat(), -100f..100f, tuning.contrast.toString()) {
                tuning = tuning.copy(contrast = it.toInt())
            }
            TuningSlider("Gamma", tuning.gamma.toFloat(), 0.2f..3f, "%.2f".format(tuning.gamma)) {
                tuning = tuning.copy(gamma = Math.round(it * 20) / 20.0)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(tuning.autoLevels, { tuning = tuning.copy(autoLevels = it) })
                Spacer(Modifier.width(12.dp))
                Text("Auto levels", style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton({ tuning = preset.tuning }) { Text("Reset tuning") }
            Text("Real thermal paper prints darker than this screen — confirm on paper when the printer arrives.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val DitherStyle.label get() = when (this) {
    DitherStyle.FLOYD_STEINBERG -> "Smooth (Floyd–Steinberg)"
    DitherStyle.ATKINSON -> "Crisp (Atkinson)"
    DitherStyle.THRESHOLD -> "Black & white only"
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) FilterChip(o == selected, { onPick(o) }, { Text(name(o), style = MaterialTheme.typography.labelLarge) })
    }

@Composable
private fun TuningSlider(name: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) =
    Column {
        Row { Label(name); Spacer(Modifier.weight(1f)); Text(shown, style = MaterialTheme.typography.bodyMedium) }
        Slider(value, onChange, valueRange = range)
    }
