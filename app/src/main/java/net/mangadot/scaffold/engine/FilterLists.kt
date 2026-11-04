package net.mangadot.scaffold.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * uBlock Origin's default public lists, plus AdGuard's for mobile ads, and Brave's scriptlet
 * resources, and uBlock Origin's scriptlets and redirect stand-ins. They are fetched from their
 * publishers and kept on the device, which leaves them under their own licenses (uBlock Origin's
 * being GPLv3) rather than shipped in the app.
 */
object FilterLists {

    private class FilterList(val fileName: String, val url: String, val trackers: Boolean = false, val trusted: Boolean = false)

    private val LISTS = listOf(
        FilterList("easylist.txt", "https://easylist.to/easylist/easylist.txt"),
        FilterList("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt", trackers = true),
        // uBlock Origin's own lists may use the scriptlets it marks as needing trust, as they can there
        FilterList("ublock.txt", "https://ublockorigin.github.io/uAssets/filters/filters.txt", trusted = true),
        FilterList("ublock-privacy.txt", "https://ublockorigin.github.io/uAssets/filters/privacy.txt", trackers = true, trusted = true),
        FilterList("ublock-badware.txt", "https://ublockorigin.github.io/uAssets/filters/badware.txt", trusted = true),
        FilterList("ublock-unbreak.txt", "https://ublockorigin.github.io/uAssets/filters/unbreak.txt", trusted = true),
        FilterList("ublock-quick-fixes.txt", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt", trusted = true),
        FilterList("peter-lowe.txt", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"),
        FilterList("adguard-mobile.txt", "https://filters.adtidy.org/extension/ublock/filters/11.txt")
    )

    // Brave's scriptlets that +js rules call on, under MPL-2.0
    private val RESOURCES = FilterList("resources.json", "https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json")

    /**
     * The scriptlets the lists' +js rules mostly call on, YouTube's among them: uBlock Origin's,
     * from its latest release. Any module exporting `builtinScriptlets` the same way can stand in.
     */
    const val SCRIPTLETS_SOURCE = "https://cdn.jsdelivr.net/gh/gorhill/uBlock/src/js/resources/scriptlets.js"
    private const val SCRIPTLETS_FILE = "scriptlets.json"

    /**
     * The harmless files $redirect rules hand pages in place of what they block: uBlock Origin's,
     * from the same release. Any module exporting the same map, beside a web_accessible_resources
     * folder holding the files it names, can stand in.
     */
    const val REDIRECTS_SOURCE = "https://cdn.jsdelivr.net/gh/gorhill/uBlock/src/js/redirect-resources.js"
    private const val REDIRECTS_FILE = "redirects.json"
    private val RESOURCE_FILES = listOf(RESOURCES.fileName, SCRIPTLETS_FILE, REDIRECTS_FILE)

    private const val PREFS_NAME = "scraffold_filter_lists"
    private const val KEY_UPDATED = "updated_at"
    private const val KEY_ATTEMPTED = "attempted_at"
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(4)
    private val RETRY_AFTER_MS = TimeUnit.HOURS.toMillis(6)
    private const val MAX_LIST_BYTES = 20L * 1024 * 1024
    private const val MIN_LIST_LINES = 20

    private fun folder(context: Context) = File(context.filesDir, "filters").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun files(context: Context): List<File> {
        return LISTS.map { File(folder(context), it.fileName) }.filter { it.exists() }
    }

    /** The downloaded lists that are about tracking rather than ads, for telling the two apart. */
    fun trackerFiles(context: Context): List<File> {
        return LISTS.filter { it.trackers }.map { File(folder(context), it.fileName) }.filter { it.exists() }
    }

    fun trustedFiles(context: Context): List<File> {
        return LISTS.filter { it.trusted }.map { File(folder(context), it.fileName) }.filter { it.exists() }
    }

    /** Brave's resources, then the imported scriptlets and redirect stand-ins when there are any. */
    fun resources(context: Context): List<File> {
        return RESOURCE_FILES.map { File(folder(context), it) }.filter { it.exists() }
    }

    /** Keeps what [ScriptletImport] produced. A half that failed leaves its last good copy in place. */
    fun saveImport(context: Context, result: ScriptletImport.Result) {
        result.scriptlets?.let { save(context, SCRIPTLETS_FILE, it) }
        result.redirects?.let { save(context, REDIRECTS_FILE, it) }
    }

    private fun save(context: Context, fileName: String, json: String) {
        val partial = File(folder(context), "$fileName.part")
        partial.writeText(json)
        if (!partial.renameTo(File(folder(context), fileName))) partial.delete()
    }

    /** How many block lists there are to download. */
    val listCount: Int
        get() = LISTS.size

    /** How many of the block lists are on the phone. */
    fun downloadedCount(context: Context): Int = files(context).size

    fun lastUpdated(context: Context): Long = prefs(context).getLong(KEY_UPDATED, 0L)

    fun isDue(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val stale = now - lastUpdated(context) > MAX_AGE_MS || files(context).size < LISTS.size || resources(context).size < RESOURCE_FILES.size
        return stale && now - prefs(context).getLong(KEY_ATTEMPTED, 0L) > RETRY_AFTER_MS
    }

    /** A list that fails keeps its last good copy. Returns how many lists arrived. */
    suspend fun update(context: Context): Int = withContext(Dispatchers.IO) {
        prefs(context).edit().putLong(KEY_ATTEMPTED, System.currentTimeMillis()).apply()
        val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
        val arrived = (LISTS + RESOURCES).count { list ->
            try {
                download(client, list, folder(context))
                true
            } catch (e: IOException) {
                false
            }
        }
        if (arrived > 0) prefs(context).edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply()
        arrived
    }

    private fun download(client: OkHttpClient, list: FilterList, folder: File) {
        val partial = File(folder, list.fileName + ".part")
        try {
            client.newCall(Request.Builder().url(list.url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) throw IOException("HTTP ${response.code}")
                if (body.contentLength() > MAX_LIST_BYTES) throw IOException("Too large")
                partial.outputStream().use { out ->
                    val copied = body.byteStream().copyTo(out)
                    if (copied > MAX_LIST_BYTES) throw IOException("Too large")
                }
            }
            // An error page served with a success code must not replace a good list
            if (list === RESOURCES) {
                val start = partial.bufferedReader().use { reader -> CharArray(64).let { String(it, 0, maxOf(reader.read(it), 0)) } }
                if (!start.trimStart().startsWith("[{")) throw IOException("Not a resource list")
            } else {
                val lines = partial.useLines { it.take(MIN_LIST_LINES).toList() }
                if (lines.size < MIN_LIST_LINES || lines.any { it.contains("<html", ignoreCase = true) }) throw IOException("Not a filter list")
            }
            if (!partial.renameTo(File(folder, list.fileName))) throw IOException("Couldn't save")
        } finally {
            partial.delete()
        }
    }
}
