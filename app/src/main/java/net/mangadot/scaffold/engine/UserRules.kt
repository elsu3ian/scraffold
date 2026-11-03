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
}
