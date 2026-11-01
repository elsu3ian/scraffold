package net.mangadot.scaffold.ui.shelf

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import net.mangadot.scaffold.ui.container.WebContainerActivity
import net.mangadot.scaffold.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: WebAppRepository
    private lateinit var adapter: AppAdapter
    private var allApps: List<WebAppEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = WebAppRepository(db.webAppDao())

        setupRecyclerView()
        setupListeners()
        observeWebApps()
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter(
            onAppClick = { app -> launchWebApp(app) },
            onAppLongClick = { app -> showAppOptionsDialog(app) }
        )

        binding.rvShelf.layoutManager = GridLayoutManager(this, 4)
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
                adapter.submitList(allApps)
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

        // Settings Button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Add App FAB
        binding.fabAddApp.setOnClickListener {
            showAddAppDialog()
        }

        // View Switcher Tabs
        binding.tabIcons.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.ICONS) }
        binding.tabGrid.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.GRID) }
        binding.tabList.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.LIST) }
    }

    private fun setLayoutMode(mode: AppAdapter.LayoutMode) {
        adapter.currentLayoutMode = mode
        when (mode) {
            AppAdapter.LayoutMode.ICONS -> {
                binding.rvShelf.layoutManager = GridLayoutManager(this, 4)
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
        for (inactive in inactives) {
            inactive.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            inactive.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    private fun observeWebApps() {
        lifecycleScope.launch {
            repository.allWebApps.collectLatest { apps ->
                allApps = apps
                if (apps.isEmpty()) {
                    seedDefaultApps()
                } else {
                    filterApps(binding.etSearch.text.toString())
                }
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
        val inputUrl = EditText(this).apply {
            hint = "https://mangadot.net"
            setSingleLine()
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_web_app)
            .setView(inputUrl)
            .setPositiveButton(R.string.save_to_shelf) { _, _ ->
                var url = inputUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val name = android.net.Uri.parse(url).host ?: url
                    lifecycleScope.launch {
                        repository.insertWebApp(
                            WebAppEntity(name = name, url = url)
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppOptionsDialog(app: WebAppEntity) {
        val options = arrayOf("Launch", "Delete from Shelf")
        AlertDialog.Builder(this)
            .setTitle(app.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchWebApp(app)
                    1 -> lifecycleScope.launch { repository.deleteWebAppById(app.id) }
                }
            }
            .show()
    }

    private fun seedDefaultApps() {
        lifecycleScope.launch {
            repository.insertWebApp(
                WebAppEntity(name = "MangaDot", url = "https://mangadot.net")
            )
            repository.insertWebApp(
                WebAppEntity(name = "ComicK", url = "https://comick.io")
            )
        }
    }
}
