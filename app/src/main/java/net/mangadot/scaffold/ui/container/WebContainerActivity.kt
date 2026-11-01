package net.mangadot.scaffold.ui.container

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.databinding.ActivityWebContainerBinding
import net.mangadot.scaffold.engine.*
import net.mangadot.scaffold.engine.gesture.FourFingerReloadGesture

class WebContainerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebContainerBinding
    private lateinit var webAppRepository: WebAppRepository
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var scriptEngine: ScriptEngine
    private lateinit var webViewClient: DeckWebViewClient
    private lateinit var webChromeClient: DeckWebChromeClient

    private var currentApp: WebAppEntity? = null

    companion object {
        const val EXTRA_APP_ID = "extra_app_id"
        const val EXTRA_APP_URL = "extra_app_url"
        const val EXTRA_APP_NAME = "extra_app_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebContainerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        webAppRepository = WebAppRepository(db.webAppDao())
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
        adBlockEngine = AdBlockEngine.getInstance(this)
        scriptEngine = ScriptEngine(scriptRepository, lifecycleScope)

        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        val appUrl = intent.getStringExtra(EXTRA_APP_URL) ?: "https://mangadot.net"

        setupWebView(appId, appUrl)
        setupBackNavigation()
        loadAppData(appId, appUrl)
    }

    private fun setupWebView(appId: String, initialUrl: String) {
        val dummyApp = WebAppEntity(id = appId, name = "", url = initialUrl)

        webViewClient = DeckWebViewClient(
            context = this,
            adBlockEngine = adBlockEngine,
            scriptEngine = scriptEngine,
            webApp = dummyApp
        )

        webChromeClient = DeckWebChromeClient(
            webApp = dummyApp,
            onProgressUpdate = { progress ->
                if (progress < 100) {
                    binding.containerProgressBar.visibility = View.VISIBLE
                    binding.containerProgressBar.progress = progress
                } else {
                    binding.containerProgressBar.visibility = View.GONE
                }
            }
        )

        binding.deckWebview.webViewClient = webViewClient
        binding.deckWebview.webChromeClient = webChromeClient

        // Attach 4-Finger Swipe-Down Reload Gesture
        binding.deckWebview.setOnTouchListener(
            FourFingerReloadGesture {
                binding.deckWebview.reload()
            }
        )
    }

    private fun loadAppData(appId: String, initialUrl: String) {
        lifecycleScope.launch {
            val app = webAppRepository.getWebAppByUrl(initialUrl) ?: WebAppEntity(id = appId, name = "", url = initialUrl)
            currentApp = app
            binding.deckWebview.applyWebAppConfig(app)
            webViewClient.updateWebApp(app)
            webChromeClient.updateWebApp(app)

            // Load scripts and attach pre-DOM engine
            val scripts = scriptRepository.getEnabledScriptsList()
            webViewClient.updateActiveScripts(scripts)

            val host = android.net.Uri.parse(initialUrl).host ?: ""
            val cosmeticCss = adBlockEngine.getCosmeticCss(host)
            val scriptlets = adBlockEngine.getScriptletDefusers(host)

            scriptEngine.attachPreDomScripts(
                webView = binding.deckWebview,
                appId = app.id,
                currentUrl = initialUrl,
                activeScripts = scripts,
                cosmeticCss = cosmeticCss,
                scriptlets = scriptlets
            )

            // Start navigation
            binding.deckWebview.loadUrl(initialUrl)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.deckWebview.canGoBack()) {
                    binding.deckWebview.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onDestroy() {
        binding.deckWebview.destroy()
        super.onDestroy()
    }
}
