package net.mangadot.scaffold.engine

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import net.mangadot.scaffold.R
import net.mangadot.scaffold.ui.settings.SettingsActivity
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.dialogField
import net.mangadot.scaffold.ui.theme.showCosmic

/**
 * Cloudflare's "Verify you are human" check never lets desktop mode through: WebView names the app in
 * a header on every request, which no desktop browser does, and nothing an app can do takes it off.
 * The same check passes Chrome on a phone, so a site whose check turns up in desktop mode can be given
 * the phone's identity instead, still laid out wide.
 */
object DesktopFallback {

    const val ASK = 0
    const val ALWAYS_MOBILE = 1
    const val KEEP_DESKTOP = 2

    private const val KEY_ANSWER = "pref_desktop_cf_answer"
    private const val KEY_SITES = "pref_desktop_cf_sites"

    // How wide pages are laid out where a site gets the phone's identity in desktop mode
    const val WIDE_LAYOUT = 1280

    // Sites already asked about this time the app is open, so a check reloading itself doesn't ask again
    private val askedNow = HashSet<String>()
    private var showing: AlertDialog? = null

    fun answer(context: Context): Int = prefs(context).getInt(KEY_ANSWER, ASK)

    fun setAnswer(context: Context, answer: Int) {
        prefs(context).edit().putInt(KEY_ANSWER, answer).apply()
    }

    /** The sites that get the phone's identity in desktop mode. */
    fun sites(context: Context): Set<String> = prefs(context).getStringSet(KEY_SITES, emptySet()).orEmpty()

    fun forgetSites(context: Context) {
        prefs(context).edit().remove(KEY_SITES).apply()
        askedNow.clear()
    }

    /** The site [host] is filed under: without "www.", so it covers the site's other pages too. */
    fun siteOf(host: String?): String = host.orEmpty().lowercase().removePrefix("www.")

    /** Whether [host] gets the phone's identity in desktop mode. */
    fun usesMobile(context: Context, host: String?): Boolean {
        val site = siteOf(host)
        if (site.isEmpty()) return false
        return sites(context).any { site == it || site.endsWith(".$it") }
    }

    /**
     * Whether [url], fetched for a page, is Cloudflare's check page at work. Its own scripts come from
     * the site's /cdn-cgi/challenge-platform/ under "orchestrate"; the bot scripts Cloudflare adds to
     * ordinary pages are under "scripts" and don't count.
     */
    fun isCheck(url: Uri): Boolean {
        val path = url.path ?: return false
        return path.startsWith("/cdn-cgi/challenge-platform/") && path.contains("/orchestrate/")
    }

    /** Desktop mode met the check on [host]: switch, ask, or leave it, as the user chose. */
    fun met(activity: Activity, web: DeckWebView, host: String) {
        val site = siteOf(host)
        if (site.isEmpty() || usesMobile(activity, site) || activity.isFinishing) return
        when (answer(activity)) {
            KEEP_DESKTOP -> return
            ALWAYS_MOBILE -> {
                useMobile(activity, web, site)
                Toast.makeText(activity, "Using the mobile version of $site, as its Cloudflare check doesn't accept desktop mode.", Toast.LENGTH_LONG).show()
            }
            else -> ask(activity, web, site)
        }
    }

    private fun ask(activity: Activity, web: DeckWebView, site: String) {
        if (site in askedNow || showing?.isShowing == true) return
        askedNow += site
        val dontAsk = CheckBox(activity).apply {
            text = "Don't ask again"
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            buttonTintList = android.content.res.ColorStateList.valueOf(CosmicAccent.color)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        showing = AlertDialog.Builder(activity)
            .setTitle("Cloudflare check")
            .setMessage(
                "$site checks that you're human, and its check never accepts desktop mode from an app, " +
                    "so it will keep asking.\n\nUse the mobile version of this site instead? Its pages still open wide, " +
                    "though some sites send their phone pages to it."
            )
            .setView(dialogField(dontAsk))
            .setPositiveButton("Use mobile version") { _, _ ->
                if (dontAsk.isChecked) setAnswer(activity, ALWAYS_MOBILE)
                useMobile(activity, web, site)
            }
            .setNegativeButton("Keep desktop") { _, _ ->
                if (dontAsk.isChecked) setAnswer(activity, KEEP_DESKTOP)
            }
            .setOnDismissListener { showing = null }
            .showCosmic()
    }

    private fun useMobile(context: Context, web: DeckWebView, site: String) {
        prefs(context).edit().putStringSet(KEY_SITES, sites(context) + site).apply()
        web.url?.let { web.beforeNavigation(it) }
        web.reload()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
}
