package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import net.mangadot.scaffold.data.settings.WebAppSettings

/**
 * Custom hardware-accelerated Chromium WebView tailored for high-density PWA execution.
 */
class DeckWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

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
        private val CHROME_MAJOR = Regex("Chrome/(\\d+)")

        @Volatile
        private var engineUserAgent: String? = null

        // Service workers have one set of settings for the whole app
        private var workersQuiet = false

        /** What Chrome on a phone sends, so sites see a browser rather than WebView's "wv" and "Version/4.0" marks. */
        fun mobileUserAgent(context: Context): String =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor(context)}.0.0.0 Mobile Safari/537.36"

        fun desktopUserAgent(context: Context): String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor(context)}.0.0.0 Safari/537.36"

        // Chrome's own reduced user agent, which carries only the major version of the engine underneath
        private fun chromeMajor(context: Context): String {
            val engine = engineUserAgent ?: WebSettings.getDefaultUserAgent(context).also { engineUserAgent = it }
            return CHROME_MAJOR.find(engine)?.groupValues?.get(1) ?: "130"
        }

        // Where a page asks for brands in script, WebView names itself; Chrome names itself instead
        private const val BRANDS_SCRIPT = """
(function () {
  var proto = window.NavigatorUAData && NavigatorUAData.prototype;
  if (!proto) return;
  var fix = function (list) {
    if (!Array.isArray(list)) return list;
    var webview = list.filter(function (b) { return b && b.brand === 'Android WebView'; })[0];
    if (!webview) return list;
    return list.filter(function (b) { return b !== webview; }).concat([{ brand: 'Google Chrome', version: webview.version }]);
  };
  var brands = Object.getOwnPropertyDescriptor(proto, 'brands');
  if (brands && brands.get) {
    Object.defineProperty(proto, 'brands', { get: function () { return fix(brands.get.call(this)); }, configurable: true, enumerable: true });
  }
  var high = proto.getHighEntropyValues;
  if (high) {
    proto.getHighEntropyValues = function () {
      return high.apply(this, arguments).then(function (values) {
        if (values) {
          if (values.brands) values.brands = fix(values.brands);
          if (values.fullVersionList) values.fullVersionList = fix(values.fullVersionList);
        }
        return values;
      });
    };
  }
  var toJSON = proto.toJSON;
  if (toJSON) {
    proto.toJSON = function () {
      var values = toJSON.apply(this, arguments);
      if (values && values.brands) values.brands = fix(values.brands);
      return values;
    };
  }
})();
"""
    }

    init {
        configureEngine()
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
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

            userAgentString = mobileUserAgent(context)
        }

        // WebView otherwise names this app to every site in an X-Requested-With header
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            if (!workersQuiet && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
                ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings.setRequestedWithHeaderOriginAllowList(emptySet())
                workersQuiet = true
            }
        }
    }

    /** Has scripts on the page see Chrome too. Call after [AppProfiles.attach], which a page script would block. */
    @SuppressLint("RequiresFeature")
    fun presentAsBrowser() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, BRANDS_SCRIPT, setOf("*"))
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
                userAgentString = desktopUserAgent(context)
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                userAgentString = mobileUserAgent(context)
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
