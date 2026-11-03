package net.mangadot.scaffold.ui.container

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import net.mangadot.scaffold.databinding.LayoutDialogShieldBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.ShieldStats
import net.mangadot.scaffold.ui.theme.showCosmic

/** What the shield has stopped on the site on screen, kept up to date while it's open. */
object ShieldMeter {

    private const val REFRESH_MS = 500L

    fun show(activity: Activity, stats: ShieldStats, shieldOn: Boolean) {
        val engine = AdBlockEngine.getInstance(activity)
        val view = LayoutDialogShieldBinding.inflate(activity.layoutInflater)

        fun refresh() {
            view.tvShieldSite.text = stats.site.ifEmpty { "No page open" }
            view.tvShieldAds.text = stats.ads.get().toString()
            view.tvShieldTrackers.text = stats.trackers.get().toString()
            view.tvShieldPopups.text = stats.popups.get().toString()
            view.tvShieldTotal.text = if (shieldOn) {
                "All sites: ${engine.totalBlockedRequests} blocked · ${engine.ruleCount} rules"
            } else {
                "The shield is off here, so nothing is being blocked"
            }
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
            .setOnDismissListener { view.root.removeCallbacks(ticker) }
            .showCosmic()
    }
}
