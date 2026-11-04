package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
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
    var shield: PageShield? = null
    /** The app this page belongs to, whose login its downloads use again when tried once more. */
    var appId: String? = null
    /** Saves the files a page makes itself, which only it can read out. */
    var files: PageFiles? = null
        private set
    val shieldStats = ShieldStats()

    /** While on, leaving the app doesn't suspend this page, so what it's playing carries on. */
    var keepRunningHidden = false
        set(value) {
            if (field == value) return
            field = value
            setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, !value)
            applyVisibility()
            if (!value) super.onVisibilityChanged(this, visibility)
        }

    /**
     * Desktop sites get the user agent Chrome on a phone sends for them, and client hints that say the
     * same, except on sites whose Cloudflare check was answered with the mobile version ([DesktopFallback]).
     */
    @Volatile
    var desktopMode = false
        set(value) {
            field = value
            if (!value) unwiden()
            beforeNavigation(url.orEmpty())
        }

    // Whether pages are being told this is a desktop, read on the network thread
    @Volatile
    private var presentedDesktop = false

    // Per site, the script that lays its phone pages out wide in desktop mode
    private val widened = HashMap<String, androidx.webkit.ScriptHandler>()

    // The page's own host, which decides the identity, read on the network thread
    @Volatile
    private var pageHost = ""

    /**
     * Call before a page of [url] loads: in desktop mode, a site answered with the mobile version gets
     * the phone's identity, everything else the desktop's. Returns whether the identity changed.
     */
    fun beforeNavigation(url: String): Boolean {
        val host = android.net.Uri.parse(url).host
        if (!host.isNullOrEmpty()) pageHost = host
        val mobileHere = desktopMode && DesktopFallback.usesMobile(context, host)
        val desktop = desktopMode && !mobileHere
        val changed = desktop != presentedDesktop
        if (changed) presentAs(desktop)
        if (mobileHere) widen(DesktopFallback.siteOf(host))
        return changed
    }

    /**
     * Call from onPageStarted. Pages brought back by Android, and history and form navigations, start
     * without [beforeNavigation]; one that started with the wrong identity loads again with the right one.
     */
    fun pageStarted(url: String) {
        files?.pageStarted()
        if (beforeNavigation(url)) {
            stopLoading()
            reload()
        }
    }

    /** Call from shouldInterceptRequest: notices Cloudflare's check turning desktop mode away. */
    fun watchRequest(request: android.webkit.WebResourceRequest) {
        if (!presentedDesktop || !DesktopFallback.isCheck(request.url)) return
        // The page's site is the one to switch, where the check is in a frame from somewhere else
        val host = pageHost.ifEmpty { request.url.host.orEmpty() }
        if (host.isEmpty()) return
        post {
            var owner = context
            while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner = owner.baseContext
            (owner as? android.app.Activity)?.let { DesktopFallback.met(it, this, host) }
        }
    }

    // A phone page fills a desktop's width when its viewport says so; the site's own viewport is overruled
    @SuppressLint("RequiresFeature")
    private fun widen(site: String) {
        if (site.isEmpty() || site in widened || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = WIDE_SCRIPT.replace("@WIDTH@", DesktopFallback.WIDE_LAYOUT.toString())
        widened[site] = WebViewCompat.addDocumentStartJavaScript(this, script, setOf("https://$site", "https://*.$site"))
    }

    private fun unwiden() {
        widened.values.forEach { it.remove() }
        widened.clear()
    }

    /** Whether this touch has pulled the page down past its top, which a pull to reload waits for. */
    var pulledPastTop = false
        private set

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pulledPastTop = false
        return super.dispatchTouchEvent(event)
    }

    // Only the page as a whole reports this, not a box scrolling inside it, so it's the same top Chrome pulls from
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) pulledPastTop = true
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

        /** What Chrome on a phone sends, so sites see a browser rather than WebView's "wv" and "Version/4.0" marks. */
        fun mobileUserAgent(context: Context): String =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor(context)}.0.0.0 Mobile Safari/537.36"

        /**
         * Chrome on Linux, on this phone's own processor, so it reads the same as what the page itself
         * finds in navigator.platform. Claiming Windows, or an Intel chip on an ARM phone, had
         * Cloudflare's check see the mismatch and ask again and again.
         */
        fun desktopUserAgent(context: Context): String =
            "Mozilla/5.0 (X11; Linux ${cpu.machine}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor(context)}.0.0.0 Safari/537.36"

        /** How Linux names the processor ([machine]) and how client hints do ([architecture], [bitness]). */
        private class Cpu(val machine: String, val architecture: String, val bitness: String)

        // Pages run in a process as wide as this app's, which is what navigator.platform reports
        private val cpu: Cpu by lazy {
            val wide = Process.is64Bit()
            val abi = (if (wide) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).firstOrNull().orEmpty()
            when {
                abi.startsWith("arm64") -> Cpu("aarch64", "arm", "64")
                // A 32-bit app on a 64-bit ARM chip
                abi.startsWith("armeabi") && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() -> Cpu("armv8l", "arm", "32")
                abi.startsWith("armeabi") -> Cpu("armv7l", "arm", "32")
                abi == "x86" -> Cpu("i686", "x86", "32")
                else -> Cpu("x86_64", "x86", "64")
            }
        }

        // Chrome's own reduced user agent, which carries only the major version of the engine underneath
        private fun chromeMajor(context: Context): String {
            val engine = engineUserAgent ?: WebSettings.getDefaultUserAgent(context).also { engineUserAgent = it }
            return CHROME_MAJOR.find(engine)?.groupValues?.get(1) ?: "130"
        }

        @Volatile
        private var engineVersion: String? = null

        private fun chromeFullVersion(context: Context): String {
            engineVersion?.let { return it }
            val major = chromeMajor(context)
            val version = WebViewCompat.getCurrentWebViewPackage(context)?.versionName
                ?.takeIf { it.startsWith("$major.") } ?: "$major.0.0.0"
            return version.also { engineVersion = it }
        }

        /**
         * The brands Chrome itself names: Google Chrome, Chromium and a made-up one, ordered and
         * spelled the way Chrome works them out from its major version. Each is brand, major, full version.
         */
        private fun chromeBrands(context: Context): List<Triple<String, String, String>> {
            val major = chromeMajor(context)
            val full = chromeFullVersion(context)
            val seed = major.toIntOrNull() ?: 0
            val grease = "Not${GREASE_CHARS[seed % GREASE_CHARS.size]}A${GREASE_CHARS[(seed + 1) % GREASE_CHARS.size]}Brand"
            val greaseMajor = GREASE_VERSIONS[seed % GREASE_VERSIONS.size]
            val order = GREASE_ORDERS[seed % GREASE_ORDERS.size]
            val brands = arrayOfNulls<Triple<String, String, String>>(3)
            brands[order[0]] = Triple(grease, greaseMajor, "$greaseMajor.0.0.0")
            brands[order[1]] = Triple("Chromium", major, full)
            brands[order[2]] = Triple("Google Chrome", major, full)
            return brands.filterNotNull()
        }

        private val GREASE_CHARS = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        private val GREASE_VERSIONS = listOf("8", "99", "24")
        private val GREASE_ORDERS = listOf(
            intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
            intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0)
        )

        /** What client hints say, in the Sec-CH-UA headers and navigator.userAgentData alike. */
        private fun chromeMetadata(context: Context, desktop: Boolean): UserAgentMetadata {
            val brands = chromeBrands(context).map { (brand, major, full) ->
                UserAgentMetadata.BrandVersion.Builder().setBrand(brand).setMajorVersion(major).setFullVersion(full).build()
            }
            val builder = UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(chromeFullVersion(context))
                .setMobile(!desktop)
                .setFormFactors(listOf(if (desktop) "Desktop" else "Mobile"))
            if (desktop) {
                builder.setPlatform("Linux").setPlatformVersion("").setArchitecture(cpu.architecture)
                    .setBitness(cpu.bitness.toInt()).setWow64(false).setModel("")
            } else {
                builder.setPlatform("Android")
            }
            return builder.build()
        }

        private fun brandsJson(brands: List<Triple<String, String, String>>, full: Boolean) =
            brands.joinToString(",", "[", "]") { (brand, major, version) ->
                "{\"brand\":\"$brand\",\"version\":\"${if (full) version else major}\"}"
            }

        // For a WebView too old to take user-agent metadata: its headers still name it, but page scripts don't
        private fun brandsScript(context: Context): String {
            val brands = chromeBrands(context)
            return BRANDS_SCRIPT
                .replace("@ARCH@", cpu.architecture)
                .replace("@BITNESS@", cpu.bitness)
                .replace("@BRANDS@", brandsJson(brands, full = false))
                .replace("@FULL_BRANDS@", brandsJson(brands, full = true))
        }

        // Sets the page's viewport to a desktop's width, and keeps it there when the page changes it
        private const val WIDE_SCRIPT = """
(function () {
  // Zoomed out to fit, as a desktop site opens on a phone
  var wide = 'width=@WIDTH@, initial-scale=' + Math.min(1, screen.width / @WIDTH@).toFixed(3);
  function fix(meta) { if (meta.getAttribute('content') !== wide) meta.setAttribute('content', wide); }
  function check(node) { if (node.nodeName === 'META' && (node.getAttribute('name') || '').toLowerCase() === 'viewport') fix(node); }
  new MutationObserver(function (changes) {
    changes.forEach(function (change) {
      if (change.type === 'attributes') check(change.target);
      else change.addedNodes.forEach(check);
    });
  }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
})();
"""

        // Where a page asks for brands in script, WebView names itself; Chrome names itself instead.
        // Desktop sites carry a Linux user agent with no Android in it, so the platform follows whichever is in use.
        private const val BRANDS_SCRIPT = """
(function () {
  var proto = window.NavigatorUAData && NavigatorUAData.prototype;
  if (!proto) return;
  var brands = @BRANDS@, fullBrands = @FULL_BRANDS@;
  var copy = function (list) { return list.map(function (b) { return { brand: b.brand, version: b.version }; }); };
  var desktop = function () { return navigator.userAgent.indexOf('Android') < 0; };
  var platform = Object.getOwnPropertyDescriptor(proto, 'platform');
  var define = function (name, get) {
    var own = Object.getOwnPropertyDescriptor(proto, name);
    if (own && own.get) Object.defineProperty(proto, name, { get: get, configurable: true, enumerable: true });
  };
  define('brands', function () { return copy(brands); });
  define('mobile', function () { return !desktop(); });
  define('platform', function () { return desktop() ? 'Linux' : platform.get.call(this); });
  var high = proto.getHighEntropyValues;
  if (high) {
    proto.getHighEntropyValues = function () {
      return high.apply(this, arguments).then(function (values) {
        if (values) {
          if (values.brands) values.brands = copy(brands);
          if (values.fullVersionList) values.fullVersionList = copy(fullBrands);
          if ('mobile' in values) values.mobile = !desktop();
          if (desktop()) {
            if ('platform' in values) values.platform = 'Linux';
            if ('formFactors' in values) values.formFactors = ['Desktop'];
            if ('platformVersion' in values) values.platformVersion = '';
            if ('architecture' in values) values.architecture = '@ARCH@';
            if ('bitness' in values) values.bitness = '@BITNESS@';
            if ('model' in values) values.model = '';
          }
        }
        return values;
      });
    };
  }
  var toJSON = proto.toJSON;
  if (toJSON) {
    proto.toJSON = function () {
      var values = toJSON.apply(this, arguments);
      if (values) {
        if (values.brands) values.brands = copy(brands);
        if ('mobile' in values) values.mobile = !desktop();
        if (desktop() && 'platform' in values) values.platform = 'Linux';
      }
      return values;
    };
  }
})();
"""
    }

    init {
        configureEngine()
        ServiceWorkerBlocker.watch(context, null, null)
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
        }
        presentAs(desktop = false)
        // WebView names this app in an X-Requested-With header on every request, which no browser sends.
        // The allow-list meant to turn it off is still offered but does nothing (tried on WebView 124),
        // and the header is added after shouldInterceptRequest, so a desktop identity always shows as an app's.
    }

    @SuppressLint("RequiresFeature")
    private fun presentAs(desktop: Boolean) {
        presentedDesktop = desktop
        settings.userAgentString = if (desktop) desktopUserAgent(context) else mobileUserAgent(context)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            WebSettingsCompat.setUserAgentMetadata(settings, chromeMetadata(context, desktop))
        }
    }

    /**
     * Has scripts on the page see Chrome too, and puts the shield's and downloads' page sides in place. Call after
     * [AppProfiles.attach], which a page script would block.
     */
    @SuppressLint("RequiresFeature")
    fun presentAsBrowser() {
        // Where client hints can be set, pages already see Chrome, without a script to give it away
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            WebViewCompat.addDocumentStartJavaScript(this, brandsScript(context), setOf("*"))
        }
        PageShield.attach(this)
        files = PageFiles.attach(this)
    }

    override fun loadUrl(url: String) {
        beforeNavigation(url)
        shield?.prepare(url)
        super.loadUrl(url)
    }

    override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
        beforeNavigation(url)
        shield?.prepare(url)
        super.loadUrl(url, additionalHttpHeaders)
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
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            // Data Saver: Block image payloads
            blockNetworkImage = webSettings.dataSaver
        }
        desktopMode = webSettings.desktop
        shield?.enabled = webSettings.adblock

        cookies.setAcceptCookie(webSettings.acceptCookies)
        cookies.setAcceptThirdPartyCookies(this, webSettings.thirdPartyCookies)
    }

    override fun destroy() {
        // A file the page was still writing can't be finished now
        files?.pageStarted()
        media?.let(MediaPlaybackService::onEnded)
        stopLoading()
        clearHistory()
        loadUrl("about:blank")
        super.destroy()
    }
}
