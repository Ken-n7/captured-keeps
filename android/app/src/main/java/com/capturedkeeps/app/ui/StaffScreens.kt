package com.capturedkeeps.app.ui

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.capturedkeeps.core.domain.*
import com.capturedkeeps.core.print.StripLayout
import com.capturedkeeps.data.Login
import com.capturedkeeps.data.PaymentInfo
import com.capturedkeeps.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Runs staff actions; a broken rule becomes [error], shown on the page as-is. */
class Actions(private val scope: CoroutineScope) {
    var error by mutableStateOf<String?>(null)
    fun run(block: suspend () -> Unit) {
        scope.launch {
            error = try {
                block(); null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) { e.message } catch (e: IllegalStateException) { e.message }
        }
    }
}

private sealed interface StaffPage {
    data object Menu : StaffPage
    data object Totals : StaffPage
    data class Payments(val event: Event) : StaffPage
    data object Prints : StaffPage
    data object Events : StaffPage
    data class EditEvent(val event: Event?) : StaffPage
    data object StaffList : StaffPage
    data class EditStaff(val staff: Staff?) : StaffPage
    data object Tuning : StaffPage
}

private val CLOCK = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

/** Everything behind staff login. Owner tools need the owner password ([Login.ownerTools]). */
@Composable
fun StaffArea(store: Store, login: Login, scope: CoroutineScope, onExit: () -> Unit) {
    var page by remember { mutableStateOf<StaffPage>(StaffPage.Menu) }
    var refresh by remember { mutableIntStateOf(0) }
    val actions = remember { Actions(scope) }
    val back = { actions.error = null; if (page == StaffPage.Menu) onExit() else page = StaffPage.Menu }
    BackHandler(onBack = back)
    val activity = LocalContext.current as? Activity

    when (val p = page) {
        StaffPage.Menu -> {
            val toConfirm by produceState(0, refresh) { value = store.printsToConfirm().size + store.failedPrints().size }
            StaffMenu(login, toConfirm, onOpen = { page = it }, onExit = onExit,
                onPin = { pinned -> if (pinned) activity?.startLockTask() else activity?.stopLockTask() })
        }
        StaffPage.Totals -> {
            val events by produceState(emptyList<Event>(), refresh) { value = store.events().filterNot { it.hidden } }
            var selected by remember { mutableStateOf<String?>(null) }
            val event = events.find { it.id == selected } ?: events.firstOrNull { it.status == EventStatus.LIVE } ?: events.firstOrNull()
            val totals by produceState<EventTotals?>(null, event, refresh) { value = event?.let { loadTotals(store, it) } }
            val context = LocalContext.current
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                val t = totals ?: return@rememberLauncherForActivityResult
                if (uri != null) actions.run {
                    val csv = store.ledger().forEvent(t.event.id).toCsv(t.names, ZoneId.systemDefault())
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                        ?: throw IllegalStateException("Couldn't save the file")
                }
            }
            TotalsPage(events, totals, onPick = { selected = it }, onBack = back,
                onPayments = { totals?.let { page = StaffPage.Payments(it.event) } },
                onExport = if (login.ownerTools && totals != null) ({ export.launch("${totals!!.event.name} sales.csv") }) else null,
                error = actions.error)
        }
        is StaffPage.Payments -> {
            val rows by produceState(emptyList<PaymentInfo>(), p, refresh) { value = store.payments(p.event.id) }
            PaymentsPage(p.event, rows, login.ownerTools, actions.error, onBack = { actions.error = null; page = StaffPage.Totals },
                onVoid = { id, reason -> actions.run { store.void(id, login.staff, reason, Instant.now()); refresh++ } },
                onRefund = { id, amount, reason -> actions.run { store.refund(id, Centavos.parse(amount), login.staff, reason, Instant.now()); refresh++ } })
        }
        StaffPage.Prints -> {
            val unknown by produceState(emptyList<PrintJob>(), refresh) { value = store.printsToConfirm() }
            val failed by produceState(emptyList<PrintJob>(), refresh) { value = store.failedPrints() }
            PrintsPage(unknown, failed, actions.error, onBack = back,
                onAnswer = { job, printed -> actions.run { store.resolvePrint(job.id, printed, Instant.now()); refresh++ } },
                onRetry = { job -> actions.run { store.retryPrint(job.id, Instant.now()); refresh++ } })
        }
        StaffPage.Events -> {
            val events by produceState(emptyList<Event>(), refresh) { value = store.events().filterNot { it.hidden } }
            EventsPage(events, actions.error, onBack = back, onNew = { page = StaffPage.EditEvent(null) },
                onEdit = { page = StaffPage.EditEvent(it) },
                onMove = { e, to -> actions.run { store.moveEvent(e.id, to, login.staff, Instant.now()); refresh++ } })
        }
        is StaffPage.EditEvent -> EventEditor(p.event, actions.error, onBack = { actions.error = null; page = StaffPage.Events },
            onSave = { e ->
                actions.run {
                    if (p.event == null) store.saveEvent(e) else store.editEvent(e, login.staff, Instant.now())
                    refresh++; page = StaffPage.Events
                }
            })
        StaffPage.StaffList -> {
            val staff by produceState(emptyList<Staff>(), refresh) { value = store.staff() }
            StaffListPage(staff, onBack = back, onEdit = { page = StaffPage.EditStaff(it) }, onNew = { page = StaffPage.EditStaff(null) })
        }
        is StaffPage.EditStaff -> StaffEditor(p.staff, actions.error, onBack = { actions.error = null; page = StaffPage.StaffList },
            onSave = { s, pin, password ->
                actions.run {
                    if (p.staff == null && pin == null) throw IllegalStateException("New staff need a PIN")
                    store.saveStaff(s, pin, password); refresh++; page = StaffPage.StaffList
                }
            })
        StaffPage.Tuning -> Column {
            TextButton(back, Modifier.padding(start = 16.dp, top = 8.dp)) { Text("‹ Back") }
            VirtualPrinterScreen()
        }
    }
}

data class EventTotals(val event: Event, val summary: LedgerSummary, val sessions: Int, val strips: Int, val names: Map<String, String>)

private suspend fun loadTotals(store: Store, event: Event): EventTotals {
    val done = store.sessions(event.id).filter { it.state == SessionState.COMPLETED }
    val names = store.staff().associate { it.id to it.name } + event.packages.associate { it.id to it.name }
    return EventTotals(event, store.ledger().forEvent(event.id).summary(), done.size, done.sum { it.pkg.copies }, names)
}

private fun List<Session>.sum(f: (Session) -> Int) = fold(0) { a, s -> a + f(s) }

// ───────────── Pages (stateless, screenshot-tested) ─────────────

@Composable
fun Page(title: String, onBack: () -> Unit, error: String? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹ Back", style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            actions()
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 8.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }

/** First launch: create the owner account. */
@Composable
fun SetupScreen(error: String?, onCreate: (name: String, password: String, pin: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome to Captured Keeps", style = MaterialTheme.typography.displayLarge)
        Text("Create the owner account. The password opens owner tools; the PIN confirms payments at the booth.",
            style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Your name", name) { name = it }
            Field("Password (8+ characters)", password, secret = true) { password = it }
            Field("Confirm password", confirm, secret = true) { confirm = it }
            Field("PIN (4–6 digits)", pin, secret = true, digits = true) { pin = it }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            val mismatch = confirm.isNotEmpty() && confirm != password
            if (mismatch) Text("Passwords don't match", color = MaterialTheme.colorScheme.error)
            Button({ onCreate(name.trim(), password, pin) }, Modifier.fillMaxWidth().height(56.dp),
                enabled = name.isNotBlank() && password.isNotEmpty() && confirm == password && pin.isNotEmpty()) {
                Text("Create owner account", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun LoginScreen(error: String?, onSubmit: (String) -> Unit, onBack: () -> Unit) {
    var secret by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Staff", style = MaterialTheme.typography.displayLarge)
        Text("Attendants: your PIN. Owner: your password for owner tools.", style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("PIN or password", secret, secret = true) { secret = it }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            Button({ onSubmit(secret); secret = "" }, Modifier.fillMaxWidth().height(56.dp), enabled = secret.isNotEmpty()) {
                Text("Open staff menu", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onBack, Modifier.align(Alignment.CenterHorizontally)) { Text("Back to booth") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffMenu(login: Login, printsToCheck: Int, onOpen: (StaffPage) -> Unit, onExit: () -> Unit, onPin: (Boolean) -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Hi, ${login.staff.name}", style = MaterialTheme.typography.displayLarge)
        Text(if (login.ownerTools) "Owner tools" else "Attendant tools — owner tools need the owner password",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Tile("Totals", "Today's sales, cash held") { onOpen(StaffPage.Totals) }
            Tile("Check prints", if (printsToCheck > 0) "$printsToCheck need attention" else "All good") { onOpen(StaffPage.Prints) }
            if (login.ownerTools) {
                Tile("Events", "Set up, go live, finish") { onOpen(StaffPage.Events) }
                Tile("Staff", "Names, PINs, password") { onOpen(StaffPage.StaffList) }
                Tile("Print tuning", "Preview strips and darkness") { onOpen(StaffPage.Tuning) }
                Tile("Lock booth", "Pin the app so customers can't leave it") { onPin(true) }
                Tile("Unlock booth", "Unpin the app") { onPin(false) }
            }
        }
        Button(onExit, Modifier.height(56.dp)) { Text("Back to booth", style = MaterialTheme.typography.labelLarge) }
    }

@Composable
private fun Tile(title: String, subtitle: String, onClick: () -> Unit) =
    Card(onClick, Modifier.width(260.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TotalsPage(events: List<Event>, totals: EventTotals?, onPick: (String) -> Unit, onBack: () -> Unit, onExport: (() -> Unit)?, error: String?,
               onPayments: () -> Unit = {}) =
    Page("Totals", onBack, error, actions = {
        if (totals != null) OutlinedButton(onPayments) { Text("Payments") }
        Spacer(Modifier.width(8.dp))
        if (onExport != null) OutlinedButton(onExport) { Text("Export CSV") }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (e in events) FilterChip(e.id == totals?.event?.id, { onPick(e.id) }, { Text("${e.name} · ${e.status.label}") })
        }
        if (totals == null) { Text("No events yet.", style = MaterialTheme.typography.bodyLarge); return@Page }
        val s = totals.summary
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Sessions done", totals.sessions.toString())
            Stat("Strips printed", totals.strips.toString())
            Stat("Net sales", s.net.toString())
            Stat("Gross", s.gross.toString())
            Stat("Refunds", s.refunds.toString())
            Stat("Customer QR", s.byPath[PaymentPath.CUSTOMER_QR].toString())
            Stat("Cash via staff", s.byPath[PaymentPath.CASH_VIA_STAFF].toString())
        }
        Text("CASH HELD — hand over at end of day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.cashHeldBy.isEmpty()) Text("None", style = MaterialTheme.typography.bodyLarge)
        for ((id, amount) in s.cashHeldBy) Text("${totals.names[id] ?: "Unknown staff"}: $amount", style = MaterialTheme.typography.titleLarge)
        if (s.missingReference.isNotEmpty()) Text(
            "${s.missingReference.size} payment(s) have no wallet reference — match them against the wallet history by hand.",
            color = Brand.Warn, style = MaterialTheme.typography.bodyLarge)
    }

@Composable
private fun Stat(label: String, value: String) =
    Card(Modifier.width(200.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }

@Composable
fun PrintsPage(unknown: List<PrintJob>, failed: List<PrintJob>, error: String?, onBack: () -> Unit,
               onAnswer: (PrintJob, Boolean) -> Unit, onRetry: (PrintJob) -> Unit) =
    Page("Check prints", onBack, error) {
        if (unknown.isEmpty() && failed.isEmpty()) Text("Nothing to check.", style = MaterialTheme.typography.bodyLarge)
        for (job in unknown) PrintRow(job, "The app stopped while this was printing. Did it come out?") {
            Button({ onAnswer(job, true) }) { Text("Yes, it printed") }
            OutlinedButton({ onAnswer(job, false) }) { Text("No") }
        }
        for (job in failed) PrintRow(job, "Didn't print: ${job.lastError ?: "unknown error"}") {
            Button({ onRetry(job) }) { Text("Print again") }
        }
    }

@Composable
private fun PrintRow(job: PrintJob, text: String, buttons: @Composable RowScope.() -> Unit) =
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumb = remember(job.imagePath) { BitmapFactory.decodeFile(job.imagePath)?.asImageBitmap() }
            if (thumb != null) Image(thumb, null, Modifier.height(120.dp))
            Column(Modifier.weight(1f)) {
                Text(CLOCK.format(job.createdAt), style = MaterialTheme.typography.titleLarge)
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            buttons()
        }
    }

private val MOVES = listOf(
    EventStatus.LIVE to "Go live", EventStatus.PAUSED to "Pause", EventStatus.COMPLETED to "Finish", EventStatus.CANCELLED to "Cancel",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventsPage(events: List<Event>, error: String?, onBack: () -> Unit, onNew: () -> Unit, onEdit: (Event) -> Unit, onMove: (Event, EventStatus?) -> Unit) =
    Page("Events", onBack, error, actions = { Button(onNew) { Text("New event") } }) {
        if (events.isEmpty()) Text("No events yet. Create one, add packages, then go live.", style = MaterialTheme.typography.bodyLarge)
        for (e in events) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text(e.status.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = when (e.status) {
                        EventStatus.LIVE -> Brand.Ok; EventStatus.PAUSED -> Brand.Warn; else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                }
                Text("${if (e.type == EventType.PAID) "Paid" else "Free"} · ${e.packages.joinToString { "${it.name} ${it.price}" }.ifEmpty { "no packages" }}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.status == EventStatus.DRAFT) for (b in e.goLiveCheck(mediaPlayable = false).blockers)
                    Text("• $b", style = MaterialTheme.typography.bodyMedium, color = Brand.Warn)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!e.status.isFinished) OutlinedButton({ onEdit(e) }) { Text("Edit") }
                    for ((to, label) in MOVES) if (to in e.nextMoves()) OutlinedButton({ onMove(e, to) }) { Text(if (to == EventStatus.LIVE && e.status == EventStatus.PAUSED) "Resume" else label) }
                    if (e.status.isFinished) OutlinedButton({ onMove(e, null) }) { Text("Archive") }
                }
            }
        }
    }

private fun Event.nextMoves() = when (status) {
    EventStatus.DRAFT -> setOf(EventStatus.LIVE, EventStatus.CANCELLED)
    EventStatus.LIVE -> setOf(EventStatus.PAUSED, EventStatus.COMPLETED)
    EventStatus.PAUSED -> setOf(EventStatus.LIVE, EventStatus.COMPLETED)
    else -> emptySet()
}

private class PackageDraft(val id: String, name: String, price: String, copies: String, retakes: String) {
    var name by mutableStateOf(name); var price by mutableStateOf(price)
    var copies by mutableStateOf(copies); var retakes by mutableStateOf(retakes)

    fun build() = SessionPackage(id, name.trim(), Centavos.parse(price.ifBlank { "0" }),
        copies.toIntOrNull() ?: throw IllegalStateException("Copies must be a number"),
        retakes.toIntOrNull() ?: throw IllegalStateException("Retakes must be a number"))
}

/** New event ([event] null) or edit. Edits only reach new sessions; running ones keep their snapshot. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditor(event: Event?, error: String?, onBack: () -> Unit, onSave: (Event) -> Unit) {
    val e = event ?: Event(name = "New event", type = EventType.PAID, layoutIds = listOf(StripLayout.CLASSIC_3.id))
    var name by remember { mutableStateOf(if (event == null) "" else e.name) }
    var type by remember { mutableStateOf(e.type) }
    var venue by remember { mutableStateOf(e.venue) }
    var welcome by remember { mutableStateOf(e.welcomeText) }
    var footer by remember { mutableStateOf(e.receiptFooter) }
    var layouts by remember { mutableStateOf(e.layoutIds.toSet()) }
    val packages = remember {
        mutableStateListOf<PackageDraft>().apply {
            addAll(e.packages.map { PackageDraft(it.id, it.name, if (it.price == Centavos.ZERO) "" else "%d.%02d".format(it.price.value / 100, it.price.value % 100), "${it.copies}", "${it.retakes}") })
        }
    }
    var localError by remember { mutableStateOf<String?>(null) }

    Page(if (event == null) "New event" else "Edit event", onBack, localError ?: error, actions = {
        Button({
            localError = try {
                onSave(e.copy(name = name.trim(), type = type, venue = venue.trim(), welcomeText = welcome.trim(),
                    receiptFooter = footer.trim(), layoutIds = StripLayout.PRESETS.map { it.id }.filter { it in layouts },
                    packages = packages.map { it.build() }))
                null
            } catch (x: IllegalStateException) { x.message } catch (x: IllegalArgumentException) { x.message }
        }) { Text("Save") }
    }) {
        Field("Event name (max 32)", name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(type == EventType.PAID, { type = EventType.PAID }, { Text("Paid") })
            FilterChip(type == EventType.FREE, { type = EventType.FREE }, { Text("Free (sponsored)") })
        }
        Field("Venue", venue) { venue = it }
        Field("Welcome text", welcome) { welcome = it }
        Field("Receipt footer", footer) { footer = it }

        Text("STRIP LAYOUTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (l in StripLayout.PRESETS) FilterChip(l.id in layouts, { layouts = if (l.id in layouts) layouts - l.id else layouts + l.id }, { Text(l.name) })
        }

        Text("PACKAGES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (p in packages) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(p.name, { p.name = it }, Modifier.weight(2f), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(p.price, { p.price = it }, Modifier.weight(1.3f), label = { Text(if (type == EventType.FREE) "Price (free)" else "Price ₱") },
                singleLine = true, enabled = type == EventType.PAID, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(p.copies, { p.copies = it }, Modifier.weight(1f), label = { Text("Prints") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(p.retakes, { p.retakes = it }, Modifier.weight(1f), label = { Text("Retakes") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton({ packages.remove(p) }) { Text("Remove") }
        }
        OutlinedButton({ packages += PackageDraft(newId(), "", "", "1", "2") }) { Text("Add package") }
        LaunchedEffect(type) { if (type == EventType.FREE) packages.forEach { it.price = "" } }
    }
}

@Composable
fun StaffListPage(staff: List<Staff>, onBack: () -> Unit, onEdit: (Staff) -> Unit, onNew: () -> Unit) =
    Page("Staff", onBack, actions = { Button(onNew) { Text("Add attendant") } }) {
        for (s in staff) Card(onClick = { onEdit(s) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(16.dp).fillMaxWidth()) {
                Text(s.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(s.role.name.lowercase() + if (s.active) "" else " · disabled", style = MaterialTheme.typography.bodyLarge,
                    color = if (s.active) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Warn)
            }
        }
        Text("Staff who leave are disabled, not deleted — their name stays on past sales.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

@Composable
fun StaffEditor(staff: Staff?, error: String?, onBack: () -> Unit, onSave: (Staff, pin: String?, password: String?) -> Unit) {
    var name by remember { mutableStateOf(staff?.name ?: "") }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(staff?.active ?: true) }
    val owner = staff?.role == Role.OWNER
    Page(staff?.name ?: "New attendant", onBack, error, actions = {
        Button({
            onSave(staff?.copy(name = name.trim(), active = active) ?: Staff(name = name.trim(), role = Role.ATTENDANT),
                pin.ifEmpty { null }, password.ifEmpty { null })
        }, enabled = name.isNotBlank()) { Text("Save") }
    }) {
        Field("Name", name) { name = it }
        Field(if (staff == null) "PIN (4–6 digits)" else "New PIN (leave empty to keep)", pin, secret = true, digits = true) { pin = it }
        if (owner) Field("New password (leave empty to keep)", password, secret = true) { password = it }
        if (staff != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(active, { active = it })
            Spacer(Modifier.width(12.dp))
            Text(if (active) "Active — can log in and confirm payments" else "Disabled — can't log in or confirm payments",
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, digits: Boolean = false, onChange: (String) -> Unit) =
    OutlinedTextField(value, { if (!digits || (it.length <= 6 && it.all(Char::isDigit))) onChange(it) }, Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            digits -> KeyboardType.NumberPassword; secret -> KeyboardType.Password; else -> KeyboardType.Text
        }))

/** Every payment of an event. Void = cancel a payment that was never used; refund = give money back (owner password only). */
@Composable
fun PaymentsPage(event: Event, rows: List<PaymentInfo>, ownerTools: Boolean, error: String?, onBack: () -> Unit,
                 onVoid: (saleId: String, reason: String) -> Unit, onRefund: (saleId: String, amount: String, reason: String) -> Unit) {
    var voiding by remember { mutableStateOf<PaymentInfo?>(null) }
    var refunding by remember { mutableStateOf<PaymentInfo?>(null) }
    val names = event.packages.associate { it.id to it.name }
    Page("Payments · ${event.name}", onBack, error) {
        if (rows.isEmpty()) Text("No payments yet.", style = MaterialTheme.typography.bodyLarge)
        for (p in rows) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${CLOCK.format(p.sale.at)} · ${names[p.sale.packageId] ?: "Package"} · ${p.sale.amount}", style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(
                        if (p.sale.path == PaymentPath.CUSTOMER_QR) "Customer QR" else "Cash via staff",
                        p.sale.qrReference?.let { "ref $it" } ?: "no reference",
                    ).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.statusText(), style = MaterialTheme.typography.bodyLarge, color = when {
                        p.voided || p.refunded > Centavos.ZERO -> Brand.Error; p.unused -> Brand.Warn; else -> Brand.Ok
                    })
                }
                if (p.unused && !p.voided) OutlinedButton({ voiding = p }) { Text("Void") }
                if (ownerTools && !p.voided && p.refunded < p.sale.amount) OutlinedButton({ refunding = p }) { Text("Refund") }
            }
        }
        if (!ownerTools) Text("Refunds need the owner password login.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    voiding?.let { p ->
        ReasonDialog("Void ${p.sale.amount}?", "Use this when the customer paid but never had their session (and you returned the money or they left).",
            amount = null, onDismiss = { voiding = null }) { _, reason -> onVoid(p.sale.id, reason); voiding = null }
    }
    refunding?.let { p ->
        val left = p.sale.amount - p.refunded
        ReasonDialog("Refund (up to $left)", "Record money given back. The payment and this refund both stay on record.",
            amount = "%d.%02d".format(left.value / 100, left.value % 100), onDismiss = { refunding = null }) { amount, reason ->
            onRefund(p.sale.id, amount!!, reason); refunding = null
        }
    }
}

private fun PaymentInfo.statusText() = when {
    voided -> "Voided"
    refunded >= sale.amount -> "Refunded in full"
    refunded > Centavos.ZERO -> "Refunded $refunded"
    unused -> "Paid, not used yet — resume at the booth or void"
    session == SessionState.COMPLETED -> "Used — strip printed"
    else -> "Used — session ${session?.name?.lowercase()}"
}

@Composable
private fun ReasonDialog(title: String, body: String, amount: String?, onDismiss: () -> Unit, onConfirm: (amount: String?, reason: String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var value by remember { mutableStateOf(amount ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                if (amount != null) OutlinedTextField(value, { value = it }, label = { Text("Amount ₱") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason (required)") }, singleLine = true)
            }
        },
        confirmButton = { Button({ onConfirm(amount?.let { value }, reason) }, enabled = reason.isNotBlank()) { Text("Confirm") } },
        dismissButton = { TextButton(onDismiss) { Text("Keep it") } })
}
