package net.mangadot.scaffold.ui.shelf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.settings.effectiveSettings
import net.mangadot.scaffold.data.settings.withSettings
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.databinding.ActivityMainBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.cache.CacheManager
import net.mangadot.scaffold.ui.container.WebContainerActivity
import net.mangadot.scaffold.ui.settings.SettingsActivity
import net.mangadot.scaffold.ui.theme.CosmicTheme
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyHeader

class MainActivity : CosmicActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: WebAppRepository
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var adapter: AppAdapter
    private val iconAttempts = mutableSetOf<String>()
    private lateinit var adBlockEngine: AdBlockEngine
    private var allApps: List<WebAppEntity> = emptyList()
    private var reopenCosmicDialog = false
    private var cosmicDialog: CosmicThemeDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyTheme(CosmicThemeStore.load(this))

        val db = AppDatabase.getInstance(this)
        repository = WebAppRepository(db.webAppDao())
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
        adBlockEngine = AdBlockEngine.getInstance(this)

        setupWindowInsets()
        setupRecyclerView()
        setupListeners()
        observeWebApps()
        updateShieldTelemetry()
        highlightTab(binding.tabIcons, listOf(binding.tabGrid, binding.tabList))

        // An accent change rebuilds the screen, so bring the dialog straight back
        if (savedInstanceState?.getBoolean(STATE_REOPEN_COSMIC) == true) {
            showCosmicThemeDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REOPEN_COSMIC, reopenCosmicDialog)
    }

    override fun onDestroy() {
        cosmicDialog?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateShieldTelemetry()
        runAutoPurge()
    }

    private fun runAutoPurge() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(SettingsActivity.KEY_AUTO_PURGE, true)) {
            lifecycleScope.launch { CacheManager.checkAndAutoPurge(this@MainActivity) }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRootLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.headerLayout.setPadding(
                binding.headerLayout.paddingLeft,
                statusBarHeight + dpToPx(12),
                binding.headerLayout.paddingRight,
                dpToPx(12)
            )

            binding.shelfBottomBar.setPadding(
                binding.shelfBottomBar.paddingLeft,
                dpToPx(10),
                binding.shelfBottomBar.paddingRight,
                navBarHeight + dpToPx(10)
            )

            insets
        }
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter(
            onAppClick = { app -> launchWebApp(app) },
            onAppOptionsClick = { app -> showAppActionSheet(app) }
        )

        // Default to Icons View with 3 columns (matching scriptdeck_prototype.html)
        binding.rvShelf.layoutManager = GridLayoutManager(this, 3)
        binding.rvShelf.adapter = adapter
    }

    private fun setupListeners() {
        // Toggle Search Bar
        binding.btnToggleSearch.setOnClickListener {
            val isVisible = binding.searchContainer.visibility == View.VISIBLE
            binding.searchContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.etSearch.requestFocus()
            } else {
                binding.etSearch.setText("")
                filterApps("")
            }
        }

        // Live Search Filter
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Cosmic Theme Customizer Button (🎨)
        binding.btnTheme.setOnClickListener {
            showCosmicThemeDialog()
        }

        // Settings Button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Add App FAB
        binding.fabAddApp.setOnClickListener {
            showAddAppDialog()
        }

        // View Switcher Tabs (Icons | Grid | List)
        binding.tabIcons.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.ICONS) }
        binding.tabGrid.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.GRID) }
        binding.tabList.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.LIST) }
    }

    private fun setLayoutMode(mode: AppAdapter.LayoutMode) {
        adapter.currentLayoutMode = mode
        when (mode) {
            AppAdapter.LayoutMode.ICONS -> {
                binding.rvShelf.layoutManager = GridLayoutManager(this, 3)
                highlightTab(binding.tabIcons, listOf(binding.tabGrid, binding.tabList))
            }
            AppAdapter.LayoutMode.GRID -> {
                binding.rvShelf.layoutManager = GridLayoutManager(this, 2)
                highlightTab(binding.tabGrid, listOf(binding.tabIcons, binding.tabList))
            }
            AppAdapter.LayoutMode.LIST -> {
                binding.rvShelf.layoutManager = LinearLayoutManager(this)
                highlightTab(binding.tabList, listOf(binding.tabIcons, binding.tabGrid))
            }
        }
    }

    private fun highlightTab(active: TextView, inactives: List<TextView>) {
        active.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        active.setTypeface(null, android.graphics.Typeface.BOLD)
        active.background = CosmicAccent.tabIndicator(this)

        for (inactive in inactives) {
            inactive.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            inactive.setTypeface(null, android.graphics.Typeface.NORMAL)
            inactive.setBackgroundResource(R.drawable.bg_tab_inactive)
        }
    }

    private fun observeWebApps() {
        lifecycleScope.launch {
            repository.allWebApps.collectLatest { apps ->
                allApps = apps
                filterApps(binding.etSearch.text.toString())
                apps.filter { it.iconUrl == null && iconAttempts.add(it.id) }.forEach(::fetchIcon)
            }
        }
        lifecycleScope.launch {
            scriptRepository.allScripts.collectLatest { scripts ->
                adapter.scripts = scripts
            }
        }
    }

    private fun fetchIcon(app: WebAppEntity) {
        lifecycleScope.launch {
            val icon = AppIcons.fetch(this@MainActivity, app.url) ?: return@launch
            repository.updateIconUrl(app.id, AppIcons.save(this@MainActivity, app.id, icon))
        }
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.name.lowercase().contains(q) || it.url.lowercase().contains(q)
            }
        }

        adapter.submitList(filtered)

        // Empty State Lifecycle
        val isEmpty = filtered.isEmpty()
        binding.emptyStateContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvShelf.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateShieldTelemetry() {
        val blocked = adBlockEngine.totalBlockedRequests
        binding.tvShelfShieldCount.text = "Shield: $blocked blocked"
        adapter.blockedCounts = adBlockEngine.blockedCountsByApp()
    }

    private fun launchWebApp(app: WebAppEntity) {
        lifecycleScope.launch {
            repository.recordLaunch(app.id)
        }
        val intent = Intent(this, WebContainerActivity::class.java).apply {
            putExtra(WebContainerActivity.EXTRA_APP_ID, app.id)
            putExtra(WebContainerActivity.EXTRA_APP_URL, app.url)
            putExtra(WebContainerActivity.EXTRA_APP_NAME, app.name)
        }
        startActivity(intent)
    }

    private fun showAddAppDialog() {
        val sheet = AddWebAppBottomSheetDialog { name, url, userscripts, pinchZoom, customIcon ->
            lifecycleScope.launch {
                val blank = WebAppEntity(name = name, url = url, userscriptsEnabled = userscripts)
                val app = if (customIcon == null) blank else blank.copy(iconUrl = AppIcons.save(this@MainActivity, blank.id, customIcon))
                repository.insertWebApp(
                    if (pinchZoom) app else app.copy(overrideGlobal = true)
                        .withSettings(app.effectiveSettings(this@MainActivity).copy(pinchZoom = false))
                )
                Toast.makeText(this@MainActivity, "Added $name to Shelf", Toast.LENGTH_SHORT).show()
            }
        }
        sheet.show(supportFragmentManager, "add_webapp")
    }

    private fun showCosmicThemeDialog() {
        CosmicThemeDialog(
            this,
            onThemeChanged = { theme -> applyTheme(theme) },
            onAccentChanged = {
                reopenCosmicDialog = true
                recreate()
            }
        ).also { cosmicDialog = it }.show()
    }

    private fun applyTheme(theme: CosmicTheme) {
        theme.applyBackground(binding.mainRootLayout)
        theme.applyHeader(binding.headerLayout, binding.rvShelf)
    }

    private fun showAppActionSheet(app: WebAppEntity) {
        val sheet = AppActionBottomSheetDialog(
            app = app,
            onLaunch = { launchWebApp(it) },
            onDelete = { target ->
                lifecycleScope.launch {
                    repository.deleteWebAppById(target.id)
                    AppIcons.delete(this@MainActivity, target.id)
                    Toast.makeText(this@MainActivity, "Removed ${target.name} from Shelf", Toast.LENGTH_SHORT).show()
                }
            },
            onUpdate = { updated ->
                lifecycleScope.launch { repository.updateWebApp(updated) }
            }
        )
        sheet.show(supportFragmentManager, AppActionBottomSheetDialog.TAG)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val STATE_REOPEN_COSMIC = "reopen_cosmic"
    }
}
