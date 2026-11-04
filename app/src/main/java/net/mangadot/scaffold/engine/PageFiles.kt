package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.DownloadEntity
import net.mangadot.scaffold.ui.downloads.DownloadFiles
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves files a page makes itself, straight to Downloads. Two ways in:
 *
 * - A blob: or data: link, which only exists inside the page, so the download manager can't fetch
 *   it. The page reads the file out and hands it over in pieces.
 * - The save picker desktop Chrome has, which sites that put a file together (a video joined from
 *   its stream, say) write to piece by piece as it arrives. Android's WebView lacks it, so this
 *   stands in, with Downloads for the place picked. Nothing has to fit in the page's own storage first.
 */
class PageFiles private constructor(private val webView: DeckWebView, private val event: String) {

    private class Asked(val contentDisposition: String?, val mimeType: String?, val site: String, val appId: String?)

    /**
     * Where the file goes: a Downloads entry from Android 10, a file in the Downloads folder before.
     * Pieces may land anywhere in it, as the save picker allows. [done] gives the name it ended up with.
     */
    private class Target(
        val name: String,
        val location: String,
        val channel: FileChannel,
        val closeAll: () -> Unit,
        val done: (Boolean) -> String
    )

    private class Saving(val target: Target, val mimeType: String, val row: Long) {
        /** The end of the furthest piece, which is the file's size so far. */
        var written = 0L
    }

    private val context: Context = webView.context
    private val main = Handler(Looper.getMainLooper())
    private val downloads = AppDatabase.getInstance(context).downloadDao()
    private val asked = ConcurrentHashMap<String, Asked>()
    private val saving = ConcurrentHashMap<String, Saving>()

    fun save(context: Context, url: String, contentDisposition: String?, mimeType: String?) {
        if (!WebDownloads.canWrite(context)) return
        // The page's address can only be read here, on the main thread
        asked[url] = Asked(contentDisposition, mimeType, WebDownloads.siteOf(webView.url).orEmpty(), webView.appId)
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent(${JSONObject.quote(event)},{detail:${JSONObject.quote(url)}}))",
            null
        )
    }

    /** A new page: whatever the last one was still writing won't be finished, so it goes. */
    fun pageStarted() {
        saving.keys.toList().forEach { end(it, false, quietly = true) }
    }

    // Called from the page, on WebView's own thread for it, so writing to storage here keeps the screen free
    private inner class Bridge {

        /** A blob: or data: link the person clicked. */
        @JavascriptInterface
        fun begin(url: String, name: String, type: String, size: Double): String {
            // Only a file the person started downloading, never one a page asks to save by itself
            val request = asked.remove(url) ?: return ""
            return start(
                fileName(name, type, url, request), type.ifEmpty { request.mimeType.orEmpty() },
                request.site, request.appId, size.toLong()
            )
        }

        /**
         * The save picker, from a page at [page]. The page script only gets this far while the
         * person's tap is still fresh, as Chrome requires for its picker, so no page can save files
         * nobody asked for.
         */
        @JavascriptInterface
        fun pick(page: String, name: String, type: String): String {
            if (!WebDownloads.canWrite(context)) return ""
            val request = Asked(null, type, WebDownloads.siteOf(page).orEmpty(), webView.appId)
            return start(fileName(name, type, page, request), type, request.site, request.appId, 0)
        }

        /** A piece at [position], or after the last one when it's negative. */
        @JavascriptInterface
        fun write(id: String, position: Double, base64: String): Boolean {
            val file = saving[id] ?: return false
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val channel = file.target.channel
                val buffer = ByteBuffer.wrap(bytes)
                var offset = if (position < 0) file.written else position.toLong()
                while (buffer.hasRemaining()) offset += channel.write(buffer, offset)
                if (offset > file.written) {
                    file.written = offset
                    downloads.progress(file.row, file.written)
                }
                true
            } catch (e: Exception) {
                end(id, false)
                false
            }
        }

        @JavascriptInterface
        fun truncate(id: String, size: Double): Boolean {
            val file = saving[id] ?: return false
            return runCatching {
                file.target.channel.truncate(size.toLong())
                file.written = minOf(file.written, size.toLong())
            }.isSuccess
        }

        @JavascriptInterface
        fun end(id: String, ok: Boolean) = this@PageFiles.end(id, ok)

        @JavascriptInterface
        fun failed(url: String) {
            if (asked.remove(url) != null) notice("This file can't be saved, the page no longer has it")
        }
    }

    private fun start(fileName: String, type: String, site: String, appId: String?, size: Long): String {
        val mime = mimeOf(fileName, type)
        val target = try {
            open(fileName, mime)
        } catch (e: Exception) {
            null
        }
        if (target == null) {
            notice("Couldn't save $fileName")
            return ""
        }
        val row = downloads.insert(
            DownloadEntity(
                name = target.name, mimeType = mime, site = site, url = "", appId = appId,
                fileUri = target.location, size = size.coerceAtLeast(0)
            )
        )
        DownloadFiles.writing += row
        val id = UUID.randomUUID().toString()
        saving[id] = Saving(target, mime, row)
        main.post {
            keepScreenOn()
            WebDownloads.started(webView, target.name)
        }
        return id
    }

    private fun end(id: String, ok: Boolean, quietly: Boolean = false) {
        val file = saving.remove(id) ?: return
        val saved = ok && runCatching { file.target.channel.force(true) }.isSuccess
        runCatching { file.target.closeAll() }
        val name = runCatching { file.target.done(saved) }.getOrDefault(file.target.name)
        downloads.finish(
            file.row,
            if (saved) DownloadEntity.DONE else DownloadEntity.FAILED,
            file.written,
            if (saved) file.target.location else null,
            name
        )
        DownloadFiles.writing -= file.row
        main.post {
            keepScreenOn()
            when {
                saved -> WebDownloads.saved(webView, name) {
                    DownloadFiles.open(webView.context, Uri.parse(file.target.location), file.mimeType)
                }
                !quietly -> WebDownloads.notice(webView, "Couldn't save $name")
            }
        }
    }

    /** A page writing a file can't do it asleep, so the screen stays on until it's done. */
    private fun keepScreenOn() {
        webView.keepScreenOn = saving.isNotEmpty()
    }

    /** The name the page gave the file, else one from the download itself, with an extension that fits. */
    private fun fileName(given: String, type: String, url: String, request: Asked): String {
        val mime = type.ifEmpty { request.mimeType.orEmpty() }
        var name = given.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isEmpty() && request.contentDisposition != null) {
            name = URLUtil.guessFileName(url, request.contentDisposition, mime)
        }
        name = name.replace(Regex("[\\u0000-\\u001f\"*:<>?|]"), "_").trimStart('.').take(MAX_NAME)
        if (name.isEmpty()) name = "download"
        if (!name.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name += ".$it" }
        }
        return name
    }

    private fun mimeOf(name: String, type: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: type.ifEmpty { "application/octet-stream" }

    private fun open(name: String, mime: String): Target =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openInMediaStore(name, mime) else openInFolder(name, mime)

    @SuppressLint("NewApi")
    private fun openInMediaStore(name: String, mime: String): Target {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hidden from other apps until the whole file is there
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("No place in Downloads")
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: run {
            resolver.delete(uri, null, null)
            error("Downloads can't be written")
        }
        val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        return Target(name, uri.toString(), stream.channel, { stream.close() }) { ok ->
            if (ok) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                // A name already taken in Downloads gets a number added once the file is finished
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: name
            } else {
                resolver.delete(uri, null, null)
                name
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun openInFolder(name: String, mime: String): Target {
        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        folder.mkdirs()
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var file = File(folder, name)
        var copy = 1
        while (file.exists()) file = File(folder, "$base (${copy++})$extension")
        val access = RandomAccessFile(file, "rw")
        return Target(file.name, Uri.fromFile(file).toString(), access.channel, { access.close() }) { ok ->
            if (ok) MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null) else file.delete()
            file.name
        }
    }

    private fun notice(text: String) = main.post { WebDownloads.notice(webView, text) }

    companion object {
        private const val MAX_NAME = 120

        /** Call after [AppProfiles.attach], which an added interface or script would block. */
        @SuppressLint("RequiresFeature", "JavascriptInterface")
        fun attach(webView: DeckWebView): PageFiles? {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
            // Names no site could know to look for, and the bridge is gone before any of them runs
            val name = "_" + (1..12).map { ('a'..'z').random() }.joinToString("")
            val event = "_" + (1..12).map { ('a'..'z').random() }.joinToString("")
            val files = PageFiles(webView, event)
            webView.addJavascriptInterface(files.Bridge(), name)
            WebViewCompat.addDocumentStartJavaScript(webView, script(name, event), setOf("*"))
            return files
        }

        private fun script(name: String, event: String) = """
(function () {
  var bridge = window[${JSONObject.quote(name)}];
  if (!bridge) return;
  try { delete window[${JSONObject.quote(name)}]; } catch (e) {}
  var STEP = 2097152, KEEP = 60000, NAMES = 50;
  var made = new Map(), names = new Map();

  // A page often lets go of the link as soon as it's clicked, before the file can be read through it
  var create = URL.createObjectURL, revoke = URL.revokeObjectURL;
  URL.createObjectURL = function (thing) {
    var url = create.apply(this, arguments);
    if (thing instanceof Blob) made.set(url, thing);
    return url;
  };
  URL.revokeObjectURL = function (url) {
    if (made.has(url)) setTimeout(function () { made.delete(url); }, KEEP);
    return revoke.apply(this, arguments);
  };

  // The name a page gives a download lives on its link, which the download itself doesn't carry
  function remember(a) {
    try {
      var href = a.href;
      if (typeof href !== 'string' || !a.hasAttribute('download') || !/^(blob|data):/i.test(href)) return;
      names.delete(href);
      names.set(href, a.getAttribute('download'));
      if (names.size > NAMES) names.delete(names.keys().next().value);
    } catch (e) {}
  }
  addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (a) remember(a);
  }, true);
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    remember(this);
    return click.apply(this, arguments);
  };

  function read(part) {
    return new Promise(function (done, fail) {
      var reader = new FileReader();
      reader.onload = function () { done(reader.result); };
      reader.onerror = function () { fail(reader.error); };
      reader.readAsDataURL(part);
    });
  }

  // Hands a file over in pieces small enough for the bridge, starting at [at], or after the last piece when it's -1
  async function send(id, blob, at) {
    for (var offset = 0; offset < blob.size; offset += STEP) {
      var text = await read(blob.slice(offset, offset + STEP));
      if (!bridge.write(id, at < 0 ? -1 : at + offset, text.slice(text.indexOf(',') + 1))) {
        throw new DOMException('The file could not be written.', 'InvalidStateError');
      }
    }
  }

  addEventListener(${JSONObject.quote(event)}, function (e) {
    var url = e.detail;
    if (typeof url !== 'string') return;
    (async function () {
      var id = '';
      try {
        var blob = made.get(url) || await (await fetch(url)).blob();
        id = bridge.begin(url, names.get(url) || '', blob.type || '', blob.size);
        if (!id) return;
        await send(id, blob, 0);
        bridge.end(id, true);
      } catch (err) {
        if (id) bridge.end(id, false); else bridge.failed(url);
      }
    })();
  });

  // Desktop Chrome's save picker, which sites that put a file together write to as it comes in.
  // Newer WebViews have one of their own, but inside an app it has nowhere to save and answers
  // as if the person cancelled, so this one always takes its place.
  if (!window.isSecureContext || !window.WritableStream) return;

  function asBlob(data) {
    if (data instanceof Blob) return data;
    if (data === undefined || data === null) throw new TypeError('Nothing to write.');
    return new Blob([data]);
  }

  function writable(id) {
    var at = 0, queue = Promise.resolve();
    function apply(chunk) {
      if (chunk && typeof chunk === 'object' && !(chunk instanceof Blob) && !(chunk instanceof ArrayBuffer) &&
          !ArrayBuffer.isView(chunk) && typeof chunk.type === 'string') {
        if (chunk.type === 'seek') { at = Number(chunk.position) || 0; return Promise.resolve(); }
        if (chunk.type === 'truncate') {
          var size = Number(chunk.size) || 0;
          if (!bridge.truncate(id, size)) return Promise.reject(new DOMException('The file could not be written.', 'InvalidStateError'));
          if (at > size) at = size;
          return Promise.resolve();
        }
        if (chunk.position !== undefined && chunk.position !== null) at = Number(chunk.position) || 0;
        chunk = chunk.data;
      }
      var blob = asBlob(chunk), start = at;
      at = start + blob.size;
      return send(id, blob, start);
    }
    function queued(work) {
      var next = queue.then(work);
      queue = next.catch(function () {});
      return next;
    }
    var stream = new WritableStream({
      write: function (chunk) { return queued(function () { return apply(chunk); }); },
      close: function () { return queued(function () { bridge.end(id, true); }); },
      abort: function () { bridge.end(id, false); }
    });
    stream.write = function (chunk) { return queued(function () { return apply(chunk); }); };
    stream.seek = function (position) { return stream.write({ type: 'seek', position: position }); };
    stream.truncate = function (size) { return stream.write({ type: 'truncate', size: size }); };
    stream.close = function () { return queued(function () { bridge.end(id, true); }); };
    stream.abort = function () { bridge.end(id, false); return Promise.resolve(); };
    return stream;
  }

  function fileHandle(id, name, type) {
    var handle = {
      kind: 'file',
      name: name,
      getFile: function () { return Promise.resolve(new File([], name, { type: type })); },
      createWritable: function () { return Promise.resolve(writable(id)); },
      remove: function () { bridge.end(id, false); return Promise.resolve(); },
      isSameEntry: function (other) { return Promise.resolve(other === handle); },
      queryPermission: function () { return Promise.resolve('granted'); },
      requestPermission: function () { return Promise.resolve('granted'); }
    };
    return handle;
  }

  window.showSaveFilePicker = function showSaveFilePicker(options) {
    return new Promise(function (done, fail) {
      // As in Chrome, only right after a tap, so no page saves files nobody asked for
      if (!navigator.userActivation || !navigator.userActivation.isActive) {
        fail(new DOMException('Must be handling a user gesture to show a file picker.', 'SecurityError'));
        return;
      }
      var name = options && options.suggestedName ? String(options.suggestedName) : '';
      var type = '';
      try { type = Object.keys(options.types[0].accept)[0] || ''; } catch (e) {}
      var id = bridge.pick(location.href, name, type);
      if (!id) {
        fail(new DOMException('The user aborted a request.', 'AbortError'));
        return;
      }
      done(fileHandle(id, name, type));
    });
  };
})();
"""
    }
}
