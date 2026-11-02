package net.mangadot.scaffold.engine.userscript

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Saving, installing and updating scripts, including their @require and @resource files. */
object ScriptInstaller {

    // Android can't read back a row over 2 MB, which would break the whole table's queries
    private const val MAX_SCRIPT_BYTES = 1_500_000
    // Stored as UTF-8, so this many characters always stays well under that limit
    private const val MAX_INLINE_VALUE_CHARS = 256 * 1024
    private const val VALUE_FILE_PREFIX = "value_"
    // Stored values are JSON, which can't start with a control character
    private const val VALUE_IN_FILE = "\u0001scraffold-value-in-file"

    private val client = OkHttpClient()

    sealed class UpdateResult {
        data class Updated(val version: String) : UpdateResult()
        object UpToDate : UpdateResult()
        object NoUpdateUrl : UpdateResult()
    }

    suspend fun fetchText(url: String): String = String(fetchBytes(url), Charsets.UTF_8)

    private suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * Stores [code] under [existing]'s identity when given. Throws if a @require or @resource
     * can't be downloaded, because the script would break at runtime without it.
     */
    suspend fun save(
        context: Context,
        repository: ScriptRepository,
        rawCode: String,
        existing: ScriptEntity? = null,
        sourceUrl: String? = existing?.sourceUrl,
        fallbackName: String = "Untitled Script"
    ): ScriptEntity {
        val code = rawCode.replace("\r\n", "\n")
        if (code.toByteArray().size > MAX_SCRIPT_BYTES) {
            throw IOException("script is over 1.5 MB; move large libraries to @require")
        }
        val meta = UserscriptMeta.parse(code)
        val base = existing ?: ScriptEntity(name = "", code = "", matchPattern = "")
        val script = base.copy(
            name = meta.name.ifEmpty { fallbackName },
            code = code,
            matchPattern = (meta.matches + meta.includes).joinToString(" "),
            runAt = meta.runAt,
            sourceUrl = sourceUrl,
            updatedAt = System.currentTimeMillis()
        )
        downloadDependencies(context, script.id, meta)
        repository.insertScript(script)
        return script
    }

    suspend fun delete(context: Context, repository: ScriptRepository, id: String) {
        repository.deleteScriptById(id)
        repository.clearAppStorage(id)
        withContext(Dispatchers.IO) { scriptDir(context, id).deleteRecursively() }
    }

    /** Saves a GM value, keeping large ones in a file beside the script. */
    suspend fun storeValue(context: Context, repository: ScriptRepository, id: String, key: String, value: String) {
        val file = valueFile(context, id, key)
        if (value.length > MAX_INLINE_VALUE_CHARS) {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                // Replaced in one step, so a crash mid-write can't leave half a value
                val partial = File(file.path + ".tmp")
                partial.writeText(value)
                partial.renameTo(file)
            }
            repository.setStorageValue(id, key, VALUE_IN_FILE)
        } else {
            repository.setStorageValue(id, key, value)
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    /** What a stored row really holds, or null if its file has gone. */
    fun restoreValue(context: Context, id: String, key: String, stored: String): String? {
        if (stored != VALUE_IN_FILE) return stored
        return valueFile(context, id, key).takeIf { it.exists() }?.readText()
    }

    suspend fun removeValue(context: Context, repository: ScriptRepository, id: String, key: String) {
        repository.deleteStorageValue(id, key)
        withContext(Dispatchers.IO) { valueFile(context, id, key).delete() }
    }

    suspend fun clearValues(context: Context, repository: ScriptRepository, id: String) {
        repository.clearAppStorage(id)
        withContext(Dispatchers.IO) { valueFiles(context, id).forEach { it.delete() } }
    }

    fun hasValueFiles(context: Context, id: String) = valueFiles(context, id).isNotEmpty()

    private fun valueFiles(context: Context, id: String): List<File> {
        return scriptDir(context, id).listFiles { file -> file.name.startsWith(VALUE_FILE_PREFIX) }?.toList().orEmpty()
    }

    private fun valueFile(context: Context, id: String, key: String) =
        File(scriptDir(context, id), VALUE_FILE_PREFIX + hash(key))

    suspend fun checkForUpdate(context: Context, repository: ScriptRepository, script: ScriptEntity): UpdateResult {
        val meta = UserscriptMeta.parse(script.code)
        val checkUrl = meta.updateUrl ?: meta.downloadUrl ?: script.sourceUrl ?: return UpdateResult.NoUpdateUrl
        val remote = UserscriptMeta.parse(fetchText(checkUrl))
        if (!UserscriptMeta.isNewerVersion(remote.version, meta.version)) return UpdateResult.UpToDate
        val code = fetchText(remote.downloadUrl ?: meta.downloadUrl ?: script.sourceUrl ?: checkUrl)
        save(context, repository, code, script)
        return UpdateResult.Updated(remote.version)
    }

    private suspend fun downloadDependencies(context: Context, id: String, meta: UserscriptMeta) {
        val dir = scriptDir(context, id)
        meta.requires.forEach { url ->
            val bytes = fetchBytes(url)
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                File(dir, "require_" + hash(url)).writeBytes(bytes)
            }
        }
        meta.resources.forEach { (name, url) ->
            val bytes = fetchBytes(url)
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                File(dir, "resource_" + hash(name)).writeBytes(bytes)
            }
        }
    }

    fun readRequires(context: Context, id: String, meta: UserscriptMeta): List<String> {
        return meta.requires.mapNotNull { url ->
            File(scriptDir(context, id), "require_" + hash(url)).takeIf { it.exists() }?.readText()
        }
    }

    fun readResource(context: Context, id: String, name: String): ByteArray? {
        return File(scriptDir(context, id), "resource_" + hash(name)).takeIf { it.exists() }?.readBytes()
    }

    private fun scriptDir(context: Context, id: String) = File(context.filesDir, "userscripts/$id")

    private fun hash(text: String): String {
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
