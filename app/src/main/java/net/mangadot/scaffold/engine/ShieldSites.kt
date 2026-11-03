package net.mangadot.scaffold.engine

import android.content.Context

/**
 * Sites the person turned the shield off for, from the Shield meter. Off for a site means no
 * blocking, hiding or scriptlets on its pages or its subdomains', whatever the app's own setting.
 * Asked from WebView's network threads, so it answers from memory.
 */
object ShieldSites {

    private const val PREFS_NAME = "scraffold_shield_sites"
    private const val KEY_OFF = "off"

    @Volatile
    private var off: Set<String>? = null

    /** How a host is written here, and in [ShieldStats.site]. */
    fun site(host: String?): String = host?.lowercase()?.removePrefix("www.").orEmpty()

    fun isOff(context: Context, host: String?): Boolean {
        val site = site(host)
        if (site.isEmpty()) return false
        val sites = load(context)
        return sites.isNotEmpty() && (site in sites || sites.any { site.endsWith(".$it") })
    }

    fun setOff(context: Context, host: String?, turnedOff: Boolean) {
        val site = site(host)
        if (site.isEmpty()) return
        synchronized(this) {
            val next = if (turnedOff) load(context) + site else load(context) - site
            off = next
            prefs(context).edit().putStringSet(KEY_OFF, next).apply()
        }
    }

    private fun load(context: Context): Set<String> {
        off?.let { return it }
        return synchronized(this) {
            off ?: prefs(context).getStringSet(KEY_OFF, emptySet()).orEmpty().toSet().also { off = it }
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
