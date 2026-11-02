package net.mangadot.scaffold.engine

/**
 * Network filtering rules in the Adblock Plus and uBlock Origin syntax, indexed so a request only
 * checks the few rules that could apply to it. A rule using something this engine can't honor is
 * left out rather than guessed at, because a half-applied rule is how sites get broken.
 */
class FilterSet private constructor(
    private val plainHosts: HashSet<String>,
    private val blocks: RuleIndex,
    private val important: RuleIndex,
    private val exceptions: RuleIndex,
    private val allowedPages: HashSet<String>,
    private val selectors: HashMap<String, MutableList<String>>,
    val ruleCount: Int
) {

    /** [sourceHost] is the page asking for it; null counts as a different site. */
    fun isBlocked(url: String, host: String, sourceHost: String?, type: Int): Boolean {
        if (sourceHost != null && anySuffix(sourceHost) { it in allowedPages }) return false
        val lowerUrl = url.lowercase()
        val thirdParty = isThirdParty(host, sourceHost)
        if (important.matches(url, lowerUrl, host, sourceHost, type, thirdParty)) return true
        val listed = anySuffix(host) { it in plainHosts } ||
            blocks.matches(url, lowerUrl, host, sourceHost, type, thirdParty)
        return listed && !exceptions.matches(url, lowerUrl, host, sourceHost, type, thirdParty)
    }

    /** Element-hiding selectors written for this site. */
    fun selectorsFor(host: String): List<String> {
        val found = mutableListOf<String>()
        anySuffix(host) { suffix ->
            selectors[suffix]?.let(found::addAll)
            false
        }
        return found
    }

    private class Rule(
        val pattern: String,
        val typeMask: Int,
        val party: Int,
        val includes: Array<String>?,
        val excludes: Array<String>?,
        val denyAllow: Array<String>?,
        val matchCase: Boolean
    ) {
        private var regex: Regex? = null
        private var unusable = false

        fun applies(url: String, host: String, source: String?, type: Int, thirdParty: Boolean): Boolean {
            if (type != TYPE_UNKNOWN && typeMask and type == 0) return false
            if (party == PARTY_THIRD && !thirdParty) return false
            if (party == PARTY_FIRST && thirdParty) return false
            if (includes != null && (source == null || includes.none { isSameOrSubdomain(source, it) })) return false
            if (excludes != null && source != null && excludes.any { isSameOrSubdomain(source, it) }) return false
            if (denyAllow != null && denyAllow.any { isSameOrSubdomain(host, it) }) return false
            return pattern.isEmpty() || patternMatches(url)
        }

        private fun patternMatches(url: String): Boolean {
            if (unusable) return false
            val compiled = regex ?: try {
                toRegex(pattern, matchCase).also { regex = it }
            } catch (e: Exception) {
                unusable = true
                return false
            }
            return compiled.containsMatchIn(url)
        }
    }

    private class RuleIndex {
        private val byHost = HashMap<String, MutableList<Rule>>()
        private val byToken = HashMap<String, MutableList<Rule>>()
        private val bySource = HashMap<String, MutableList<Rule>>()
        private val unindexed = ArrayList<Rule>()

        fun add(rule: Rule, hostKey: String?) {
            val token = if (hostKey == null) bestToken(rule.pattern) else null
            when {
                hostKey != null -> byHost.getOrPut(hostKey) { ArrayList(1) }.add(rule)
                token != null -> byToken.getOrPut(token) { ArrayList(1) }.add(rule)
                rule.includes != null -> rule.includes.forEach { bySource.getOrPut(it) { ArrayList(1) }.add(rule) }
                else -> unindexed.add(rule)
            }
        }

        fun matches(url: String, lowerUrl: String, host: String, source: String?, type: Int, thirdParty: Boolean): Boolean {
            fun any(rules: List<Rule>?) = rules != null && rules.any { it.applies(url, host, source, type, thirdParty) }

            if (anySuffix(host) { any(byHost[it]) }) return true
            if (source != null && anySuffix(source) { any(bySource[it]) }) return true
            if (any(unindexed)) return true
            var start = -1
            for (i in 0..lowerUrl.length) {
                val isWordChar = i < lowerUrl.length && lowerUrl[i].let { it in 'a'..'z' || it in '0'..'9' }
                if (isWordChar) {
                    if (start < 0) start = i
                } else if (start >= 0) {
                    if (i - start >= MIN_TOKEN && any(byToken[lowerUrl.substring(start, i)])) return true
                    start = -1
                }
            }
            return false
        }
    }

    /** Collects lists into one set; rules a list has retired with badfilter are read first, so they can be dropped. */
    class Builder {
        private val plainHosts = HashSet<String>()
        private val blocks = RuleIndex()
        private val important = RuleIndex()
        private val exceptions = RuleIndex()
        private val allowedPages = HashSet<String>()
        private val selectors = HashMap<String, MutableList<String>>()
        private val retired = HashSet<String>()
        private var count = 0

        fun noteRetired(line: String) {
            if ("badfilter" !in line) return
            val text = line.trim()
            val cut = optionsStart(text)
            if (cut < 0 || text.startsWith("!")) return
            val kept = text.substring(cut + 1).split(',').filterNot { it == "badfilter" }
            if (kept.size < text.substring(cut + 1).split(',').size) {
                retired += if (kept.isEmpty()) text.substring(0, cut) else text.substring(0, cut) + "$" + kept.joinToString(",")
            }
        }

        fun add(line: String) {
            val text = line.trim()
            if (text.isEmpty() || text[0] == '!' || text[0] == '[' || text.startsWith("# ")) return
            if (text in retired) return
            val mark = text.indexOf("##")
            when {
                COSMETIC_OTHER.containsMatchIn(text) -> return
                mark >= 0 && '/' !in text.substring(0, mark) -> addCosmetic(text.substring(0, mark), text.substring(mark + 2))
                else -> addNetwork(text)
            }
        }

        private fun addCosmetic(domains: String, selector: String) {
            // Selectors for every site would need matching against each page's own classes to stay fast
            if (domains.isEmpty() || selector.isEmpty()) return
            if ('~' in domains || '*' in domains || PROCEDURAL.containsMatchIn(selector) || selector.startsWith("+js") || selector[0] == '^') return
            domains.split(',').forEach { selectors.getOrPut(it.lowercase()) { ArrayList(1) }.add(selector) }
            count++
        }

        private fun addNetwork(text: String) {
            val exception = text.startsWith("@@")
            val body = if (exception) text.substring(2) else text
            val cut = optionsStart(body)
            val pattern = if (cut >= 0) body.substring(0, cut) else body
            if (pattern.length > 1 && pattern.startsWith("/") && pattern.endsWith("/")) return

            var typeMask = 0
            var negatedTypes = 0
            var party = PARTY_ANY
            var includes: Array<String>? = null
            var excludes: Array<String>? = null
            var denyAllow: Array<String>? = null
            var matchCase = false
            var isImportant = false
            var document = false
            if (cut >= 0) {
                for (option in body.substring(cut + 1).split(',')) {
                    val name = option.substringBefore('=').lowercase()
                    val value = option.substringAfter('=', "")
                    when {
                        name == "third-party" || name == "3p" || name == "~first-party" || name == "~1p" -> party = PARTY_THIRD
                        name == "~third-party" || name == "~3p" || name == "first-party" || name == "1p" -> party = PARTY_FIRST
                        name == "domain" || name == "from" -> {
                            val all = value.lowercase().split('|')
                            if (all.any { '*' in it || it.startsWith("/") }) return
                            includes = all.filterNot { it.startsWith("~") }.toTypedArray().takeIf { it.isNotEmpty() }
                            excludes = all.filter { it.startsWith("~") }.map { it.substring(1) }.toTypedArray().takeIf { it.isNotEmpty() }
                        }
                        name == "denyallow" -> denyAllow = value.lowercase().split('|').toTypedArray()
                        name == "match-case" -> matchCase = true
                        name == "important" -> isImportant = true
                        name == "all" -> typeMask = TYPE_ALL
                        // The request is stopped either way; there is no local stand-in to serve here
                        name == "redirect" -> {}
                        name == "document" || name == "doc" -> document = true
                        name.startsWith("~") && name.substring(1) in TYPES -> negatedTypes = negatedTypes or TYPES.getValue(name.substring(1))
                        name in TYPES -> typeMask = typeMask or TYPES.getValue(name)
                        else -> return
                    }
                }
            }

            if (document) {
                // A page-wide exception. As a block it would stop whole pages, which is never done here.
                if (exception && typeMask == 0 && includes == null) hostOf(pattern)?.let { allowedPages += it; count++ }
                return
            }
            val mask = when {
                typeMask != 0 -> typeMask
                negatedTypes != 0 -> TYPE_ALL and negatedTypes.inv()
                else -> TYPE_ALL
            }
            val bare = pattern.trim('*')
            // A rule this broad and unrestricted would block the whole web
            if (bare.length < 3 && includes == null) return

            val host = hostOf(pattern)
            if (!exception && !isImportant && cut < 0 && host != null && pattern == "||$host^") {
                plainHosts += host
                count++
                return
            }
            val rule = Rule(if (pattern == "*") "" else pattern, mask, party, includes, excludes, denyAllow, matchCase)
            (if (exception) exceptions else if (isImportant) important else blocks).add(rule, host)
            count++
        }

        fun build() = FilterSet(plainHosts, blocks, important, exceptions, allowedPages, selectors, count)
    }

    companion object {
        const val TYPE_UNKNOWN = 0
        const val TYPE_SCRIPT = 1
        const val TYPE_IMAGE = 1 shl 1
        const val TYPE_STYLESHEET = 1 shl 2
        const val TYPE_XHR = 1 shl 3
        const val TYPE_SUBDOCUMENT = 1 shl 4
        const val TYPE_FONT = 1 shl 5
        const val TYPE_MEDIA = 1 shl 6
        const val TYPE_OTHER = 1 shl 7
        private const val TYPE_ALL = (1 shl 8) - 1

        private const val PARTY_ANY = 0
        private const val PARTY_THIRD = 1
        private const val PARTY_FIRST = 2
        private const val MIN_TOKEN = 3

        private val TYPES = mapOf(
            "script" to TYPE_SCRIPT,
            "image" to TYPE_IMAGE,
            "stylesheet" to TYPE_STYLESHEET,
            "css" to TYPE_STYLESHEET,
            "xmlhttprequest" to TYPE_XHR,
            "xhr" to TYPE_XHR,
            "subdocument" to TYPE_SUBDOCUMENT,
            "frame" to TYPE_SUBDOCUMENT,
            "font" to TYPE_FONT,
            "media" to TYPE_MEDIA,
            "object" to TYPE_OTHER,
            "ping" to TYPE_OTHER,
            "beacon" to TYPE_OTHER,
            "websocket" to TYPE_OTHER,
            "other" to TYPE_OTHER
        )

        private val COSMETIC_OTHER = Regex("#@#|#\\?#|#\\$#|#%#|\\$\\$|#@\\?#|##\\^")
        private val PROCEDURAL = Regex(":has-text\\(|:style\\(|:-abp-|:xpath\\(|:upward\\(|:matches-|:remove\\(|:min-text-length|:watch-attr|:others\\(|:nth-ancestor\\(|:contains\\(")
        private val OPTION_TEXT = Regex("^[~a-zA-Z0-9_,=|.*/\\-]+$")
        private val PLAIN_HOST = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")
        private val COUNTRY_SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "ac", "edu")

        fun build(sources: List<() -> Sequence<String>>): FilterSet {
            val builder = Builder()
            sources.forEach { source -> source().forEach(builder::noteRetired) }
            sources.forEach { source -> source().forEach(builder::add) }
            return builder.build()
        }

        private fun optionsStart(rule: String): Int {
            val cut = rule.lastIndexOf('$')
            return if (cut >= 0 && cut < rule.length - 1 && OPTION_TEXT.matches(rule.substring(cut + 1))) cut else -1
        }

        /** The host a ||host... rule names outright, when it names a whole one. */
        private fun hostOf(pattern: String): String? {
            if (!pattern.startsWith("||")) return null
            val end = pattern.indexOfFirst2()
            val host = pattern.substring(2, end).lowercase()
            return host.takeIf { PLAIN_HOST.matches(it) }
        }

        private fun String.indexOfFirst2(): Int {
            for (i in 2 until length) if (this[i] in "^/*?|:") return i
            return length
        }

        /** The longest whole word the rule is sure to contain, so requests only meet rules sharing a word. */
        private fun bestToken(pattern: String): String? {
            val lower = pattern.lowercase()
            var best: String? = null
            var start = -1
            for (i in 0..lower.length) {
                val isWordChar = i < lower.length && lower[i].let { it in 'a'..'z' || it in '0'..'9' }
                if (isWordChar) {
                    if (start < 0) start = i
                } else if (start >= 0) {
                    // A word touching a wildcard or an open end may continue in the address
                    val closedLeft = start > 0 && lower[start - 1] != '*'
                    val closedRight = i < lower.length && lower[i] != '*'
                    if (closedLeft && closedRight && i - start >= MIN_TOKEN && i - start > (best?.length ?: 0)) {
                        best = lower.substring(start, i)
                    }
                    start = -1
                }
            }
            return best
        }

        private fun toRegex(pattern: String, matchCase: Boolean): Regex {
            val out = StringBuilder()
            var body = pattern
            when {
                body.startsWith("||") -> {
                    out.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?")
                    body = body.substring(2)
                }
                body.startsWith("|") -> {
                    out.append('^')
                    body = body.substring(1)
                }
            }
            val anchoredEnd = body.endsWith("|")
            if (anchoredEnd) body = body.dropLast(1)
            for (char in body) {
                when (char) {
                    '*' -> out.append(".*?")
                    '^' -> out.append("(?:[^a-zA-Z0-9_.%-]|$)")
                    else -> {
                        if (!char.isLetterOrDigit()) out.append('\\')
                        out.append(char)
                    }
                }
            }
            if (anchoredEnd) out.append('$')
            return if (matchCase) Regex(out.toString()) else Regex(out.toString(), RegexOption.IGNORE_CASE)
        }

        private inline fun anySuffix(host: String, test: (String) -> Boolean): Boolean {
            var current = host
            while (true) {
                if (test(current)) return true
                val dot = current.indexOf('.')
                if (dot < 0) return false
                current = current.substring(dot + 1)
            }
        }

        private fun isSameOrSubdomain(host: String, domain: String): Boolean {
            return host == domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.')
        }

        private fun siteOf(host: String): String {
            val labels = host.split('.')
            if (labels.size < 3) return host
            val keep = if (labels.last().length == 2 && labels[labels.size - 2] in COUNTRY_SECOND_LEVEL) 3 else 2
            return labels.takeLast(keep).joinToString(".")
        }

        private fun isThirdParty(host: String, source: String?): Boolean {
            return source == null || siteOf(host) != siteOf(source)
        }
    }
}
