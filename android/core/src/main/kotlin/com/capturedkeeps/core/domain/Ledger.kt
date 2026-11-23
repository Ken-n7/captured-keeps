package com.capturedkeeps.core.domain

import java.time.Instant

enum class Role { OWNER, ATTENDANT }

data class Staff(val id: String = newId(), val name: String, val role: Role) {
    init {
        rule(name.isNotBlank()) { "Staff name is required" }
    }
}

/** Every paid session ends up as a QR payment into the business wallet (PROJECT_NOTES §2). */
enum class PaymentPath { CUSTOMER_QR, CASH_VIA_STAFF }

/** Append-only money records. Nothing is edited or deleted; corrections are new entries. */
sealed interface LedgerEntry {
    val id: String
    val at: Instant
    val byStaffId: String

    data class Sale(
        override val id: String = newId(),
        override val at: Instant,
        /** Staff who saw the payment arrive and confirmed with their PIN. */
        override val byStaffId: String,
        val eventId: String,
        val packageId: String,
        val amount: Centavos,
        val path: PaymentPath,
        /** Cash path only: who holds the customer's cash. */
        val receivedCashBy: String? = null,
        val tendered: Centavos? = null,
        val qrReference: String? = null,
    ) : LedgerEntry {
        val change get() = tendered?.minus(amount)
    }

    data class Void(
        override val id: String = newId(),
        override val at: Instant,
        override val byStaffId: String,
        val saleId: String,
        val reason: String,
    ) : LedgerEntry

    data class Refund(
        override val id: String = newId(),
        override val at: Instant,
        override val byStaffId: String,
        val saleId: String,
        val amount: Centavos,
        val reason: String,
    ) : LedgerEntry
}

private val QR_REFERENCE = Regex("[A-Za-z0-9-]{4,40}")
private val SESSION_PAST_START = setOf(SessionState.CAPTURING, SessionState.REVIEWING, SessionState.PRINTING, SessionState.COMPLETED)

class Ledger(val entries: List<LedgerEntry> = emptyList()) {
    operator fun plus(entry: LedgerEntry) = Ledger(entries + entry)

    val sales get() = entries.filterIsInstance<LedgerEntry.Sale>()
    private fun sale(id: String) = sales.find { it.id == id } ?: throw RuleBroken("Payment not found")
    fun isVoided(saleId: String) = entries.any { it is LedgerEntry.Void && it.saleId == saleId }
    fun refunded(saleId: String) = entries.filterIsInstance<LedgerEntry.Refund>()
        .filter { it.saleId == saleId }.fold(Centavos.ZERO) { sum, r -> sum + r.amount }

    /** Staff confirms a payment they've seen arrive in the wallet. Returns the entry to append. */
    fun sale(
        event: Event,
        packageId: String,
        path: PaymentPath,
        confirmedBy: Staff,
        at: Instant,
        tendered: Centavos? = null,
        qrReference: String? = null,
    ): LedgerEntry.Sale {
        rule(event.type == EventType.PAID) { "This is a free event — no payment needed" }
        rule(event.status == EventStatus.LIVE) { "This event isn't live" }
        val pkg = event.packages.find { it.id == packageId }
        rule(pkg != null && pkg.price > Centavos.ZERO) { "Choose a paid package" }
        val price = pkg!!.price
        when (path) {
            PaymentPath.CASH_VIA_STAFF -> {
                rule(tendered != null) { "Enter the cash received" }
                rule(tendered!! >= price) { "Cash received ($tendered) is less than the price ($price)" }
            }
            PaymentPath.CUSTOMER_QR -> rule(tendered == null) { "Cash received only applies to cash payments" }
        }
        val ref = qrReference?.trim()?.takeIf { it.isNotEmpty() }
        rule(ref == null || QR_REFERENCE.matches(ref)) { "Reference number should be 4–40 letters or digits" }
        return LedgerEntry.Sale(
            at = at, byStaffId = confirmedBy.id, eventId = event.id, packageId = pkg.id, amount = price,
            path = path, receivedCashBy = confirmedBy.id.takeIf { path == PaymentPath.CASH_VIA_STAFF },
            tendered = tendered, qrReference = ref,
        )
    }

    /** A sale can unlock a session only if no other session has used it (cancelled/failed ones free it again). */
    fun authorize(saleId: String, sessions: List<Session>): LedgerEntry.Sale {
        val sale = sale(saleId)
        rule(!isVoided(saleId)) { "This payment was voided" }
        rule(refunded(saleId) == Centavos.ZERO) { "This payment was refunded" }
        rule(sessions.none { it.saleId == saleId && it.state != SessionState.CANCELLED && it.state != SessionState.FAILED }) {
            "This payment was already used for a session"
        }
        return sale
    }

    /** Cancel a payment before its session has started capturing. */
    fun void(saleId: String, by: Staff, reason: String, sessions: List<Session>, at: Instant): LedgerEntry.Void {
        sale(saleId)
        rule(!isVoided(saleId)) { "Already voided" }
        rule(refunded(saleId) == Centavos.ZERO) { "Already refunded — can't void" }
        rule(reason.isNotBlank()) { "Enter a reason" }
        rule(sessions.none { it.saleId == saleId && it.state in SESSION_PAST_START }) {
            "The session already started — use a refund instead"
        }
        return LedgerEntry.Void(at = at, byStaffId = by.id, saleId = saleId, reason = reason.trim())
    }

    /** Owner only. Partial refunds allowed up to what's left. */
    fun refund(saleId: String, amount: Centavos, by: Staff, reason: String, at: Instant): LedgerEntry.Refund {
        val sale = sale(saleId)
        rule(by.role == Role.OWNER) { "Only the owner can refund" }
        rule(!isVoided(saleId)) { "This payment was voided" }
        rule(amount > Centavos.ZERO) { "Refund amount must be more than zero" }
        val left = sale.amount - refunded(saleId)
        rule(amount <= left) { "Refund ($amount) is more than what's left to refund ($left)" }
        rule(reason.isNotBlank()) { "Enter a reason" }
        return LedgerEntry.Refund(at = at, byStaffId = by.id, saleId = saleId, amount = amount, reason = reason.trim())
    }

    /** Sales for one event plus the voids/refunds that point at them. */
    fun forEvent(eventId: String): Ledger {
        val ids = sales.filter { it.eventId == eventId }.map { it.id }.toSet()
        return Ledger(entries.filter {
            when (it) {
                is LedgerEntry.Sale -> it.id in ids
                is LedgerEntry.Void -> it.saleId in ids
                is LedgerEntry.Refund -> it.saleId in ids
            }
        })
    }

    /** Spreadsheet export, one row per entry. [names] maps ids (staff, packages) to readable names. */
    fun toCsv(names: Map<String, String>, zone: java.time.ZoneId): String {
        val time = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        fun money(c: Centavos?) = c?.let { "%d.%02d".format(it.value / 100, it.value % 100) } ?: ""
        val rows = listOf(listOf("time", "type", "package", "amount", "path", "cash received", "change",
            "reference", "staff", "cash held by", "reason", "entry id", "sale id")) +
            entries.map { e ->
                when (e) {
                    is LedgerEntry.Sale -> listOf(time.format(e.at), "sale", names[e.packageId] ?: e.packageId, money(e.amount),
                        e.path.name.lowercase(), money(e.tendered), money(e.change), e.qrReference ?: "",
                        names[e.byStaffId] ?: e.byStaffId, e.receivedCashBy?.let { names[it] ?: it } ?: "", "", e.id, e.id)
                    is LedgerEntry.Void -> listOf(time.format(e.at), "void", "", "", "", "", "", "",
                        names[e.byStaffId] ?: e.byStaffId, "", e.reason, e.id, e.saleId)
                    is LedgerEntry.Refund -> listOf(time.format(e.at), "refund", "", "-" + money(e.amount), "", "", "", "",
                        names[e.byStaffId] ?: e.byStaffId, "", e.reason, e.id, e.saleId)
                }
            }
        return rows.joinToString("\r\n", postfix = "\r\n") { r -> r.joinToString(",") { csvCell(it) } }
    }

    fun summary(): LedgerSummary {
        val live = sales.filterNot { isVoided(it.id) }
        val refunds = entries.filterIsInstance<LedgerEntry.Refund>().fold(Centavos.ZERO) { s, r -> s + r.amount }
        fun sum(list: List<LedgerEntry.Sale>) = list.fold(Centavos.ZERO) { s, x -> s + x.amount }
        return LedgerSummary(
            gross = sum(live),
            refunds = refunds,
            net = sum(live) - refunds,
            byPath = PaymentPath.entries.associateWith { p -> sum(live.filter { it.path == p }) },
            cashHeldBy = live.filter { it.path == PaymentPath.CASH_VIA_STAFF }
                .groupBy { it.receivedCashBy!! }.mapValues { (_, s) -> sum(s) },
            missingReference = live.filter { it.qrReference == null },
        )
    }
}

data class LedgerSummary(
    val gross: Centavos,
    val refunds: Centavos,
    val net: Centavos,
    val byPath: Map<PaymentPath, Centavos>,
    /** Customer cash each staff member is holding after paying the QR from their own wallet. */
    val cashHeldBy: Map<String, Centavos>,
    /** Should be matched against the wallet history by hand. */
    val missingReference: List<LedgerEntry.Sale>,
)

/** RFC 4180 quoting; a leading =+-@ is prefixed with ' so spreadsheets don't run it as a formula. */
internal fun csvCell(v: String): String {
    val safe = if (v.isNotEmpty() && v[0] in "=+-@" && v.toDoubleOrNull() == null) "'$v" else v
    return if (safe.any { it in ",\"\r\n" }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
}
