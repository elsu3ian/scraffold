package net.mangadot.scaffold.ui.container

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.engine.DeckWebView
import net.mangadot.scaffold.engine.ManifestInstall

/**
 * Keeps an app's icon, name and Recents card up to date from what its pages offer. [current] is the
 * app as the screen knows it, and [update] hands back a changed copy.
 */
class AppIdentity(
    private val activity: ComponentActivity,
    private val repository: WebAppRepository,
    private val web: DeckWebView,
    private val current: () -> WebAppEntity?,
    private val update: (WebAppEntity) -> Unit
) {
    private var iconLookup: Job? = null
    private var iconLookups = 0
    private var manifestReads = 0

    fun showInRecents(app: WebAppEntity) {
        if (app.name.isEmpty()) return
        activity.lifecycleScope.launch {
            val icon = withContext(Dispatchers.IO) {
                app.iconUrl?.let(BitmapFactory::decodeFile)?.let { Bitmap.createScaledBitmap(it, RECENTS_ICON_PX, RECENTS_ICON_PX, true) }
            }
            @Suppress("DEPRECATION")
            activity.setTaskDescription(ActivityManager.TaskDescription(app.name, icon))
        }
    }

    /** Sites that refuse the shelf's own icon fetch still hand their favicon to the page they load. */
    fun storeFavicon(icon: Bitmap) {
        val app = current()?.takeIf { it.iconUrl == null && it.name.isNotEmpty() } ?: return
        update(app.copy(iconUrl = ""))
        activity.lifecycleScope.launch {
            repository.updateIconUrl(app.id, AppIcons.save(activity, app.id, icon))
        }
    }

    fun upgradeIcon() {
        val app = current()?.takeIf { it.name.isNotEmpty() } ?: return
        if (iconLookups >= MAX_LOOKUPS) return
        iconLookups++
        // A newer page replaces the last lookup, which may have been on a security check that navigated away
        iconLookup?.cancel()
        iconLookup = activity.lifecycleScope.launch {
            val icon = AppIcons.betterIconFromPage(activity, web, app)
            if (icon != null) {
                val path = AppIcons.save(activity, app.id, icon)
                repository.updateIconUrl(app.id, path)
                current()?.let { update(it.copy(iconUrl = path)) }
            }
        }
    }

    /**
     * For an app whose site adds its manifest with script, so it couldn't be read when the app was
     * added. [onRead] gets the app with what the manifest says.
     */
    fun readManifest(onRead: (WebAppEntity) -> Unit) {
        val app = current()?.takeIf { it.name.isNotEmpty() && ManifestInstall.isPending(activity, it.id) } ?: return
        if (manifestReads++ >= MAX_LOOKUPS) return
        activity.lifecycleScope.launch {
            val updated = ManifestInstall.fromPage(activity, web, app) ?: return@launch
            ManifestInstall.clear(activity, app.id)
            val latest = repository.getWebAppById(app.id).first() ?: return@launch
            val merged = latest.copy(
                name = updated.name, startUrl = updated.startUrl, scope = updated.scope, display = updated.display,
                themeColor = updated.themeColor, backgroundColor = updated.backgroundColor
            )
            repository.updateWebApp(merged)
            update(merged)
            onRead(merged)
        }
    }

    private companion object {
        const val MAX_LOOKUPS = 6
        const val RECENTS_ICON_PX = 96
    }
}
