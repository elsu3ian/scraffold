package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Chooses whose cookies and site storage a page uses. Web apps and the browsers share one set, so
 * signing in once (to Google, say) carries everywhere. An app set to keep its own login gets a set
 * of its own instead, so a second account on the same site can sit beside the first, and removing
 * that app logs it out. On a WebView too old for profiles, everything shares the default one.
 */
@SuppressLint("RequiresFeature")
object AppProfiles {

    private const val PREFS_NAME = "scraffold_profiles"
    private const val KEY_PENDING_DELETE = "pending_delete"
    private const val KEY_OWN_LOGIN = "own_login"

    /** Whether an app can keep a login of its own on this device. */
    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun profileName(appId: String) = "app_$appId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun keepsOwnLogin(context: Context, appId: String): Boolean {
        return appId in prefs(context).getStringSet(KEY_OWN_LOGIN, emptySet()).orEmpty()
    }

    /** Takes effect the next time the app opens. Turning it back on brings back the login it kept. */
    fun setKeepsOwnLogin(context: Context, appId: String, own: Boolean) {
        val apps = prefs(context).getStringSet(KEY_OWN_LOGIN, emptySet()).orEmpty()
        prefs(context).edit().putStringSet(KEY_OWN_LOGIN, if (own) apps + appId else apps - appId).apply()
    }

    // Null for the shared set, which is the WebView's default profile
    private fun ownProfile(context: Context, appId: String?): String? {
        if (!supported || appId.isNullOrEmpty() || !keepsOwnLogin(context, appId)) return null
        return profileName(appId)
    }

    /** Must run before the WebView loads anything or has a JavaScript interface added. */
    fun attach(webView: WebView, appId: String?) {
        val name = ownProfile(webView.context, appId) ?: return
        val profile = ProfileStore.getInstance().getOrCreateProfile(name)
        WebViewCompat.setProfile(webView, name)
        ServiceWorkerBlocker.watch(webView.context, profile, appId)
    }

    fun cookies(context: Context, appId: String?): CookieManager {
        val name = ownProfile(context, appId) ?: return CookieManager.getInstance()
        return ProfileStore.getInstance().getOrCreateProfile(name).cookieManager
    }

    /** Signs the shared set out of everything. Apps that keep their own login are left alone. */
    fun clearShared() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }

    /** Erases a removed app's own logins and site data. A profile still in use is retried on the next launch. */
    fun delete(context: Context, appId: String) {
        setKeepsOwnLogin(context, appId, false)
        if (!supported) return
        val pending = prefs(context).getStringSet(KEY_PENDING_DELETE, emptySet()).orEmpty()
        val name = profileName(appId)
        try {
            ProfileStore.getInstance().deleteProfile(name)
        } catch (e: IllegalStateException) {
            prefs(context).edit().putStringSet(KEY_PENDING_DELETE, pending + name).apply()
        }
    }

    fun deletePending(context: Context) {
        if (!supported) return
        val pending = prefs(context).getStringSet(KEY_PENDING_DELETE, emptySet()).orEmpty()
        if (pending.isEmpty()) return
        val remaining = pending.filterNot { name ->
            try {
                ProfileStore.getInstance().deleteProfile(name)
                true
            } catch (e: IllegalStateException) {
                false
            }
        }
        prefs(context).edit().putStringSet(KEY_PENDING_DELETE, remaining.toSet()).apply()
    }
}
