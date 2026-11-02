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

    var media: BackgroundMedia? = null

    /** While on, leaving the app doesn't suspend this page, so what it's playing carries on. */
    var keepRunningHidden = false
        set(value) {
            if (field == value) return
            field = value
            setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, !value)
            applyVisibility()
            if (!value) super.onVisibilityChanged(this, visibility)
        }

    private var windowVisibility = View.GONE
    private var appliedWindowVisibility = -1
    private var pausedByActivity = false
    private var pauseApplied = false

    val isOnScreen: Boolean
        get() = windowVisibility == View.VISIBLE && visibility == View.VISIBLE

    // Being swapped out for another tab hides a page the same way leaving the app does
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (keepRunningHidden && changedView === this && visibility != View.VISIBLE) return
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) media?.let(MediaPlaybackService::onWindowChanged)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        windowVisibility = visibility
        applyVisibility()
        media?.let(MediaPlaybackService::onWindowChanged)
    }

    override fun onPause() {
        pausedByActivity = true
        applyVisibility()
    }

    override fun onResume() {
        pausedByActivity = false
        applyVisibility()
    }

    // WebView suspends a page once it believes the page is hidden, so while media plays it isn't told
    private fun applyVisibility() {
        val shown = if (keepRunningHidden) View.VISIBLE else windowVisibility
        if (shown != appliedWindowVisibility) {
            appliedWindowVisibility = shown
            super.onWindowVisibilityChanged(shown)
        }
        val pause = pausedByActivity && !keepRunningHidden
        if (pause != pauseApplied) {
            pauseApplied = pause
            if (pause) super.onPause() else super.onResume()
        }
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
    fun applySettings(webSettings: WebAppSettings, cookies: CookieManager) {
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

        cookies.setAcceptCookie(webSettings.acceptCookies)
        cookies.setAcceptThirdPartyCookies(this, webSettings.thirdPartyCookies)
    }

    override fun destroy() {
        media?.let(MediaPlaybackService::onEnded)
        stopLoading()
        clearHistory()
        loadUrl("about:blank")
        super.destroy()
    }
}
