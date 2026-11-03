package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.engine.userscript.GmBridge
import net.mangadot.scaffold.engine.userscript.ScriptInstaller
import net.mangadot.scaffold.engine.userscript.UserscriptMeta
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Registers every enabled userscript as its own document-start script. Each one decides at
 * runtime whether it matches the page, so SPA and cross-host navigation need no re-injection,
 * and a syntax error in one script can't take the others down.
 */
class ScriptEngine(
    private val context: Context,
    private val scriptRepository: ScriptRepository,
    scope: CoroutineScope
) {

    val bridge = GmBridge(context, scriptRepository, scope)
    private val handlers = mutableListOf<ScriptHandler>()
    private var fallbackScripts: List<String> = emptyList()

    private val runtime: String by lazy {
        context.assets.open("gm_runtime.js").bufferedReader().use { it.readText() }
    }

    /** Scripts that can't run, with the error to show for them. */
    var failedScripts: Map<String, String> = emptyMap()
        private set

    fun attach(webView: WebView, appId: String?) {
        bridge.webView = webView
        bridge.cookies = AppProfiles.cookies(appId)
    }

    /** Replaces what is registered on [webView]. Call before loading a page. */
    @SuppressLint("JavascriptInterface")
    suspend fun register(webView: WebView, scripts: List<ScriptEntity>) {
        handlers.forEach { it.remove() }
        handlers.clear()
        bridge.reset()

        val failed = mutableMapOf<String, String>()
        val runnable = scripts.filter { script ->
            val error = syntaxError(webView, script)
            if (error != null) failed[script.id] = error
            error == null
        }
        failedScripts = failed

        val wrapped = withContext(Dispatchers.IO) {
            runnable.filter { it.enabled }.mapNotNull { script ->
                val meta = UserscriptMeta.parse(script.code)
                if (meta.includeRegexes().isEmpty()) return@mapNotNull null
                val token = UUID.randomUUID().toString()
                bridge.prepare(token, script, meta)
                wrap(script, meta, token)
            }
        }

        // Pages can see an added interface and take it for an app, so it's only there for scripts that use it
        if (wrapped.isEmpty()) {
            webView.removeJavascriptInterface(GmBridge.INTERFACE_NAME)
        } else {
            webView.addJavascriptInterface(bridge, GmBridge.INTERFACE_NAME)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            fallbackScripts = emptyList()
            wrapped.forEach { source ->
                handlers += WebViewCompat.addDocumentStartJavaScript(webView, source, setOf("*"))
            }
        } else {
            fallbackScripts = wrapped
        }
    }

    /** Compiles a script without running it. An unsure answer counts as fine, since the page will report a real error. */
    private suspend fun syntaxError(webView: WebView, script: ScriptEntity): String? {
        val body = withContext(Dispatchers.IO) { scriptBody(script) }
        val check = "(function(){try{new Function(${JSONObject.quote(body)});return ''}" +
            "catch(e){return e instanceof SyntaxError?e.message:''}})()"
        val result = withTimeoutOrNull(SYNTAX_CHECK_MS) {
            suspendCancellableCoroutine<String?> { done -> webView.evaluateJavascript(check) { if (done.isActive) done.resume(it) } }
        }
        return result?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun scriptBody(script: ScriptEntity): String {
        val meta = UserscriptMeta.parse(script.code)
        val required = ScriptInstaller.readRequires(context, script.id, meta).joinToString("") { "$it\n;\n" }
        return "$required${script.code}\n"
    }

    /** Old WebViews lack document-start scripts, so everything runs once the page has loaded. */
    fun onPageFinished(webView: WebView) {
        fallbackScripts.filter { it.isNotEmpty() }.forEach { webView.evaluateJavascript(it, null) }
    }

    private fun wrap(script: ScriptEntity, meta: UserscriptMeta, token: String): String {
        val config = JSONObject()
            .put("token", token)
            .put("callback", "__sf" + UUID.randomUUID().toString().replace("-", ""))
            .put("includes", JSONArray(meta.includeRegexes()))
            .put("excludes", JSONArray(meta.excludeRegexes()))
            .put("grants", JSONArray(meta.grants))
            .put("noframes", meta.noframes)
            .put("runAt", meta.runAt)
            .put("info", JSONObject()
                .put("scriptHandler", "Scraffold")
                .put("version", "1.0")
                .put("script", JSONObject()
                    .put("name", meta.name.ifEmpty { script.name })
                    .put("namespace", meta.namespace)
                    .put("version", meta.version)
                    .put("description", meta.description)
                    .put("author", meta.author)
                    .put("matches", JSONArray(meta.matches))
                    .put("includes", JSONArray(meta.includes))
                    .put("excludes", JSONArray(meta.excludes))
                    .put("grant", JSONArray(meta.grants))
                    .put("run-at", meta.runAt)))

        val names = GM_NAMES.joinToString(", ")
        val arguments = GM_NAMES.joinToString(", ") { "__api.$it" }
        return """
(function () {
$runtime
__sfboot($config, function (__api) {
  (function ($names) {
${scriptBody(script)}
  }).call(window, $arguments);
});
})();
"""
    }

    private companion object {
        const val SYNTAX_CHECK_MS = 3000L
        val GM_NAMES = listOf(
            "unsafeWindow", "GM", "GM_info", "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues",
            "GM_addValueChangeListener", "GM_removeValueChangeListener", "GM_addStyle", "GM_addElement", "GM_log",
            "GM_getResourceText", "GM_getResourceURL", "GM_openInTab", "GM_setClipboard", "GM_notification",
            "GM_download", "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_xmlhttpRequest"
        )
    }
}
