package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.ui.container.AppTasks

/**
 * An app's cache and data, cleared the way a phone's app info clears a real app's. The app stays on
 * the shelf with its settings either way.
 */
object AppData {

    /**
     * Its temporary files, which pages simply download again. Apps that share sign-ins share one
     * cache, so theirs go too. Call on the main thread.
     */
    fun clearCache(context: Context, app: WebAppEntity) {
        val web = WebView(context)
        AppProfiles.attach(web, app.id)
        web.clearCache(true)
        web.destroy()
    }

    /**
     * Signs it out and erases what its site stored on the phone, and where it left off. An app that
     * keeps its own login loses all of it; one that shares sign-ins loses only its own site's, so
     * other apps stay signed in. Call on the main thread.
     */
    @SuppressLint("RequiresFeature")
    fun clearData(context: Context, app: WebAppEntity) {
        // A page left open would carry on with what it has
        AppTasks.restart(context, app.id)
        if (AppProfiles.keepsOwnLogin(context, app.id) && AppProfiles.supported) {
            val profile = ProfileStore.getInstance().getOrCreateProfile(AppProfiles.profileName(app.id))
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
        } else {
            val hosts = hostsOf(app)
            forgetCookies(CookieManager.getInstance(), hosts)
            hosts.forEach { host ->
                WebStorage.getInstance().deleteOrigin("https://$host")
                WebStorage.getInstance().deleteOrigin("http://$host")
            }
        }
        clearCache(context, app)
    }

    /** The app's site, with and without www, and the sites its start page and scope are on. */
    private fun hostsOf(app: WebAppEntity): Set<String> {
        return listOfNotNull(app.url, app.startUrl, app.scope)
            .mapNotNull { Uri.parse(it).host?.lowercase() }
            .flatMap { host -> listOf(host.removePrefix("www."), "www." + host.removePrefix("www.")) + host }
            .toSet()
    }

    /**
     * Cookies can't be listed by site, only read as a page would see them, so each one the site
     * sees is set again already expired: on the site itself and on the domains above it.
     */
    private fun forgetCookies(cookies: CookieManager, hosts: Set<String>) {
        for (host in hosts) {
            val url = "https://$host/"
            val names = cookies.getCookie(url)?.split(';')
                ?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
                .orEmpty()
            val domains = listOf<String?>(null) + parentDomains(host).map { ".$it" }
            for (name in names) {
                for (domain in domains) {
                    val expired = "$name=; Max-Age=0; Path=/" + (domain?.let { "; Domain=$it" } ?: "")
                    cookies.setCookie(url, expired)
                    cookies.setCookie(url, "$expired; Secure")
                }
            }
        }
        cookies.flush()
    }

    // www.comick.dev gives www.comick.dev and comick.dev; a bare suffix like dev is left out
    private fun parentDomains(host: String): List<String> {
        val labels = host.split('.')
        return (0 until labels.size - 1).map { labels.drop(it).joinToString(".") }
    }
}
