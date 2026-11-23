package com.capturedkeeps.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.capturedkeeps.app.printer.PrinterConfig
import com.capturedkeeps.app.printer.PrinterKind

/** A device the owner can pick: paired Bluetooth printer or plugged-in USB device. */
data class PrinterChoice(val address: String, val name: String, val detail: String, val needsAccess: Boolean = false)

/** Owner printer setup. Calibration knobs are here because the real printer hasn't been tested yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrinterPage(
    config: PrinterConfig,
    bluetooth: List<PrinterChoice>,
    usb: List<PrinterChoice>,
    bluetoothAllowed: Boolean,
    message: String?,
    error: String?,
    onBack: () -> Unit,
    onChange: (PrinterConfig) -> Unit,
    onAllowBluetooth: () -> Unit,
    onBluetoothSettings: () -> Unit,
    onAllowUsb: (String) -> Unit,
    onRefresh: () -> Unit,
    onStatus: () -> Unit,
    onTestPage: () -> Unit,
) = Page("Printer", onBack, error, actions = {
    OutlinedButton(onStatus) { Text("Check status") }
    Spacer(Modifier.width(8.dp))
    Button(onTestPage) { Text("Print test page") }
}) {
    if (message != null) Text(message, style = MaterialTheme.typography.titleLarge)
    Text("Now: " + when (config.kind) {
        PrinterKind.VIRTUAL -> "virtual printer (no real printing — strips show on screen)"
        else -> "${config.name ?: config.address ?: "no device chosen"} over ${config.kind.name.lowercase().replace("bluetooth", "Bluetooth")}"
    }, style = MaterialTheme.typography.bodyLarge)

    Heading("Connection")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((kind, label) in listOf(PrinterKind.VIRTUAL to "No printer (virtual)", PrinterKind.BLUETOOTH to "Bluetooth", PrinterKind.USB to "USB cable"))
            FilterChip(config.kind == kind, { onChange(config.copy(kind = kind, address = null, name = null)) }, { Text(label) })
    }

    when (config.kind) {
        PrinterKind.BLUETOOTH -> {
            Text("Pair the printer first in Android Settings → Bluetooth (the PIN is often 0000 or 1234), then choose it here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!bluetoothAllowed) Button(onAllowBluetooth) { Text("Allow Bluetooth") }
                OutlinedButton(onBluetoothSettings) { Text("Open Bluetooth settings") }
                TextButton(onRefresh) { Text("Refresh") }
            }
            if (bluetoothAllowed && bluetooth.isEmpty()) Text("No paired devices.", style = MaterialTheme.typography.bodyLarge)
            for (d in bluetooth) DeviceRow(d, d.address == config.address) { onChange(config.copy(address = d.address, name = d.name)) }
        }
        PrinterKind.USB -> {
            Text("Plug the printer into the tablet (USB-C OTG adapter), then choose it and allow access.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onRefresh) { Text("Refresh") }
            if (usb.isEmpty()) Text("No USB devices connected.", style = MaterialTheme.typography.bodyLarge)
            for (d in usb) DeviceRow(d, d.address == config.address, action = if (d.needsAccess) ({ onAllowUsb(d.address) }) else null) {
                onChange(config.copy(address = d.address, name = d.name))
            }
        }
        PrinterKind.VIRTUAL -> {}
    }

    Heading("Calibration (adjust when the printer arrives)")
    Text("Printer width", style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((d, label) in listOf(576 to "576 dots (80 mm)", 512 to "512 dots", 384 to "384 dots (58 mm)"))
            FilterChip(config.dots == d, { onChange(config.copy(dots = d)) }, { Text(label) })
    }
    Knob("Band height", config.bandHeight, 8..255, "lower if strips come out garbled or with skipped lines") { onChange(config.copy(bandHeight = it)) }
    Knob("Feed after print", config.feedLines, 0..10, "lines, so the strip clears the tear bar / cutter") { onChange(config.copy(feedLines = it)) }
    Knob("Pause between chunks", config.pauseMs, 0..100, "ms — raise if long strips get cut off over Bluetooth") { onChange(config.copy(pauseMs = it)) }
    Text("Chunk size", style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in listOf(512, 1024, 4096, 16384)) FilterChip(config.chunkBytes == n, { onChange(config.copy(chunkBytes = n)) }, { Text("$n bytes") })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(config.cut, { onChange(config.copy(cut = it)) })
        Spacer(Modifier.width(12.dp))
        Text(if (config.cut) "Cut paper after each strip" else "No cutter — tear by hand", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Heading(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun DeviceRow(d: PrinterChoice, selected: Boolean, action: (() -> Unit)? = null, onPick: () -> Unit) =
    Card(onClick = onPick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onPick)
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleLarge)
                Text(d.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) Button(action) { Text("Allow access") }
        }
    }

@Composable
private fun Knob(label: String, value: Int, range: IntRange, hint: String, onChange: (Int) -> Unit) =
    Column {
        Row { Text(label, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.weight(1f)); Text("$value", style = MaterialTheme.typography.bodyLarge) }
        Slider(value.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
