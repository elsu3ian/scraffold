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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The shield: Brave's adblock-rust ([AdblockNative]) running the lists from [FilterLists]. Until
 * those have downloaded, a short built-in list still covers the largest ad networks.
 */
class AdBlockEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstLoad = CountDownLatch(1)
    private val cacheDir = File(context.noBackupFilesDir, "shield")

    // Telemetry
    private val statsPrefs = context.getSharedPreferences(STATS_PREFS_NAME, Context.MODE_PRIVATE)
    private val totalBlocked = AtomicLong(statsPrefs.getLong(KEY_TOTAL_BLOCKED, 0L))
    private val blockedByApp = ConcurrentHashMap<String, AtomicLong>()

    val totalBlockedRequests: Long
        get() = totalBlocked.get()

    @Volatile
    var ruleCount: Int = 0
        private set

    init {
        statsPrefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_APP_BLOCKED_PREFIX) && value is Long) {
                blockedByApp[key.removePrefix(KEY_APP_BLOCKED_PREFIX)] = AtomicLong(value)
            }
        }
        scope.launch {
            loadLists(rebuild = false)
            firstLoad.countDown()
        }
    }

    /** The engine kept from the last build loads in moments; parsing every list again is for when they change. */
    private fun loadLists(rebuild: Boolean) {
        val started = SystemClock.elapsedRealtime()
        val files = FilterLists.files(context)
        val resources = FilterLists.resources(context)?.path
        var count = if (!rebuild && files.isNotEmpty()) AdblockNative.restore(cacheDir.path, resources) else -1
        if (count < 0) {
            val keep = cacheDir.path.takeIf { files.isNotEmpty() }
            val trackers = FilterLists.trackerFiles(context).map { it.path }.toTypedArray()
            count = AdblockNative.build(files.map { it.path }.toTypedArray(), trackers, BUILT_IN_RULES, resources, keep)
        }
        ruleCount = count.coerceAtLeast(0)
        Log.i(TAG, "Loaded $ruleCount rules in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /** Called when the shelf opens. Quiet and in the background; the app works without it. */
    fun updateListsIfDue() {
        if (FilterLists.isDue(context)) scope.launch { updateListsNow() }
    }

    suspend fun updateListsNow(): Int {
        val arrived = FilterLists.update(context)
        if (arrived > 0) withContext(Dispatchers.IO) { loadLists(rebuild = true) }
        return arrived
    }

    /**
     * Runs on WebView's network threads. A page load started right at launch waits here for the lists.
     * What it stops is counted in [stats], as an ad or a tracker.
     */
    fun shouldBlock(request: WebResourceRequest, pageHost: String?, stats: ShieldStats? = null): Boolean {
        // A page someone chose to open is theirs to see, and only subresources are ads
        if (request.isForMainFrame) return false
        val uri = request.url
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (NEVER_BLOCKED.any { host == it || host.endsWith(".$it") }) return false

        firstLoad.await(FIRST_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
        val source = request.requestHeaders["Referer"] ?: siteUrl(pageHost)
        val url = uri.toString()
        val flags = requestTypes(request).map { type -> AdblockNative.check(url, source, type, false) }
            .firstOrNull { it and AdblockNative.BLOCK != 0 } ?: return false
        totalBlocked.incrementAndGet()
        if (flags and AdblockNative.TRACKER != 0) stats?.trackers?.incrementAndGet() else stats?.ads?.incrementAndGet()
        return true
    }

    /**
     * What a page at [url] needs at document start, as [AdblockNative.cosmetics] gives it. Pages
     * asking from the bridge wait for the lists like requests do; the main thread doesn't.
     */
    fun cosmetics(url: String, waitForLists: Boolean): String? {
        if (waitForLists) firstLoad.await(FIRST_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
        return AdblockNative.cosmetics(url)
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
            (view as? DeckWebView)?.shieldStats?.popups?.incrementAndGet()
            Toast.makeText(context, "Shield stopped this page opening another app", Toast.LENGTH_SHORT).show()
            return true
        }
        // A server sending a tapped link on its way is still the link that was tapped
        val tapped = request.isRedirect || (request.hasGesture() && TappedLink(view).leadsTo(uri))
        if (!blocksNavigation(uri, pageHost, popup = false, tapped = tapped)) {
            (view as? DeckWebView)?.shield?.prepare(uri.toString())
            return false
        }
        totalBlocked.incrementAndGet()
        (view as? DeckWebView)?.shieldStats?.popups?.incrementAndGet()
        Toast.makeText(context, "Shield blocked ${uri.host}", Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * For a new window's first address. A window opened by a tapped link, on the opener's own site,
     * or for signing in opens; anything else a page opened on another site is held for the person to decide.
     */
    fun judgePopup(uri: Uri, openerHost: String?, tapped: TappedLink, openerStats: ShieldStats? = null): PopupVerdict {
        val verdict = popupVerdict(uri, openerHost, tapped)
        if (verdict != PopupVerdict.OPEN) openerStats?.popups?.incrementAndGet()
        return verdict
    }

    private fun popupVerdict(uri: Uri, openerHost: String?, tapped: TappedLink): PopupVerdict {
        val followsLink = tapped.leadsTo(uri)
        if (blocksNavigation(uri, openerHost, popup = true, tapped = followsLink)) {
            totalBlocked.incrementAndGet()
            return PopupVerdict.BLOCK
        }
        if (followsLink) return PopupVerdict.OPEN
        val sameSite = AdblockNative.check(uri.toString(), siteUrl(openerHost), "other", false) and AdblockNative.THIRD_PARTY == 0
        if (openerHost != null && sameSite) return PopupVerdict.OPEN
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
        val url = uri.toString()
        val source = siteUrl(sourceHost)
        // Rules written for page loads always count, and for a new window so do the lists' $popup rules
        if (AdblockNative.check(url, source, "document", false) and AdblockNative.BLOCK != 0) return true
        if (popup && AdblockNative.check(url, source, "document", true) and AdblockNative.BLOCK != 0) return true
        if (tapped) return false
        // Rules for an ad host's requests count too when nobody tapped for it, unless a site is moving between its own pages
        val flags = AdblockNative.check(url, source, "other", false)
        return flags and AdblockNative.BLOCK != 0 && flags and AdblockNative.THIRD_PARTY != 0
    }

    /**
     * WebView doesn't say what a request is for, so this works from its Accept header and file
     * extension. Where those don't tell, it's whichever of the usual two a rule would stop.
     */
    private fun requestTypes(request: WebResourceRequest): List<String> {
        val accept = request.requestHeaders["Accept"].orEmpty()
        val path = request.url.path.orEmpty().lowercase()
        val extension = path.substringAfterLast('.', "")
        return when {
            accept.startsWith("text/css") || extension == "css" -> STYLESHEET
            accept.startsWith("image/") || extension in IMAGE_EXTENSIONS -> IMAGE
            accept.startsWith("text/html") -> SUBDOCUMENT
            extension == "js" || extension == "mjs" -> SCRIPT
            extension in FONT_EXTENSIONS -> FONT
            extension in MEDIA_EXTENSIONS -> MEDIA
            else -> UNSURE
        }
    }

    private fun siteUrl(host: String?): String = host?.takeIf { it.isNotEmpty() }?.let { "https://$it/" }.orEmpty()

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

        private val STYLESHEET = listOf("stylesheet")
        private val IMAGE = listOf("image")
        private val SUBDOCUMENT = listOf("subdocument")
        private val SCRIPT = listOf("script")
        private val FONT = listOf("font")
        private val MEDIA = listOf("media")
        private val UNSURE = listOf("xmlhttprequest", "script")

        private val BUILT_IN_RULES = listOf(
            "doubleclick.net", "googlesyndication.com", "google-analytics.com", "googletagmanager.com",
            "googleadservices.com", "adservice.google.com", "criteo.com", "adnxs.com", "amazon-adsystem.com",
            "taboola.com", "outbrain.com", "popads.net", "popcash.net", "exoclick.com", "adsterra.com",
            "propellerads.com", "mgid.com", "yadro.ru", "scorecardresearch.com", "quantserve.com", "hotjar.com",
            "branch.io", "appsflyer.com", "adjust.com", "adcolony.com", "mc.yandex.ru", "mc.yandex.com"
        ).map { "||$it^" }.plus(
            listOf(
                ".ad-banner", ".ad-box", ".ad-container", ".ad-slot", ".ad_wrapper",
                "#banner-ad", "#div-gpt-ad", ".sponsored-post", ".adsbygoogle",
                "[class*='AdSlot']", "[id*='AdSlot']", "[class*='banner_ad']"
            ).map { "##$it" }
        ).joinToString("\n")

        @Volatile
        private var INSTANCE: AdBlockEngine? = null

        fun getInstance(context: Context): AdBlockEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdBlockEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
