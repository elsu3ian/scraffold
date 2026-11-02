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
import androidx.appcompat.app.AppCompatActivity
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
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.databinding.ActivityMainBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.ui.container.WebContainerActivity
import net.mangadot.scaffold.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: WebAppRepository
    private lateinit var adapter: AppAdapter
    private lateinit var adBlockEngine: AdBlockEngine
    private var allApps: List<WebAppEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = WebAppRepository(db.webAppDao())
        adBlockEngine = AdBlockEngine.getInstance(this)

        setupWindowInsets()
        setupRecyclerView()
        setupListeners()
        observeWebApps()
        updateShieldTelemetry()
    }

    override fun onResume() {
        super.onResume()
        updateShieldTelemetry()
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
        active.setBackgroundResource(R.drawable.bg_tab_active)

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
            }
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
        val sheet = AddWebAppBottomSheetDialog { name, url ->
            lifecycleScope.launch {
                repository.insertWebApp(
                    WebAppEntity(name = name, url = url)
                )
                Toast.makeText(this@MainActivity, "Added $name to Shelf", Toast.LENGTH_SHORT).show()
            }
        }
        sheet.show(supportFragmentManager, "add_webapp")
    }

    private fun showCosmicThemeDialog() {
        CosmicThemeDialog(this).show()
    }

    private fun showAppActionSheet(app: WebAppEntity) {
        val sheet = AppActionBottomSheetDialog(
            app = app,
            onLaunch = { launchWebApp(it) },
            onDelete = { target ->
                lifecycleScope.launch {
                    repository.deleteWebAppById(target.id)
                    Toast.makeText(this@MainActivity, "Removed ${target.name} from Shelf", Toast.LENGTH_SHORT).show()
                }
            },
            onToggleFullscreen = { target, enabled ->
                lifecycleScope.launch {
                    repository.updateFullScreenMode(target.id, enabled)
                }
            }
        )
        sheet.show(supportFragmentManager, AppActionBottomSheetDialog.TAG)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
