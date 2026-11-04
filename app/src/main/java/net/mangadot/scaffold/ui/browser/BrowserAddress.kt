package net.mangadot.scaffold.ui.browser

import android.net.Uri
import android.util.Patterns

/** What the browser treats as a page, a sign-in, or a search, from what's typed or loaded. */
object BrowserAddress {

    private const val SEARCH_URL = "https://www.google.com/search?q="
    private val SIGN_IN_HOST = Regex("^(accounts|login|signin|auth|sso)\\.",RegexOption.IGNORE_CASE)
    // Cloudflare's "Just a moment" check adds this to the address while it runs
    private const val SECURITY_CHECK_TOKEN = "__cf_chl"
    private val SECRET_IN_URL = Regex("[?&#](code|state|token|access_token|id_token|password)=", RegexOption.IGNORE_CASE)

    fun isWebPage(url: String?) =
        url != null && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))

    /**
     * These carry one-time login codes, or a security check's one-time token, which don't belong in
     * history or in a backup file.
     */
    fun isSignIn(url: String): Boolean {
        return SIGN_IN_HOST.containsMatchIn(Uri.parse(url).host.orEmpty()) || SECRET_IN_URL.containsMatchIn(url) ||
            url.contains(SECURITY_CHECK_TOKEN)
    }

    /** An address opens as it is, and anything else, javascript: included, is searched for. */
    fun addressFor(text: String): String = when {
        isWebPage(text) -> text
        !text.contains(' ') && !text.contains(':') && Patterns.WEB_URL.matcher(text).matches() -> "https://$text"
        else -> SEARCH_URL + Uri.encode(text)
    }
}
