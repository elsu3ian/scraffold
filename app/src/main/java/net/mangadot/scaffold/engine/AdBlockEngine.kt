package net.mangadot.scaffold.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.ui.browser.QuickBrowserActivity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The shield: blocks requests using [FilterSet] and the lists from [FilterLists]. Until those have
 * downloaded, a short built-in list still covers the largest ad networks.
 */
class AdBlockEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstLoad = CountDownLatch(1)

    @Volatile
    private var filters: FilterSet = FilterSet.build(listOf { BUILT_IN_RULES.asSequence() })

    // Telemetry
    private val statsPrefs = context.getSharedPreferences(STATS_PREFS_NAME, Context.MODE_PRIVATE)
    private val totalBlocked = AtomicLong(statsPrefs.getLong(KEY_TOTAL_BLOCKED, 0L))
    private val blockedByApp = ConcurrentHashMap<String, AtomicLong>()

    val totalBlockedRequests: Long
        get() = totalBlocked.get()

    val ruleCount: Int
        get() = filters.ruleCount

    init {
        statsPrefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_APP_BLOCKED_PREFIX) && value is Long) {
                blockedByApp[key.removePrefix(KEY_APP_BLOCKED_PREFIX)] = AtomicLong(value)
            }
        }
        scope.launch {
            loadLists()
            firstLoad.countDown()
        }
    }

    private fun loadLists() {
        val files = FilterLists.files(context)
        if (files.isEmpty()) return
        val started = SystemClock.elapsedRealtime()
        val sources: List<() -> Sequence<String>> = files.map { file ->
            { sequence { file.useLines { lines -> yieldAll(lines.toList()) } } }
        } + listOf { BUILT_IN_RULES.asSequence() }
        filters = FilterSet.build(sources)
        Log.i(TAG, "Loaded ${filters.ruleCount} rules in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /** Called when the shelf opens. Quiet and in the background; the app works without it. */
    fun updateListsIfDue() {
        if (FilterLists.isDue(context)) scope.launch { updateListsNow() }
    }

    suspend fun updateListsNow(): Int {
        val arrived = FilterLists.update(context)
        if (arrived > 0) withContext(Dispatchers.IO) { loadLists() }
        return arrived
    }

    /** Runs on WebView's network threads. A page load started right at launch waits here for the lists. */
    fun shouldBlock(request: WebResourceRequest, pageHost: String?): Boolean {
        // A page someone chose to open is theirs to see, and only subresources are ads
        if (request.isForMainFrame) return false
        val uri = request.url
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (NEVER_BLOCKED.any { host == it || host.endsWith(".$it") }) return false

        firstLoad.await(FIRST_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
        val source = request.requestHeaders["Referer"]?.let { Uri.parse(it).host?.lowercase() } ?: pageHost?.takeIf { it.isNotEmpty() }
        val blocked = filters.isBlocked(uri.toString(), host, source, guessType(request))
        if (blocked) totalBlocked.incrementAndGet()
        return blocked
    }

    enum class PopupVerdict { OPEN, BLOCK, ASK }

    /**
     * For a page about to replace the one in [view], on the main thread. Says so when it stops one.
     * Rules for page loads always count; ad hosts count too unless the person tapped a link to them.
     */
    fun stopsNavigation(view: WebView, request: WebResourceRequest, pageHost: String?): Boolean {
        if (!request.isForMainFrame) return false
        val uri = request.url
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme in PAGE_SCHEMES) return false
        if (scheme != "http" && scheme != "https") {
            // Pages that throw open the Play Store or another app by themselves, as Chrome also stops
            if (request.hasGesture() || request.isRedirect) return false
            totalBlocked.incrementAndGet()
            Toast.makeText(context, "Shield stopped this page opening another app", Toast.LENGTH_SHORT).show()
            return true
        }
        // A server sending a tapped link on its way is still the link that was tapped
        val tapped = request.isRedirect || (request.hasGesture() && TappedLink(view).leadsTo(uri))
        if (!blocksNavigation(uri, pageHost, popup = false, tapped = tapped)) return false
        totalBlocked.incrementAndGet()
        Toast.makeText(context, "Shield blocked ${uri.host}", Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * For a new window's first address. A window opened by a tapped link, on the opener's own site,
     * or for signing in opens; anything else a page opened on another site is held for the person to decide.
     */
    fun judgePopup(uri: Uri, openerHost: String?, tapped: TappedLink): PopupVerdict {
        val followsLink = tapped.leadsTo(uri)
        if (blocksNavigation(uri, openerHost, popup = true, tapped = followsLink)) {
            totalBlocked.incrementAndGet()
            return PopupVerdict.BLOCK
        }
        if (followsLink) return PopupVerdict.OPEN
        val host = uri.host?.lowercase()
        if (host != null && openerHost != null && FilterSet.siteOf(host) == FilterSet.siteOf(openerHost.lowercase())) {
            return PopupVerdict.OPEN
        }
        // Signing in through another site happens in a window the page needs to hear back from
        if (QuickBrowserActivity.isSignIn(uri.toString())) return PopupVerdict.OPEN
        return PopupVerdict.ASK
    }

    // Decided on the main thread, so it goes with whatever lists have loaded rather than waiting for them
    private fun blocksNavigation(uri: Uri, sourceHost: String?, popup: Boolean, tapped: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (NEVER_BLOCKED.any { host == it || host.endsWith(".$it") }) return false
        return filters.blocksNavigation(uri.toString(), host, sourceHost?.lowercase()?.takeIf { it.isNotEmpty() }, popup, tapped)
    }

    /** WebView doesn't say what a request is for, so this works from its Accept header and file extension. */
    private fun guessType(request: WebResourceRequest): Int {
        val accept = request.requestHeaders["Accept"].orEmpty()
        val path = request.url.path.orEmpty().lowercase()
        val extension = path.substringAfterLast('.', "")
        return when {
            accept.startsWith("text/css") || extension == "css" -> FilterSet.TYPE_STYLESHEET
            accept.startsWith("image/") || extension in IMAGE_EXTENSIONS -> FilterSet.TYPE_IMAGE
            accept.startsWith("text/html") -> FilterSet.TYPE_SUBDOCUMENT
            extension == "js" || extension == "mjs" -> FilterSet.TYPE_SCRIPT
            extension in FONT_EXTENSIONS -> FilterSet.TYPE_FONT
            extension in MEDIA_EXTENSIONS -> FilterSet.TYPE_MEDIA
            else -> FilterSet.TYPE_UNKNOWN
        }
    }

    /** Fails the way a blocked request does: script error events fire, and cross-site fetches reject. */
    fun blockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * CSS for the small built-in list, for pages under [host] loading at document start.
     */
    fun getCosmeticCss(host: String): String {
        return "${BUILT_IN_SELECTORS.joinToString(", ")} { display: none !important; }"
    }

    /** Hides what the lists name for this site. Each selector is its own rule, so one bad one can't sink the rest. */
    fun siteCosmeticScript(host: String): String? {
        val selectors = filters.selectorsFor(host.lowercase())
        if (selectors.isEmpty()) return null
        val css = selectors.joinToString("") { "$it{display:none!important}" }
        return "(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};" +
            "(document.head||document.documentElement).appendChild(s);})();"
    }

    /**
     * Quiets the common checks pages use to detect an ad blocker.
     */
    fun getScriptletDefusers(host: String): String {
        return """
            (function() {
                try {
                    window.canRunAds = true;
                    window.isAdBlockActive = false;
                    window.google_ad_status = 1;
                } catch(e) {}
            })();
        """.trimIndent()
    }

    fun recordBlockedForApp(appId: String) {
        blockedByApp.getOrPut(appId) { AtomicLong(0L) }.incrementAndGet()
    }

    fun blockedCountsByApp(): Map<String, Long> {
        return blockedByApp.mapValues { it.value.get() }
    }

    /**
     * Counters change on WebView network threads, so they are written out on pause rather than per request
     */
    fun flushStats() {
        statsPrefs.edit().apply {
            putLong(KEY_TOTAL_BLOCKED, totalBlocked.get())
            blockedByApp.forEach { (appId, count) ->
                putLong(KEY_APP_BLOCKED_PREFIX + appId, count.get())
            }
        }.apply()
    }

    fun resetStats() {
        totalBlocked.set(0L)
        blockedByApp.clear()
        statsPrefs.edit().clear().apply()
    }

    companion object {
        private const val STATS_PREFS_NAME = "scraffold_shield_stats"
        private const val KEY_TOTAL_BLOCKED = "total_blocked"
        private const val KEY_APP_BLOCKED_PREFIX = "app_blocked_"
        private const val TAG = "Shield"
        private const val FIRST_LOAD_WAIT_MS = 4000L

        // Human checks and sign-in. Blocking these locks people out, whatever a list says.
        private val NEVER_BLOCKED = listOf("challenges.cloudflare.com", "accounts.google.com")
        // Addresses a page uses for itself, rather than ones that leave for an app
        private val PAGE_SCHEMES = setOf("about", "blob", "data", "javascript", "file")

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")
        private val FONT_EXTENSIONS = setOf("woff", "woff2", "ttf", "otf", "eot")
        private val MEDIA_EXTENSIONS = setOf("mp4", "webm", "m3u8", "mp3", "m4a", "ogg", "ts", "mpd")

        private val BUILT_IN_RULES = listOf(
            "doubleclick.net", "googlesyndication.com", "google-analytics.com", "googletagmanager.com",
            "googleadservices.com", "adservice.google.com", "criteo.com", "adnxs.com", "amazon-adsystem.com",
            "taboola.com", "outbrain.com", "popads.net", "popcash.net", "exoclick.com", "adsterra.com",
            "propellerads.com", "mgid.com", "yadro.ru", "scorecardresearch.com", "quantserve.com", "hotjar.com",
            "branch.io", "appsflyer.com", "adjust.com", "adcolony.com", "mc.yandex.ru", "mc.yandex.com"
        ).map { "||$it^" }

        private val BUILT_IN_SELECTORS = listOf(
            ".ad-banner", ".ad-box", ".ad-container", ".ad-slot", ".ad_wrapper",
            "#banner-ad", "#div-gpt-ad", ".sponsored-post", ".adsbygoogle",
            "[class*='AdSlot']", "[id*='AdSlot']", "[class*='banner_ad']"
        )

        @Volatile
        private var INSTANCE: AdBlockEngine? = null

        fun getInstance(context: Context): AdBlockEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdBlockEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
