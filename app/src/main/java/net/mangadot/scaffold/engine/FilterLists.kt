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
 * uBlock Origin's default public lists, plus AdGuard's for mobile ads. They are fetched from their
 * publishers and kept on the device, which leaves them under their own licenses rather than shipped in the app.
 */
object FilterLists {

    private class FilterList(val fileName: String, val url: String)

    private val LISTS = listOf(
        FilterList("easylist.txt", "https://easylist.to/easylist/easylist.txt"),
        FilterList("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt"),
        FilterList("ublock.txt", "https://ublockorigin.github.io/uAssets/filters/filters.txt"),
        FilterList("ublock-privacy.txt", "https://ublockorigin.github.io/uAssets/filters/privacy.txt"),
        FilterList("ublock-badware.txt", "https://ublockorigin.github.io/uAssets/filters/badware.txt"),
        FilterList("ublock-unbreak.txt", "https://ublockorigin.github.io/uAssets/filters/unbreak.txt"),
        FilterList("ublock-quick-fixes.txt", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt"),
        FilterList("peter-lowe.txt", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"),
        FilterList("adguard-mobile.txt", "https://filters.adtidy.org/extension/ublock/filters/11.txt")
    )

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

    fun lastUpdated(context: Context): Long = prefs(context).getLong(KEY_UPDATED, 0L)

    fun isDue(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val stale = now - lastUpdated(context) > MAX_AGE_MS || files(context).size < LISTS.size
        return stale && now - prefs(context).getLong(KEY_ATTEMPTED, 0L) > RETRY_AFTER_MS
    }

    /** A list that fails keeps its last good copy. Returns how many lists arrived. */
    suspend fun update(context: Context): Int = withContext(Dispatchers.IO) {
        prefs(context).edit().putLong(KEY_ATTEMPTED, System.currentTimeMillis()).apply()
        val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
        val arrived = LISTS.count { list ->
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
            val lines = partial.useLines { it.take(MIN_LIST_LINES).toList() }
            if (lines.size < MIN_LIST_LINES || lines.any { it.contains("<html", ignoreCase = true) }) throw IOException("Not a filter list")
            if (!partial.renameTo(File(folder, list.fileName))) throw IOException("Couldn't save")
        } finally {
            partial.delete()
        }
    }
}
