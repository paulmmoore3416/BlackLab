package com.blacklab.app.link

import com.blacklab.app.core.JsonStore
import com.blacklab.app.facility.Facility
import com.blacklab.app.vehicle.Garage
import com.blacklab.app.vehicle.Nhtsa
import com.blacklab.app.vehicle.VehicleLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * What the desktop link watches besides Room. File-backed [JsonStore]s don't go through Room's
 * invalidation tracker, so without this the desktop's Facility and Garage views stayed stale until
 * a manual refresh. LinkWatchTest fails when a new JsonStore is added but not listed here.
 */
object LinkWatch {
    fun stores(): List<JsonStore<*>> = listOf(Facility.store, Garage.store, VehicleLink.store, Nhtsa.store)

    /**
     * Calls [onChange] when any of [sources] changes; each source's current value is skipped. Bursts (live car readings
     * arrive every second) are throttled to one call per [minGapMs], and the last change of a burst always gets a call.
     */
    fun launch(scope: CoroutineScope, sources: List<Flow<*>>, minGapMs: Long = 5_000, onStart: () -> Unit = {}, onChange: () -> Unit): Job =
        scope.launch {
            sources.map { it.drop(1) }.merge().onStart { onStart() }.conflate().collect { onChange(); delay(minGapMs) }
        }
}

/**
 * Which snapshot sections each source feeds, so a change refreshes only those on the desktop
 * (`/api/changes` → `changed`, then `/api/snapshot?sections=…`). SectionsTest fails when a Room
 * table or JsonStore is added without saying what it feeds.
 */
object Sections {
    /** Sent with every snapshot, partial or not: the phone's status and the Health checks, which depend on everything. */
    val ALWAYS = setOf("info", "generatedAt", "health")
    /** Not a snapshot key: tells the desktop to reload /api/settings. */
    const val SETTINGS_VALUES = "settings"
    val PSEUDO = setOf(SETTINGS_VALUES)
    val SETTINGS = setOf(SETTINGS_VALUES, "info")
    val ASSISTANT = setOf("assistant")

    val TABLES: Map<String, Set<String>> = mapOf(
        "documents" to setOf("documents"),
        "journal" to setOf("journal"),
        "task_lists" to setOf("lists", "tasks"),
        "tasks" to setOf("tasks", "timeline"),
        "subtasks" to setOf("tasks"),
        "event_calendars" to setOf("events"),
        "events" to setOf("events", "timeline"),
        "courses" to setOf("courses", "assignments", "decks"),
        "assignments" to setOf("assignments"),
        "certifications" to setOf("certifications"),
        "ceu_entries" to setOf("certifications"),
        "decks" to setOf("decks"),
        "flashcards" to setOf("decks"),
        "recordings" to setOf("recordings", "timeline"),
        "notebooks" to setOf("brainstorm"),
        "nb_sections" to setOf("brainstorm"),
        "nb_pages" to setOf("brainstorm", "timeline"),
        "notes" to setOf("brainstorm", "timeline"),
        "note_labels" to setOf("brainstorm"),
    )

    /** Tables the desktop doesn't show (schedules, grading setup, study timer, comments and share links). */
    val IGNORED_TABLES = setOf("terms", "class_meetings", "grade_categories", "study_sessions", "bs_comments", "bs_shares")

    fun forTables(tables: Set<String>): Set<String> = tables.flatMap { TABLES[it].orEmpty() }.toSet()

    fun forStore(name: String): Set<String> = when (name) {
        "Facility" -> setOf("facility", "timeline")
        "Garage", "Drive log", "Recalls" -> setOf("vehicle", "timeline")
        else -> emptySet()
    }

    /** `?sections=a,b` → the set, or null for "everything". */
    fun parse(q: String?): Set<String>? = q?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }
}

/** The link revision at which each section last changed. Revisions only grow while the app process lives. */
class SectionRevs(private val baseline: Long) {
    private val revs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun mark(sections: Set<String>, rev: Long) = sections.forEach { revs.merge(it, rev, ::maxOf) }

    /** Sections changed after [since]; null when [since] predates this process (the client must reload everything). */
    fun changedSince(since: Long): Set<String>? =
        if (since < baseline) null else revs.filterValues { it > since }.keys.toSet()
}
