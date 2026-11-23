package com.capturedkeeps.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.capturedkeeps.core.domain.*
import java.time.Instant
import java.time.LocalDate

// Rows mirror the domain types. Money = Long centavos, times = epoch millis, dates = ISO text.
// ponytail: no sync columns yet — add (with a migration) when cloud sync is built.

@Entity(tableName = "staff")
data class StaffRow(@PrimaryKey val id: String, val name: String, val role: String)

@Entity(tableName = "events")
data class EventRow(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val status: String,
    val venue: String,
    val startDate: String?,
    val endDate: String?,
    /** Newline-separated. */
    val layoutIds: String,
    val idleMedia: String?,
    val welcomeText: String,
    val receiptFooter: String,
    val hidden: Boolean,
)

@Entity(tableName = "packages", indices = [Index("eventId")])
data class PackageRow(
    @PrimaryKey val id: String,
    val eventId: String,
    val position: Int,
    val name: String,
    val price: Long,
    val copies: Int,
    val retakes: Int,
)

/** Package columns are a snapshot, so editing the event never changes a past or running session. */
@Entity(tableName = "sessions", indices = [Index("eventId")])
data class SessionRow(
    @PrimaryKey val id: String,
    val eventId: String,
    val deviceId: String,
    val pkgId: String,
    val pkgName: String,
    val pkgPrice: Long,
    val pkgCopies: Int,
    val pkgRetakes: Int,
    val layoutId: String,
    val layoutVersion: Int,
    val saleId: String?,
    val state: String,
    val retakesUsed: Int,
    val endReason: String?,
    val startedAt: Long,
    val endedAt: Long?,
)

@Entity(tableName = "print_jobs", indices = [Index("sessionId")])
data class PrintJobRow(
    @PrimaryKey val id: String,
    val sessionId: String,
    val imagePath: String,
    val copies: Int,
    val state: String,
    val attempts: Int,
    val lastError: String?,
    val reprintOf: String?,
    val reason: String?,
    val operatorId: String?,
    val createdAt: Long,
)

/** Sales, voids and refunds in one append-only table (triggers block UPDATE/DELETE). */
@Entity(tableName = "ledger", indices = [Index("saleId"), Index("eventId")])
data class LedgerRow(
    @PrimaryKey val id: String,
    val kind: String,
    val at: Long,
    val byStaffId: String,
    val eventId: String?,
    val packageId: String?,
    val saleId: String?,
    val amount: Long?,
    val path: String?,
    val receivedCashBy: String?,
    val tendered: Long?,
    val qrReference: String?,
    val reason: String?,
)

/** Append-only like the ledger. */
@Entity(tableName = "audit", indices = [Index("entityId")])
data class AuditRow(
    @PrimaryKey val id: String,
    val at: Long,
    val byStaffId: String,
    val entity: String,
    val entityId: String,
    val field: String,
    val oldValue: String,
    val newValue: String,
)

// ---- mapping ----

private fun Instant.ms() = toEpochMilli()
private fun Long.instant() = Instant.ofEpochMilli(this)

fun Staff.row() = StaffRow(id, name, role.name)
fun StaffRow.domain() = Staff(id, name, Role.valueOf(role))

fun Event.row() = EventRow(id, name, type.name, status.name, venue, startDate?.toString(), endDate?.toString(),
    layoutIds.joinToString("\n"), idleMedia, welcomeText, receiptFooter, hidden)

fun Event.packageRows() = packages.mapIndexed { i, p ->
    PackageRow(p.id, id, i, p.name, p.price.value, p.copies, p.retakes)
}

fun EventRow.domain(packages: List<PackageRow>) = Event(
    id = id, name = name, type = EventType.valueOf(type), status = EventStatus.valueOf(status), venue = venue,
    startDate = startDate?.let(LocalDate::parse), endDate = endDate?.let(LocalDate::parse),
    packages = packages.sortedBy { it.position }.map { SessionPackage(it.id, it.name, Centavos(it.price), it.copies, it.retakes) },
    layoutIds = layoutIds.split("\n").filter { it.isNotEmpty() }, idleMedia = idleMedia,
    welcomeText = welcomeText, receiptFooter = receiptFooter, hidden = hidden,
)

fun Session.row() = SessionRow(id, eventId, deviceId, pkg.id, pkg.name, pkg.price.value, pkg.copies, pkg.retakes,
    layoutId, layoutVersion, saleId, state.name, retakesUsed, endReason?.name, startedAt.ms(), endedAt?.ms())

fun SessionRow.domain() = Session(
    id = id, eventId = eventId, deviceId = deviceId,
    pkg = SessionPackage(pkgId, pkgName, Centavos(pkgPrice), pkgCopies, pkgRetakes),
    layoutId = layoutId, layoutVersion = layoutVersion, saleId = saleId, state = SessionState.valueOf(state),
    retakesUsed = retakesUsed, endReason = endReason?.let(EndReason::valueOf),
    startedAt = startedAt.instant(), endedAt = endedAt?.instant(),
)

fun PrintJob.row() = PrintJobRow(id, sessionId, imagePath, copies, state.name, attempts, lastError, reprintOf,
    reason, operatorId, createdAt.ms())

fun PrintJobRow.domain() = PrintJob(id, sessionId, imagePath, copies, PrintState.valueOf(state), attempts, lastError,
    reprintOf, reason, operatorId, createdAt.instant())

fun LedgerEntry.row() = when (this) {
    is LedgerEntry.Sale -> LedgerRow(id, "SALE", at.ms(), byStaffId, eventId, packageId, null, amount.value,
        path.name, receivedCashBy, tendered?.value, qrReference, null)
    is LedgerEntry.Void -> LedgerRow(id, "VOID", at.ms(), byStaffId, null, null, saleId, null, null, null, null, null, reason)
    is LedgerEntry.Refund -> LedgerRow(id, "REFUND", at.ms(), byStaffId, null, null, saleId, amount.value,
        null, null, null, null, reason)
}

fun LedgerRow.domain(): LedgerEntry = when (kind) {
    "SALE" -> LedgerEntry.Sale(id, at.instant(), byStaffId, eventId!!, packageId!!, Centavos(amount!!),
        PaymentPath.valueOf(path!!), receivedCashBy, tendered?.let(::Centavos), qrReference)
    "VOID" -> LedgerEntry.Void(id, at.instant(), byStaffId, saleId!!, reason!!)
    "REFUND" -> LedgerEntry.Refund(id, at.instant(), byStaffId, saleId!!, Centavos(amount!!), reason!!)
    else -> error("Unknown ledger entry kind: $kind")
}

fun AuditEntry.row() = AuditRow(id, at.ms(), byStaffId, entity, entityId, field, oldValue, newValue)
fun AuditRow.domain() = AuditEntry(id, at.instant(), byStaffId, entity, entityId, field, oldValue, newValue)
