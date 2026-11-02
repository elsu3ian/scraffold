package net.mangadot.scaffold.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.BrowserHistoryEntity
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.db.entity.ScriptStorageEntity
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.engine.userscript.ScriptInstaller
import java.io.File
import java.io.IOException

/**
 * scraffold-backup.json holds the shelf, scripts with their files and stored values, icons,
 * browser history and settings. Logins are deliberately not included: they stay in each WebView profile.
 */
class BackupManager(private val context: Context) {

    data class BackupFile(
        @SerializedName("format") val format: String = FORMAT,
        @SerializedName("version") val version: Int = VERSION,
        @SerializedName("webApps") val webApps: List<WebAppEntity> = emptyList(),
        @SerializedName("scripts") val scripts: List<ScriptEntity> = emptyList(),
        @SerializedName("values") val values: List<ScriptStorageEntity> = emptyList(),
        // Script id, then file name, then base64 content
        @SerializedName("scriptFiles") val scriptFiles: Map<String, Map<String, String>> = emptyMap(),
        // App id, then base64 PNG
        @SerializedName("icons") val icons: Map<String, String> = emptyMap(),
        // Preference file, then key, then a boolean or int value
        @SerializedName("settings") val settings: Map<String, Map<String, Any>> = emptyMap(),
        @SerializedName("browserHistory") val browserHistory: List<BrowserHistoryEntity> = emptyList()
    )

    data class Summary(val apps: Int, val scripts: Int)

    private val db = AppDatabase.getInstance(context)
    private val gson = Gson()

    suspend fun export(target: Uri): Summary = withContext(Dispatchers.IO) {
        val apps = db.webAppDao().getAll()
        val scripts = db.scriptDao().getAll()
        val backup = BackupFile(
            webApps = apps.map { it.copy(iconUrl = null) },
            scripts = scripts,
            values = db.scriptStorageDao().getAll(),
            scriptFiles = scripts.associate { script ->
                script.id to ScriptInstaller.scriptFiles(context, script.id).associate { it.name to encode(it) }
            }.filterValues { it.isNotEmpty() },
            icons = apps.mapNotNull { app ->
                app.iconUrl?.let(::File)?.takeIf { it.exists() }?.let { app.id to encode(it) }
            }.toMap(),
            settings = SETTINGS_FILES.associateWith { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    .filterValues { it is Boolean || it is Int }
                    .mapValues { it.value as Any }
            },
            browserHistory = db.browserHistoryDao().getAll()
        )
        val stream = context.contentResolver.openOutputStream(target) ?: throw IOException("Couldn't open that file")
        stream.bufferedWriter().use { gson.toJson(backup, it) }
        Summary(apps.size, scripts.size)
    }

    /** Reads and checks a backup without changing anything, so the user can confirm first. */
    suspend fun read(source: Uri): BackupFile = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(source) ?: throw IOException("Couldn't open that file")
        // Checked on the raw JSON, since a parsed file without this field would get the default value
        val backup = try {
            val json = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
            if (json.get("format")?.asString != FORMAT) throw IOException("Not a backup")
            gson.fromJson(json, BackupFile::class.java)
        } catch (e: Exception) {
            throw IOException("That isn't a Scraffold backup")
        }
        if (backup.version > VERSION) throw IOException("That backup is from a newer version of Scraffold")
        backup
    }

    /** Adds to what is already here; items with the same id are replaced. */
    suspend fun restore(backup: BackupFile): Summary = withContext(Dispatchers.IO) {
        // A file can claim any name, so nothing from it may choose where it lands outside these folders
        val scripts = backup.scripts.filter { SAFE_NAME.matches(it.id) && ScriptInstaller.fitsInDatabase(it.code) }
        val scriptIds = scripts.map { it.id }.toSet()
        val apps = backup.webApps.filter { SAFE_NAME.matches(it.id) }
        val icons = backup.icons.filterKeys { key -> apps.any { it.id == key } }
        val restored = apps.map { app ->
            val icon = icons[app.id]?.let { AppIcons.saveEncoded(context, app.id, decode(it)) }
            app.copy(iconUrl = icon)
        }
        db.withTransaction {
            restored.forEach { db.webAppDao().insertWebApp(it) }
            scripts.forEach { db.scriptDao().insertScript(it) }
            backup.values
                .filter { it.scriptId in scriptIds && ScriptInstaller.fitsInDatabase(it.value) }
                .forEach { db.scriptStorageDao().setValue(it) }
            // A tapped history row opens its address, so a file can't be allowed to supply a script: URL
            backup.browserHistory
                .filter { WEB_ADDRESS.containsMatchIn(it.url) }
                .forEach { db.browserHistoryDao().insert(it) }
        }
        backup.scriptFiles.filterKeys { it in scriptIds }.forEach { (id, files) ->
            files.filterKeys { SAFE_NAME.matches(it) }.forEach { (name, content) ->
                ScriptInstaller.writeScriptFile(context, id, name, decode(content))
            }
        }
        backup.settings.filterKeys { it in SETTINGS_FILES }.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    // JSON has one number type, which Gson reads back as a double
                    is Number -> editor.putInt(key, value.toInt())
                }
            }
            editor.apply()
        }
        Summary(restored.size, scripts.size)
    }

    private fun encode(file: File) = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val FORMAT = "scraffold-backup"
        const val VERSION = 1
        val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        val WEB_ADDRESS = Regex("^https?://", RegexOption.IGNORE_CASE)
        val SETTINGS_FILES = listOf("scraffold_global_prefs", "scraffold_web_defaults", "scraffold_cosmic", "scraffold_text_zoom", "scraffold_shelf", "scraffold_page_zoom")
    }
}
