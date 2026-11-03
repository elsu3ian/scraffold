package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Hides ads and runs the lists' scriptlets in every frame, from the first moment of each page.
 * Pages ask for what applies to them through an interface that answers at once, so hiding is in
 * place before anything paints; the script that asks then removes it, so pages never see it.
 *
 * Scriptlets can't be handed over as text, since pages that forbid eval (YouTube among them) would
 * refuse them. So a site's scriptlets become a document-start script of their own as soon as a page
 * heads there, and run ahead of the site's own code from then on.
 */
class PageShield private constructor(private val webView: DeckWebView, private val engine: AdBlockEngine) {

    /** Follows the shield setting of whatever the page belongs to. */
    @Volatile
    var enabled = true

    private val scriptedOrigins = ConcurrentHashMap.newKeySet<String>()

    /** For a page about to load [url], on the main thread. */
    @SuppressLint("RequiresFeature")
    fun prepare(url: String) {
        if (!enabled) return
        val origin = originOf(url) ?: return
        if (origin in scriptedOrigins) return
        val script = engine.cosmetics(url, waitForLists = false)?.let { JSONObject(it).optString("script") }.orEmpty()
        if (script.isEmpty()) return
        scriptedOrigins += origin
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
        Log.i("Shield", "Scriptlets ready for $origin")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun page(url: String): String {
            if (!enabled) return ""
            val found = engine.cosmetics(url, waitForLists = true) ?: return ""
            val data = JSONObject(found)
            val origin = originOf(url)
            if (origin != null && origin in scriptedOrigins) {
                data.remove("script")
            } else if (data.optString("script").isNotEmpty()) {
                // This time they can only be tried as text; the next page here gets them properly
                webView.post { prepare(url) }
            }
            return data.toString()
        }

        @JavascriptInterface
        fun generic(url: String, classes: String, ids: String): String {
            if (!enabled) return ""
            return AdblockNative.genericCss(url, strings(classes), strings(ids)).orEmpty()
        }
    }

    companion object {
        /** Call after [AppProfiles.attach], which an added interface or script would block. */
        @SuppressLint("RequiresFeature", "JavascriptInterface")
        fun attach(webView: DeckWebView): PageShield? {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
            val shield = PageShield(webView, AdBlockEngine.getInstance(webView.context))
            // A name no site could know to look for, and gone before any of them runs
            val name = "_" + (1..12).map { ('a'..'z').random() }.joinToString("")
            webView.addJavascriptInterface(shield.Bridge(), name)
            WebViewCompat.addDocumentStartJavaScript(webView, script(name), setOf("*"))
            webView.shield = shield
            return shield
        }

        private fun originOf(url: String): String? {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase() ?: return null
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }

        private fun strings(json: String): Array<String> {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyArray()
            return Array(array.length()) { array.optString(it) }
        }

        // Generic rules name ad classes and ids found anywhere, so those are gathered as the page grows
        private fun script(name: String) = """
(function () {
  var shield = window.$name;
  if (!shield) return;
  try { delete window.$name; } catch (e) {}
  var url = location.href;
  if (!/^https?:/.test(url)) return;
  var data;
  try { data = JSON.parse(shield.page(url) || 'null'); } catch (e) { return; }
  if (!data) return;
  if (data.script) {
    try { (0, eval)(data.script); } catch (e) {}
  }
  var css = data.css || '';
  var later = window.setTimeout.bind(window);
  var sheet = null;
  var style = null;
  var apply = function () {
    if (!css) return;
    try {
      if (!sheet) {
        sheet = new CSSStyleSheet();
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      }
      sheet.replaceSync(css);
    } catch (e) {}
    var target = document.head || document.documentElement;
    if (!target) return;
    if (!style) style = document.createElement('style');
    style.textContent = css;
    if (style.parentNode !== target) target.appendChild(style);
  };
  apply();
  if (!document.head) document.addEventListener('DOMContentLoaded', apply, { once: true });
  if (!data.generic) return;

  var seen = Object.create(null);
  var classes = [];
  var ids = [];
  var timer = 0;
  var note = function (element) {
    if (element.id && !seen['#' + element.id]) {
      seen['#' + element.id] = true;
      ids.push(element.id);
    }
    var list = element.classList;
    if (!list) return;
    for (var i = 0; i < list.length; i++) {
      if (!seen['.' + list[i]]) {
        seen['.' + list[i]] = true;
        classes.push(list[i]);
      }
    }
  };
  var flush = function () {
    timer = 0;
    if (!classes.length && !ids.length) return;
    var found = shield.generic(url, JSON.stringify(classes), JSON.stringify(ids));
    classes = [];
    ids = [];
    if (found) {
      css += found;
      apply();
    }
  };
  var scan = function (node) {
    if (node.nodeType !== 1) return;
    note(node);
    var all = node.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) note(all[i]);
  };
  new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var record = records[i];
      if (record.type === 'attributes') note(record.target);
      else for (var j = 0; j < record.addedNodes.length; j++) scan(record.addedNodes[j]);
    }
    if (!timer) timer = later(flush, 50);
  }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
  if (document.documentElement) scan(document.documentElement);
  flush();
})();
"""
    }
}
