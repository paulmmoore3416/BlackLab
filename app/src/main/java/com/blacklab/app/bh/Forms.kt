package com.blacklab.app.bh

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A small form engine for the Behavioral Health tools. Every structured note (peer session, incident,
 * shift handoff, debrief, check-in, supervision, group session, referral…) is described once as a
 * [Spec] — sections of dropdowns, multi-select chips, sliders, switches and text — and stored as a
 * [Rec]. The same spec drives the editor screen and the written note, so adding an option to a
 * dropdown is a one-line change.
 */
object Forms {

    enum class Type { TEXT, LONG, NUMBER, DROPDOWN, MULTI, SLIDER, SWITCH, TIME }

    data class Field(
        val key: String,
        val label: String,
        val type: Type,
        val options: List<String> = emptyList(),
        val hint: String = "",
        val min: Int = 0,
        val max: Int = 10,
        /** Show only when another field has one of these values (for MULTI: contains any of them). */
        val showIf: Pair<String, Set<String>>? = null,
        val default: String = "",
        /** Leave the field out of the written note (still saved). */
        val internal: Boolean = false,
        /** Words before the value in the written note, e.g. "Participant appeared". Defaults to the label. */
        val phrase: String? = null,
    )

    data class Section(val title: String, val fields: List<Field>, val help: String = "")
    data class Spec(val id: String, val title: String, val sections: List<Section>, val help: String = "") {
        val fields: List<Field> get() = sections.flatMap { it.fields }
        fun field(key: String) = fields.firstOrNull { it.key == key }
    }

    /** One saved form. [ref] links it to a participant, census entry, group plan, shift… */
    data class Rec(
        val id: String = UUID.randomUUID().toString(),
        val kind: String,
        val created: Long = System.currentTimeMillis(),
        val updated: Long = created,
        val ref: String = "",
        val values: Map<String, String> = emptyMap(),
        val multi: Map<String, List<String>> = emptyMap(),
    ) {
        fun v(key: String): String = values[key].orEmpty()
        fun m(key: String): List<String> = multi[key].orEmpty()
        fun int(key: String): Int? = values[key]?.toIntOrNull()
        fun on(key: String): Boolean = values[key] == "true"
        fun with(key: String, value: String) = copy(values = values + (key to value), updated = System.currentTimeMillis())
        fun withMulti(key: String, value: List<String>) = copy(multi = multi + (key to value), updated = System.currentTimeMillis())
    }

    fun blank(spec: Spec, ref: String = ""): Rec = Rec(kind = spec.id, ref = ref,
        values = spec.fields.filter { it.default.isNotEmpty() && it.type != Type.MULTI }.associate { it.key to it.default },
        multi = spec.fields.filter { it.default.isNotEmpty() && it.type == Type.MULTI }.associate { it.key to it.default.split('|') })

    fun visible(f: Field, r: Rec): Boolean {
        val (key, vals) = f.showIf ?: return true
        val cur = r.values[key]
        return (cur != null && cur in vals) || r.m(key).any { it in vals }
    }

    /** Human value of a field, or null when empty / hidden. */
    fun display(f: Field, r: Rec): String? {
        if (!visible(f, r)) return null
        return when (f.type) {
            Type.MULTI -> r.m(f.key).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
            Type.SWITCH -> when (r.values[f.key]) { "true" -> "Yes"; "false" -> "No"; else -> null }
            Type.SLIDER -> r.values[f.key]?.takeIf { it.isNotBlank() }?.let { "$it/${f.max}" }
            else -> r.values[f.key]?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /** A plain labeled write-up: section headings, then "Label: value" lines. Empty fields are skipped. */
    fun render(spec: Spec, r: Rec, title: String = spec.title): String = buildString {
        appendLine(title)
        spec.sections.forEach { s ->
            val lines = s.fields.filter { !it.internal }.mapNotNull { f -> display(f, r)?.let { v -> if (f.type == Type.LONG) "${f.label}:\n$v" else "${f.label}: $v" } }
            if (lines.isNotEmpty()) {
                appendLine()
                appendLine(s.title.uppercase())
                lines.forEach { appendLine(it) }
            }
        }
    }.trim()

    /** How many visible fields have something in them — for the "x of y answered" progress line. */
    fun progress(spec: Spec, r: Rec): Pair<Int, Int> {
        val vis = spec.fields.filter { visible(it, r) && !it.internal }
        return vis.count { display(it, r) != null } to vis.size
    }

    // ---------------------------------------------------------------- JSON

    fun toJson(r: Rec): JSONObject = JSONObject().put("id", r.id).put("kind", r.kind).put("created", r.created).put("updated", r.updated).put("ref", r.ref)
        .put("values", JSONObject().apply { r.values.forEach { (k, v) -> put(k, v) } })
        .put("multi", JSONObject().apply { r.multi.forEach { (k, v) -> put(k, JSONArray(v)) } })

    fun fromJson(o: JSONObject): Rec {
        val vals = o.optJSONObject("values")?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } }.orEmpty()
        val multi = o.optJSONObject("multi")?.let { j -> j.keys().asSequence().associateWith { k -> j.optJSONArray(k).strings() } }.orEmpty()
        return Rec(o.getString("id"), o.getString("kind"), o.optLong("created"), o.optLong("updated", o.optLong("created")), o.optString("ref"), vals, multi)
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun <T> List<T>.toJsonArray(f: (T) -> Any): JSONArray = JSONArray().also { a -> forEach { a.put(f(it)) } }
internal fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
