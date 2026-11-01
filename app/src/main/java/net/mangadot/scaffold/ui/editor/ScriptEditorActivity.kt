package net.mangadot.scaffold.ui.editor

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.databinding.ActivityScriptEditorBinding

class ScriptEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var scriptRepository: ScriptRepository
    private var scriptId: String? = null

    companion object {
        const val EXTRA_SCRIPT_ID = "extra_script_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())

        scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID)

        setupListeners()
        loadScriptData()
    }

    private fun setupListeners() {
        binding.editorBtnBack.setOnClickListener {
            finish()
        }

        binding.editorBtnSave.setOnClickListener {
            saveScript()
        }
    }

    private fun loadScriptData() {
        val id = scriptId ?: return
        lifecycleScope.launch {
            val script = scriptRepository.getScriptById(id).firstOrNull() ?: return@launch
            binding.editorEtScriptName.setText(script.name)
            binding.editorEtMatchPattern.setText(script.matchPattern)
            binding.editorEtCode.setText(script.code)
        }
    }

    private fun saveScript() {
        val name = binding.editorEtScriptName.text.toString().trim()
        val pattern = binding.editorEtMatchPattern.text.toString().trim()
        val code = binding.editorEtCode.text.toString()

        if (name.isEmpty() || pattern.isEmpty()) {
            Toast.makeText(this, "Please enter script name and match pattern", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this@ScriptEditorActivity, "Script saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
