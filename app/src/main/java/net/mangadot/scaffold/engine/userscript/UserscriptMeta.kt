package net.mangadot.scaffold.engine.userscript

import net.mangadot.scaffold.data.db.entity.ScriptEntity
import java.util.regex.Pattern

/** A script's ==UserScript== header. The code is the source of truth for all of this. */
data class UserscriptMeta(
    val name: String = "",
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val excludeMatches: List<String> = emptyList(),
    val runAt: String = ScriptEntity.RUN_AT_DOCUMENT_IDLE,
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val resources: List<Pair<String, String>> = emptyList(),
    val connects: List<String> = emptyList(),
    val noframes: Boolean = false,
    val updateUrl: String? = null,
    val downloadUrl: String? = null
) {

    /** Regexes a URL must hit. An empty list means the script targets nothing. */
    fun includeRegexes(): List<String> =
        matches.map(::matchPatternToRegex) + includes.map(::includeToRegex)

    fun excludeRegexes(): List<String> =
        excludeMatches.map(::matchPatternToRegex) + excludes.map(::includeToRegex)

    fun appliesTo(url: String): Boolean {
        val hit = includeRegexes().any { Pattern.compile(it, Pattern.CASE_INSENSITIVE).matcher(url).find() }
        return hit && excludeRegexes().none { Pattern.compile(it, Pattern.CASE_INSENSITIVE).matcher(url).find() }
    }

    companion object {
        private val BLOCK = Regex("""//\s*==UserScript==(.*?)//\s*==/UserScript==""", RegexOption.DOT_MATCHES_ALL)
        private val LINE = Regex("""^\s*//\s*@(\S+)(?:\s+(.*?))?\s*$""")

        fun parse(code: String): UserscriptMeta {
            val block = BLOCK.find(code)?.groupValues?.get(1) ?: return UserscriptMeta()
            val values = mutableListOf<Pair<String, String>>()
            block.lineSequence().forEach { line ->
                LINE.find(line)?.let { values += it.groupValues[1] to it.groupValues[2] }
            }
            fun first(key: String) = values.firstOrNull { it.first == key }?.second
            fun all(key: String) = values.filter { it.first == key && it.second.isNotEmpty() }.map { it.second }

            return UserscriptMeta(
                name = first("name").orEmpty(),
                namespace = first("namespace").orEmpty(),
                version = first("version").orEmpty(),
                description = first("description").orEmpty(),
                author = first("author").orEmpty(),
                matches = all("match"),
                includes = all("include"),
                excludes = all("exclude"),
                excludeMatches = all("exclude-match"),
                runAt = first("run-at")?.takeIf { it.isNotEmpty() } ?: ScriptEntity.RUN_AT_DOCUMENT_IDLE,
                grants = all("grant"),
                requires = all("require"),
                resources = all("resource").mapNotNull { value ->
                    val parts = value.split(Regex("\\s+"), 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                },
                connects = all("connect"),
                noframes = values.any { it.first == "noframes" },
                updateUrl = first("updateURL"),
                downloadUrl = first("downloadURL")
            )
        }

        /** Replaces every [key] line in the header with one line per value, keeping its position. */
        fun withHeaderValues(code: String, key: String, values: List<String>): String {
            val block = BLOCK.find(code) ?: return code
            val lines = block.value.lines().toMutableList()
            val keyLine = Regex("""^\s*//\s*@${Regex.escape(key)}(\s.*)?$""")
            val at = lines.indexOfFirst { keyLine.matches(it) }.takeIf { it >= 0 } ?: (lines.size - 1)
            lines.removeAll { keyLine.matches(it) }
            lines.addAll(at.coerceAtMost(lines.size - 1), values.map { "// @${key.padEnd(12)} $it" })
            return code.replaceRange(block.range, lines.joinToString("\n"))
        }

        /** Match pattern rules: scheme, host with an optional leading wildcard, then a path glob. */
        fun matchPatternToRegex(pattern: String): String {
            if (pattern == "<all_urls>" || pattern == "*") return "^https?://.*$"
            val parsed = Regex("""^(\*|[a-zA-Z][a-zA-Z0-9+.-]*)://([^/]*)(/.*)?$""").find(pattern)
                ?: return "^" + globBody(pattern) + "$"
            val (scheme, host, path) = parsed.destructured
            val schemeRegex = if (scheme == "*") "https?" else escapeForRegex(scheme)
            val hostRegex = when {
                host == "*" -> "[^/]*"
                host.startsWith("*.") -> "(?:[^/]*\\.)?" + escapeForRegex(host.removePrefix("*."))
                else -> globBody(host)
            }
            // A pattern without a path still covers the whole site
            val pathRegex = if (path.isEmpty()) "/.*" else globBody(path)
            return "^$schemeRegex://$hostRegex$pathRegex$"
        }

        /** @include is a glob, or a regex when written as /.../ */
        fun includeToRegex(include: String): String {
            if (include == "*") return "^.*$"
            if (include.length > 2 && include.startsWith("/") && include.endsWith("/")) {
                return include.substring(1, include.length - 1)
            }
            return "^" + globBody(include) + "$"
        }

        private fun globBody(glob: String): String {
            return glob.split("*").joinToString(".*") { escapeForRegex(it) }
        }

        /** These regexes also run in the page, so no Java-only syntax such as Regex.escape's quoting. */
        private fun escapeForRegex(text: String): String {
            return text.replace(Regex("""[\\^$.|?+()\[\]{}]""")) { "\\" + it.value }
        }

        /** True when [candidate] is a higher dotted version than [current]. */
        fun isNewerVersion(candidate: String, current: String): Boolean {
            val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (difference != 0) return difference > 0
            }
            return false
        }
    }
}
