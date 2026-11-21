package com.blacklab.app.files

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Spreadsheet formula engine: + - * / ^ & and comparisons, cell refs and ranges, references to other sheets
 * (`Assumptions!B5`, `'Rate Build'!C4:C10`) and workbook names, and the functions business workbooks use most —
 * SUM/AVERAGE/MIN/MAX/COUNT(A/BLANK)/MEDIAN/PRODUCT, ROUND/ROUNDUP/ROUNDDOWN/INT/MOD/ABS/SQRT/POWER/EXP/LN/LOG10/
 * CEILING/FLOOR, IF/IFS/IFERROR/IFNA/AND/OR/NOT, SUMPRODUCT, SUMIF(S)/COUNTIF(S)/AVERAGEIF(S)/MAXIFS/MINIFS,
 * VLOOKUP/HLOOKUP/INDEX/MATCH/CHOOSE, NPV/PMT/FV/PV, text (CONCAT/LEN/LEFT/RIGHT/MID/UPPER/LOWER/TRIM/TEXT basics)
 * and dates (TODAY/DATE/YEAR/MONTH/DAY/EDATE/EOMONTH).
 *
 * IF and IFERROR are lazy, like Excel's: `IF(B2=0, 0, A2/B2)` never divides by zero. Anything it doesn't know
 * returns `#NAME?` — callers then show the value Excel last calculated instead (see [isUnsupported]).
 */
class Evaluator(
    private val sheet: Sheet,
    /** Another sheet's evaluator by name (case-insensitive), for cross-sheet references. */
    private val other: ((String) -> Evaluator?)? = null,
    /** Workbook names (upper-case) → their reference text, e.g. "Assumptions!$B$5". */
    private val names: Map<String, String> = emptyMap(),
) {
    private val cache = HashMap<Long, Any>()
    private val visiting = HashSet<Long>()

    class FormulaError(val code: String) : Exception(code)
    /** An error value inside an argument list (so IFERROR / IF can decide what to do with it). */
    private class Err(val code: String)
    /** A range argument: its values row by row, including blanks (""), with its shape. */
    private class Rng(val values: List<Any>, val rows: Int, val cols: Int) { operator fun get(r: Int, c: Int) = values[r * cols + c] }

    companion object {
        private val UNSUPPORTED = setOf("#NAME?", "#ERROR!")
        /** True when [v] means "this engine can't compute it" rather than a real spreadsheet error. */
        fun isUnsupported(v: Any?): Boolean = v is String && v in UNSUPPORTED
        private val EPOCH = java.time.LocalDate.of(1899, 12, 30)
        fun serial(d: java.time.LocalDate): Double = java.time.temporal.ChronoUnit.DAYS.between(EPOCH, d).toDouble()
        fun dateOf(serial: Double): java.time.LocalDate = EPOCH.plusDays(floor(serial).toLong())
    }

    fun display(r: Int, c: Int): String = when (val v = value(r, c)) { is Double -> Sheets.fmt(v); is Boolean -> if (v) "TRUE" else "FALSE"; else -> v.toString() }

    fun value(r: Int, c: Int): Any {
        val key = r.toLong() shl 20 or c.toLong()
        cache[key]?.let { return it }
        val raw = sheet[r, c]
        if (!raw.startsWith("=") || raw.length == 1) return (raw.toDoubleOrNull() ?: when { raw.equals("TRUE", true) -> true; raw.equals("FALSE", true) -> false; else -> raw }).also { cache[key] = it }
        if (!visiting.add(key)) return "#CIRC!"
        val v: Any = try { Expr(raw.substring(1)).parseAll().let { if (it is Rng) it.values.firstOrNull() ?: "" else it } } catch (e: FormulaError) { e.code } catch (e: StackOverflowError) { "#CIRC!" } catch (e: Exception) { "#ERROR!" }
        visiting.remove(key)
        cache[key] = v
        return v
    }

    private fun num(v: Any): Double = when (v) {
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is Err -> throw FormulaError(v.code)
        is Rng -> num(v.values.firstOrNull() ?: 0.0)
        is String -> if (v.isBlank()) 0.0 else v.trim().removeSuffix("%").toDoubleOrNull()?.let { if (v.trim().endsWith("%")) it / 100 else it }
            ?: if (v.startsWith("#")) throw FormulaError(v) else throw FormulaError("#VALUE!")
        else -> 0.0
    }
    private fun str(v: Any): String = when (v) { is Double -> Sheets.fmt(v); is Boolean -> if (v) "TRUE" else "FALSE"; is Err -> throw FormulaError(v.code); is Rng -> str(v.values.firstOrNull() ?: ""); else -> v.toString() }
    private fun bool(v: Any): Boolean = when (v) { is Boolean -> v; is Double -> v != 0.0; is Err -> throw FormulaError(v.code); is String -> v.equals("TRUE", true) || (v.toDoubleOrNull() ?: 0.0) != 0.0; else -> false }
    private fun isErr(v: Any) = v is Err || (v is String && v.startsWith("#") && v.endsWith("!") || v == "#N/A" || v == "#NAME?")

    private inner class Expr(val s: String) {
        var i = 0
        fun parseAll(): Any { val v = compare(); skip(); if (i < s.length) throw FormulaError("#ERROR!"); return v }
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(t: String): Boolean { skip(); return s.startsWith(t, i, ignoreCase = true) }
        fun eat(t: String): Boolean { if (peek(t)) { i += t.length; return true }; return false }

        fun compare(): Any {
            var l = concat()
            while (true) {
                val op = listOf("<=", ">=", "<>", "=", "<", ">").firstOrNull { peek(it) } ?: return l
                i += op.length
                val r = concat()
                fun test(a: Any, b: Any): Boolean {
                    val cmp = if (a is Double || b is Double || a is Boolean || b is Boolean) num(a).compareTo(num(b)) else str(a).compareTo(str(b), ignoreCase = true)
                    return when (op) { "<=" -> cmp <= 0; ">=" -> cmp >= 0; "<>" -> cmp != 0; "=" -> cmp == 0; "<" -> cmp < 0; else -> cmp > 0 }
                }
                // a range compared with a value gives an array of TRUE/FALSE (used inside INDEX/MATCH/SUMPRODUCT)
                l = when {
                    l is Rng && r !is Rng -> Rng(l.values.map { test(it, r) }, l.rows, l.cols)
                    r is Rng && l !is Rng -> Rng(r.values.map { test(l, it) }, r.rows, r.cols)
                    l is Rng && r is Rng && l.values.size == r.values.size -> Rng(l.values.indices.map { test(l.values[it], r.values[it]) }, l.rows, l.cols)
                    else -> test(scalar(l), scalar(r))
                }
            }
        }
        fun scalar(v: Any): Any = if (v is Rng) v.values.firstOrNull() ?: "" else v
        fun concat(): Any { var l = add(); while (eat("&")) l = str(l) + str(add()); return l }
        fun add(): Any {
            var l = mul()
            while (true) { l = when { eat("+") -> num(l) + num(mul()); eat("-") -> num(l) - num(mul()); else -> return l } }
        }
        fun mul(): Any {
            var l = pow()
            while (true) {
                l = when {
                    eat("*") -> num(l) * num(pow())
                    eat("/") -> { val d = num(pow()); if (d == 0.0) throw FormulaError("#DIV/0!"); num(l) / d }
                    else -> return l
                }
            }
        }
        fun pow(): Any { val b = unary(); return if (eat("^")) num(b).pow(num(pow())) else b }
        fun unary(): Any = when { eat("-") -> -num(unary()); eat("+") -> num(unary()); else -> postfix() }
        fun postfix(): Any { val v = primary(); return if (eat("%")) num(v) / 100 else v }

        fun primary(): Any {
            skip()
            if (i >= s.length) throw FormulaError("#ERROR!")
            val ch = s[i]
            if (ch == '(') { i++; val v = compare(); if (!eat(")")) throw FormulaError("#ERROR!"); return v }
            if (ch == '"') {
                val sb = StringBuilder(); i++
                while (i < s.length) { if (s[i] == '"') { if (i + 1 < s.length && s[i + 1] == '"') { sb.append('"'); i += 2; continue } else break }; sb.append(s[i]); i++ }
                if (i >= s.length) throw FormulaError("#ERROR!"); i++
                return sb.toString()
            }
            if (ch == '#') { val st = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "#/!?")) i++; throw FormulaError(s.substring(st, i)) }
            if (ch.isDigit() || ch == '.') {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && (s[i] == 'E' || s[i] == 'e') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '+' || s[i + 1] == '-')) { i += 2; while (i < s.length && s[i].isDigit()) i++ }
                return s.substring(st, i).toDouble()
            }
            range()?.let { return it }
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            val word = s.substring(st, i).uppercase(Locale.US)
            if (word.isEmpty()) throw FormulaError("#ERROR!")
            if (eat("(")) return call(word.removePrefix("_XLFN.").removePrefix("_XLWS."))
            if (word == "TRUE") return true
            if (word == "FALSE") return false
            names[word]?.let { return Expr(it.removePrefix("=")).parseAll() }
            throw FormulaError("#NAME?")
        }

        /** A cell or range, optionally on another sheet: A1, $B$2:$D$9, Sheet2!C3, 'My sheet'!A1:A5. Restores i when there's none. */
        fun range(): Any? {
            skip()
            val save = i
            var target: Evaluator = this@Evaluator
            if (i < s.length && s[i] == '\'') {
                val sb = StringBuilder(); i++
                while (i < s.length) { if (s[i] == '\'') { if (i + 1 < s.length && s[i + 1] == '\'') { sb.append('\''); i += 2; continue } else break }; sb.append(s[i]); i++ }
                i++
                if (i >= s.length || s[i] != '!') { i = save; return null }
                i++
                target = sheetEval(sb.toString())
            } else {
                var j = i
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.')) j++
                if (j < s.length && s[j] == '!' && j > i) { target = sheetEval(s.substring(i, j)); i = j + 1 }
            }
            val a = cellRef() ?: run { i = save; return null }
            if (i < s.length && s[i] == '(') { i = save; return null } // a function like LOG10(
            if (peek(":")) {
                val back = i; i++ ; skip()
                val b = cellRef() ?: run { i = back; return target.valueOf(a.first, a.second) }
                return target.rangeOf(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
            }
            return target.valueOf(a.first, a.second)
        }

        fun cellRef(): Pair<Int, Int>? {
            val m = Regex("\\$?([A-Za-z]{1,3})\\$?(\\d{1,7})").find(s, i)?.takeIf { it.range.first == i } ?: return null
            val after = m.range.last + 1
            if (after < s.length && (s[after].isLetterOrDigit() || s[after] == '_')) return null
            i = after
            return (m.groupValues[2].toInt() - 1) to Sheets.colIndex(m.groupValues[1].uppercase(Locale.US))
        }

        /** One argument, evaluated; errors become [Err] values so IF/IFERROR can handle them. */
        fun arg(): Any = try { compare() } catch (e: FormulaError) { skipArg(); Err(e.code) }

        /** After an error mid-argument, skip to the next ',' / ';' / ')' at this nesting level. */
        fun skipArg() {
            var depth = 0; var q = false
            while (i < s.length) {
                val c = s[i]
                if (c == '"') q = !q
                if (!q) { if (c == '(') depth++; if (c == ')') { if (depth == 0) return; depth-- }; if ((c == ',' || c == ';') && depth == 0) return }
                i++
            }
        }

        fun args(): List<Any> {
            val out = mutableListOf<Any>()
            if (eat(")")) return out
            do { skip(); out += if (peek(",") || peek(";") || peek(")")) "" else arg() } while (eat(",") || eat(";"))
            if (!eat(")")) throw FormulaError("#ERROR!")
            return out
        }

        fun call(name: String): Any {
            val a = args()
            fun flat(v: Any): List<Any> = if (v is Rng) v.values else listOf(v)
            val all = a.flatMap { flat(it) }
            fun nums(list: List<Any> = all) = list.filter { it is Double || (it is String && it.isNotBlank() && it.toDoubleOrNull() != null) || it is Err }.map { num(it) }
            fun n(k: Int, d: Double? = null) = a.getOrNull(k)?.let { num(scalar(it)) } ?: d ?: throw FormulaError("#VALUE!")
            fun t(k: Int) = a.getOrNull(k)?.let { str(scalar(it)) } ?: throw FormulaError("#VALUE!")
            fun rng(k: Int): Rng = when (val v = a.getOrNull(k)) { is Rng -> v; null -> throw FormulaError("#VALUE!"); else -> Rng(listOf(v), 1, 1) }
            fun round(x: Double, d: Double, mode: Int): Double {
                val p = 10.0.pow(d)
                val y = x * p
                val r = when (mode) { 0 -> Math.round(abs(y)).toDouble() * kotlin.math.sign(y); 1 -> kotlin.math.ceil(abs(y) - 1e-9) * kotlin.math.sign(y); else -> floor(abs(y) + 1e-9) * kotlin.math.sign(y) }
                return r / p
            }
            return when (name) {
                "SUM" -> nums().sum()
                "AVERAGE", "AVG" -> nums().takeIf { it.isNotEmpty() }?.average() ?: throw FormulaError("#DIV/0!")
                "MIN" -> nums().minOrNull() ?: 0.0
                "MAX" -> nums().maxOrNull() ?: 0.0
                "COUNT" -> all.count { it is Double || (it is String && it.isNotBlank() && it.toDoubleOrNull() != null) }.toDouble()
                "COUNTA" -> all.count { !(it is String && it.isEmpty()) }.toDouble()
                "COUNTBLANK" -> all.count { it is String && it.isEmpty() }.toDouble()
                "MEDIAN" -> nums().sorted().let { l -> if (l.isEmpty()) 0.0 else if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2 }
                "PRODUCT" -> nums().fold(1.0) { x, y -> x * y }
                "ROUND" -> round(n(0), n(1, 0.0), 0)
                "ROUNDUP" -> round(n(0), n(1, 0.0), 1)
                "ROUNDDOWN", "TRUNC" -> round(n(0), n(1, 0.0), 2)
                "INT" -> floor(n(0))
                "MOD" -> { val d = n(1); if (d == 0.0) throw FormulaError("#DIV/0!"); val x = n(0); x - d * floor(x / d) }
                "ABS" -> abs(n(0))
                "SIGN" -> kotlin.math.sign(n(0))
                "SQRT" -> n(0).let { if (it < 0) throw FormulaError("#NUM!") else kotlin.math.sqrt(it) }
                "POWER" -> n(0).pow(n(1))
                "EXP" -> kotlin.math.exp(n(0))
                "LN" -> kotlin.math.ln(n(0))
                "LOG10" -> kotlin.math.log10(n(0))
                "CEILING", "CEILING.MATH" -> { val sig = n(1, 1.0); if (sig == 0.0) 0.0 else kotlin.math.ceil(n(0) / sig - 1e-12) * sig }
                "FLOOR", "FLOOR.MATH" -> { val sig = n(1, 1.0); if (sig == 0.0) 0.0 else floor(n(0) / sig + 1e-12) * sig }
                "PI" -> Math.PI
                "TRUE" -> true
                "FALSE" -> false
                "IRR" -> {
                    val cf = all.filter { it is Double }.map { it as Double }
                    if (cf.size < 2 || cf.none { it < 0 } || cf.none { it > 0 }) throw FormulaError("#NUM!")
                    var rate = n(1, 0.1)
                    repeat(100) {
                        var f = 0.0; var d = 0.0
                        cf.forEachIndexed { k, c -> val disc = (1 + rate).pow(k); f += c / disc; d -= k * c / (disc * (1 + rate)) }
                        if (d == 0.0) throw FormulaError("#NUM!")
                        val next = rate - f / d
                        if (abs(next - rate) < 1e-12) return next
                        rate = next
                    }
                    throw FormulaError("#NUM!")
                }
                "IF" -> {
                    val c = a.getOrNull(0) ?: throw FormulaError("#VALUE!")
                    val pick = if (bool(scalar(c))) a.getOrNull(1) ?: true else a.getOrNull(2) ?: false
                    if (pick is Err) throw FormulaError(pick.code) else pick
                }
                "IFS" -> { var k = 0; while (k + 1 < a.size) { if (bool(scalar(a[k]))) return a[k + 1].let { if (it is Err) throw FormulaError(it.code) else it }; k += 2 }; throw FormulaError("#N/A") }
                "IFERROR" -> { val v = a.getOrNull(0) ?: ""; if (isErr(scalar(v))) a.getOrNull(1) ?: "" else v }
                "IFNA" -> { val v = a.getOrNull(0) ?: ""; if ((v as? Err)?.code == "#N/A" || v == "#N/A") a.getOrNull(1) ?: "" else v }
                "ISERROR" -> isErr(scalar(a.getOrNull(0) ?: ""))
                "ISBLANK" -> scalar(a.getOrNull(0) ?: "").let { it is String && it.isEmpty() }
                "ISNUMBER" -> scalar(a.getOrNull(0) ?: "") is Double
                "AND" -> all.all { bool(it) }
                "OR" -> all.any { bool(it) }
                "NOT" -> !bool(scalar(a.getOrNull(0) ?: false))
                "CHOOSE" -> { val k = n(0).toInt(); a.getOrNull(k) ?: throw FormulaError("#VALUE!") }
                "CONCAT", "CONCATENATE" -> all.joinToString("") { str(it) }
                "TEXTJOIN" -> { val sep = t(0); val skipEmpty = bool(scalar(a.getOrNull(1) ?: true)); a.drop(2).flatMap { flat(it) }.map { str(it) }.filter { !skipEmpty || it.isNotEmpty() }.joinToString(sep) }
                "LEN" -> t(0).length.toDouble()
                "UPPER" -> t(0).uppercase()
                "LOWER" -> t(0).lowercase()
                "PROPER" -> t(0).lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                "TRIM" -> t(0).trim().replace(Regex("\\s+"), " ")
                "LEFT" -> t(0).take(n(1, 1.0).toInt())
                "RIGHT" -> t(0).takeLast(n(1, 1.0).toInt())
                "MID" -> t(0).drop(n(1).toInt() - 1).take(n(2).toInt())
                "VALUE" -> num(t(0))
                "TEXT" -> XlsxBook.format(Sheets.fmt(n(0)), t(1))
                "TODAY" -> serial(java.time.LocalDate.now())
                "DATE" -> serial(java.time.LocalDate.of(n(0).toInt(), 1, 1).plusMonths(n(1).toLong() - 1).plusDays(n(2).toLong() - 1))
                "YEAR" -> dateOf(n(0)).year.toDouble()
                "MONTH" -> dateOf(n(0)).monthValue.toDouble()
                "DAY" -> dateOf(n(0)).dayOfMonth.toDouble()
                "EDATE" -> serial(dateOf(n(0)).plusMonths(n(1).toLong()))
                "EOMONTH" -> serial(dateOf(n(0)).plusMonths(n(1).toLong()).let { it.withDayOfMonth(it.lengthOfMonth()) })
                "DAYS" -> floor(n(0)) - floor(n(1))
                "SUMPRODUCT" -> {
                    val rs = a.indices.map { rng(it) }
                    val len = rs.first().values.size
                    if (rs.any { it.values.size != len }) throw FormulaError("#VALUE!")
                    (0 until len).sumOf { k -> rs.fold(1.0) { acc, r -> acc * (r.values[k].let { v -> if (v is Double) v else if (v is Boolean) (if (v) 1.0 else 0.0) else if (v is Err) throw FormulaError(v.code) else (v as? String)?.toDoubleOrNull() ?: 0.0 }) } }
                }
                "SUMIF", "AVERAGEIF" -> {
                    val crit = rng(0); val test = scalar(a.getOrNull(1) ?: ""); val sum = if (a.size > 2) rng(2) else crit
                    val hits = crit.values.indices.filter { matches(crit.values[it], test) }.mapNotNull { sum.values.getOrNull(it) }.filter { it is Double }.map { it as Double }
                    if (name == "SUMIF") hits.sum() else hits.takeIf { it.isNotEmpty() }?.average() ?: throw FormulaError("#DIV/0!")
                }
                "COUNTIF" -> { val crit = rng(0); val test = scalar(a.getOrNull(1) ?: ""); crit.values.count { matches(it, test) }.toDouble() }
                "SUMIFS", "AVERAGEIFS", "MAXIFS", "MINIFS" -> {
                    val target = rng(0)
                    val idx = target.values.indices.filter { k -> (1 until a.size step 2).all { p -> matches(rng(p).values.getOrNull(k) ?: "", scalar(a.getOrNull(p + 1) ?: "")) } }
                    val vals = idx.mapNotNull { target.values[it] as? Double }
                    when (name) { "SUMIFS" -> vals.sum(); "AVERAGEIFS" -> vals.takeIf { it.isNotEmpty() }?.average() ?: throw FormulaError("#DIV/0!"); "MAXIFS" -> vals.maxOrNull() ?: 0.0; else -> vals.minOrNull() ?: 0.0 }
                }
                "COUNTIFS" -> { val first = rng(0); first.values.indices.count { k -> (0 until a.size step 2).all { p -> matches(rng(p).values.getOrNull(k) ?: "", scalar(a.getOrNull(p + 1) ?: "")) } }.toDouble() }
                "MATCH" -> {
                    val needle = scalar(a.getOrNull(0) ?: ""); val hay = rng(1).values; val type = n(2, 1.0).toInt()
                    val k = when (type) {
                        0 -> hay.indexOfFirst { matches(it, needle, exact = true) }
                        1 -> hay.indexOfLast { it is Double && needle is Double && it <= needle || it is String && needle is String && it.compareTo(needle, true) <= 0 && it.isNotEmpty() }
                        else -> hay.indexOfLast { it is Double && needle is Double && it >= needle }
                    }
                    if (k < 0) throw FormulaError("#N/A") else (k + 1).toDouble()
                }
                "INDEX" -> {
                    val r = rng(0); val row = n(1, 1.0).toInt(); val col = n(2, 1.0).toInt()
                    // a 0 row or column means "all of it", as in INDEX(range>0, 0)
                    if (row == 0 && (col == 0 || r.cols == 1)) return r
                    if (row == 0) return Rng((0 until r.rows).map { r[it, col - 1] }, r.rows, 1)
                    if (col == 0 && a.size > 2) return Rng((0 until r.cols).map { r[row - 1, it] }, 1, r.cols)
                    val (rr, cc) = if (r.rows == 1 && a.size == 2) 1 to row else row to col
                    if (rr < 1 || cc < 1 || rr > r.rows || cc > r.cols) throw FormulaError("#REF!") else r[rr - 1, cc - 1]
                }
                "VLOOKUP", "HLOOKUP" -> {
                    val needle = scalar(a.getOrNull(0) ?: ""); val tbl = rng(1); val k = n(2).toInt(); val approx = a.getOrNull(3)?.let { bool(scalar(it)) } ?: true
                    val v = name == "VLOOKUP"
                    val keys = if (v) (0 until tbl.rows).map { tbl[it, 0] } else (0 until tbl.cols).map { tbl[0, it] }
                    val hit = if (!approx) keys.indexOfFirst { matches(it, needle, exact = true) } else keys.indexOfLast { it is Double && needle is Double && it <= needle || it is String && needle is String && it.isNotEmpty() && it.compareTo(needle, true) <= 0 }
                    if (hit < 0) throw FormulaError("#N/A")
                    if (k < 1 || (v && k > tbl.cols) || (!v && k > tbl.rows)) throw FormulaError("#REF!")
                    if (v) tbl[hit, k - 1] else tbl[k - 1, hit]
                }
                "NPV" -> { val rate = n(0); a.drop(1).flatMap { flat(it) }.filter { it is Double }.mapIndexed { k, x -> (x as Double) / (1 + rate).pow(k + 1) }.sum() }
                "PMT" -> { val r = n(0); val np = n(1); val pv = n(2); val fv = n(3, 0.0); val type = n(4, 0.0); if (r == 0.0) -(pv + fv) / np else -(r * (pv * (1 + r).pow(np) + fv)) / ((1 + r * type) * ((1 + r).pow(np) - 1)) }
                "FV" -> { val r = n(0); val np = n(1); val pmt = n(2); val pv = n(3, 0.0); val type = n(4, 0.0); if (r == 0.0) -(pv + pmt * np) else -(pv * (1 + r).pow(np) + pmt * (1 + r * type) * ((1 + r).pow(np) - 1) / r) }
                "PV" -> { val r = n(0); val np = n(1); val pmt = n(2); val fv = n(3, 0.0); val type = n(4, 0.0); if (r == 0.0) -(fv + pmt * np) else -(fv + pmt * (1 + r * type) * ((1 + r).pow(np) - 1) / r) / (1 + r).pow(np) }
                else -> throw FormulaError("#NAME?")
            }
        }
    }

    /** SUMIF-style criteria: 5, "apples", ">=10", "<>x", "=", wildcards * and ?. */
    private fun matches(v: Any, crit: Any, exact: Boolean = false): Boolean {
        if (crit is Double) return (v as? Double ?: (v as? String)?.toDoubleOrNull())?.let { it == crit } ?: false
        if (crit is Boolean) return v == crit
        val c = crit.toString()
        val m = if (exact) null else Regex("^(<=|>=|<>|=|<|>)(.*)$").find(c)
        val op = m?.groupValues?.get(1) ?: "="
        val rhs = m?.groupValues?.get(2) ?: c
        val rn = rhs.toDoubleOrNull()
        val vn = v as? Double ?: (v as? String)?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        if (rn != null && vn != null) return when (op) { "<=" -> vn <= rn; ">=" -> vn >= rn; "<>" -> vn != rn; "<" -> vn < rn; ">" -> vn > rn; else -> vn == rn }
        val vs = str(if (v is Err) v.code else v)
        return when (op) {
            "=" -> if (rhs.any { it == '*' || it == '?' }) Regex("^" + rhs.split("*").joinToString(".*") { p -> p.split("?").joinToString(".") { Regex.escape(it) } } + "$", RegexOption.IGNORE_CASE).matches(vs) else vs.equals(rhs, true)
            "<>" -> !vs.equals(rhs, true)
            "<" -> vs.compareTo(rhs, true) < 0; ">" -> vs.compareTo(rhs, true) > 0
            "<=" -> vs.compareTo(rhs, true) <= 0; else -> vs.compareTo(rhs, true) >= 0
        }
    }

    private fun sheetEval(name: String): Evaluator = (other?.invoke(name) ?: throw FormulaError("#REF!"))
    private fun valueOf(r: Int, c: Int): Any = value(r, c)
    private fun rangeOf(r1: Int, c1: Int, r2: Int, c2: Int): Rng {
        val rows = (r2 - r1 + 1).coerceAtMost(100_000); val cols = (c2 - c1 + 1).coerceAtMost(1_000)
        // whole-column refs (A:A) arrive as A1:A1048576; cap at the sheet's used size
        val maxR = minOf(r2, r1 + rows - 1, sheet.rowCount - 1).coerceAtLeast(r1)
        val out = ArrayList<Any>((maxR - r1 + 1) * cols)
        for (r in r1..maxR) for (c in c1 until c1 + cols) out += value(r, c)
        return Rng(out, maxR - r1 + 1, cols)
    }
}

/** Evaluators for every sheet of a workbook, wired together so formulas can reference other sheets and names. */
class BookEvaluator(sheets: List<Sheet>, names: Map<String, String> = emptyMap()) {
    private val byName = HashMap<String, Evaluator>()
    val evaluators: List<Evaluator> = sheets.map { s -> Evaluator(s, { n -> byName[n.uppercase(Locale.US)] }, names).also { byName[s.name.uppercase(Locale.US)] = it } }
    operator fun get(i: Int): Evaluator = evaluators[i]
}
