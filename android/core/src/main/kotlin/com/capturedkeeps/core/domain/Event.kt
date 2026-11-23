package com.capturedkeeps.core.domain

import com.capturedkeeps.core.print.MAX_EVENT_NAME
import java.time.Instant
import java.time.LocalDate

enum class EventStatus(val label: String) {
    DRAFT("draft"), LIVE("live"), PAUSED("paused"), COMPLETED("completed"), CANCELLED("cancelled");

    val isFinished get() = this == COMPLETED || this == CANCELLED
}

enum class EventType { FREE, PAID }

/** What a customer gets for one session. Copied into each session when it starts. */
data class SessionPackage(
    val id: String = newId(),
    val name: String,
    val price: Centavos,
    val copies: Int = 1,
    val retakes: Int = 2,
) {
    init {
        rule(name.isNotBlank()) { "Package name is required" }
        rule(copies in 1..5) { "Copies must be 1 to 5" }
        rule(retakes in 0..5) { "Retakes must be 0 to 5" }
        rule(price <= Centavos.MAX) { "Price is too large — check for typos" }
    }
}

data class Event(
    val id: String = newId(),
    val name: String,
    val type: EventType,
    val status: EventStatus = EventStatus.DRAFT,
    val venue: String = "",
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val packages: List<SessionPackage> = emptyList(),
    val layoutIds: List<String> = emptyList(),
    val idleMedia: String? = null,
    val welcomeText: String = "Tap to start",
    val receiptFooter: String = "",
    /** "Archived" = finished and hidden from the main list. */
    val hidden: Boolean = false,
) {
    init {
        rule(name.isNotBlank()) { "Event name is required" }
        rule(name.length <= MAX_EVENT_NAME) { "Event name can be at most $MAX_EVENT_NAME characters" }
        rule(startDate == null || endDate == null || !endDate.isBefore(startDate)) { "End date is before the start date" }
        rule(!hidden || status.isFinished) { "Only completed or cancelled events can be hidden" }
        rule(packages.distinctBy { it.id }.size == packages.size) { "Duplicate package" }
        rule(type == EventType.PAID || packages.all { it.price == Centavos.ZERO }) { "Free events can't have paid packages" }
    }

    /** "Scheduled" is a label shown for drafts with a future start date, not a separate status. */
    fun isScheduled(today: LocalDate) = status == EventStatus.DRAFT && startDate?.isAfter(today) == true

    fun goLiveCheck(mediaPlayable: Boolean): GoLiveCheck {
        val blockers = buildList {
            if (packages.isEmpty()) add("Add at least one package")
            if (type == EventType.PAID && packages.none { it.price > Centavos.ZERO }) add("Paid events need a package with a price")
            if (layoutIds.isEmpty()) add("Enable at least one strip layout")
        }
        val warnings = buildList {
            if (idleMedia == null || !mediaPlayable) add("Idle video missing or not playable — the branded TAP TO START screen will show instead")
        }
        return GoLiveCheck(blockers, warnings)
    }
}

data class GoLiveCheck(val blockers: List<String>, val warnings: List<String>) {
    val ok get() = blockers.isEmpty()
}

private val ALLOWED_MOVES = mapOf(
    EventStatus.DRAFT to setOf(EventStatus.LIVE, EventStatus.CANCELLED),
    EventStatus.LIVE to setOf(EventStatus.PAUSED, EventStatus.COMPLETED),
    EventStatus.PAUSED to setOf(EventStatus.LIVE, EventStatus.COMPLETED),
)

/** Draft → Live ⇄ Paused → Completed; Draft → Cancelled. Only one live event per booth. */
fun Event.moveTo(to: EventStatus, anotherEventLive: Boolean, mediaPlayable: Boolean): Event {
    rule(to in ALLOWED_MOVES[status].orEmpty()) { "A ${status.label} event can't be changed to ${to.label}" }
    if (to == EventStatus.LIVE) {
        rule(!anotherEventLive) { "Another event is already live on this booth. Pause or finish it first." }
        val check = goLiveCheck(mediaPlayable)
        rule(check.ok) { check.blockers.joinToString("\n") }
    }
    return copy(status = to)
}

fun Event.hide(): Event {
    rule(status.isFinished) { "Only completed or cancelled events can be hidden" }
    return copy(hidden = true)
}

/** One audit-log line: who changed what, from → to. */
data class AuditEntry(
    val id: String = newId(),
    val at: Instant,
    val byStaffId: String,
    val entity: String,
    val entityId: String,
    val field: String,
    val oldValue: String,
    val newValue: String,
)

/**
 * Saves an edit to an event. Status changes go through [moveTo]; finished events are read-only.
 * Price/package/layout edits only reach new sessions — running sessions keep their snapshot.
 */
fun Event.edit(updated: Event, by: Staff, at: Instant): Pair<Event, List<AuditEntry>> {
    rule(updated.id == id) { "Wrong event" }
    rule(!status.isFinished) { "This event is ${status.label} and can't be edited" }
    rule(updated.status == status && updated.hidden == hidden) { "Use start/pause/finish to change the event status" }
    val fields = listOf<Pair<String, (Event) -> Any?>>(
        "name" to { it.name }, "type" to { it.type }, "venue" to { it.venue },
        "startDate" to { it.startDate }, "endDate" to { it.endDate }, "packages" to { it.packages },
        "layouts" to { it.layoutIds }, "idleMedia" to { it.idleMedia },
        "welcomeText" to { it.welcomeText }, "receiptFooter" to { it.receiptFooter },
    )
    val log = fields.mapNotNull { (field, get) ->
        val old = get(this)
        val new = get(updated)
        if (old == new) null
        else AuditEntry(at = at, byStaffId = by.id, entity = "event", entityId = id, field = field,
            oldValue = old.toString(), newValue = new.toString())
    }
    return updated to log
}
