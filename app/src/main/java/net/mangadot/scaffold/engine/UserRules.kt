package net.mangadot.scaffold.engine

import android.content.Context
import java.io.File

/**
 * The person's own filters, in the lists' syntax, one per line: what the element picker saves and
 * what they write under My filters. The engine builds them in next to its built-in rules.
 */
object UserRules {

    private const val FILE_NAME = "my_filters.txt"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")

    /** Keeps [text] as written, apart from blank lines at either end. */
    fun save(context: Context, text: String) {
        val trimmed = text.trim('\n', '\r', ' ')
        file(context).writeText(if (trimmed.isEmpty()) "" else "$trimmed\n")
    }

    /** Returns false when [rule] was already there. */
    fun add(context: Context, rule: String): Boolean {
        val current = load(context)
        if (current.lineSequence().any { it.trim() == rule }) return false
        save(context, if (current.isBlank()) rule else current.trimEnd() + "\n" + rule)
        return true
    }

    /** The elements hidden on [site], which the element picker saves as `site##selector`. */
    fun hiddenOn(context: Context, site: String): List<String> {
        return load(context).lineSequence().map { it.trim() }.filter { hidesOn(it, site) }.toList()
    }

    /** Lets [site] show everything it hid again. Returns how many filters went. */
    fun unhide(context: Context, site: String): Int {
        val lines = load(context).lines()
        val kept = lines.filterNot { hidesOn(it.trim(), site) }
        if (kept.size != lines.size) save(context, kept.joinToString("\n"))
        return lines.size - kept.size
    }

    // A rule for example.com covers www.example.com, and one for www.example.com covers example.com
    private fun hidesOn(rule: String, site: String): Boolean {
        if (site.isEmpty() || !rule.contains("##")) return false
        val domains = rule.substringBefore("##").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("~") }
        val host = site.lowercase().removePrefix("www.")
        return domains.any { val d = it.removePrefix("www."); host == d || host.endsWith(".$d") }
    }
}
