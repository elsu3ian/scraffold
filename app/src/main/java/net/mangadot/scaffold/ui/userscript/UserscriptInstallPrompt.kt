package net.mangadot.scaffold.ui.userscript

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.engine.userscript.ScriptInstaller
import net.mangadot.scaffold.engine.userscript.UserscriptMeta
import net.mangadot.scaffold.ui.theme.showCosmic

/** Shows what a script asks for before it is installed, as Tampermonkey does. */
object UserscriptInstallPrompt {

    fun fromUrl(activity: AppCompatActivity, repository: ScriptRepository, url: String) {
        activity.lifecycleScope.launch {
            try {
                show(activity, repository, ScriptInstaller.fetchText(url), sourceUrl = url)
            } catch (e: Exception) {
                Toast.makeText(activity, "Couldn't fetch script: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun show(activity: AppCompatActivity, repository: ScriptRepository, code: String, sourceUrl: String?) {
        val meta = UserscriptMeta.parse(code)
        if (meta.name.isEmpty()) {
            Toast.makeText(activity, "That file has no ==UserScript== header with a @name", Toast.LENGTH_LONG).show()
            return
        }
        activity.lifecycleScope.launch {
            val existing = repository.allScripts.first().firstOrNull {
                val installed = UserscriptMeta.parse(it.code)
                it.name == meta.name && installed.namespace == meta.namespace
            }
            val details = buildString {
                if (meta.version.isNotEmpty()) append("Version ${meta.version}\n")
                if (meta.author.isNotEmpty()) append("By ${meta.author}\n")
                if (meta.description.isNotEmpty()) append("\n${meta.description}\n")
                append("\nRuns on:\n")
                (meta.matches + meta.includes).forEach { append("  $it\n") }
                if (meta.grants.isNotEmpty()) append("\nPermissions:\n  ${meta.grants.joinToString(", ")}\n")
                if (meta.requires.isNotEmpty()) append("\nDownloads:\n  ${meta.requires.joinToString("\n  ")}\n")
                if (existing != null) append("\nReplaces the installed ${existing.name}.")
            }
            AlertDialog.Builder(activity)
                .setTitle(if (existing != null) "Update ${meta.name}" else "Install ${meta.name}")
                .setMessage(details)
                .setPositiveButton(if (existing != null) "Update" else "Install") { _, _ ->
                    activity.lifecycleScope.launch {
                        try {
                            ScriptInstaller.save(activity, repository, code, existing, sourceUrl ?: existing?.sourceUrl)
                            Toast.makeText(activity, "Installed ${meta.name}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(activity, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showCosmic()
        }
    }
}
