package net.mangadot.scaffold.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.data.settings.GlobalWebSettingsStore
import net.mangadot.scaffold.databinding.ActivityInAppBrowserBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.AppProfiles
import net.mangadot.scaffold.engine.BackgroundMedia
import net.mangadot.scaffold.engine.DeckWebView
import net.mangadot.scaffold.ui.shelf.MainActivity
import net.mangadot.scaffold.engine.ScriptEngine
import net.mangadot.scaffold.engine.WebDownloads
import net.mangadot.scaffold.ui.container.AppTasks
import net.mangadot.scaffold.ui.container.ElementPicker
import net.mangadot.scaffold.ui.container.ShieldMeter
import net.mangadot.scaffold.ui.container.WebContainerActivity
import net.mangadot.scaffold.ui.theme.CosmicActivity

/** The web app a link left from. It shares that app's login, so sign-in redirects can come back. */
data class LinkOwner(
    val appId: String,
    val appUrl: String,
    val adblock: Boolean,
    val userscripts: Boolean
)

/** Where links that leave a web app go. Web pages open over the app, as in an installed PWA. */
object ExternalLinks {

    fun open(context: Context, uri: Uri, owner: LinkOwner? = null) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            context.startActivity(Intent(context, InAppBrowserActivity::class.java).apply {
                putExtra(InAppBrowserActivity.EXTRA_URL, uri.toString())
                if (owner != null) {
                    putExtra(InAppBrowserActivity.EXTRA_APP_ID, owner.appId)
                    putExtra(InAppBrowserActivity.EXTRA_APP_URL, owner.appUrl)
                    putExtra(InAppBrowserActivity.EXTRA_ADBLOCK, owner.adblock)
                    putExtra(InAppBrowserActivity.EXTRA_USERSCRIPTS, owner.userscripts)
                }
            })
        } else {
            openNative(context, uri, owner)
        }
    }

    /** tel:, mailto:, tg:, intent: and so on belong to the apps that handle them. */
    fun openNative(context: Context, uri: Uri, owner: LinkOwner? = null) {
        val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
            // A page chooses this intent, so it may only reach what a browser link could
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback.isNullOrEmpty()) {
                Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
            } else {
                open(context, Uri.parse(fallback), owner)
            }
        }
    }

    /** Hands a page to the system browser, for the user's "Open in browser" button. */
    fun openInBrowser(context: Context, uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "No browser available on this device", Toast.LENGTH_SHORT).show()
        }
    }
}

class InAppBrowserActivity : CosmicActivity() {

    private lateinit var binding: ActivityInAppBrowserBinding
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var scriptEngine: ScriptEngine
    private lateinit var cookies: CookieManager
    private var appId: String? = null
    private var appHost: String? = null
    private var adblock = true

    // Read on the network thread, where the WebView itself can't be asked
    @Volatile
    private var pageHost: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInAppBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appId = intent.getStringExtra(EXTRA_APP_ID)
        appHost = intent.getStringExtra(EXTRA_APP_URL)?.let { siteHost(Uri.parse(it)) }
        adblock = intent.getBooleanExtra(EXTRA_ADBLOCK, GlobalWebSettingsStore.load(this).adblock)
        adBlockEngine = AdBlockEngine.getInstance(this)
        val db = AppDatabase.getInstance(this)
        val scripts = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
        scriptEngine = ScriptEngine(this, scripts, lifecycleScope)

        AppProfiles.attach(binding.browserWebview, appId)
        // Reopening an app's task would close this page on top of it, so the notification leads to the shelf
        BackgroundMedia.attach(binding.browserWebview, "Browser", Intent(this, MainActivity::class.java))
        binding.browserWebview.presentAsBrowser()
        binding.browserWebview.shield?.enabled = adblock
        cookies = AppProfiles.cookies(this, appId)

        ViewCompat.setOnApplyWindowInsetsListener(binding.browserRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.browserWebview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        binding.browserWebview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (adblock) {
                    adBlockEngine.intercept(request, pageHost, (view as? DeckWebView)?.shieldStats)?.let { answer ->
                        appId?.let { adBlockEngine.recordBlockedForApp(it) }
                        return answer
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (adblock && adBlockEngine.stopsNavigation(view, request, pageHost)) return true
                val scheme = request.url.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    ExternalLinks.openNative(this@InAppBrowserActivity, request.url)
                    return true
                }
                // Arriving back on the app's own site, as a sign-in redirect does, returns to the app
                val host = siteHost(request.url)
                val base = appHost
                if (request.isForMainFrame && base != null && (host == base || host.endsWith(".$base"))) {
                    returnToApp(request.url.toString())
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                binding.browserTvHost.text = Uri.parse(url).host ?: url
                pageHost = Uri.parse(url).host?.lowercase()
                (view as? DeckWebView)?.shieldStats?.enter(pageHost)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finish()
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                scriptEngine.onPageFinished(view)
            }
        }
        binding.browserPull.page = { binding.browserWebview }
        binding.browserWebview.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.browserProgress.progress = newProgress
                binding.browserProgress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
                binding.browserPull.onProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                binding.browserTvTitle.text = title
            }
        }
        WebDownloads.listen(this, binding.browserWebview, cookies)

        binding.browserBtnClose.setOnClickListener { finish() }
        binding.browserBtnShield.setOnClickListener { ShieldMeter.show(this, binding.browserWebview, adblock) }
        binding.browserBtnShield.setOnLongClickListener {
            ElementPicker.start(this, binding.browserWebview)
            true
        }
        binding.browserBtnExternal.setOnClickListener {
            binding.browserWebview.url?.let { ExternalLinks.openInBrowser(this, Uri.parse(it)) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.browserWebview.canGoBack()) binding.browserWebview.goBack() else finish()
            }
        })

        lifecycleScope.launch {
            scriptEngine.attach(binding.browserWebview, appId)
            val active = if (intent.getBooleanExtra(EXTRA_USERSCRIPTS, true)) scripts.getEnabledScriptsList() else emptyList()
            scriptEngine.register(binding.browserWebview, active)
            if (savedInstanceState != null) {
                binding.browserWebview.restoreState(savedInstanceState)
            } else {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                binding.browserTvTitle.text = Uri.parse(url).host ?: url
                binding.browserWebview.loadUrl(url)
            }
        }
    }

    private fun siteHost(uri: Uri) = uri.host?.lowercase()?.removePrefix("www.").orEmpty()

    private fun returnToApp(url: String) {
        val owner = appId ?: return finish()
        startActivity(AppTasks.intent(this, owner).apply {
            putExtra(WebContainerActivity.EXTRA_NAVIGATE_URL, url)
        })
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.browserWebview.saveState(outState)
    }

    override fun onPause() {
        cookies.flush()
        super.onPause()
    }

    override fun onDestroy() {
        binding.browserWebview.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_APP_ID = "extra_app_id"
        const val EXTRA_APP_URL = "extra_app_url"
        const val EXTRA_ADBLOCK = "extra_adblock"
        const val EXTRA_USERSCRIPTS = "extra_userscripts"
    }
}
