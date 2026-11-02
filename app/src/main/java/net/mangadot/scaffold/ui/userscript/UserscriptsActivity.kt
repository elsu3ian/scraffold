package net.mangadot.scaffold.ui.userscript

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.databinding.ActivityUserscriptsBinding
import net.mangadot.scaffold.engine.userscript.ScriptInstaller
import net.mangadot.scaffold.engine.userscript.UserscriptMeta
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyHeader
import net.mangadot.scaffold.ui.editor.ScriptEditorActivity
import net.mangadot.scaffold.ui.theme.showCosmic
import net.mangadot.scaffold.ui.theme.dialogField

class UserscriptsActivity : CosmicActivity() {

    private lateinit var binding: ActivityUserscriptsBinding
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var adapter: UserscriptAdapter
    private var filterDomain: String? = null
    private var allScripts: List<ScriptEntity> = emptyList()
    private var exportTarget: ScriptEntity? = null

    private val pickScriptFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val code = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }.orEmpty()
            UserscriptInstallPrompt.show(this, scriptRepository, code, sourceUrl = null)
        }
    }

    private val saveScriptFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/javascript")) { uri: Uri? ->
        val script = exportTarget
        if (uri != null && script != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(script.code.toByteArray()) }
            Toast.makeText(this, "Saved to disk", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_FILTER_DOMAIN = "extra_filter_domain"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserscriptsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.userscriptsRootLayout)
            theme.applyHeader(binding.userscriptsHeaderLayout, binding.rvUserscripts)
        }

        val db = AppDatabase.getInstance(this)
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())

        filterDomain = intent.getStringExtra(EXTRA_FILTER_DOMAIN)

        setupWindowInsets()
        setupRecyclerView()
        setupListeners()
        observeScripts()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.userscriptsRootLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.userscriptsHeaderLayout.setPadding(
                binding.userscriptsHeaderLayout.paddingLeft,
                statusBarHeight + dpToPx(12),
                binding.userscriptsHeaderLayout.paddingRight,
                dpToPx(12)
            )
            insets
        }
    }

    private fun setupRecyclerView() {
        adapter = UserscriptAdapter(
            onScriptClick = { script ->
                val intent = Intent(this, ScriptEditorActivity::class.java).apply {
                    putExtra(ScriptEditorActivity.EXTRA_SCRIPT_ID, script.id)
                }
                startActivity(intent)
            },
            onToggleEnabled = { script, enabled ->
                lifecycleScope.launch {
                    scriptRepository.setScriptEnabled(script.id, enabled)
                }
            },
            onMoreClick = { script, anchor -> showScriptActions(script, anchor) }
        )

        binding.rvUserscripts.layoutManager = LinearLayoutManager(this)
        binding.rvUserscripts.adapter = adapter
    }

    private fun setupListeners() {
        binding.userscriptsBtnBack.setOnClickListener {
            finish()
        }

        binding.userscriptsBtnAdd.setOnClickListener { anchor ->
            GlassMenu(this)
                .item("New script") { openNewScript() }
                .item("Install from URL") { promptInstallUrl() }
                .item("Install from file") { pickScriptFile.launch(arrayOf("*/*")) }
                .divider()
                .item("Check all for updates", enabled = allScripts.isNotEmpty()) { checkAllForUpdates() }
                .show(anchor)
        }

        binding.userscriptsBtnClearFilter.setOnClickListener {
            filterDomain = null
            binding.userscriptsFilterBanner.visibility = View.GONE
            applyFilter()
        }

        if (!filterDomain.isNullOrEmpty()) {
            binding.userscriptsFilterBanner.visibility = View.VISIBLE
            binding.userscriptsTvFilterText.text = "Filtered to: $filterDomain"
        }
    }

    private fun observeScripts() {
        lifecycleScope.launch {
            scriptRepository.allScripts.collectLatest { scripts ->
                allScripts = scripts
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val domain = filterDomain
        val filtered = if (domain.isNullOrEmpty()) {
            allScripts
        } else {
            allScripts.filter { UserscriptMeta.parse(it.code).appliesTo("https://$domain/") }
        }

        adapter.submitList(filtered)
        val isEmpty = filtered.isEmpty()
        binding.userscriptsEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvUserscripts.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun openNewScript() {
        val intent = Intent(this, ScriptEditorActivity::class.java)
        if (!filterDomain.isNullOrEmpty()) {
            intent.putExtra(ScriptEditorActivity.EXTRA_PREFILL_PATTERN, "https://$filterDomain/*")
        }
        startActivity(intent)
    }

    private fun promptInstallUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/script.user.js"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Install from URL")
            .setView(dialogField(input))
            .setPositiveButton("Fetch") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) UserscriptInstallPrompt.fromUrl(this, scriptRepository, url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showCosmic()
    }

    private fun showScriptActions(script: ScriptEntity, anchor: View) {
        GlassMenu(this)
            .item("Check for update") { checkForUpdate(script) }
            .item("Save to disk") {
                exportTarget = script
                saveScriptFile.launch(script.name.replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".user.js")
            }
            .item("Clear stored values") {
                lifecycleScope.launch {
                    ScriptInstaller.clearValues(this@UserscriptsActivity, scriptRepository, script.id)
                    Toast.makeText(this@UserscriptsActivity, "Cleared stored values", Toast.LENGTH_SHORT).show()
                }
            }
            .divider()
            .item("Delete", danger = true) { showDeleteDialog(script) }
            .show(anchor)
    }

    private fun checkForUpdate(script: ScriptEntity) {
        lifecycleScope.launch {
            val message = try {
                when (val result = ScriptInstaller.checkForUpdate(this@UserscriptsActivity, scriptRepository, script)) {
                    is ScriptInstaller.UpdateResult.Updated -> "${script.name} updated to v${result.version}"
                    ScriptInstaller.UpdateResult.UpToDate -> "${script.name} is up to date"
                    ScriptInstaller.UpdateResult.NoUpdateUrl -> "${script.name} has no update link"
                }
            } catch (e: Exception) {
                "Update failed: ${e.message}"
            }
            Toast.makeText(this@UserscriptsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAllForUpdates() {
        lifecycleScope.launch {
            var updated = 0
            var failed = 0
            allScripts.forEach { script ->
                try {
                    val result = ScriptInstaller.checkForUpdate(this@UserscriptsActivity, scriptRepository, script)
                    if (result is ScriptInstaller.UpdateResult.Updated) updated++
                } catch (e: Exception) {
                    failed++
                }
            }
            val summary = if (failed > 0) "$updated updated, $failed failed" else "$updated updated"
            Toast.makeText(this@UserscriptsActivity, summary, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeleteDialog(script: ScriptEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Userscript")
            .setMessage("Delete '${script.name}' and its stored values from Scraffold?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    ScriptInstaller.delete(this@UserscriptsActivity, scriptRepository, script.id)
                    Toast.makeText(this@UserscriptsActivity, "Script deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showCosmic()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
