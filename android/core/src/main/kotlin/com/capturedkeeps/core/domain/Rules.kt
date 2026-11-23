package com.capturedkeeps.core.domain

import java.math.BigDecimal
import java.util.UUID

/** A business rule was broken. The message is written to be shown to staff as-is. */
class RuleBroken(message: String) : IllegalStateException(message)

internal inline fun rule(ok: Boolean, message: () -> String) {
    if (!ok) throw RuleBroken(message())
}

/** Device-generated unique id: records can be created offline and synced later without clashes. */
fun newId(): String = UUID.randomUUID().toString()

/** Money in integer centavos (₱150.00 = 15000). Never floating point. */
@JvmInline
value class Centavos(val value: Long) : Comparable<Centavos> {
    init {
        rule(value >= 0) { "Amount can't be negative" }
    }

    operator fun plus(other: Centavos) = Centavos(Math.addExact(value, other.value))
    operator fun minus(other: Centavos) = Centavos(value - other.value)
    override fun compareTo(other: Centavos) = value.compareTo(other.value)
    override fun toString() = "₱%,d.%02d".format(value / 100, value % 100)

    companion object {
        val ZERO = Centavos(0)
        val MAX = Centavos(100_000_000) // ₱1,000,000.00 — catches typos like an extra zero or two

        fun pesos(pesos: Long) = Centavos(Math.multiplyExact(pesos, 100L))

        /** Parses staff input such as "150", "150.50", "₱1,500". */
        fun parse(input: String): Centavos {
            val amount = input.trim().removePrefix("₱").replace(",", "").trim().toBigDecimalOrNull()
            rule(amount != null) { "Enter an amount like 150 or 150.50" }
            amount!!
            rule(amount.signum() >= 0) { "Amount can't be negative" }
            rule(amount.stripTrailingZeros().scale() <= 2) { "Amount can have at most 2 decimal places" }
            rule(amount <= BigDecimal(MAX.value).movePointLeft(2)) { "Amount is too large — check for typos" }
            return Centavos(amount.movePointRight(2).longValueExact())
        }
    }
}

enum class SyncState {
    PENDING, SYNCED, ERROR, LOCAL_ONLY;

    /** Never delete a record the cloud doesn't have yet. */
    val canDeleteLocally get() = this == SYNCED
}

enum class StorageStatus { OK, WARN, BLOCK }

/** Warn the owner early; block new sessions before a photo or record could fail to save. */
fun storageStatus(
    freeBytes: Long,
    warnBelow: Long = 2L * 1024 * 1024 * 1024,
    blockBelow: Long = 500L * 1024 * 1024,
): StorageStatus = when {
    freeBytes < blockBelow -> StorageStatus.BLOCK
    freeBytes < warnBelow -> StorageStatus.WARN
    else -> StorageStatus.OK
}
