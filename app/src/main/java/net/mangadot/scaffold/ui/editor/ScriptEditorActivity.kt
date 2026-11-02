package net.mangadot.scaffold.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.databinding.ActivityScriptEditorBinding
import net.mangadot.scaffold.engine.userscript.ScriptInstaller
import net.mangadot.scaffold.engine.userscript.UserscriptMeta
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyHeader
import net.mangadot.scaffold.ui.userscript.GlassMenu
import net.mangadot.scaffold.ui.theme.showCosmic
import net.mangadot.scaffold.ui.theme.dialogField

class ScriptEditorActivity : CosmicActivity() {

    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var scriptRepository: ScriptRepository
    private var scriptId: String? = null
    private var script: ScriptEntity? = null
    private var isCodeTabActive = true
    private var savedCode = ""

    // Undo history: edits within UNDO_MERGE_MS of each other are one step
    private data class Snapshot(val text: String, val cursor: Int)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastEditAt = 0L
    private var applyingHistory = false

    private var lastFind = ""
    private val selectionHistory = ArrayDeque<Pair<Int, Int>>()
    private val grantBoxes = linkedMapOf<String, CheckBox>()

    private val saveToDisk = registerForActivityResult(ActivityResultContracts.CreateDocument("text/javascript")) { uri: Uri? ->
        if (uri != null) {
            val code = binding.editorEtCode.text.toString()
            lifecycleScope.launch(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { it.write(code.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScriptEditorActivity, "Saved to disk", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCRIPT_ID = "extra_script_id"
        const val EXTRA_PREFILL_PATTERN = "extra_prefill_pattern"
        private const val UNDO_MERGE_MS = 600L
        private const val UNDO_LIMIT = 200
        private val COMMON_GRANTS = listOf(
            "none", "GM_addStyle", "GM_addElement", "GM_setValue", "GM_getValue", "GM_deleteValue", "GM_listValues",
            "GM_addValueChangeListener", "GM_removeValueChangeListener", "GM_getResourceText", "GM_getResourceURL",
            "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_xmlhttpRequest", "GM_openInTab",
            "GM_setClipboard", "GM_notification", "GM_download", "GM_log", "unsafeWindow"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.editorRootLayout)
            theme.applyHeader(binding.editorHeaderLayout)
        }

        val db = AppDatabase.getInstance(this)
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())

        scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID)

        setupWindowInsets()
        setupGrantBoxes()
        setupListeners()
        setupBackNavigation()

        if (scriptId != null) {
            loadScriptData()
        } else {
            val pattern = intent.getStringExtra(EXTRA_PREFILL_PATTERN) ?: "https://*/*"
            setCode(newScriptTemplate(pattern), resetHistory = true)
            binding.editorTvBadge.text = "custom.user.js"
            savedCode = binding.editorEtCode.text.toString()
        }
        updateStatus()
    }

    private fun newScriptTemplate(pattern: String) = """// ==UserScript==
// @name         New Custom Script
// @version      1.0.0
// @description
// @match        $pattern
// @run-at       document-start
// @grant        none
// ==/UserScript==

(function() {
    'use strict';

})();
"""

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.editorRootLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            )

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
                bottom + dpToPx(6)
            )

            insets
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeEditor()
        })
    }

    private fun closeEditor() {
        if (!isCodeTabActive) syncSettingsToCode()
        if (binding.editorEtCode.text.toString() == savedCode) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save your changes to this script?")
            .setPositiveButton("Save") { _, _ -> saveScript(closeAfter = true) }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .showCosmic()
    }

    private fun setupGrantBoxes() {
        COMMON_GRANTS.forEach { grant ->
            val box = CheckBox(this).apply {
                text = grant
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                buttonTintList = android.content.res.ColorStateList.valueOf(net.mangadot.scaffold.ui.theme.CosmicAccent.color)
            }
            grantBoxes[grant] = box
            binding.editorGroupGrants.addView(box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun setupListeners() {
        binding.editorBtnBack.setOnClickListener { closeEditor() }
        binding.editorBtnSave.setOnClickListener { saveScript() }

        binding.editorTabCode.setOnClickListener { switchTab(isCode = true) }
        binding.editorTabSettings.setOnClickListener { switchTab(isCode = false) }

        binding.editorEtCode.addTextChangedListener(object : TextWatcher {
            private var before: Snapshot? = null

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!applyingHistory) {
                    before = Snapshot(s.toString(), binding.editorEtCode.selectionStart.coerceAtLeast(0))
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                updateStatus()
                if (applyingHistory) return
                val now = System.currentTimeMillis()
                if (now - lastEditAt > UNDO_MERGE_MS) {
                    before?.let { undoStack.addLast(it) }
                    while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
                }
                lastEditAt = now
                redoStack.clear()
            }
        })

        binding.menuBtnFile.setOnClickListener { showFileMenu(it) }
        binding.menuBtnEdit.setOnClickListener { showEditMenu(it) }
        binding.menuBtnSelection.setOnClickListener { showSelectionMenu(it) }
        binding.menuBtnFind.setOnClickListener { showFindMenu(it) }
        binding.menuBtnGoto.setOnClickListener { showGotoMenu(it) }

        binding.editorTvRunAt.setOnClickListener { view ->
            val menu = GlassMenu(this)
            listOf(
                ScriptEntity.RUN_AT_DOCUMENT_START to "Pre-DOM Render",
                "document-body" to "Body Exists",
                ScriptEntity.RUN_AT_DOCUMENT_END to "DOM Ready",
                ScriptEntity.RUN_AT_DOCUMENT_IDLE to "Post-Network Load"
            ).forEach { (value, label) ->
                menu.item("$value ($label)") { binding.editorTvRunAt.text = value }
            }
            menu.show(view)
        }

        binding.editorBtnDeleteScript.setOnClickListener { confirmDelete() }
    }

    // ---------------- File menu

    private fun showFileMenu(anchor: View) {
        GlassMenu(this)
            .item("Save", "Ctrl-S") { saveScript() }
            .item("Save to disk") { exportToDisk() }
            .divider()
            .item("Discard changes") { discardChanges() }
            .item("Check for updates", enabled = script != null) { checkForUpdates() }
            .item("Delete", danger = true) { confirmDelete() }
            .divider()
            .item("Close", "Ctrl-Q") { closeEditor() }
            .show(anchor)
    }

    private fun exportToDisk() {
        val meta = UserscriptMeta.parse(binding.editorEtCode.text.toString())
        saveToDisk.launch(meta.name.ifEmpty { "script" }.replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".user.js")
    }

    private fun checkForUpdates() {
        val current = script ?: return
        lifecycleScope.launch {
            val message = try {
                when (val result = ScriptInstaller.checkForUpdate(this@ScriptEditorActivity, scriptRepository, current)) {
                    is ScriptInstaller.UpdateResult.Updated -> {
                        loadScriptData()
                        "Updated to v${result.version}"
                    }
                    ScriptInstaller.UpdateResult.UpToDate -> "Already up to date"
                    ScriptInstaller.UpdateResult.NoUpdateUrl -> "This script has no @updateURL, @downloadURL or install link"
                }
            } catch (e: Exception) {
                "Update failed: ${e.message}"
            }
            Toast.makeText(this@ScriptEditorActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        val id = scriptId
        if (id == null) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete Userscript")
            .setMessage("Delete this script and its stored values from Scraffold?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    ScriptInstaller.delete(this@ScriptEditorActivity, scriptRepository, id)
                    Toast.makeText(this@ScriptEditorActivity, "Script deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showCosmic()
    }

    private fun discardChanges() {
        setCode(savedCode, resetHistory = true)
        if (!isCodeTabActive) syncCodeToSettings()
    }

    // ---------------- Edit menu

    private fun showEditMenu(anchor: View) {
        GlassMenu(this)
            .item("Undo", "Ctrl-Z", enabled = undoStack.isNotEmpty()) { undo() }
            .item("Redo", "Shift-Ctrl-Z", enabled = redoStack.isNotEmpty()) { redo() }
            .divider()
            .item("Cut") { copySelection(); replaceSelection("") }
            .item("Copy") { copySelection() }
            .item("Paste") { paste() }
            .item("Delete") { replaceSelection("") }
            .item("Select All", "Ctrl-A") { selectAll() }
            .divider()
            .item("Toggle Comment", "Ctrl-K") { toggleLineComment() }
            .item("Toggle Block Comment", "Shift-Ctrl-K") { toggleBlockComment() }
            .show(anchor)
    }

    private fun undo() = restoreFrom(undoStack, redoStack)
    private fun redo() = restoreFrom(redoStack, undoStack)

    private fun restoreFrom(from: ArrayDeque<Snapshot>, to: ArrayDeque<Snapshot>) {
        val target = from.removeLastOrNull() ?: return
        val editor = binding.editorEtCode
        to.addLast(Snapshot(editor.text.toString(), editor.selectionStart.coerceAtLeast(0)))
        applyingHistory = true
        editor.setText(target.text)
        editor.setSelection(target.cursor.coerceAtMost(editor.length()))
        applyingHistory = false
        lastEditAt = 0L
    }

    private fun selectionRange(): Pair<Int, Int> {
        val editor = binding.editorEtCode
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        return minOf(start, end) to maxOf(start, end)
    }

    private fun replaceSelection(text: String) {
        val (start, end) = selectionRange()
        binding.editorEtCode.text.replace(start, end, text)
    }

    private fun copySelection() {
        val (start, end) = selectionRange()
        if (start == end) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("code", binding.editorEtCode.text.substring(start, end)))
    }

    private fun paste() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this) ?: return
        replaceSelection(text.toString())
    }

    private fun selectAll() {
        binding.editorEtCode.requestFocus()
        binding.editorEtCode.selectAll()
    }

    private fun toggleLineComment() {
        val editor = binding.editorEtCode
        val text = editor.text.toString()
        val (start, end) = selectionRange()
        val first = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0) 0 else it + 1 }
        val last = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val lines = text.substring(first, last).split('\n')
        val allCommented = lines.all { it.isBlank() || it.trimStart().startsWith("//") }
        val changed = lines.joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                allCommented -> line.replaceFirst(Regex("""^(\s*)// ?"""), "$1")
                else -> "// $line"
            }
        }
        editor.text.replace(first, last, changed)
        editor.setSelection(first, first + changed.length)
    }

    private fun toggleBlockComment() {
        val (start, end) = selectionRange()
        val selected = binding.editorEtCode.text.substring(start, end)
        val changed = if (selected.startsWith("/*") && selected.endsWith("*/")) {
            selected.removePrefix("/*").removeSuffix("*/").trim(' ')
        } else {
            "/* $selected */"
        }
        binding.editorEtCode.text.replace(start, end, changed)
        binding.editorEtCode.setSelection(start, start + changed.length)
    }

    // ---------------- Selection menu

    private fun showSelectionMenu(anchor: View) {
        GlassMenu(this)
            .item("Select All", "Ctrl-A") { selectAll() }
            .item("Expand Selection") { expandSelection() }
            .item("Shrink Selection", enabled = selectionHistory.isNotEmpty()) { shrinkSelection() }
            .show(anchor)
    }

    /** Word, then line, then everything. */
    private fun expandSelection() {
        val editor = binding.editorEtCode
        val text = editor.text.toString()
        val (start, end) = selectionRange()
        selectionHistory.addLast(start to end)

        var wordStart = start
        var wordEnd = end
        while (wordStart > 0 && isWordChar(text[wordStart - 1])) wordStart--
        while (wordEnd < text.length && isWordChar(text[wordEnd])) wordEnd++
        if (wordStart != start || wordEnd != end) {
            editor.setSelection(wordStart, wordEnd)
            return
        }
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        if (lineStart != start || lineEnd != end) {
            editor.setSelection(lineStart, lineEnd)
        } else {
            editor.selectAll()
        }
    }

    private fun shrinkSelection() {
        val (start, end) = selectionHistory.removeLastOrNull() ?: return
        binding.editorEtCode.setSelection(start, end)
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    // ---------------- Find menu

    private fun showFindMenu(anchor: View) {
        GlassMenu(this)
            .item("Find...", "Ctrl-F") { promptFind() }
            .item("Find Next", "F3", enabled = lastFind.isNotEmpty()) { findNext() }
            .item("Replace...", "Ctrl-H") { promptReplace() }
            .show(anchor)
    }

    private fun promptFind() {
        val input = EditText(this).apply {
            hint = "Search term"
            setText(lastFind)
        }
        AlertDialog.Builder(this)
            .setTitle("Find in script")
            .setView(dialogField(input))
            .setPositiveButton("Find") { _, _ ->
                lastFind = input.text.toString()
                findNext()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showCosmic()
    }

    /** Searches forward from the cursor and wraps round at the end. */
    private fun findNext(): Boolean {
        if (lastFind.isEmpty()) return false
        val editor = binding.editorEtCode
        val text = editor.text.toString()
        val (_, end) = selectionRange()
        var index = text.indexOf(lastFind, end, ignoreCase = true)
        if (index < 0) index = text.indexOf(lastFind, 0, ignoreCase = true)
        if (index < 0) {
            Toast.makeText(this, "\"$lastFind\" not found", Toast.LENGTH_SHORT).show()
            return false
        }
        editor.requestFocus()
        editor.setSelection(index, index + lastFind.length)
        return true
    }

    private fun promptReplace() {
        val findInput = EditText(this).apply {
            hint = "Find"
            setText(lastFind)
        }
        val replaceInput = EditText(this).apply { hint = "Replace with" }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
            addView(findInput)
            addView(replaceInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Replace")
            .setView(form)
            .setPositiveButton("Replace") { _, _ ->
                lastFind = findInput.text.toString()
                replaceNext(replaceInput.text.toString())
            }
            .setNeutralButton("Replace All") { _, _ ->
                lastFind = findInput.text.toString()
                if (lastFind.isNotEmpty()) {
                    val text = binding.editorEtCode.text.toString()
                    val count = text.split(lastFind, ignoreCase = true).size - 1
                    binding.editorEtCode.setText(text.replace(lastFind, replaceInput.text.toString(), ignoreCase = true))
                    Toast.makeText(this, "Replaced $count", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showCosmic()
    }

    private fun replaceNext(replacement: String) {
        val editor = binding.editorEtCode
        val (start, end) = selectionRange()
        val selected = editor.text.substring(start, end)
        if (selected.equals(lastFind, ignoreCase = true)) {
            editor.text.replace(start, end, replacement)
            editor.setSelection(start + replacement.length)
        }
        findNext()
    }

    // ---------------- GoTo menu

    private fun showGotoMenu(anchor: View) {
        GlassMenu(this)
            .item("Go to Line...", "Ctrl-G") { promptGotoLine() }
            .item("Jump to Bracket") { jumpToBracket() }
            .show(anchor)
    }

    private fun promptGotoLine() {
        val input = EditText(this).apply {
            hint = "Line number"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Go to line")
            .setView(dialogField(input))
            .setPositiveButton("Go") { _, _ ->
                val line = input.text.toString().toIntOrNull() ?: 1
                val lines = binding.editorEtCode.text.toString().split("\n")
                var offset = 0
                for (i in 0 until (line - 1).coerceIn(0, lines.size - 1)) {
                    offset += lines[i].length + 1
                }
                binding.editorEtCode.requestFocus()
                binding.editorEtCode.setSelection(offset.coerceAtMost(binding.editorEtCode.length()))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showCosmic()
    }

    private fun jumpToBracket() {
        val editor = binding.editorEtCode
        val text = editor.text.toString()
        val cursor = selectionRange().first
        val opens = "([{"
        val closes = ")]}"
        val at = when {
            cursor < text.length && text[cursor] in opens + closes -> cursor
            cursor > 0 && text[cursor - 1] in opens + closes -> cursor - 1
            else -> -1
        }
        if (at < 0) {
            Toast.makeText(this, "Cursor is not next to a bracket", Toast.LENGTH_SHORT).show()
            return
        }
        val c = text[at]
        val forward = c in opens
        val mate = if (forward) closes[opens.indexOf(c)] else opens[closes.indexOf(c)]
        var depth = 0
        var i = at
        while (i in text.indices) {
            if (text[i] == c) depth++
            if (text[i] == mate) depth--
            if (depth == 0) {
                editor.requestFocus()
                editor.setSelection(i)
                return
            }
            i += if (forward) 1 else -1
        }
        Toast.makeText(this, "No matching bracket", Toast.LENGTH_SHORT).show()
    }

    // ---------------- Tabs: the settings tab is a form over the code's header

    private fun switchTab(isCode: Boolean) {
        if (isCode == isCodeTabActive) return
        if (isCode) syncSettingsToCode() else syncCodeToSettings()
        isCodeTabActive = isCode
        val active = if (isCode) binding.editorTabCode else binding.editorTabSettings
        val inactive = if (isCode) binding.editorTabSettings else binding.editorTabCode
        active.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        active.setBackgroundResource(R.drawable.bg_subtab_active)
        inactive.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        inactive.setBackgroundResource(R.drawable.bg_subtab_inactive)
        binding.editorViewCode.visibility = if (isCode) View.VISIBLE else View.GONE
        binding.editorViewSettings.visibility = if (isCode) View.GONE else View.VISIBLE
    }

    private fun syncCodeToSettings() {
        val meta = UserscriptMeta.parse(binding.editorEtCode.text.toString())
        binding.editorEtScriptName.setText(meta.name)
        binding.editorEtVersion.setText(meta.version)
        binding.editorEtDescription.setText(meta.description)
        binding.editorEtMatchPattern.setText((meta.matches + meta.includes.map { "include:$it" }).joinToString("\n"))
        binding.editorTvRunAt.text = meta.runAt

        val extra = meta.grants.filter { it !in COMMON_GRANTS }
        extra.forEach { grant ->
            if (grant !in grantBoxes) {
                val box = CheckBox(this).apply {
                    text = grant
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                grantBoxes[grant] = box
                binding.editorGroupGrants.addView(box)
            }
        }
        grantBoxes.forEach { (grant, box) -> box.isChecked = grant in meta.grants }
    }

    private fun syncSettingsToCode() {
        var code = binding.editorEtCode.text.toString()
        val patterns = binding.editorEtMatchPattern.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val meta = UserscriptMeta.parse(code)
        val set = { key: String, values: List<String>, current: List<String> ->
            val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.toSet() != current.toSet()) code = UserscriptMeta.withHeaderValues(code, key, cleaned)
        }
        set("name", listOf(binding.editorEtScriptName.text.toString()), listOf(meta.name).filter { it.isNotEmpty() })
        set("version", listOf(binding.editorEtVersion.text.toString()), listOf(meta.version).filter { it.isNotEmpty() })
        set("description", listOf(binding.editorEtDescription.text.toString()), listOf(meta.description).filter { it.isNotEmpty() })
        set("match", patterns.filterNot { it.startsWith("include:") }, meta.matches)
        set("include", patterns.filter { it.startsWith("include:") }.map { it.removePrefix("include:") }, meta.includes)
        set("run-at", listOf(binding.editorTvRunAt.text.toString()), listOf(meta.runAt))
        set("grant", grantBoxes.filter { it.value.isChecked }.keys.toList(), meta.grants)
        if (code != binding.editorEtCode.text.toString()) {
            binding.editorEtCode.setText(code)
        }
    }

    // ---------------- Loading and saving

    private fun setCode(code: String, resetHistory: Boolean) {
        applyingHistory = true
        binding.editorEtCode.setText(code)
        applyingHistory = false
        if (resetHistory) {
            undoStack.clear()
            redoStack.clear()
        }
        updateStatus()
    }

    private fun updateStatus() {
        val text = binding.editorEtCode.text.toString()
        val lineCount = text.count { it == '\n' } + 1
        val numbers = StringBuilder()
        for (i in 1..lineCount) numbers.append(i).append('\n')
        binding.editorTvLineNumbers.text = numbers.toString().trimEnd()
        binding.editorTvStatusPos.text = "$lineCount lines, ${text.length} chars"
        binding.editorTvStatusType.text = "UTF-8 • JavaScript"
    }

    private fun loadScriptData() {
        val id = scriptId ?: return
        lifecycleScope.launch {
            val loaded = scriptRepository.getScriptById(id).firstOrNull() ?: return@launch
            script = loaded
            savedCode = loaded.code
            setCode(loaded.code, resetHistory = true)
            binding.editorTvBadge.text = loaded.name.lowercase().replace(Regex("\\s+"), "_") + ".user.js"
        }
    }

    private fun saveScript(closeAfter: Boolean = false) {
        if (!isCodeTabActive) syncSettingsToCode()
        val code = binding.editorEtCode.text.toString()
        val meta = UserscriptMeta.parse(code)
        if (meta.name.isEmpty()) {
            Toast.makeText(this, "Add a @name to the script header", Toast.LENGTH_LONG).show()
            return
        }
        if (meta.includeRegexes().isEmpty()) {
            Toast.makeText(this, "Add a @match so the script has a page to run on", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                val saved = ScriptInstaller.save(this@ScriptEditorActivity, scriptRepository, code, script)
                script = saved
                scriptId = saved.id
                savedCode = code
                binding.editorTvBadge.text = saved.name.lowercase().replace(Regex("\\s+"), "_") + ".user.js"
                Toast.makeText(this@ScriptEditorActivity, "Saved ✓", Toast.LENGTH_SHORT).show()
                if (closeAfter) finish()
            } catch (e: Exception) {
                Toast.makeText(this@ScriptEditorActivity, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
