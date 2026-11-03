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
 * Turns uBlock Origin's scriptlet module and its list of redirect stand-ins into resources for
 * [AdblockNative]. The modules register their entries as JavaScript, so it takes a JavaScript engine
 * to read them: a page nobody sees imports them straight from [FilterLists.SCRIPTLETS_SOURCE] and
 * [FilterLists.REDIRECTS_SOURCE], fetches each stand-in file, and hands back JSON.
 */
object ScriptletImport {

    private const val TIMEOUT_MS = 90_000L
    private const val TAG = "Shield"

    /** Each half is resources as JSON, or null when that module couldn't be fetched or read. */
    class Result(val scriptlets: String?, val redirects: String?)

    suspend fun run(context: Context, scriptletSource: String, redirectSource: String): Result = withContext(Dispatchers.Main) {
        withTimeoutOrNull(TIMEOUT_MS) { convert(context, scriptletSource, redirectSource) }
            ?: Result(null, null).also { Log.w(TAG, "Scriptlet import gave no answer") }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun convert(context: Context, scriptletSource: String, redirectSource: String): Result =
        suspendCancellableCoroutine { done ->
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
            web.addJavascriptInterface(object {
                @JavascriptInterface
                fun done(scriptlets: String, redirects: String) {
                    main.post {
                        if (done.isActive) done.resume(Result(scriptlets.ifEmpty { null }, redirects.ifEmpty { null }))
                        web.destroy()
                    }
                }

                @JavascriptInterface
                fun fail(what: String, reason: String) {
                    Log.w(TAG, "Couldn't read $what: $reason")
                }
            }, BRIDGE)
            done.invokeOnCancellation { main.post { web.destroy() } }
            web.loadDataWithBaseURL(BASE_URL, page(scriptletSource, redirectSource), "text/html", "utf-8", null)
        }

    private const val BRIDGE = "scriptletImport"
    private const val BASE_URL = "https://scriptlets.invalid/"

    // Main scriptlets end in .js and run with the rule's arguments; the rest are helpers they pull in.
    // Stand-ins sit beside the redirect module in web_accessible_resources, and only a full set replaces the last one.
    private fun page(scriptletSource: String, redirectSource: String) = """
<!doctype html><script>
  var b64 = function (text) { return btoa(unescape(encodeURIComponent(text))); };
  var bytesB64 = function (buffer) {
    var bytes = new Uint8Array(buffer);
    var text = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(text);
  };
  var MIME = {
    js: 'application/javascript', html: 'text/html', xml: 'text/xml', png: 'image/png', gif: 'image/gif',
    mp3: 'audio/mp3', mp4: 'video/mp4', txt: 'text/plain', css: 'text/css', json: 'application/json'
  };
  var mimeOf = function (name) {
    var dot = name.lastIndexOf('.');
    return (dot > 0 && MIME[name.slice(dot + 1).toLowerCase()]) || 'text/plain';
  };
  var settle = function (what, work) {
    return work.catch(function (error) { $BRIDGE.fail(what, String(error)); return ''; });
  };

  var scriptlets = settle('scriptlets', import(${JSONObject.quote(scriptletSource)}).then(function (module) {
    var list = module.builtinScriptlets;
    if (!Array.isArray(list) || !list.length) throw new Error('No scriptlets');
    return JSON.stringify(list.map(function (entry) {
      return {
        name: entry.name,
        aliases: entry.aliases || [],
        kind: { mime: /\.js$/.test(entry.name) ? 'application/javascript' : 'fn/javascript' },
        content: b64(entry.fn.toString()),
        dependencies: entry.dependencies || [],
        permission: entry.requiresTrust ? 1 : 0
      };
    }));
  }));

  var redirectSource = ${JSONObject.quote(redirectSource)};
  var redirects = settle('redirect resources', import(redirectSource).then(function (module) {
    var map = module.default;
    if (!(map instanceof Map) || !map.size) throw new Error('No redirect resources');
    return Promise.all(Array.from(map.entries()).map(function (pair) {
      var name = pair[0];
      var details = pair[1] || {};
      var file = new URL('../web_accessible_resources/' + name, redirectSource).href;
      return fetch(file).then(function (response) {
        if (!response.ok) throw new Error(name + ': HTTP ' + response.status);
        return response.arrayBuffer();
      }).then(function (buffer) {
        var alias = details.alias;
        return {
          name: name,
          aliases: Array.isArray(alias) ? alias : alias ? [alias] : [],
          kind: { mime: mimeOf(name) },
          content: bytesB64(buffer)
        };
      });
    })).then(JSON.stringify);
  }));

  Promise.all([scriptlets, redirects]).then(function (found) { $BRIDGE.done(found[0], found[1]); });
</script>
"""
}
