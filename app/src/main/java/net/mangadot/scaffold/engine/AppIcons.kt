package net.mangadot.scaffold.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Finds, stores and shows web app icons. WebAppEntity.iconUrl holds the stored file's path. */
object AppIcons {

    private const val MAX_ICON_PX = 512
    /** An icon at least this wide looks sharp on any shelf layout, so a bigger one isn't sought. */
    private const val SHARP_ICON_PX = 256
    private const val MEMORY_CACHE_BYTES = 24 * 1024 * 1024
    private const val PAGE_ICON_POLLS = 40
    private const val PAGE_ICON_POLL_MS = 250L
    private const val MAX_PAGE_BYTES = 1024L * 1024
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val MAX_CANDIDATES = 6

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val linkTag = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)

    private data class Candidate(val url: HttpUrl, val size: Int)

    /** What a site offers for installing it: its best icon and its manifest, either of which may be missing. */
    data class Site(val icon: Bitmap?, val manifest: WebManifest?)

    /** The site's best icon: its manifest and touch icons first, then favicons. Null if it has none we can read. */
    suspend fun fetch(context: Context, pageUrl: String): Bitmap? = fetchSite(context, pageUrl).icon

    suspend fun fetchSite(context: Context, pageUrl: String): Site = withContext(Dispatchers.IO) {
        try {
            val userAgent = DeckWebView.mobileUserAgent(context)
            get(pageUrl, userAgent).use { page ->
                val base = page.request.url
                val html = if (page.isSuccessful) page.peekBody(MAX_PAGE_BYTES).string() else ""
                var manifest: WebManifest? = null
                val candidates = (parseLinks(html, base, userAgent) { manifest = it } +
                    listOfNotNull(base.resolve("/favicon.ico")?.let { Candidate(it, 0) }))
                    .sortedByDescending { it.size }
                    .distinctBy { it.url }
                    .take(MAX_CANDIDATES)
                Site(candidates.firstNotNullOfOrNull { download(it.url, userAgent) }, manifest)
            }
        } catch (e: Exception) {
            Site(null, null)
        }
    }

    /** Reads a manifest the loaded page points to, for sites that add the link with script. */
    suspend fun fetchManifest(context: Context, manifestUrl: String, pageUrl: String): WebManifest? = withContext(Dispatchers.IO) {
        try {
            val page = pageUrl.toHttpUrlOrNull() ?: return@withContext null
            get(manifestUrl, DeckWebView.mobileUserAgent(context)).use { response ->
                if (!response.isSuccessful) return@withContext null
                WebManifest.parse(JSONObject(response.peekBody(MAX_PAGE_BYTES).string()), response.request.url, page)
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

    private fun parseLinks(html: String, base: HttpUrl, userAgent: String, onManifest: (WebManifest) -> Unit): List<Candidate> {
        val found = mutableListOf<Candidate>()
        linkTag.findAll(html).forEach { match ->
            val rel = attribute(match.value, "rel")?.lowercase() ?: return@forEach
            val href = attribute(match.value, "href") ?: return@forEach
            val url = base.resolve(href.replace("&amp;", "&")) ?: return@forEach
            when {
                rel == "manifest" -> found += parseManifest(url, base, userAgent, onManifest)
                "apple-touch-icon" in rel -> found += Candidate(url, parseSize(attribute(match.value, "sizes"), 180))
                "icon" in rel -> found += Candidate(url, parseSize(attribute(match.value, "sizes"), 32))
            }
        }
        return found
    }

    private fun parseManifest(url: HttpUrl, page: HttpUrl, userAgent: String, onManifest: (WebManifest) -> Unit): List<Candidate> {
        return try {
            get(url.toString(), userAgent).use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = JSONObject(response.peekBody(MAX_PAGE_BYTES).string())
                onManifest(WebManifest.parse(json, response.request.url, page))
                val icons = json.optJSONArray("icons") ?: return emptyList()
                (0 until icons.length()).mapNotNull { index ->
                    val icon = icons.getJSONObject(index)
                    // Maskable icons are drawn edge to edge for a launcher to crop, which is how the shelf shows them
                    val size = parseSize(icon.optString("sizes"), 64) * if ("maskable" in icon.optString("purpose")) 4 else 1
                    url.resolve(icon.optString("src"))?.let { Candidate(it, size) }
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

    /**
     * Writes the icon and returns the path to store in WebAppEntity.iconUrl. The width is part of the name so
     * that an upgraded icon changes the path, which is what makes the shelf redraw it.
     */
    suspend fun save(context: Context, appId: String, bitmap: Bitmap, custom: Boolean = false): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "icons")
        dir.mkdirs()
        val file = File(dir, "$appId-${bitmap.width}.png")
        val partial = File(dir, "$appId.tmp")
        partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        storedFiles(context, appId).forEach { old ->
            memory.remove(old.path)
            old.delete()
        }
        partial.renameTo(file)
        if (custom) customMarker(context, appId).createNewFile()
        file.path
    }

    /**
     * Asks the loaded page for its largest icon, since the page already got past whatever blocks a plain
     * request and can also draw SVG. Null when the app's icon is already sharp, was chosen by the user, or
     * the page has nothing bigger.
     */
    suspend fun betterIconFromPage(context: Context, webView: WebView, app: WebAppEntity): Bitmap? {
        if (customMarker(context, app.id).exists()) return null
        val current = withContext(Dispatchers.IO) { storedWidth(app) }
        if (current >= SHARP_ICON_PX) return null
        val slot = "__sfIcon" + UUID.randomUUID().toString().replace("-", "")
        webView.evaluateJavascript(pageIconScript(slot), null)
        repeat(PAGE_ICON_POLLS) {
            delay(PAGE_ICON_POLL_MS)
            val raw = evaluate(webView, "(function(){var v=window.$slot;if(v!==null&&v!==undefined)delete window.$slot;return v})()")
            if (raw != null && raw != "null") {
                val icon = withContext(Dispatchers.Default) { decodeDataUrl(raw) } ?: return null
                return icon.takeIf { it.width > current }
            }
        }
        return null
    }

    private suspend fun evaluate(webView: WebView, script: String): String? = suspendCancellableCoroutine { done ->
        webView.evaluateJavascript(script) { if (done.isActive) done.resume(it) }
    }

    private fun decodeDataUrl(raw: String): Bitmap? {
        return try {
            val url = JSONArray("[$raw]").getString(0)
            val bytes = Base64.decode(url.substringAfter("base64,", ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun storedWidth(app: WebAppEntity): Int {
        val path = app.iconUrl?.takeIf { it.isNotEmpty() } ?: return 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return bounds.outWidth.coerceAtLeast(0)
    }

    private fun pageIconScript(slot: String) = """
(function () {
  var slot = ${JSONObject.quote(slot)};
  var found = [];
  var size = function (text, fallback) {
    var best = 0;
    (text || '').replace(/(\d+)x\d+/gi, function (_, n) { best = Math.max(best, +n); });
    return best || fallback;
  };
  var add = function (url, score, base) {
    try { found.push({ url: new URL(url, base || document.baseURI).href, score: score }); } catch (e) {}
  };
  var drawn = function (source, width, height) {
    var longest = Math.max(width, height);
    var ratio = Math.min(1, $MAX_ICON_PX / longest);
    var canvas = document.createElement('canvas');
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    canvas.getContext('2d').drawImage(source, 0, 0, canvas.width, canvas.height);
    return canvas.toDataURL('image/png');
  };
  var read = async function (blob) {
    try {
      var bitmap = await createImageBitmap(blob);
      return drawn(bitmap, bitmap.width, bitmap.height);
    } catch (e) {
      // SVG can't become an ImageBitmap directly, so it goes through an image at the largest size
      var url = await new Promise(function (ok, no) {
        var reader = new FileReader();
        reader.onload = function () { ok(reader.result); };
        reader.onerror = no;
        reader.readAsDataURL(blob);
      });
      var image = new Image();
      image.src = url;
      await image.decode();
      return drawn(image, image.naturalWidth || $MAX_ICON_PX, image.naturalHeight || $MAX_ICON_PX);
    }
  };
  (async function () {
    var links = document.querySelectorAll('link[rel~="icon"],link[rel~="apple-touch-icon"],link[rel~="manifest"]');
    for (var i = 0; i < links.length; i++) {
      var link = links[i];
      if (link.rel.indexOf('manifest') >= 0) {
        try {
          var manifest = await (await fetch(link.href)).json();
          (manifest.icons || []).forEach(function (icon) {
            var score = /svg/.test(icon.type || icon.src) ? $MAX_ICON_PX : size(icon.sizes, 64);
            add(icon.src, /maskable/.test(icon.purpose || '') ? score * 4 : score, link.href);
          });
        } catch (e) {}
      } else {
        var isTouch = link.rel.indexOf('apple-touch-icon') >= 0;
        var svg = /svg/.test(link.type) || /\.svg(\?|$)/.test(link.href);
        add(link.href, svg ? $MAX_ICON_PX : size(link.getAttribute('sizes'), isTouch ? 180 : 32));
      }
    }
    found.sort(function (a, b) { return b.score - a.score; });
    for (var n = 0; n < Math.min(found.length, $MAX_CANDIDATES); n++) {
      try {
        var response = await fetch(found[n].url);
        if (response.ok) {
          window[slot] = await read(await response.blob());
          return;
        }
      } catch (e) {}
    }
    window[slot] = '';
  })().catch(function () { window[slot] = ''; });
})();
"""

    /** Stores already-encoded icon bytes from a backup, and returns the path for WebAppEntity.iconUrl. */
    fun saveEncoded(context: Context, appId: String, bytes: ByteArray): String {
        val file = file(context, appId)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        memory.remove(file.path)
        return file.path
    }

    fun delete(context: Context, appId: String) {
        storedFiles(context, appId).forEach { file ->
            memory.remove(file.path)
            file.delete()
        }
        customMarker(context, appId).delete()
    }

    private fun storedFiles(context: Context, appId: String): List<File> {
        return File(context.filesDir, "icons").listFiles { file ->
            file.name.endsWith(".png") && (file.name == "$appId.png" || file.name.startsWith("$appId-"))
        }?.toList().orEmpty()
    }

    private fun customMarker(context: Context, appId: String) = File(context.filesDir, "icons/$appId.custom")

    private fun file(context: Context, appId: String) = File(context.filesDir, "icons/$appId.png")

    /** Shows the app's icon over its monogram tile, or leaves the monogram when it has none. */
    fun bind(icon: ImageView, monogram: View, app: WebAppEntity) {
        val bitmap = app.iconUrl?.let(::load)
        icon.setImageBitmap(bitmap)
        icon.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        monogram.visibility = if (bitmap != null) View.GONE else View.VISIBLE
        (icon.parent as? View)?.clipToOutline = true
    }

    /** A small copy for the home screen widget, which can't hold full-size icons. Null when the app has no icon. */
    fun thumbnail(path: String?, sizePx: Int): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun load(path: String): Bitmap? {
        memory.get(path)?.let { return it }
        return BitmapFactory.decodeFile(path)?.also { memory.put(path, it) }
    }
}
