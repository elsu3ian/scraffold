package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import net.mangadot.scaffold.data.settings.WebAppSettings

/**
 * Custom hardware-accelerated Chromium WebView tailored for high-density PWA execution.
 */
class DeckWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    private val defaultMobileUserAgent: String by lazy {
        settings.userAgentString
    }

    companion object {
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }

    init {
        configureEngine()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureEngine() {
        // Enforce hardware acceleration
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isScrollbarFadingEnabled = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            
            // Layout & Viewport
            useWideViewPort = true
            loadWithOverviewMode = true

            // Pinch-To-Zoom (gestures on, on-screen +/- buttons off)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Caching & Storage
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            
            // Multi-window & External Popups
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
    }

    /**
     * Applies an app's settings. Call before loading a page.
     */
    fun applySettings(webSettings: WebAppSettings) {
        settings.apply {
            javaScriptEnabled = webSettings.javascript
            setGeolocationEnabled(webSettings.allowLocation)
            mixedContentMode = if (webSettings.allowHttp) {
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            setSupportZoom(webSettings.pinchZoom)
            builtInZoomControls = webSettings.pinchZoom

            if (webSettings.desktop) {
                userAgentString = DESKTOP_USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                userAgentString = defaultMobileUserAgent
            }

            // Data Saver: Block image payloads
            blockNetworkImage = webSettings.dataSaver
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(webSettings.acceptCookies)
        cookies.setAcceptThirdPartyCookies(this, webSettings.thirdPartyCookies)
    }

    override fun destroy() {
        stopLoading()
        clearHistory()
        loadUrl("about:blank")
        super.destroy()
    }
}
