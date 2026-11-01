package net.mangadot.scaffold.engine

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Modern Userscript Engine using WebViewCompat.addDocumentStartJavaScript()
 * and WebViewCompat.addWebMessageListener() for zero-race-condition pre-DOM execution.
 */
class ScriptEngine(
    private val scriptRepository: ScriptRepository,
    private val scope: CoroutineScope
) {

    /**
     * Injects uBlock cosmetic CSS, scriptlets, and document-start userscripts
     * directly into the C++ RenderFrame before HTML parsing begins.
     */
    fun attachPreDomScripts(
        webView: WebView,
        appId: String,
        currentUrl: String,
        activeScripts: List<ScriptEntity>,
        cosmeticCss: String,
        scriptlets: String
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }

        val matchingScripts = activeScripts.filter { script ->
            script.enabled && matchesPattern(script.matchPattern, currentUrl)
        }

        val docStartScripts = matchingScripts.filter {
            it.runAt == ScriptEntity.RUN_AT_DOCUMENT_START
        }

        val bundleBuilder = StringBuilder()

        // 1. Greasemonkey / Tampermonkey Polyfill Bridge
        bundleBuilder.append("""
            (function() {
                window.__scraffold_app_id = "$appId";
                
                // GM_addStyle
                window.GM_addStyle = function(css) {
                    const style = document.createElement('style');
                    style.textContent = css;
                    (document.head || document.documentElement).appendChild(style);
                    return style;
                };

                // GM_log
                window.GM_log = function(msg) {
                    console.log('[Scraffold Script]', msg);
                };

                // GM_setValue / GM_getValue Bridge via WebMessageListener
                window.GM_setValue = function(key, value) {
                    try {
                        if (window.scraffoldBridge) {
                            window.scraffoldBridge.postMessage(JSON.stringify({
                                action: "setValue",
                                appId: window.__scraffold_app_id,
                                key: key,
                                value: JSON.stringify(value)
                            }));
                        }
                    } catch(e) { console.error('GM_setValue error', e); }
                };

                window.GM_getValue = function(key, defaultValue) {
                    // Synchronous fallback from local cache if present
                    return defaultValue;
                };
            })();
        """.trimIndent()).append("\n")

        // 2. uBlock Scriptlets (Defusers)
        if (scriptlets.isNotEmpty()) {
            bundleBuilder.append(scriptlets).append("\n")
        }

        // 3. uBlock Cosmetic CSS Injection (Runs immediately as DOM elements appear)
        if (cosmeticCss.isNotEmpty()) {
            val escapedCss = JSONObject.quote(cosmeticCss)
            bundleBuilder.append("""
                (function() {
                    const injectStyle = function() {
                        const target = document.head || document.documentElement;
                        if (target && !document.getElementById('scraffold-cosmetic-shield')) {
                            const style = document.createElement('style');
                            style.id = 'scraffold-cosmetic-shield';
                            style.textContent = $escapedCss;
                            target.appendChild(style);
                        }
                    };
                    if (document.documentElement) {
                        injectStyle();
                    } else {
                        document.addEventListener('DOMContentLoaded', injectStyle, { once: true });
                    }
                })();
            """.trimIndent()).append("\n")
        }

        // 4. User-defined // @run-at document-start Scripts
        for (script in docStartScripts) {
            bundleBuilder.append("""
                // --- Script: ${script.name} ---
                try {
                    ${script.code}
                } catch(err) {
                    console.error('[Scraffold Script Error in ${script.name}]', err);
                }
            """.trimIndent()).append("\n")
        }

        // Register with native Chromium RenderFrame
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            bundleBuilder.toString(),
            setOf("*")
        )

        // Setup Two-Way WebMessageListener for GM_setValue
        setupWebMessageListener(webView)
    }

    private fun setupWebMessageListener(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "scraffoldBridge",
            setOf("*"),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    val rawData = message.data ?: return
                    try {
                        val json = JSONObject(rawData)
                        when (json.optString("action")) {
                            "setValue" -> {
                                val appId = json.getString("appId")
                                val key = json.getString("key")
                                val value = json.getString("value")
                                scope.launch(Dispatchers.IO) {
                                    scriptRepository.setStorageValue(appId, key, value)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore malformed payloads
                    }
                }
            }
        )
    }

    /**
     * Executes // @run-at document-end scripts when DOMContentLoaded fires
     */
    fun executeDocumentEndScripts(
        webView: WebView,
        currentUrl: String,
        activeScripts: List<ScriptEntity>
    ) {
        val matchingScripts = activeScripts.filter { script ->
            script.enabled &&
            script.runAt == ScriptEntity.RUN_AT_DOCUMENT_END &&
            matchesPattern(script.matchPattern, currentUrl)
        }

        for (script in matchingScripts) {
            val wrappedCode = """
                (function() {
                    try {
                        ${script.code}
                    } catch(e) {
                        console.error('[Scraffold document-end Script Error]', e);
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(wrappedCode, null)
        }
    }

    /**
     * Match pattern evaluator supporting wildcard * domains and paths
     */
    fun matchesPattern(patternStr: String, url: String): Boolean {
        if (patternStr == "<all_urls>" || patternStr == "*") return true
        return try {
            val regex = patternToRegex(patternStr)
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(url).find()
        } catch (e: Exception) {
            false
        }
    }

    private fun patternToRegex(pattern: String): String {
        return "^" + pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".?") + "$"
    }
}
