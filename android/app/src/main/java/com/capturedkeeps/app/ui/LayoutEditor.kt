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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.capturedkeeps.app.print.AndroidStrip
import com.capturedkeeps.app.print.toBitmap
import com.capturedkeeps.core.print.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

private const val DOTS_PER_MM = 8 // 203 dpi

/** What the preview shows: the strip exactly as the printer would print it, plus its paper/data size. */
class StripPreview(val bitmap: ImageBitmap, val width: Int, val height: Int, val bytes: Int)

fun renderPreview(layout: StripLayout, info: StripInfo, photos: List<GrayImage>): StripPreview {
    val dots = AndroidStrip.render(layout, info, photos)
    return StripPreview(dots.toBitmap().asImageBitmap(), dots.width, dots.height, EscPos.printJob(dots).size)
}

private val SAMPLE_INFO = StripInfo("Maria & Jose's Wedding", LocalDateTime.of(2026, 11, 23, 18, 42), 42)

/**
 * Layout editor + virtual printer: every setting from PROJECT_NOTES §5.1 (except logo/overlay images),
 * with a dot-for-dot preview of what the thermal printer will print.
 * [initialPreview] lets screenshot tests render synchronously; on the device the preview renders in the background.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutEditor(
    initial: StripLayout,
    error: String?,
    onBack: () -> Unit,
    onSave: (StripLayout) -> Unit,
    onReset: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    photos: List<GrayImage> = SamplePhotos.DEFAULT,
    initialPreview: StripPreview? = null,
) {
    var l by remember(initial) { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Rendered off the main thread so sliders stay smooth; an invalid combination shows its message instead.
    val preview by produceState(initialPreview, l) {
        value = try {
            problem = null
            withContext(Dispatchers.Default) { renderPreview(l, SAMPLE_INFO, photos) }
        } catch (e: IllegalArgumentException) { problem = e.message; value }
    }
    fun set(change: StripLayout.() -> StripLayout) {
        l = try { l.change() } catch (e: IllegalArgumentException) { problem = e.message; l }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // One printer dot = one screen pixel, so nothing is blurred or resampled.
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
            preview?.let { p ->
                val width = with(LocalDensity.current) { p.width.toDp() }
                Image(p.bitmap, "Strip as it will print", filterQuality = FilterQuality.None, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.width(width).border(1.dp, MaterialTheme.colorScheme.outline))
            } ?: Box(Modifier.size(288.dp, 400.dp), Alignment.Center) { CircularProgressIndicator() }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onBack) { Text("‹ Back") }
                Text("Strip layout", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button({ onSave(l) }, enabled = l != initial) { Text("Save") }
            }
            (error ?: problem)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            preview?.let {
                Text("${it.width} × ${it.height} dots · ${it.height / DOTS_PER_MM} mm of paper · ${it.bytes / 1024} KB to send · version ${initial.version}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            OutlinedTextField(l.name, { v -> set { copy(name = v) } }, Modifier.fillMaxWidth(), label = { Text("Layout name") }, singleLine = true)

            Label("Photos")
            Choices((1..4).toList(), l.shots, { "$it ${if (it == 1) "photo" else "photos"}" }) { n -> set { copy(shots = n) } }
            Choices(PhotoShape.entries, l.shape, { it.label }) { s -> set { copy(shape = s) } }
            TuningSlider("Margin", l.margin.toFloat(), 0f..120f, "${l.margin} dots") { v -> set { copy(margin = v.toInt()) } }
            TuningSlider("Gap between photos", l.gap.toFloat(), 0f..120f, "${l.gap} dots") { v -> set { copy(gap = v.toInt()) } }

            Label("Text")
            Toggle("Header", l.showHeader) { v -> set { copy(showHeader = v) } }
            if (l.showHeader) {
                OutlinedTextField(l.headerText, { v -> set { copy(headerText = v) } }, Modifier.fillMaxWidth(),
                    label = { Text("Header (max ${StripLayout.MAX_HEADER})") }, singleLine = true)
                OutlinedTextField(l.subheaderText, { v -> set { copy(subheaderText = v) } }, Modifier.fillMaxWidth(),
                    label = { Text("Subheader (max ${StripLayout.MAX_LINE})") }, singleLine = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Toggle("Event name", l.showEventName) { v -> set { copy(showEventName = v) } }
                Toggle("Date", l.showDate) { v -> set { copy(showDate = v) } }
                Toggle("Time", l.showTime) { v -> set { copy(showTime = v) } }
                Toggle("Strip number", l.showNumber) { v -> set { copy(showNumber = v) } }
            }
            OutlinedTextField(l.footerText, { v -> set { copy(footerText = v) } }, Modifier.fillMaxWidth(),
                label = { Text("Footer (max ${StripLayout.MAX_LINE}, empty = none)") }, singleLine = true)

            Label("Print darkness (photos only — text stays crisp)")
            Choices(DitherStyle.entries, l.tuning.dither, { it.label }) { d -> set { copy(tuning = tuning.copy(dither = d)) } }
            TuningSlider("Brightness", l.tuning.brightness.toFloat(), -100f..100f, l.tuning.brightness.toString()) { v ->
                set { copy(tuning = tuning.copy(brightness = v.toInt())) }
            }
            TuningSlider("Contrast", l.tuning.contrast.toFloat(), -100f..100f, l.tuning.contrast.toString()) { v ->
                set { copy(tuning = tuning.copy(contrast = v.toInt())) }
            }
            TuningSlider("Gamma", l.tuning.gamma.toFloat(), 0.2f..3f, "%.2f".format(l.tuning.gamma)) { v ->
                set { copy(tuning = tuning.copy(gamma = Math.round(v * 20) / 20.0)) }
            }
            Toggle("Auto levels", l.tuning.autoLevels) { v -> set { copy(tuning = tuning.copy(autoLevels = v)) } }
            Text("Real thermal paper prints darker than this screen — confirm on paper when the printer arrives.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ l = initial }, enabled = l != initial) { Text("Undo changes") }
                if (onReset != null) OutlinedButton(onReset) { Text("Reset to built-in") }
                if (onDelete != null) TextButton(onDelete) { Text("Delete layout", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private val PhotoShape.label get() = when (this) {
    PhotoShape.LANDSCAPE -> "Landscape 4:3"
    PhotoShape.SQUARE -> "Square"
    PhotoShape.PORTRAIT -> "Portrait 3:4"
}

private val DitherStyle.label get() = when (this) {
    DitherStyle.FLOYD_STEINBERG -> "Smooth (Floyd–Steinberg)"
    DitherStyle.ATKINSON -> "Crisp (Atkinson)"
    DitherStyle.THRESHOLD -> "Black & white only"
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(on, onChange)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }

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
