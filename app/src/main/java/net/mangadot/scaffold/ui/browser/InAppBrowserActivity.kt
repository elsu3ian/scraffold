package net.mangadot.scaffold.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.mangadot.scaffold.databinding.ActivityInAppBrowserBinding
import net.mangadot.scaffold.ui.theme.CosmicActivity

/** Where links that leave a web app go. Web pages open over the app, as in an installed PWA. */
object ExternalLinks {

    fun open(context: Context, uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            context.startActivity(Intent(context, InAppBrowserActivity::class.java).apply {
                putExtra(InAppBrowserActivity.EXTRA_URL, uri.toString())
            })
        } else {
            openNative(context, uri)
        }
    }

    /** tel:, mailto:, tg:, intent: and so on belong to the apps that handle them. */
    fun openNative(context: Context, uri: Uri) {
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
                open(context, Uri.parse(fallback))
            }
        }
    }

    /** Hands a page to the system browser, for the user's "Open in browser" button and downloads. */
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInAppBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.browserRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                ExternalLinks.openNative(this@InAppBrowserActivity, request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                binding.browserTvHost.text = Uri.parse(url).host ?: url
            }
        }
        binding.browserWebview.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.browserProgress.progress = newProgress
                binding.browserProgress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                binding.browserTvTitle.text = title
            }
        }
        binding.browserWebview.setDownloadListener { url, _, _, _, _ ->
            ExternalLinks.openInBrowser(this, Uri.parse(url))
        }

        binding.browserBtnClose.setOnClickListener { finish() }
        binding.browserBtnExternal.setOnClickListener {
            binding.browserWebview.url?.let { ExternalLinks.openInBrowser(this, Uri.parse(it)) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.browserWebview.canGoBack()) binding.browserWebview.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            binding.browserWebview.restoreState(savedInstanceState)
        } else {
            val url = intent.getStringExtra(EXTRA_URL).orEmpty()
            binding.browserTvTitle.text = Uri.parse(url).host ?: url
            binding.browserWebview.loadUrl(url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.browserWebview.saveState(outState)
    }

    override fun onDestroy() {
        binding.browserWebview.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
