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

    val ads = AtomicInteger()
    val trackers = AtomicInteger()
    val popups = AtomicInteger()

    /** Called as a page starts loading [host]. */
    fun enter(host: String?) {
        val next = host?.lowercase()?.removePrefix("www.").orEmpty()
        if (next == site) return
        site = next
        ads.set(0)
        trackers.set(0)
        popups.set(0)
    }
}
