package net.mangadot.scaffold.ui.editor

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.databinding.ActivityScriptEditorBinding

class ScriptEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var scriptRepository: ScriptRepository
    private var scriptId: String? = null
    private var isCodeTabActive = true

    companion object {
        const val EXTRA_SCRIPT_ID = "extra_script_id"
        const val EXTRA_PREFILL_PATTERN = "extra_prefill_pattern"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())

        scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID)
        val prefillPattern = intent.getStringExtra(EXTRA_PREFILL_PATTERN)

        setupWindowInsets()
        setupListeners()

        if (scriptId != null) {
            loadScriptData()
        } else {
            val pattern = prefillPattern ?: "https://*/*"
            binding.editorEtMatchPattern.setText(pattern)
            binding.editorEtScriptName.setText("New Custom Script")
            binding.editorTvBadge.text = "custom.user.js"
            binding.editorEtCode.setText(
                """// ==UserScript==
// @name         New Custom Script
// @version      1.0.0
// @match        $pattern
// @run-at       document-start
// @grant        none
// ==/UserScript==

(function() {
    'use strict';
    console.log('[Scraffold] Custom script executed at document-start');
})();
"""
            )
        }

        updateLineNumbers()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.editorRootLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.editorHeaderLayout.setPadding(
                binding.editorHeaderLayout.paddingLeft,
                statusBarHeight + dpToPx(10),
                binding.editorHeaderLayout.paddingRight,
                dpToPx(10)
            )

            binding.editorBottomBar.setPadding(
                binding.editorBottomBar.paddingLeft,
                dpToPx(6),
                binding.editorBottomBar.paddingRight,
                navBarHeight + dpToPx(6)
            )

            insets
        }
    }

    private fun setupListeners() {
        binding.editorBtnBack.setOnClickListener {
            finish()
        }

        binding.editorBtnSave.setOnClickListener {
            saveScript()
        }

        // Sub-Tab Switcher: Editor vs Settings
        binding.editorTabCode.setOnClickListener {
            switchTab(isCode = true)
        }

        binding.editorTabSettings.setOnClickListener {
            switchTab(isCode = false)
        }

        // Live Line Numbers & Pos Updates
        binding.editorEtCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateLineNumbers()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Menu Bar Actions
        binding.menuBtnFile.setOnClickListener {
            val items = arrayOf("Save Script", "Discard Changes", "New Script Template")
            AlertDialog.Builder(this)
                .setTitle("File Menu")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> saveScript()
                        1 -> discardChanges()
                        2 -> insertTemplate()
                    }
                }
                .show()
        }

        binding.menuBtnEdit.setOnClickListener {
            val items = arrayOf("Select All", "Clear All", "Insert GM Polyfill")
            AlertDialog.Builder(this)
                .setTitle("Edit Menu")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> binding.editorEtCode.selectAll()
                        1 -> binding.editorEtCode.setText("")
                        2 -> binding.editorEtCode.append("\n// GM Polyfill\nGM_addStyle('body { background: #000 !important; }');\n")
                    }
                }
                .show()
        }

        binding.menuBtnSelection.setOnClickListener {
            binding.editorEtCode.selectAll()
        }

        binding.menuBtnFind.setOnClickListener {
            val input = EditText(this).apply { hint = "Search term" }
            AlertDialog.Builder(this)
                .setTitle("Find in Script")
                .setView(input)
                .setPositiveButton("Search") { _, _ ->
                    val term = input.text.toString()
                    val idx = binding.editorEtCode.text.indexOf(term)
                    if (idx != -1) {
                        binding.editorEtCode.setSelection(idx, idx + term.length)
                    } else {
                        Toast.makeText(this, "Pattern not found", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }

        binding.menuBtnGoto.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Line number"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            AlertDialog.Builder(this)
                .setTitle("Go To Line")
                .setView(input)
                .setPositiveButton("Go") { _, _ ->
                    val line = input.text.toString().toIntOrNull() ?: 1
                    val lines = binding.editorEtCode.text.split("\n")
                    var charOffset = 0
                    for (i in 0 until (line - 1).coerceAtMost(lines.size - 1)) {
                        charOffset += lines[i].length + 1
                    }
                    binding.editorEtCode.setSelection(charOffset.coerceAtMost(binding.editorEtCode.text.length))
                }
                .show()
        }

        // Delete button in Settings tab
        binding.editorBtnDeleteScript.setOnClickListener {
            val id = scriptId
            if (id != null) {
                AlertDialog.Builder(this)
                    .setTitle("Delete Userscript")
                    .setMessage("Are you sure you want to delete this userscript from Scraffold?")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            scriptRepository.deleteScriptById(id)
                            Toast.makeText(this@ScriptEditorActivity, "Script deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                finish()
            }
        }
    }

    private fun switchTab(isCode: Boolean) {
        isCodeTabActive = isCode
        if (isCode) {
            binding.editorTabCode.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.editorTabCode.setBackgroundResource(R.drawable.bg_subtab_active)
            binding.editorTabSettings.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.editorTabSettings.setBackgroundResource(R.drawable.bg_subtab_inactive)

            binding.editorViewCode.visibility = View.VISIBLE
            binding.editorViewSettings.visibility = View.GONE
        } else {
            binding.editorTabSettings.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.editorTabSettings.setBackgroundResource(R.drawable.bg_subtab_active)
            binding.editorTabCode.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.editorTabCode.setBackgroundResource(R.drawable.bg_subtab_inactive)

            binding.editorViewCode.visibility = View.GONE
            binding.editorViewSettings.visibility = View.VISIBLE
        }
    }

    private fun updateLineNumbers() {
        val text = binding.editorEtCode.text.toString()
        val lineCount = text.count { it == '\n' } + 1
        val sb = java.lang.StringBuilder()
        for (i in 1..lineCount) {
            sb.append(i).append("\n")
        }
        binding.editorTvLineNumbers.text = sb.toString().trimEnd()
        binding.editorTvStatusPos.text = "Lines: $lineCount • UTF-8"
    }

    private fun loadScriptData() {
        val id = scriptId ?: return
        lifecycleScope.launch {
            val script = scriptRepository.getScriptById(id).firstOrNull() ?: return@launch
            binding.editorEtScriptName.setText(script.name)
            binding.editorEtMatchPattern.setText(script.matchPattern)
            binding.editorEtCode.setText(script.code)
            val badge = script.name.lowercase().replace(" ", "_") + ".user.js"
            binding.editorTvBadge.text = badge
            updateLineNumbers()
        }
    }

    private fun saveScript() {
        val name = binding.editorEtScriptName.text.toString().trim()
        val pattern = binding.editorEtMatchPattern.text.toString().trim()
        val code = binding.editorEtCode.text.toString()

        if (name.isEmpty() || pattern.isEmpty()) {
            Toast.makeText(this, "Please enter script name and match pattern in Settings tab", Toast.LENGTH_SHORT).show()
            switchTab(isCode = false)
            return
        }

        lifecycleScope.launch {
            val script = ScriptEntity(
                id = scriptId ?: java.util.UUID.randomUUID().toString(),
                name = name,
                matchPattern = pattern,
                code = code,
                runAt = ScriptEntity.RUN_AT_DOCUMENT_START,
                enabled = true,
                updatedAt = System.currentTimeMillis()
            )
            scriptRepository.insertScript(script)
            Toast.makeText(this@ScriptEditorActivity, "Userscript saved to Scraffold engine", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun discardChanges() {
        if (scriptId != null) {
            loadScriptData()
            Toast.makeText(this, "Reloaded original script", Toast.LENGTH_SHORT).show()
        }
    }

    private fun insertTemplate() {
        binding.editorEtCode.setText(
            """// ==UserScript==
// @name         MangaDot Cosmic
// @version      3.26
// @match        https://mangadot.net/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(function() {
    'use strict';
    console.log('[Scraffold] Cosmic Engine Active');
})();
"""
        )
        updateLineNumbers()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
