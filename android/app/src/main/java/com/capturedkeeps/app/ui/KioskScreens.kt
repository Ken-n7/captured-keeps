package com.capturedkeeps.app.ui

import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.capturedkeeps.app.media.Media
import com.capturedkeeps.data.Store
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.capturedkeeps.app.print.AndroidStrip
import com.capturedkeeps.app.print.Photos
import com.capturedkeeps.app.print.toBitmap
import com.capturedkeeps.core.domain.*
import com.capturedkeeps.core.print.StripInfo
import com.capturedkeeps.data.KioskFlow
import com.capturedkeeps.data.KioskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

private const val SECOND = 1000L
private val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/** How long a screen waits for a touch before going back to idle (null = never). */
private fun timeoutFor(s: KioskState): Long? = when (s) {
    is KioskState.ChoosePackage, is KioskState.Review, is KioskState.Blocked -> 60 * SECOND
    is KioskState.Payment -> 120 * SECOND
    is KioskState.Done -> 20 * SECOND
    else -> null
}

/** Connects the screens to [KioskFlow]. [scope] outlives the screen so saves always finish. */
@Composable
fun KioskScreen(
    flow: KioskFlow, store: Store, scope: CoroutineScope, filesDir: File,
    /** Prints a saved strip; null = printed, otherwise a message for the customer/staff. */
    print: suspend (path: String, copies: Int) -> String?,
    onStaff: () -> Unit,
) {
    val state by flow.state.collectAsState()
    val context = LocalContext.current
    // Re-read when back at idle, so event edits (welcome text, video) show up without a restart.
    val live by produceState<Event?>(null, state is KioskState.Idle) { if (state is KioskState.Idle) value = store.liveEvent() }
    val qr = remember(state is KioskState.Payment) { Media.image(Media.paymentQr(context)) }
    fun go(action: suspend KioskFlow.() -> Unit) { scope.launch { flow.action() } }

    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state, touched) { timeoutFor(state)?.let { delay(it); flow.timeout() } }
    // Android Back never leaves the booth: it backs out of package/payment (and free sessions); otherwise it does nothing.
    BackHandler { go { cancel() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touched = System.nanoTime() } }
    }) {
        when (val s = state) {
            KioskState.Idle -> IdleScreen(live?.welcomeText, live?.idleMedia, onTap = { go { start() } }, onStaff = onStaff)
            is KioskState.Blocked -> Message("Please call staff", s.message) {
                Button({ go { start() } }) { Text("Try again") }
            }
            is KioskState.ChoosePackage -> ChoosePackageScreen(s.event, onPick = { id -> go { choose(id) } }, onBack = { go { cancel() } })
            is KioskState.Payment -> PaymentScreen(s.pkg, s.error, s.unused, qr,
                onConfirm = { path, pin, tendered, ref -> go { confirmPayment(path, pin, tendered, ref) } },
                onResume = { saleId, pin -> go { resumePayment(saleId, pin) } },
                onBack = { go { cancel() } })
            is KioskState.Capturing -> key(s.session.id, s.session.retakesUsed) {
                CaptureScreen(
                    shot = s.shot, of = s.layout.shots,
                    photoFile = { n -> File(Photos.dir(filesDir, s.session.id), "photo-${s.session.retakesUsed}-$n.jpg") },
                    onPhoto = { path -> go { photoTaken(path) } },
                    onError = { msg -> go { cameraFailed(msg) } },
                    onCancel = if (s.session.saleId == null) ({ go { cancel() } }) else null,
                )
            }
            is KioskState.Review -> {
                var failed by remember(s) { mutableStateOf(false) }
                val dots by produceState<com.capturedkeeps.core.print.BitImage?>(null, s) {
                    value = try {
                        withContext(Dispatchers.Default) {
                            AndroidStrip.render(s.layout, StripInfo(s.event.name, LocalDateTime.now(), s.number), s.photos.map(Photos::loadGray))
                        }
                    } catch (e: Exception) { failed = true; null }
                }
                val bitmap = remember(dots) { dots?.toBitmap()?.asImageBitmap() }
                ReviewScreen(bitmap, s.retakesLeft, failed,
                    onRetake = { go { retake() } },
                    onPrint = {
                        dots?.let { d ->
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { Photos.saveStrip(d, File(Photos.dir(filesDir, s.session.id), "strip.png")) }
                                flow.accept(f.path)
                            }
                        }
                    },
                    onCancel = if (s.session.saleId == null) ({ go { cancel() } }) else null,
                )
            }
            is KioskState.Printing -> {
                val bitmap = rememberStrip(s.job.imagePath)
                // Each send (first try or retry) is keyed by attempt, so it runs once per attempt.
                LaunchedEffect(s.job.id, s.job.attempts) {
                    if (s.job.state == PrintState.SENDING) scope.launch { flow.printResult(print(s.job.imagePath, s.job.copies)) }
                }
                PrintingScreen(bitmap, s.job.lastError.takeIf { s.job.state == PrintState.FAILED },
                    onRetry = { go { retryPrint() } }, onLater = { go { printLater() } })
            }
            is KioskState.Done -> DoneScreen(rememberStrip(s.stripPath), s.session.pkg.copies, onDone = { go { timeout() } })
        }
    }
}

@Composable
private fun rememberStrip(path: String) = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }

// ───────────── Screens (stateless, screenshot-tested) ─────────────

/** Event video on a silent loop with the welcome text; the branded screen if there's no video or it won't play. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IdleScreen(welcome: String?, video: String?, onTap: () -> Unit, onStaff: () -> Unit) {
    // ponytail: long-press opens staff login; a hidden corner/gesture is enough until the Tab A11 kiosk setup.
    var videoFailed by remember(video) { mutableStateOf(false) }
    val playVideo = video != null && !videoFailed && File(video).exists()
    Box(Modifier.fillMaxSize().combinedClickable(onClick = onTap, onLongClick = onStaff)) {
        if (playVideo) {
            AndroidView({ ctx ->
                VideoView(ctx).apply {
                    setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f); start() }
                    setOnErrorListener { _, _, _ -> videoFailed = true; true }
                    setVideoPath(video)
                }
            }, Modifier.fillMaxSize(), onRelease = { it.stopPlayback() })
            Text(welcome?.ifBlank { null } ?: "Tap to start", style = MaterialTheme.typography.headlineMedium,
                color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x66000000)).padding(24.dp))
        } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("CAPTURED KEEPS", style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center)
            Text("RECEIPT PHOTO STUDIO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(48.dp))
            Text(welcome?.ifBlank { null } ?: "Tap anywhere to start", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("Little moments. Lifelong keeps.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ChoosePackageScreen(event: Event, onPick: (String) -> Unit, onBack: () -> Unit) =
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(event.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Choose your strip", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (p in event.packages) Card(onClick = { onPick(p.id) }, Modifier.width(280.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name, style = MaterialTheme.typography.headlineMedium)
                    Text(if (p.price == Centavos.ZERO) "Free" else p.price.toString(), style = MaterialTheme.typography.titleLarge)
                    Text("${p.copies} ${if (p.copies == 1) "print" else "prints"} · ${p.retakes} retakes",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onBack) { Text("Back", style = MaterialTheme.typography.labelLarge) }
    }

/** Staff-facing: confirm the payment arrived in the business wallet, then enter PIN. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentScreen(
    pkg: SessionPackage,
    error: String?,
    unused: List<LedgerEntry.Sale>,
    qr: ImageBitmap?,
    onConfirm: (PaymentPath, pin: String, tendered: String?, reference: String?) -> Unit,
    onResume: (saleId: String, pin: String) -> Unit,
    onBack: () -> Unit,
    initialPath: PaymentPath = PaymentPath.CUSTOMER_QR,
) {
    var path by remember { mutableStateOf(initialPath) }
    var resume by remember(unused) { mutableStateOf<LedgerEntry.Sale?>(null) }
    var tendered by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val change = runCatching { Centavos.parse(tendered) - pkg.price }.getOrNull()

    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${pkg.name} · ${pkg.price}", style = MaterialTheme.typography.displayLarge)
            Text(when (path) {
                PaymentPath.CUSTOMER_QR -> "Scan the booth QR code with your e-wallet and pay ${pkg.price}. Then show staff your confirmation."
                PaymentPath.CASH_VIA_STAFF -> "Hand ${pkg.price} in cash to staff. Staff will pay the booth QR for you."
            }, style = MaterialTheme.typography.bodyLarge)
            // Fixed size so it can't be squeezed on short screens; the column scrolls instead.
            if (qr != null && resume == null) Image(qr, "Booth payment QR code", Modifier.size(280.dp)
                .background(Color.White).padding(8.dp), contentScale = ContentScale.Fit)
            TextButton(onBack) { Text("Back", style = MaterialTheme.typography.labelLarge) }
        }
        // Scrolls so the confirm button stays reachable on short screens / with the keyboard open.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("STAFF", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(resume == null && path == PaymentPath.CUSTOMER_QR, { path = PaymentPath.CUSTOMER_QR; resume = null }, { Text("Customer paid QR") })
                FilterChip(resume == null && path == PaymentPath.CASH_VIA_STAFF, { path = PaymentPath.CASH_VIA_STAFF; resume = null }, { Text("Cash to staff") })
                for (sale in unused) FilterChip(resume == sale, { resume = sale }, {
                    Text("Resume unused payment · ${TIME.format(sale.at.atZone(ZoneId.systemDefault()))}")
                })
            }
            if (resume != null) Text("This customer already paid, but their session was interrupted. No new payment is recorded.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (resume == null && path == PaymentPath.CASH_VIA_STAFF) {
                OutlinedTextField(tendered, { tendered = it }, label = { Text("Cash received") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    supportingText = { if (change != null) Text("Change: $change") })
            }
            if (resume == null) OutlinedTextField(reference, { reference = it }, label = { Text("Wallet reference no. (recommended)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, label = { Text("Your PIN") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            Button({
                resume?.let { onResume(it.id, pin) }
                    ?: onConfirm(path, pin, tendered.takeIf { path == PaymentPath.CASH_VIA_STAFF }, reference)
            }, enabled = pin.length >= 4, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (resume != null) "Resume session" else "Payment received — start session", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ReviewScreen(strip: ImageBitmap?, retakesLeft: Int, failed: Boolean, onRetake: () -> Unit, onPrint: () -> Unit, onCancel: (() -> Unit)?) =
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        StripView(strip, Modifier.fillMaxHeight())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                failed -> Text("We couldn't prepare your strip.", style = MaterialTheme.typography.displayLarge)
                strip == null -> Text("Preparing your strip…", style = MaterialTheme.typography.displayLarge)
                else -> Text("Love it?", style = MaterialTheme.typography.displayLarge)
            }
            Button(onPrint, enabled = strip != null, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Print my strip", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onRetake, enabled = retakesLeft > 0, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(if (retakesLeft > 0) "Retake all photos ($retakesLeft left)" else "No retakes left", style = MaterialTheme.typography.labelLarge)
            }
            if (onCancel != null) TextButton(onCancel) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }
        }
    }

@Composable
fun PrintingScreen(strip: ImageBitmap?, error: String?, onRetry: () -> Unit, onLater: () -> Unit = {}) =
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        StripView(strip, Modifier.fillMaxHeight())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (error == null) {
                Text("Printing…", style = MaterialTheme.typography.displayLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text("Print didn't finish", style = MaterialTheme.typography.displayLarge)
                Text("$error. Please call staff — you won't be charged again.", style = MaterialTheme.typography.bodyLarge)
                Button(onRetry, Modifier.fillMaxWidth().height(64.dp)) { Text("Try printing again", style = MaterialTheme.typography.labelLarge) }
                TextButton(onLater) { Text("Staff: print it later from Check prints", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }

@Composable
fun DoneScreen(strip: ImageBitmap?, copies: Int, onDone: () -> Unit) =
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        StripView(strip, Modifier.fillMaxHeight())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Here's your keep!", style = MaterialTheme.typography.displayLarge)
            Text(if (copies == 1) "Take your strip from the printer." else "Take your $copies strips from the printer.",
                style = MaterialTheme.typography.bodyLarge)
            Text("Little moments. Lifelong keeps.", style = MaterialTheme.typography.headlineMedium)
            Button(onDone, Modifier.fillMaxWidth().height(64.dp)) { Text("Done", style = MaterialTheme.typography.labelLarge) }
        }
    }

/** A strip scaled to fit; smooth filtering so the dither pattern doesn't shimmer when shrunk. */
@Composable
fun StripView(strip: ImageBitmap?, modifier: Modifier = Modifier) {
    if (strip == null) Box(modifier.aspectRatio(0.37f).background(MaterialTheme.colorScheme.surface), Alignment.Center) { CircularProgressIndicator() }
    else Image(strip, "Your photo strip", modifier.border(1.dp, MaterialTheme.colorScheme.outline),
        contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
}

@Composable
fun Message(title: String, body: String, actions: @Composable RowScope.() -> Unit) =
    Column(Modifier.fillMaxSize().padding(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), content = actions)
    }
