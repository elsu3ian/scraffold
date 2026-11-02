package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import net.mangadot.scaffold.data.db.entity.WebAppEntity

/**
 * Custom hardware-accelerated Chromium WebView tailored for high-density PWA execution.
 */
class DeckWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
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
     * Reconfigures runtime flags (Desktop UA, Data Saver, etc.) when settings change
     */
    fun applyWebAppConfig(webApp: WebAppEntity) {
        settings.apply {
            // Desktop User-Agent Mode
            if (webApp.desktopMode) {
                userAgentString = DESKTOP_USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                userAgentString = defaultMobileUserAgent
            }

            // Data Saver: Block image payloads on metered connections
            blockNetworkImage = webApp.dataSaver
        }
    }

    override fun destroy() {
        stopLoading()
        clearHistory()
        loadUrl("about:blank")
        super.destroy()
    }
}
