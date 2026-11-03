package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.settings.GlobalWebSettingsStore

/**
 * The shield for requests a site's service worker makes, which never reach a page's WebViewClient.
 * A worker has no page, so the shield setting comes from the app whose profile it runs in, or, in
 * the shared profile, the app whose site it belongs to. Anything else follows the default settings,
 * as the Quick Browser does.
 */
object ServiceWorkerBlocker {

    private class App(val host: String, val adblock: Boolean?)

    // Workers belong to the whole process, so one client per profile, set up once
    private val watched = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Kept current from the database, so a request only reads memory. A null switch follows the defaults.
    @Volatile
    private var apps: Map<String, App> = emptyMap()

    /** Call on the main thread with the profile a page runs in, or null for the shared one. */
    @SuppressLint("RequiresFeature")
    fun watch(context: Context, profile: Profile?, appId: String?) {
        val name = profile?.name ?: Profile.DEFAULT_PROFILE_NAME
        val appContext = context.applicationContext
        synchronized(watched) {
            if (!watched.add(name)) return
            if (watched.size == 1) followApps(appContext)
        }
        val engine = AdBlockEngine.getInstance(appContext)
        val controller = profile?.serviceWorkerController ?: ServiceWorkerController.getInstance()
        controller.setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                val app = (appId ?: appFor(request))?.let { apps[it] }
                val adblock = app?.adblock ?: GlobalWebSettingsStore.adblock(appContext)
                return if (adblock) engine.intercept(request, app?.host) else null
            }
        })
    }

    private fun followApps(context: Context) {
        scope.launch {
            AppDatabase.getInstance(context).webAppDao().getAllWebApps().collect { list ->
                apps = list.associate { app ->
                    app.id to App(siteHost(app.url).orEmpty(), app.adblockEnabled.takeIf { app.overrideGlobal })
                }
            }
        }
    }

    // A worker's requests carry its site as the referrer; without one, the request's own site is the best guess
    private fun appFor(request: WebResourceRequest): String? {
        val host = request.requestHeaders["Referer"]?.let(::siteHost) ?: request.url.host?.lowercase()?.removePrefix("www.")
            ?: return null
        val current = apps
        return current.entries.firstOrNull { it.value.host == host }?.key
            ?: current.entries.firstOrNull { it.value.host.isNotEmpty() && host.endsWith(".${it.value.host}") }?.key
    }

    private fun siteHost(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
}
