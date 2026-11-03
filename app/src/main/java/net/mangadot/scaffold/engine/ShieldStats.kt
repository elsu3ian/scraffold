package net.mangadot.scaffold.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * What the shield has stopped on the site a page is showing. Counts carry on while the page moves
 * around one site, and start over when it goes to another. Counted from WebView's network threads.
 */
class ShieldStats {

    @Volatile
    var site: String = ""
        private set

    /**
     * The site the page is on or heading to. It changes as soon as a new page is asked for, ahead
     * of [enter], so the page's own first moments already go by that site's [ShieldSites] switch.
     */
    @Volatile
    var top: String = ""
        private set

    val ads = AtomicInteger()
    val trackers = AtomicInteger()
    val popups = AtomicInteger()

    /** Called as a page starts loading [host]. */
    fun enter(host: String?) {
        val next = ShieldSites.site(host)
        top = next
        if (next == site) return
        site = next
        ads.set(0)
        trackers.set(0)
        popups.set(0)
    }

    /** Called when the page in this view asks for a new address on [host]. */
    fun heading(host: String?) {
        top = ShieldSites.site(host)
    }
}
