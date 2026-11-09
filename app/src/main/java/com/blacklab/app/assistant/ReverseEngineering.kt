package com.blacklab.app.assistant

import java.io.File
import java.util.Locale

/**
 * Reverse engineering happens on the computer (REA + Ghidra, desktop/companion/reverse.py): the phone only decides
 * how to hand a request over. Pure, so every phrasing is unit tested in ReverseEngineeringTest.
 *
 *  - [Route.Report]: "reverse engineer ~/Downloads/tool.bin [and find …]" → a "reverse" background job; the report
 *    comes back as a document in Reverse Engineering.
 *  - [Route.Quick]: status and "how do I … with REA" questions — answered by the desktop in seconds.
 *  - [Route.Background]: anything else about analyzing a program ("decompile 0x401000 in …", "what does tool.bin
 *    do?") — those take Ghidra a minute or more, so they run as an "ask" job instead of holding the chat open.
 */
object ReverseEngineering {
    sealed interface Route {
        data class Report(val target: String, val goal: String) : Route
        data class Quick(val text: String) : Route
        data class Background(val text: String) : Route
    }

    /** File types worth offering "Reverse-engineer on computer" for (programs, libraries, packages, firmware, bytecode, models). */
    val EXTENSIONS = setOf(
        "apk", "aab", "xapk", "apks", "dex", "jar", "class", "so", "o", "a", "ko", "elf", "bin", "out", "exe", "dll", "sys", "efi", "msi",
        "dylib", "wasm", "pyc", "beam", "gguf", "safetensors", "img", "fw", "hex", "uf2", "dtb", "asar", "node", "appimage",
    )

    fun canSend(file: File): Boolean = file.isFile && file.extension.lowercase(Locale.US) in EXTENSIONS && file.length() in 1..2_000_000_000L

    private val REPORT = Regex(
        "^(?:please )?(?:reverse[- ]?engineer|triage|(?:do|run|write) (?:a |an )?(?:full )?(?:reverse[- ]engineering|re) (?:report|pass|triage) (?:on|of|for))\\s+" +
            "(?:the |this |my )?(?:file |binary |program |app |apk |executable |firmware )?(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val TARGET = Regex("\"([^\"]+)\"|'([^']+)'|((?:~|/)[^\\s,;]+)|\\b([\\w.-]+\\.(?:${EXTENSIONS.joinToString("|")}))\\b", RegexOption.IGNORE_CASE)
    private val STATUS = Regex("^(?:rea|reverse[- ]engineering|ghidra|decompiler) (?:status|doctor|check)\\??$|^is (?:rea|ghidra|reverse engineering) (?:installed|ready|working)( on (?:my |the )?(?:computer|desktop))?\\??$")
    private val HOWTO = Regex("^(?:how (?:do|can|would|should) (?:i|we|you)|how to|what(?:'s| is) the (?:command|way)|can (?:rea|ghidra)|does rea|which rea|what rea command)\\b.*\\b(?:rea|ghidra|reverse[- ]engineer\\w*|decompil\\w*)\\b")
    private val ABOUT = Regex("\\b(?:reverse[- ]?engineer\\w*|decompil\\w*|disassembl\\w*|ghidra|rea|xrefs?|pseudo-?code)\\b")

    /** The first file named in [text]: quoted, a ~/ or / path, or a bare name with a program-like extension. */
    fun target(text: String): String? = TARGET.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }

    fun route(text: String): Route? {
        val t = text.trim()
        val l = t.lowercase(Locale.US)
        if (STATUS.containsMatchIn(l)) return Route.Quick(t)
        REPORT.find(t)?.let { m ->
            val rest = m.groupValues[1].trim()
            val file = target(rest) ?: return Route.Background(t)
            val goal = rest.replace(Regex("[\"']?" + Regex.escape(file) + "[\"']?"), "").trim(' ', ',', '.', ';', ':')
                .replace(Regex("^(?:and|to|then)\\s+", RegexOption.IGNORE_CASE), "")
            return Route.Report(file, goal)
        }
        if (HOWTO.containsMatchIn(l) && target(t) == null) return Route.Quick(t)
        if (ABOUT.containsMatchIn(l)) return Route.Background(t)
        if (target(t) != null && Regex("\\b(?:strings|functions|imports|exports|symbols|what does .+ do|how does .+ work|the code)\\b").containsMatchIn(l)) return Route.Background(t)
        return null
    }
}
