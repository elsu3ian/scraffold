package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Sizes the whole page the way a browser's zoom setting does. The site lays out at a wider or
 * narrower width and is scaled to fit, so a smaller size shows more of it, not just smaller text.
 * Pages choose their own width through the viewport tag, so this keeps that tag set.
 */
@SuppressLint("RequiresFeature")
class PageZoom(private val webView: WebView) {

    private var handler: ScriptHandler? = null

    /** Applies to the page on screen and every page loaded after it. */
    fun set(percent: Int) {
        handler?.remove()
        handler = null
        if (percent != DEFAULT_PERCENT && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler = WebViewCompat.addDocumentStartJavaScript(webView, script(percent), setOf("*"))
        }
        webView.evaluateJavascript(script(percent), null)
    }

    private fun script(percent: Int) = """
        (function () {
          if (window.top !== window) return;
          window.__scraffoldPageZoom = $percent;
          if (!window.__scraffoldApplyZoom) {
            window.__scraffoldApplyZoom = function () {
              var zoom = window.__scraffoldPageZoom / 100;
              var tags = document.querySelectorAll('meta[name="viewport"]');
              if (zoom === 1) {
                tags.forEach(function (tag) {
                  if (tag.dataset.scraffoldOriginal === undefined) return;
                  if (tag.dataset.scraffoldOriginal === '') { tag.remove(); return; }
                  tag.content = tag.dataset.scraffoldOriginal;
                  delete tag.dataset.scraffoldOriginal;
                });
                return;
              }
              if (!tags.length) {
                if (!document.head) return;
                var added = document.createElement('meta');
                added.name = 'viewport';
                added.dataset.scraffoldOriginal = '';
                document.head.appendChild(added);
                tags = [added];
              }
              var content = 'width=' + Math.round(screen.width / zoom) + ', initial-scale=' + zoom;
              tags.forEach(function (tag) {
                if (tag.dataset.scraffoldOriginal === undefined) tag.dataset.scraffoldOriginal = tag.content;
                if (tag.content !== content) tag.content = content;
              });
            };
            // Sites can rewrite the tag at any time. Once the head is there, it is all that needs watching.
            var observer = new MutationObserver(window.__scraffoldApplyZoom);
            var watch = { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] };
            observer.observe(document, watch);
            document.addEventListener('DOMContentLoaded', function () {
              observer.disconnect();
              observer.observe(document.head, watch);
              window.__scraffoldApplyZoom();
            });
            window.addEventListener('orientationchange', window.__scraffoldApplyZoom);
          }
          window.__scraffoldApplyZoom();
        })();
    """.trimIndent()

    companion object {
        const val DEFAULT_PERCENT = 100
    }
}
