package net.mangadot.scaffold.ui.container

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.mangadot.scaffold.databinding.LayoutDialogShieldBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.DeckWebView
import net.mangadot.scaffold.engine.ShieldSites
import net.mangadot.scaffold.ui.theme.showCosmic

/**
 * What the shield has stopped on the site on screen, kept up to date while it's open. From here
 * the shield can be turned off for just that site, and the element picker started.
 */
object ShieldMeter {

    private const val REFRESH_MS = 500L

    fun show(activity: AppCompatActivity, web: DeckWebView, shieldOn: Boolean) {
        val engine = AdBlockEngine.getInstance(activity)
        val stats = web.shieldStats
        val view = LayoutDialogShieldBinding.inflate(activity.layoutInflater)
        val site = stats.site

        fun refresh() {
            val siteOn = !ShieldSites.isOff(activity, site)
            view.tvShieldSite.text = stats.site.ifEmpty { "No page open" }
            view.tvShieldAds.text = stats.ads.get().toString()
            view.tvShieldTrackers.text = stats.trackers.get().toString()
            view.tvShieldPopups.text = stats.popups.get().toString()
            view.tvShieldTotal.text = when {
                !shieldOn -> "The shield is off here, so nothing is being blocked"
                !siteOn -> "Turned off for this site, so nothing is being blocked"
                else -> "All sites: ${engine.totalBlockedRequests} blocked"
            }
        }

        // The app's own shield switch comes first; this one only narrows it
        view.switchShieldSite.isChecked = shieldOn && !ShieldSites.isOff(activity, site)
        view.switchShieldSite.isEnabled = shieldOn && site.isNotEmpty()
        view.tvShieldSiteNote.text = when {
            !shieldOn -> "The shield is off for all of this app"
            site.isEmpty() -> "Open a page first"
            else -> "Blocking ads on $site"
        }
        view.switchShieldSite.setOnCheckedChangeListener { _, isChecked ->
            ShieldSites.setOff(activity, site, !isChecked)
            web.shield?.sitesChanged()
            web.reload()
            refresh()
        }

        val ticker = object : Runnable {
            override fun run() {
                refresh()
                view.root.postDelayed(this, REFRESH_MS)
            }
        }
        refresh()
        view.root.postDelayed(ticker, REFRESH_MS)

        AlertDialog.Builder(activity)
            .setTitle("Shield on this site")
            .setView(view.root)
            .setPositiveButton("Done", null)
            .setNeutralButton("Hide an element") { _, _ -> ElementPicker.start(activity, web) }
            .setOnDismissListener { view.root.removeCallbacks(ticker) }
            .showCosmic()
    }
}
