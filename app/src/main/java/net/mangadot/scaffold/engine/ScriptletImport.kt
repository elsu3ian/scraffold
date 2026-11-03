package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Turns a scriptlet module like uBlock Origin's into resources for [AdblockNative]. The module
 * registers each scriptlet as a function, so it takes a JavaScript engine to read: a page nobody
 * sees imports it straight from [FilterLists.SCRIPTLETS_SOURCE] and hands back JSON.
 */
object ScriptletImport {

    private const val TIMEOUT_MS = 60_000L
    private const val TAG = "Shield"

    /** The resources as JSON, or null when the module couldn't be fetched or read. */
    suspend fun run(context: Context, source: String): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(TIMEOUT_MS) { convert(context, source) }
            ?: null.also { Log.w(TAG, "Scriptlet import gave no answer") }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun convert(context: Context, source: String): String? = suspendCancellableCoroutine { done ->
        val web = WebView(context.applicationContext)
        web.settings.javaScriptEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "Scriptlet import: ${message.message()}")
                return true
            }
        }
        // A view never shown runs nothing posted to it, so the answer goes through the main looper
        val main = Handler(Looper.getMainLooper())
        fun finish(result: String?) {
            main.post {
                if (done.isActive) done.resume(result)
                web.destroy()
            }
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun done(json: String) = finish(json)

            @JavascriptInterface
            fun fail(reason: String) {
                Log.w(TAG, "Couldn't read scriptlets: $reason")
                finish(null)
            }
        }, BRIDGE)
        done.invokeOnCancellation { main.post { web.destroy() } }
        web.loadDataWithBaseURL(BASE_URL, page(source), "text/html", "utf-8", null)
    }

    private const val BRIDGE = "scriptletImport"
    private const val BASE_URL = "https://scriptlets.invalid/"

    // Main scriptlets end in .js and run with the rule's arguments; the rest are helpers they pull in
    private fun page(source: String) = """
<!doctype html><script>
  var b64 = function (text) { return btoa(unescape(encodeURIComponent(text))); };
  import(${JSONObject.quote(source)}).then(function (module) {
    var list = module.builtinScriptlets;
    if (!Array.isArray(list) || !list.length) throw new Error('No scriptlets');
    $BRIDGE.done(JSON.stringify(list.map(function (entry) {
      return {
        name: entry.name,
        aliases: entry.aliases || [],
        kind: { mime: /\.js$/.test(entry.name) ? 'application/javascript' : 'fn/javascript' },
        content: b64(entry.fn.toString()),
        dependencies: entry.dependencies || [],
        permission: entry.requiresTrust ? 1 : 0
      };
    })));
  }).catch(function (error) { $BRIDGE.fail(String(error)); });
</script>
"""
}
