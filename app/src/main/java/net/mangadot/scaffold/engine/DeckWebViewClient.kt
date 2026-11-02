package net.mangadot.scaffold.engine

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.settings.WebAppSettings
import net.mangadot.scaffold.ui.browser.ExternalLinks
import java.io.ByteArrayInputStream

/**
 * High-performance WebViewClient with:
 * - <0.05ms uBlock Origin network ad/telemetry interception
 * - Domain sandboxing (out-of-scope links open in the in-app browser)
 * - Safe intent/deep-link dispatching
 * - Userscript install links
 */
class DeckWebViewClient(
    private val context: Context,
    private val adBlockEngine: AdBlockEngine,
    private val scriptEngine: ScriptEngine,
    private var webApp: WebAppEntity,
    private val onPageFinishedCallback: ((url: String) -> Unit)? = null,
    private val onUserscriptLink: ((url: String) -> Unit)? = null
) : WebViewClient() {

    // Hosts the app may navigate within, and whether its first page load is still resolving
    private var redirectedHost: String = ""
    private var isInitialNavigation = true

    @Volatile
    private var settings = WebAppSettings()

    // Read on the network thread by the third-party filter
    @Volatile
    private var pageHost: String = ""

    fun updateWebApp(updated: WebAppEntity, webSettings: WebAppSettings) {
        this.webApp = updated
        this.settings = webSettings
        this.pageHost = normalizeHost(Uri.parse(updated.url).host)
        this.redirectedHost = ""
        this.isInitialNavigation = true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val uri = request.url

        // 1. uBlock Origin Network Request Interception
        if (settings.adblock && adBlockEngine.shouldBlock(uri)) {
            adBlockEngine.recordBlockedForApp(webApp.id)
            return createEmptyResponse()
        }

        // 2. Third-party requests
        if (settings.blockThirdParty && !request.isForMainFrame && isThirdParty(uri)) {
            return createEmptyResponse()
        }

        // 3. Data Saver: Block image media if Data Saver is enabled
        if (settings.dataSaver) {
            val path = uri.path?.lowercase() ?: ""
            if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".avif")) {
                return createEmptyResponse()
            }
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase() ?: ""

        // Userscript links open the install prompt, as Tampermonkey does
        if (request.isForMainFrame && uri.path?.endsWith(".user.js", ignoreCase = true) == true && onUserscriptLink != null) {
            onUserscriptLink.invoke(uri.toString())
            return true
        }

        // With Allow HTTP off, plain links are upgraded rather than loaded, unless the app itself was saved as http
        if (scheme == "http" && !settings.allowHttp && request.isForMainFrame && !webApp.url.startsWith("http://", ignoreCase = true)) {
            view.loadUrl(uri.buildUpon().scheme("https").build().toString())
            return true
        }

        // Handle non-http/https schemes and native deep-links (tg://, discord://, intent://, market://, etc.)
        if (scheme != "http" && scheme != "https" && scheme.isNotEmpty()) {
            ExternalLinks.openNative(context, uri)
            return true
        }

        // Domain Sandboxing: out-of-scope URLs open in the in-app browser, over the app
        if ((scheme == "http" || scheme == "https") && !staysInApp(uri)) {
            // The site's own launch redirect moved it to a new domain: keep the app in the container
            if (isInitialNavigation && request.isForMainFrame && request.isRedirect) {
                redirectedHost = normalizeHost(uri.host)
                return false
            }
            ExternalLinks.open(context, uri)
            return true
        }

        return false
    }

    /** Whether a link belongs inside the container, rather than in the system browser. */
    fun staysInApp(uri: Uri): Boolean {
        if (!webApp.openExternalLinks) return true
        val appBaseHost = normalizeHost(Uri.parse(webApp.url).host)
        val requestHost = normalizeHost(uri.host)
        if (appBaseHost.isEmpty() || requestHost.isEmpty()) return true
        return isSameSite(requestHost, appBaseHost) ||
            (redirectedHost.isNotEmpty() && isSameSite(requestHost, redirectedHost))
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        pageHost = normalizeHost(Uri.parse(url).host)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        isInitialNavigation = false

        scriptEngine.onPageFinished(view)

        onPageFinishedCallback?.invoke(url)
    }

    /** Compares the last two host labels, so a site's own subdomains and CDN hosts on it stay first-party. */
    private fun isThirdParty(uri: Uri): Boolean {
        val host = normalizeHost(uri.host)
        if (host.isEmpty() || pageHost.isEmpty()) return false
        return host.split('.').takeLast(2) != pageHost.split('.').takeLast(2)
    }

    private fun normalizeHost(host: String?): String {
        return host?.lowercase()?.removePrefix("www.") ?: ""
    }

    private fun isSameSite(requestHost: String, baseHost: String): Boolean {
        return requestHost == baseHost || requestHost.endsWith(".$baseHost")
    }

    private fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
