package net.mangadot.scaffold.engine.userscript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.engine.WebDownloads
import net.mangadot.scaffold.ui.browser.ExternalLinks
import net.mangadot.scaffold.ui.browser.LinkOwner
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Native side of the GM_* API. Exposed to every frame, so each call must carry the random token
 * its script received: page code never sees one, and can't act as a script without it.
 */
class GmBridge(
    private val context: Context,
    private val repository: ScriptRepository,
    private val scope: CoroutineScope
) {

    data class MenuCommand(val id: Int, val caption: String, val callback: String, val scriptName: String)

    private class Registered(val script: ScriptEntity, val meta: UserscriptMeta) {
        // GM_getValue must answer synchronously, so values are served from memory and written through
        val values = ConcurrentHashMap<String, String>()
    }

    var webView: WebView? = null
    var cookies: CookieManager = CookieManager.getInstance()
    var linkOwner: LinkOwner? = null
    val menuCommands = CopyOnWriteArrayList<MenuCommand>()
    private val registered = ConcurrentHashMap<String, Registered>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()

    fun reset() {
        registered.clear()
        menuCommands.clear()
    }

    fun prepare(token: String, script: ScriptEntity, meta: UserscriptMeta) {
        val entry = Registered(script, meta)
        // Queues behind any write still pending from the previous page
        runBlocking(WRITES) {
            storageLock.withLock {
                repository.getAllStorageValues(script.id).forEach { row ->
                    ScriptInstaller.restoreValue(context, script.id, row.key, row.value)?.let { entry.values[row.key] = it }
                }
            }
        }
        registered[token] = entry
    }

    @JavascriptInterface
    fun call(token: String, op: String, argsJson: String): String? {
        val entry = registered[token] ?: return null
        val args = JSONArray(argsJson)
        return when (op) {
            "getValue" -> entry.values[args.getString(0)]
            "setValue" -> {
                val key = args.getString(0)
                val value = args.getString(1)
                entry.values[key] = value
                writes.launch { storageLock.withLock { ScriptInstaller.storeValue(context, repository, entry.script.id, key, value) } }
                null
            }
            "deleteValue" -> {
                val key = args.getString(0)
                entry.values.remove(key)
                writes.launch { storageLock.withLock { ScriptInstaller.removeValue(context, repository, entry.script.id, key) } }
                null
            }
            "listValues" -> JSONArray(entry.values.keys.toList()).toString()
            "resourceText" -> ScriptInstaller.readResource(context, entry.script.id, args.getString(0))
                ?.toString(Charsets.UTF_8)
            "resourceUrl" -> resourceDataUrl(entry, args.getString(0))
            "openInTab" -> {
                mainHandler.post { openExternally(args.getString(0)) }
                null
            }
            "setClipboard" -> {
                val text = args.getString(0)
                mainHandler.post {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(entry.script.name, text))
                }
                null
            }
            "notification" -> {
                val text = args.getString(0)
                mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
                null
            }
            "download" -> {
                download(args.getString(0), args.getString(1))
                null
            }
            "menuReset" -> {
                menuCommands.removeAll { it.scriptName == entry.script.name }
                null
            }
            "menuAdd" -> {
                menuCommands += MenuCommand(args.getInt(0), args.getString(1), args.getString(2), entry.script.name)
                null
            }
            "menuRemove" -> {
                val id = args.getInt(0)
                menuCommands.removeAll { it.id == id && it.scriptName == entry.script.name }
                null
            }
            "xhr" -> {
                request(entry, args.getInt(0), args.getString(1), JSONObject(args.getString(2)))
                null
            }
            else -> null
        }
    }

    fun runMenuCommand(command: MenuCommand) {
        webView?.evaluateJavascript("window.${command.callback}('menu', ${command.id})", null)
    }

    private fun resourceDataUrl(entry: Registered, name: String): String? {
        val bytes = ScriptInstaller.readResource(context, entry.script.id, name) ?: return null
        val url = entry.meta.resources.firstOrNull { it.first == name }?.second.orEmpty()
        val mime = URLUtil.guessFileName(url, null, null).let {
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(it.substringAfterLast('.', "").lowercase())
        } ?: "application/octet-stream"
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun openExternally(url: String) {
        ExternalLinks.open(context, Uri.parse(url), linkOwner)
    }

    private fun download(url: String, name: String) {
        mainHandler.post { WebDownloads.start(context, url, cookies, fileName = name) }
    }

    private fun connectAllowed(meta: UserscriptMeta, url: String): Boolean {
        if (meta.connects.isEmpty()) return true
        val host = Uri.parse(url).host?.lowercase() ?: return false
        val page = runCatching { Uri.parse(meta.matches.firstOrNull().orEmpty()).host?.lowercase() }.getOrNull()
        return meta.connects.any { rule ->
            val target = rule.lowercase()
            target == "*" || target == "self" && host == page || host == target || host.endsWith(".$target")
        }
    }

    private fun request(entry: Registered, id: Int, callback: String, details: JSONObject) {
        scope.launch(Dispatchers.IO) {
            val url = details.getString("url")
            val result = JSONObject().put("finalUrl", url).put("status", 0).put("statusText", "")
                .put("responseHeaders", "").put("responseText", "")
            try {
                if (!connectAllowed(entry.meta, url)) {
                    throw SecurityException("$url is not in @connect")
                }
                val method = details.getString("method").uppercase()
                val builder = Request.Builder().url(url)
                val headers = details.getJSONObject("headers")
                headers.keys().forEach { builder.header(it, headers.getString(it)) }
                val anonymous = details.getBoolean("anonymous")
                if (!anonymous) {
                    cookies.getCookie(url)?.let { builder.header("Cookie", it) }
                }
                val data = if (details.isNull("data")) null else details.getString("data")
                val body = data?.toRequestBody(headers.optString("Content-Type", "text/plain").toMediaTypeOrNull())
                builder.method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody() else null)

                val timeout = details.getLong("timeout")
                val call = if (timeout > 0) {
                    client.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()
                } else {
                    client
                }
                call.newCall(builder.build()).execute().use { response ->
                    if (!anonymous) {
                        response.headers("Set-Cookie").forEach { cookies.setCookie(url, it) }
                    }
                    result.put("kind", "load")
                        .put("status", response.code)
                        .put("statusText", response.message)
                        .put("finalUrl", response.request.url.toString())
                        .put("responseHeaders", response.headers.toString())
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (details.getBoolean("binary")) {
                        result.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    } else {
                        result.put("responseText", bytes.toString(Charsets.UTF_8))
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                result.put("kind", "timeout").put("error", e.message.orEmpty())
            } catch (e: Exception) {
                result.put("kind", "error").put("error", e.message.orEmpty())
            }
            mainHandler.post {
                val payload = JSONObject.quote(result.toString())
                webView?.evaluateJavascript("window.$callback('xhr', $id, $payload)", null)
            }
        }
    }

    companion object {
        const val INTERFACE_NAME = "__scraffoldGM"

        // One thread starts writes in call order and the fair lock keeps them in it across their suspensions.
        // Not the activity's scope, so closing a page can't drop them.
        private val WRITES = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val storageLock = Mutex()
        private val writes = CoroutineScope(
            SupervisorJob() + WRITES + CoroutineExceptionHandler { _, e -> Log.e("GmBridge", "Couldn't store a value", e) }
        )
    }
}
