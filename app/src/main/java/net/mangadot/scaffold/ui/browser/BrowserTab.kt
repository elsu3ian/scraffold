package net.mangadot.scaffold.ui.browser

import android.content.Context
import kotlinx.coroutines.Job
import net.mangadot.scaffold.engine.DeckWebView
import net.mangadot.scaffold.engine.PageZoom
import net.mangadot.scaffold.engine.ScriptEngine
import net.mangadot.scaffold.engine.TappedLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * One of the browser's tabs, created without a page until it's first shown, so reopening with many
 * tabs stays quick. [url] is where it stands, kept to what's safe to store: null on the start page,
 * and empty for a window its opener is still filling in.
 */
class BrowserTab(var url: String?, var title: String) {
    var web: DeckWebView? = null
    var scriptEngine: ScriptEngine? = null
    var opener: BrowserTab? = null
    var desktop = false
    var pageZoom: PageZoom? = null
    var appliedPageZoom = PageZoom.DEFAULT_PERCENT
    // Set while a new window waits out of sight for its first address
    var heldPopup: TappedLink? = null
    // Script setup still under way, which must not reach a page that has since closed
    var setup: Job? = null
}

/** The tabs reopened after the app is ended, which a page's own back history doesn't survive for background tabs. */
object BrowserTabStore {

    private const val PREFS_NAME = "scraffold_browser_tabs"
    private const val KEY_TABS = "tabs"
    private const val KEY_SELECTED = "selected"

    fun save(context: Context, tabs: List<BrowserTab>, selected: Int) {
        val list = JSONArray()
        tabs.forEach { list.put(JSONObject().put("url", it.url ?: JSONObject.NULL).put("title", it.title).put("desktop", it.desktop)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TABS, list.toString())
            .putInt(KEY_SELECTED, selected)
            .apply()
    }

    /** The saved tabs, never none, and which of them was on screen. */
    fun load(context: Context): Pair<List<BrowserTab>, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = runCatching { JSONArray(prefs.getString(KEY_TABS, "[]")) }.getOrDefault(JSONArray())
        val tabs = mutableListOf<BrowserTab>()
        for (i in 0 until saved.length()) {
            val entry = saved.getJSONObject(i)
            tabs += BrowserTab(entry.optString("url").takeIf { !entry.isNull("url") && BrowserAddress.isWebPage(it) }, entry.optString("title")).apply {
                desktop = entry.optBoolean("desktop")
            }
        }
        if (tabs.isEmpty()) tabs += BrowserTab(null, "")
        return tabs to prefs.getInt(KEY_SELECTED, 0).coerceIn(0, tabs.size - 1)
    }
}
