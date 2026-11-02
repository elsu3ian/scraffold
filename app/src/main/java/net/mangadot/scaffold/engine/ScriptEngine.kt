package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.engine.userscript.GmBridge
import net.mangadot.scaffold.engine.userscript.ScriptInstaller
import net.mangadot.scaffold.engine.userscript.UserscriptMeta
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

    @SuppressLint("JavascriptInterface")
    fun attach(webView: WebView) {
        bridge.webView = webView
        webView.addJavascriptInterface(bridge, GmBridge.INTERFACE_NAME)
    }

    /** Replaces what is registered on [webView]. Call before loading a page. */
    suspend fun register(
        webView: WebView,
        scripts: List<ScriptEntity>,
        cosmeticCss: String,
        scriptlets: String
    ) {
        handlers.forEach { it.remove() }
        handlers.clear()
        bridge.reset()

        val wrapped = withContext(Dispatchers.IO) {
            scripts.filter { it.enabled }.mapNotNull { script ->
                val meta = UserscriptMeta.parse(script.code)
                if (meta.includeRegexes().isEmpty()) return@mapNotNull null
                val token = UUID.randomUUID().toString()
                bridge.prepare(token, script, meta)
                wrap(script, meta, token)
            }
        }

        val shield = buildShield(cosmeticCss, scriptlets)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            fallbackScripts = emptyList()
            (listOf(shield) + wrapped).filter { it.isNotEmpty() }.forEach { source ->
                handlers += WebViewCompat.addDocumentStartJavaScript(webView, source, setOf("*"))
            }
        } else {
            fallbackScripts = listOf(shield) + wrapped
        }
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

        val required = ScriptInstaller.readRequires(context, script.id, meta).joinToString("") { "$it\n;\n" }
        val names = GM_NAMES.joinToString(", ")
        val arguments = GM_NAMES.joinToString(", ") { "__api.$it" }
        return """
(function () {
$runtime
__sfboot($config, function (__api) {
  (function ($names) {
$required${script.code}
  }).call(window, $arguments);
});
})();
"""
    }

    private fun buildShield(cosmeticCss: String, scriptlets: String): String {
        if (cosmeticCss.isEmpty() && scriptlets.isEmpty()) return ""
        val style = if (cosmeticCss.isEmpty()) "" else """
(function () {
  var css = ${JSONObject.quote(cosmeticCss)};
  var inject = function () {
    var target = document.head || document.documentElement;
    if (target && !document.getElementById('scraffold-cosmetic-shield')) {
      var style = document.createElement('style');
      style.id = 'scraffold-cosmetic-shield';
      style.textContent = css;
      target.appendChild(style);
    }
  };
  if (document.documentElement) inject();
  else document.addEventListener('DOMContentLoaded', inject, { once: true });
})();
"""
        return scriptlets + "\n" + style
    }

    private companion object {
        val GM_NAMES = listOf(
            "unsafeWindow", "GM", "GM_info", "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues",
            "GM_addValueChangeListener", "GM_removeValueChangeListener", "GM_addStyle", "GM_addElement", "GM_log",
            "GM_getResourceText", "GM_getResourceURL", "GM_openInTab", "GM_setClipboard", "GM_notification",
            "GM_download", "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_xmlhttpRequest"
        )
    }
}
