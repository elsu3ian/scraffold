package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Gives each web app its own cookies and site storage, so two accounts on one site can coexist
 * and removing an app logs it out. On a WebView too old for profiles, apps share the default one.
 */
@SuppressLint("RequiresFeature")
object AppProfiles {

    private const val PREFS_NAME = "scraffold_profiles"
    private const val KEY_PENDING_DELETE = "pending_delete"

    private val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun profileName(appId: String) = "app_$appId"

    /** Must run before the WebView loads anything or has a JavaScript interface added. */
    fun attach(webView: WebView, appId: String) {
        if (!supported || appId.isEmpty()) return
        val name = profileName(appId)
        ProfileStore.getInstance().getOrCreateProfile(name)
        WebViewCompat.setProfile(webView, name)
    }

    fun cookies(appId: String?): CookieManager {
        if (!supported || appId.isNullOrEmpty()) return CookieManager.getInstance()
        return ProfileStore.getInstance().getOrCreateProfile(profileName(appId)).cookieManager
    }

    /** Signs a profile out of everything. Returns false where there is no separate profile to clear. */
    fun clear(appId: String): Boolean {
        if (!supported) return false
        val profile = ProfileStore.getInstance().getOrCreateProfile(profileName(appId))
        profile.cookieManager.removeAllCookies(null)
        profile.webStorage.deleteAllData()
        return true
    }

    /** Erases a removed app's logins and site data. A profile still in use is retried on the next launch. */
    fun delete(context: Context, appId: String) {
        if (!supported) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(KEY_PENDING_DELETE, emptySet()).orEmpty()
        val name = profileName(appId)
        try {
            ProfileStore.getInstance().deleteProfile(name)
        } catch (e: IllegalStateException) {
            prefs.edit().putStringSet(KEY_PENDING_DELETE, pending + name).apply()
        }
    }

    fun deletePending(context: Context) {
        if (!supported) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(KEY_PENDING_DELETE, emptySet()).orEmpty()
        if (pending.isEmpty()) return
        val remaining = pending.filterNot { name ->
            try {
                ProfileStore.getInstance().deleteProfile(name)
                true
            } catch (e: IllegalStateException) {
                false
            }
        }
        prefs.edit().putStringSet(KEY_PENDING_DELETE, remaining.toSet()).apply()
    }
}
