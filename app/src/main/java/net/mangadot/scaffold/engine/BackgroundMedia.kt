package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Intent
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Keeps a page's audio and video going after the app is left. Sites pause themselves when told they
 * went to the background, so the page is always told it's on screen. It reports what it's playing
 * here, which is what [MediaPlaybackService] goes by.
 */
class BackgroundMedia private constructor(
    val webView: DeckWebView,
    val appName: String,
    val reopen: Intent
) {

    private val playingFrames = HashMap<String, JavaScriptReplyProxy>()
    private var lastFrame: JavaScriptReplyProxy? = null

    val isPlaying: Boolean
        get() = playingFrames.isNotEmpty()

    private fun onReport(message: String, frame: JavaScriptReplyProxy) {
        val id = message.substringBefore(' ')
        if (message.endsWith(" 1")) {
            playingFrames[id] = frame
            lastFrame = frame
        } else {
            playingFrames.remove(id)
        }
        MediaPlaybackService.onChanged(webView.context, this)
    }

    fun pause() {
        playingFrames.values.toList().forEach { send(it, "pause") }
    }

    fun play() {
        lastFrame?.let { send(it, "play") }
    }

    private fun send(frame: JavaScriptReplyProxy, command: String) {
        // The frame may be gone by now, and then there is nothing left to tell
        runCatching { frame.postMessage(command) }
    }

    companion object {
        private const val PORT = "scraffoldMedia"

        @SuppressLint("RequiresFeature")
        fun attach(webView: DeckWebView, appName: String, reopen: Intent) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            ) {
                return
            }
            val media = BackgroundMedia(webView, appName, reopen)
            WebViewCompat.addWebMessageListener(webView, PORT, setOf("*")) { _, message, _, _, frame ->
                message.data?.let { media.onReport(it, frame) }
            }
            WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("*"))
            webView.media = media
        }

        // Only media that can be heard counts, or every silent preview and video banner would raise a notification
        private val SCRIPT = """
            (function () {
              var port = window.$PORT;
              if (!port || window.__scraffoldMedia) return;
              window.__scraffoldMedia = true;

              ['hidden', 'webkitHidden'].forEach(function (name) {
                Object.defineProperty(Document.prototype, name, { get: function () { return false; }, configurable: true });
              });
              ['visibilityState', 'webkitVisibilityState'].forEach(function (name) {
                Object.defineProperty(Document.prototype, name, { get: function () { return 'visible'; }, configurable: true });
              });
              ['visibilitychange', 'webkitvisibilitychange'].forEach(function (name) {
                window.addEventListener(name, function (event) { event.stopImmediatePropagation(); }, true);
              });
              window.addEventListener('blur', function (event) {
                if (event.target === window) event.stopImmediatePropagation();
              }, true);

              var id = Math.random().toString(36).slice(2);
              var live = new Set();
              var seen = new WeakSet();
              var reported = false;
              var last = null;

              function report() {
                var audible = false;
                live.forEach(function (el) {
                  if (el.paused || el.ended) { live.delete(el); return; }
                  if (!el.muted && el.volume > 0) { audible = true; last = el; }
                });
                if (audible !== reported) {
                  reported = audible;
                  port.postMessage(id + (audible ? ' 1' : ' 0'));
                }
              }
              function update(event) {
                if (!event.target.paused) live.add(event.target);
                report();
              }
              function watch(el) {
                if (seen.has(el)) return;
                seen.add(el);
                ['play', 'playing', 'pause', 'ended', 'emptied', 'volumechange'].forEach(function (name) {
                  el.addEventListener(name, update);
                });
              }

              // Media events don't bubble, and an element made in script may never join the page
              var play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                watch(this);
                return play.apply(this, arguments);
              };
              document.addEventListener('play', function (event) {
                if (event.target instanceof HTMLMediaElement) {
                  watch(event.target);
                  update(event);
                }
              }, true);

              window.addEventListener('pagehide', function () {
                live.clear();
                report();
              });
              port.onmessage = function (event) {
                if (event.data === 'pause') live.forEach(function (el) { el.pause(); });
                if (event.data === 'play' && last) last.play();
              };
            })();
        """.trimIndent()
    }
}
