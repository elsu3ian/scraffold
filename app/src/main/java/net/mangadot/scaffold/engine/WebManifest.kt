package net.mangadot.scaffold.engine

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * What a site's web app manifest says about running it as an app, read the way the W3C spec does: start_url
 * and scope must stay on the page's own origin, or they fall back to the defaults.
 */
data class WebManifest(
    val name: String?,
    val startUrl: String,
    val scope: String,
    val display: String?,
    val themeColor: String?,
    val backgroundColor: String?
) {
    companion object {
        private val DISPLAY_MODES = setOf("fullscreen", "standalone", "minimal-ui", "browser")
        private val hexColor = Regex("""#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})""", RegexOption.IGNORE_CASE)
        private val rgbColor = Regex("""rgba?\(\s*(\d{1,3})[\s,]+(\d{1,3})[\s,]+(\d{1,3}).*\)""", RegexOption.IGNORE_CASE)

        fun parse(json: JSONObject, manifestUrl: HttpUrl, pageUrl: HttpUrl): WebManifest {
            val start = json.text("start_url")?.let(manifestUrl::resolve)
                ?.takeIf { sameOrigin(it, pageUrl) }
                ?: pageUrl
            val scope = json.text("scope")?.let(manifestUrl::resolve)
                ?.takeIf { sameOrigin(it, start) && start.encodedPath.startsWith(it.encodedPath) }
                ?: start.newBuilder().encodedPath(start.encodedPath.substringBeforeLast('/') + "/").query(null).build()
            return WebManifest(
                // The shelf tile is small, which is what short_name is for
                name = json.text("short_name") ?: json.text("name"),
                startUrl = start.toString(),
                scope = scope.newBuilder().query(null).fragment(null).build().toString(),
                display = json.text("display")?.lowercase()?.takeIf { it in DISPLAY_MODES },
                themeColor = json.text("theme_color")?.let(::cssColor),
                backgroundColor = json.text("background_color")?.let(::cssColor)
            )
        }

        private fun JSONObject.text(key: String) = optString(key).trim().takeIf { it.isNotEmpty() && has(key) && !isNull(key) }

        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

        /** A CSS color as #RRGGBB. Transparency is dropped, since a status bar can't show through. */
        private fun cssColor(value: String): String? {
            val color = hexColor.matchEntire(value)?.groupValues?.get(1)?.let { hex ->
                val full = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
                full.take(6).toInt(16)
            } ?: rgbColor.matchEntire(value)?.groupValues?.drop(1)?.map { it.toInt().coerceIn(0, 255) }?.let { (r, g, b) ->
                Color.rgb(r, g, b) and 0xFFFFFF
            } ?: try {
                // The basic named colors Android knows, like "white" or "black"
                Color.parseColor(value.lowercase()) and 0xFFFFFF
            } catch (e: IllegalArgumentException) {
                return null
            }
            return String.format("#%06X", color)
        }
    }
}

/** Fills in what the user didn't choose themselves: the name only when [keepName] is false. */
fun WebAppEntity.withManifest(manifest: WebManifest, keepName: Boolean) = copy(
    name = if (keepName) name else manifest.name?.take(MAX_MANIFEST_NAME) ?: name,
    startUrl = manifest.startUrl,
    scope = manifest.scope,
    display = manifest.display,
    themeColor = manifest.themeColor,
    backgroundColor = manifest.backgroundColor
)

private const val MAX_MANIFEST_NAME = 40

/**
 * Whether a link is inside the app's manifest scope, for links on the scope's own origin. Null for other hosts and
 * for apps without a manifest, which go by the usual same-site check.
 */
fun WebAppEntity.scopeVerdict(uri: Uri): Boolean? {
    val scope = scope?.let(Uri::parse) ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    if (host != scope.host?.lowercase()?.removePrefix("www.")) return null
    return uri.path.orEmpty().ifEmpty { "/" }.startsWith(scope.path.orEmpty().ifEmpty { "/" })
}

/** A manifest color as an Android color, or null when the app has none. */
fun String?.manifestColor(): Int? = this?.let {
    try {
        Color.parseColor(it)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * Some sites add their manifest link with script, so a plain fetch at install time can't see it.
 * Such an app is marked here and reads the manifest from its own page the first time it opens.
 */
object ManifestInstall {

    private const val PREFS = "scraffold_manifest_pending"
    private const val FIND_LINK = "(function(){var l=document.querySelector('link[rel~=\"manifest\"]');return l?l.href:null})()"

    /** [keepName] says the user named the app, so the manifest mustn't rename it. */
    fun markPending(context: Context, appId: String, keepName: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(appId, keepName).apply()
    }

    fun isPending(context: Context, appId: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(appId)

    fun clear(context: Context, appId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(appId).apply()
    }

    /** The app with what its page's manifest says, or null when the page has none (yet). */
    suspend fun fromPage(context: Context, webView: WebView, app: WebAppEntity): WebAppEntity? {
        val pageUrl = webView.url ?: return null
        val raw = suspendCancellableCoroutine<String?> { done ->
            webView.evaluateJavascript(FIND_LINK) { if (done.isActive) done.resume(it) }
        }
        val href = try {
            JSONArray("[$raw]").optString(0).takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        } ?: return null
        val manifest = AppIcons.fetchManifest(context, href, pageUrl) ?: return null
        val keepName = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(app.id, true)
        return app.withManifest(manifest, keepName)
    }
}
