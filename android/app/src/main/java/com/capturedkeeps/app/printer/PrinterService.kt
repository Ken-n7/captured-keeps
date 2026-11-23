package com.capturedkeeps.app.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.capturedkeeps.core.print.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

enum class PrinterKind { VIRTUAL, BLUETOOTH, USB }

/**
 * Per-device printer setup. The numbers are calibration knobs for a printer we haven't tested yet
 * (DEVICE_TESTING C1, C4, C6, C10): lower [bandHeight] / raise [pauseMs] if strips come out garbled.
 */
data class PrinterConfig(
    val kind: PrinterKind = PrinterKind.VIRTUAL,
    /** Bluetooth MAC, or "vendorId:productId" for USB (the USB device name changes on every plug-in). */
    val address: String? = null,
    val name: String? = null,
    val dots: Int = PAPER_80MM_DOTS,
    val bandHeight: Int = 128,
    val feedLines: Int = 4,
    val cut: Boolean = true,
    val chunkBytes: Int = 4096,
    val pauseMs: Int = 0,
)

/** One open connection to a printer. */
private interface Link {
    fun write(bytes: ByteArray)
    /** One reply byte, or null if the printer doesn't answer in time (many cheap printers never do). */
    fun read(timeoutMs: Int): Int?
    fun close()
}

private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Bluetooth serial port profile

private class BluetoothLink(private val socket: BluetoothSocket) : Link {
    override fun write(bytes: ByteArray) { socket.outputStream.write(bytes); socket.outputStream.flush() }
    override fun read(timeoutMs: Int): Int? {
        val input = socket.inputStream
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (input.available() > 0) return input.read(); Thread.sleep(20) }
        return null
    }
    override fun close() = runCatching { socket.close() }.let { }
}

private class UsbLink(private val conn: UsbDeviceConnection, private val iface: UsbInterface,
                      private val out: UsbEndpoint, private val input: UsbEndpoint?) : Link {
    override fun write(bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val n = conn.bulkTransfer(out, bytes, off, minOf(16384, bytes.size - off), 5000)
            if (n <= 0) throw IOException("USB printer stopped accepting data")
            off += n
        }
    }
    override fun read(timeoutMs: Int): Int? {
        val ep = input ?: return null
        val buf = ByteArray(ep.maxPacketSize)
        return if (conn.bulkTransfer(ep, buf, buf.size, timeoutMs) > 0) buf[0].toInt() and 0xFF else null
    }
    override fun close() { runCatching { conn.releaseInterface(iface) }; conn.close() }
}

/** Sends ESC/POS bytes to the configured printer. One job at a time; a broken connection is reopened on the next job. */
class PrinterService(private val context: Context) {
    private val prefs = context.getSharedPreferences("printer", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var link: Link? = null
    @Volatile private var reconnect = false

    /** Changing it never waits for a running print; the next job reconnects with the new settings. */
    var config: PrinterConfig = load()
        set(value) { field = value; save(value); reconnect = true }

    /** Prints a saved strip [copies] times. Returns null when printed, or a message for staff. */
    suspend fun printStrip(path: String, copies: Int): String? {
        val dots = withContext(Dispatchers.IO) { loadStrip(path) } ?: return "Strip image is missing"
        val c = config
        val job = try { EscPos.printJob(dots, c.dots, c.feedLines, c.cut, c.bandHeight) } catch (e: IllegalArgumentException) { return e.message }
        return send(ByteArray(job.size * copies) { job[it % job.size] }, checkFirst = true)
    }

    /** Sends any printout. [checkFirst] asks the printer for paper/cover status before sending. */
    suspend fun send(bytes: ByteArray, checkFirst: Boolean = true): String? = lock.withLock {
        if (reconnect) { closeLink(); reconnect = false }
        val c = config
        if (c.kind == PrinterKind.VIRTUAL) { delay(1500); return@withLock null }
        withContext(Dispatchers.IO) {
            try {
                val l = link ?: open(c).also { link = it }
                if (checkFirst) statusOf(l)?.problem?.let { return@withContext it }
                withWatchdog(l, 15_000L + bytes.size / 2) {
                    var off = 0
                    while (off < bytes.size) {
                        val n = minOf(c.chunkBytes, bytes.size - off)
                        l.write(bytes.copyOfRange(off, off + n))
                        off += n
                        if (c.pauseMs > 0) Thread.sleep(c.pauseMs.toLong())
                    }
                }
                null
            } catch (e: Exception) {
                closeLink()
                (e.message ?: e.javaClass.simpleName).let { if ("printer" in it.lowercase()) it else "Printer problem: $it" }
            }
        }
    }

    /** Null = printer doesn't report status (or virtual). Throws a message if it can't be reached. */
    suspend fun status(): PrinterStatus? = lock.withLock {
        if (reconnect) { closeLink(); reconnect = false }
        val c = config
        if (c.kind == PrinterKind.VIRTUAL) return@withLock PrinterStatus(false, false, false, false)
        withContext(Dispatchers.IO) {
            try { statusOf(link ?: open(c).also { link = it }) } catch (e: Exception) { closeLink(); throw IOException(e.message ?: "Can't reach the printer") }
        }
    }

    private fun statusOf(l: Link): PrinterStatus? {
        l.write(PrinterStatusQuery.OFFLINE_QUERY); val offline = l.read(800)
        l.write(PrinterStatusQuery.PAPER_QUERY); val paper = l.read(800)
        return PrinterStatusQuery.parse(offline, paper)
    }

    /** Blocking writes can't be cancelled; closing the link from outside is what unsticks a frozen printer. */
    private inline fun withWatchdog(l: Link, timeoutMs: Long, block: () -> Unit) {
        val dog = Thread { try { Thread.sleep(timeoutMs); l.close() } catch (_: InterruptedException) {} }.apply { isDaemon = true; start() }
        try { block() } catch (e: IOException) { throw IOException("Printer stopped responding (${e.message})") } finally { dog.interrupt() }
    }

    private fun closeLink() { link?.close(); link = null }

    @SuppressLint("MissingPermission") // permission is requested on the printer settings page before choosing a device
    private fun open(c: PrinterConfig): Link = when (c.kind) {
        PrinterKind.VIRTUAL -> error("virtual")
        PrinterKind.BLUETOOTH -> {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("This device has no Bluetooth")
            if (!adapter.isEnabled) throw IOException("Bluetooth is off")
            val device = adapter.getRemoteDevice(c.address ?: throw IOException("No Bluetooth printer chosen"))
            // Some printers only accept the "insecure" socket; try the normal one first.
            val socket = runCatching { device.createRfcommSocketToServiceRecord(SPP).apply { connect() } }.getOrElse {
                device.createInsecureRfcommSocketToServiceRecord(SPP).apply { connect() }
            }
            BluetoothLink(socket)
        }
        PrinterKind.USB -> {
            val usb = context.getSystemService(UsbManager::class.java)
            val device = usbDevices(context).find { it.key == c.address }?.device ?: throw IOException("USB printer not connected")
            if (!usb.hasPermission(device)) throw IOException("USB access not allowed — open Printer settings and allow it")
            val (iface, out, input) = usbEndpoints(device) ?: throw IOException("That USB device isn't a printer")
            val conn = usb.openDevice(device) ?: throw IOException("Can't open the USB printer")
            if (!conn.claimInterface(iface, true)) { conn.close(); throw IOException("USB printer is busy") }
            UsbLink(conn, iface, out, input)
        }
    }

    private fun load() = PrinterConfig(
        kind = runCatching { PrinterKind.valueOf(prefs.getString("kind", null)!!) }.getOrDefault(PrinterKind.VIRTUAL),
        address = prefs.getString("address", null), name = prefs.getString("name", null),
        dots = prefs.getInt("dots", PAPER_80MM_DOTS), bandHeight = prefs.getInt("bandHeight", 128),
        feedLines = prefs.getInt("feedLines", 4), cut = prefs.getBoolean("cut", true),
        chunkBytes = prefs.getInt("chunkBytes", 4096), pauseMs = prefs.getInt("pauseMs", 0),
    )

    private fun save(c: PrinterConfig) = prefs.edit().putString("kind", c.kind.name).putString("address", c.address)
        .putString("name", c.name).putInt("dots", c.dots).putInt("bandHeight", c.bandHeight).putInt("feedLines", c.feedLines)
        .putBoolean("cut", c.cut).putInt("chunkBytes", c.chunkBytes).putInt("pauseMs", c.pauseMs).apply()

    companion object {
        /** Saved strips are 1-bit PNGs; any dark pixel is a printed dot. */
        fun loadStrip(path: String): BitImage? {
            val bmp = BitmapFactory.decodeFile(path) ?: return null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return BitImage(bmp.width, bmp.height, BooleanArray(px.size) { (px[it] and 0xFF) < 128 }).also { bmp.recycle() }
        }
    }
}

data class UsbChoice(val key: String, val name: String, val device: UsbDevice, val looksLikePrinter: Boolean)

fun usbDevices(context: Context): List<UsbChoice> =
    context.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty().map { d ->
        val printer = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER }
        UsbChoice("${d.vendorId}:${d.productId}", d.productName ?: "USB device ${d.vendorId}:${d.productId}", d, printer)
    }

/** Printer-class interface if there is one, otherwise any interface with a bulk OUT endpoint (some POS printers are vendor-class). */
private fun usbEndpoints(d: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
    val ifaces = (0 until d.interfaceCount).map { d.getInterface(it) }.sortedByDescending { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
    for (i in ifaces) {
        val eps = (0 until i.endpointCount).map { i.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val out = eps.find { it.direction == UsbConstants.USB_DIR_OUT } ?: continue
        return Triple(i, out, eps.find { it.direction == UsbConstants.USB_DIR_IN })
    }
    return null
}
