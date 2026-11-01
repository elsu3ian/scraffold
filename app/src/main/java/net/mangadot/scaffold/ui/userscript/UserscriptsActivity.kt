package net.mangadot.scaffold.ui.userscript

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import net.mangadot.scaffold.ui.editor.ScriptEditorActivity

class UserscriptsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserscriptsBinding
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var adapter: UserscriptAdapter
    private var filterDomain: String? = null
    private var allScripts: List<ScriptEntity> = emptyList()

    companion object {
        const val EXTRA_FILTER_DOMAIN = "extra_filter_domain"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserscriptsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            onLongClick = { script ->
                showDeleteDialog(script)
            }
        )

        binding.rvUserscripts.layoutManager = LinearLayoutManager(this)
        binding.rvUserscripts.adapter = adapter
    }

    private fun setupListeners() {
        binding.userscriptsBtnBack.setOnClickListener {
            finish()
        }

        binding.userscriptsBtnAdd.setOnClickListener {
            val intent = Intent(this, ScriptEditorActivity::class.java)
            if (!filterDomain.isNullOrEmpty()) {
                intent.putExtra(ScriptEditorActivity.EXTRA_PREFILL_PATTERN, "https://$filterDomain/*")
            }
            startActivity(intent)
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
            allScripts.filter { it.matchPattern.contains(domain, ignoreCase = true) }
        }

        adapter.submitList(filtered)
        val isEmpty = filtered.isEmpty()
        binding.userscriptsEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvUserscripts.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showDeleteDialog(script: ScriptEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Userscript")
            .setMessage("Delete '${script.name}' from Scraffold?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    scriptRepository.deleteScriptById(script.id)
                    Toast.makeText(this@UserscriptsActivity, "Script deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
