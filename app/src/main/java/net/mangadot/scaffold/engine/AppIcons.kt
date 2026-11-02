package net.mangadot.scaffold.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.webkit.WebSettings
import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Finds, stores and shows web app icons. WebAppEntity.iconUrl holds the stored file's path. */
object AppIcons {

    private const val MAX_ICON_PX = 192
    private const val MAX_PAGE_BYTES = 1024L * 1024
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val MAX_CANDIDATES = 6

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val memory = LruCache<String, Bitmap>(48)
    private val linkTag = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)

    private data class Candidate(val url: HttpUrl, val size: Int)

    /** The site's best icon: its manifest and touch icons first, then favicons. Null if it has none we can read. */
    suspend fun fetch(context: Context, pageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val userAgent = WebSettings.getDefaultUserAgent(context)
            get(pageUrl, userAgent).use { page ->
                val base = page.request.url
                val html = if (page.isSuccessful) page.peekBody(MAX_PAGE_BYTES).string() else ""
                val candidates = (parseLinks(html, base, userAgent) +
                    listOfNotNull(base.resolve("/favicon.ico")?.let { Candidate(it, 0) }))
                    .sortedByDescending { it.size }
                    .distinctBy { it.url }
                    .take(MAX_CANDIDATES)
                candidates.firstNotNullOfOrNull { download(it.url, userAgent) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun get(url: String, userAgent: String): Response {
        return client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute()
    }

    private fun attribute(tag: String, name: String): String? {
        return Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
    }

    private fun parseLinks(html: String, base: HttpUrl, userAgent: String): List<Candidate> {
        val found = mutableListOf<Candidate>()
        linkTag.findAll(html).forEach { match ->
            val rel = attribute(match.value, "rel")?.lowercase() ?: return@forEach
            val href = attribute(match.value, "href") ?: return@forEach
            val url = base.resolve(href.replace("&amp;", "&")) ?: return@forEach
            when {
                rel == "manifest" -> found += parseManifest(url, userAgent)
                "apple-touch-icon" in rel -> found += Candidate(url, parseSize(attribute(match.value, "sizes"), 180))
                "icon" in rel -> found += Candidate(url, parseSize(attribute(match.value, "sizes"), 32))
            }
        }
        return found
    }

    private fun parseManifest(url: HttpUrl, userAgent: String): List<Candidate> {
        return try {
            get(url.toString(), userAgent).use { response ->
                if (!response.isSuccessful) return emptyList()
                val icons = JSONObject(response.peekBody(MAX_PAGE_BYTES).string()).optJSONArray("icons")
                    ?: return emptyList()
                (0 until icons.length()).mapNotNull { index ->
                    val icon = icons.getJSONObject(index)
                    url.resolve(icon.optString("src"))?.let { Candidate(it, parseSize(icon.optString("sizes"), 64)) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** "48x48 192x192" gives 192. Anything unreadable, such as "any", gets the fallback. */
    private fun parseSize(sizes: String?, fallback: Int): Int {
        return Regex("""(\d+)x\d+""", RegexOption.IGNORE_CASE).findAll(sizes.orEmpty())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: fallback
    }

    private fun download(url: HttpUrl, userAgent: String): Bitmap? {
        return try {
            get(url.toString(), userAgent).use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.peekBody(MAX_IMAGE_BYTES).bytes()
                // SVG and anything else BitmapFactory can't read comes back null, so the next candidate is tried
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::scaled)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Reads an image the user picked, without loading a full-size photo into memory. */
    suspend fun decode(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_ICON_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.let(::scaled)
        } catch (e: Exception) {
            null
        }
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_ICON_PX) return bitmap
        val ratio = MAX_ICON_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    /** Writes the icon and returns the path to store in WebAppEntity.iconUrl. */
    suspend fun save(context: Context, appId: String, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val file = file(context, appId)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        memory.remove(file.path)
        file.path
    }

    fun delete(context: Context, appId: String) {
        val file = file(context, appId)
        memory.remove(file.path)
        file.delete()
    }

    private fun file(context: Context, appId: String) = File(context.filesDir, "icons/$appId.png")

    /** Shows the app's icon over its monogram tile, or leaves the monogram when it has none. */
    fun bind(icon: ImageView, monogram: View, app: WebAppEntity) {
        val bitmap = app.iconUrl?.let(::load)
        icon.setImageBitmap(bitmap)
        icon.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        monogram.visibility = if (bitmap != null) View.GONE else View.VISIBLE
        (icon.parent as? View)?.clipToOutline = true
    }

    private fun load(path: String): Bitmap? {
        memory.get(path)?.let { return it }
        return BitmapFactory.decodeFile(path)?.also { memory.put(path, it) }
    }
}
