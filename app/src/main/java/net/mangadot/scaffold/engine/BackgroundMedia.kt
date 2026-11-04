package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.content.Intent
import android.os.SystemClock
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Keeps a page's audio and video going after the app is left. Sites pause themselves when told they
 * went to the background, so the page is always told it's on screen. It reports whether it's playing,
 * what (the title, artist and cover a site gives the phone's media controls, or failing those the
 * page's own title and preview image), and how far along. [MediaPlaybackService] goes by all three.
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

    /** What's playing, as the page names it. Empty until the page says. */
    var title = ""
        private set
    var artist = ""
        private set
    /** The cover's address, or empty when the page has none. */
    var artwork = ""
        private set
    /** Whether the site has tracks to skip between, like a playlist, rather than one video to seek through. */
    var hasTracks = false
        private set

    /** How far along, in milliseconds, as of [positionAt] on the [SystemClock.elapsedRealtime] clock. */
    var positionMs = -1L
        private set
    var positionAt = 0L
        private set
    /** How long it is, or 0 when it isn't known or it's a live stream. */
    var durationMs = 0L
        private set
    var rate = 1f
        private set

    private fun onReport(message: String, frame: JavaScriptReplyProxy) {
        when {
            message.startsWith(META) -> {
                val meta = runCatching { JSONObject(message.removePrefix(META)) }.getOrNull() ?: return
                title = meta.optString("title").trim()
                artist = meta.optString("artist").trim()
                artwork = meta.optString("artwork").takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
                hasTracks = meta.optBoolean("tracks")
                MediaPlaybackService.onMetadata(this)
            }
            message.startsWith(POSITION) -> {
                val position = runCatching { JSONObject(message.removePrefix(POSITION)) }.getOrNull() ?: return
                val at = position.optDouble("at", -1.0)
                val length = position.optDouble("length", 0.0)
                positionMs = if (at.isFinite() && at >= 0) (at * 1000).toLong() else -1L
                positionAt = SystemClock.elapsedRealtime()
                durationMs = if (length.isFinite() && length > 0) (length * 1000).toLong() else 0L
                rate = position.optDouble("rate", 1.0).toFloat().takeIf { it > 0f } ?: 1f
                MediaPlaybackService.onPosition(this)
            }
            else -> {
                val id = message.substringBefore(' ')
                if (message.endsWith(" 1")) {
                    playingFrames[id] = frame
                    lastFrame = frame
                } else {
                    playingFrames.remove(id)
                }
                MediaPlaybackService.onChanged(webView.context, this)
            }
        }
    }

    fun pause() {
        playingFrames.values.toList().forEach { send(it, "pause") }
    }

    fun play() {
        lastFrame?.let { send(it, "play") }
    }

    /** The previous track, or 10 seconds back where the site has no tracks. */
    fun previous() = lastFrame?.let { send(it, "previous") }

    /** The next track, or 10 seconds on. */
    fun next() = lastFrame?.let { send(it, "next") }

    fun seekTo(ms: Long) = lastFrame?.let { send(it, "seek ${ms / 1000.0}") }

    private fun send(frame: JavaScriptReplyProxy, command: String) {
        // The frame may be gone by now, and then there is nothing left to tell
        runCatching { frame.postMessage(command) }
    }

    companion object {
        private const val PORT = "scraffoldMedia"
        private const val META = "meta "
        private const val POSITION = "pos "

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
              if (!port || port.__seen) return;
              try { Object.defineProperty(port, '__seen', { value: true }); } catch (e) {}
              // Out of the page's sight; the reference kept here goes on working
              try { delete window.$PORT; } catch (e) {}

              // Anything put in place of the browser's own reads as the browser's own when a page prints
              // it, as checks like Cloudflare's do: a page that finds rewritten browser code takes the
              // visitor for a bot and asks again and again
              var fakes = new WeakMap();
              var printOf = Function.prototype.toString;
              var print = ({ toString() { return fakes.has(this) ? fakes.get(this) : printOf.call(this); } }).toString;
              function native(fn, name) {
                fakes.set(fn, 'function ' + (name || fn.name) + '() { [native code] }');
                return fn;
              }
              native(print, 'toString');
              Object.defineProperty(Function.prototype, 'toString', { value: print, writable: true, configurable: true, enumerable: false });
              function getter(holder, name) {
                return native(Object.getOwnPropertyDescriptor(holder, name).get);
              }

              var shown = {
                get hidden() { return false; },
                get webkitHidden() { return false; },
                get visibilityState() { return 'visible'; },
                get webkitVisibilityState() { return 'visible'; }
              };
              Object.keys(shown).forEach(function (name) {
                Object.defineProperty(Document.prototype, name, { get: getter(shown, name), set: undefined, configurable: true, enumerable: true });
              });
              ['visibilitychange', 'webkitvisibilitychange'].forEach(function (name) {
                window.addEventListener(name, function (event) { event.stopImmediatePropagation(); }, true);
              });
              window.addEventListener('blur', function (event) {
                if (event.target === window) event.stopImmediatePropagation();
              }, true);

              // Chrome gives every page a window.chrome; WebView doesn't, and its absence names the app
              if (!window.chrome) {
                var started = Date.now() / 1000;
                var chrome = {
                  app: {
                    isInstalled: false,
                    InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
                    RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' },
                    getDetails() { return null; },
                    getIsInstalled() { return false; },
                    installState(callback) { if (callback) setTimeout(function () { callback('not_installed'); }); },
                    runningState() { return 'cannot_run'; }
                  },
                  csi() {
                    var t = performance.timing;
                    return { startE: t.navigationStart, onloadT: t.domContentLoadedEventEnd, pageT: performance.now(), tran: 15 };
                  },
                  loadTimes() {
                    var t = performance.timing;
                    return {
                      requestTime: t.navigationStart / 1000, startLoadTime: t.navigationStart / 1000,
                      commitLoadTime: t.responseStart / 1000, finishDocumentLoadTime: t.domContentLoadedEventEnd / 1000,
                      finishLoadTime: t.loadEventEnd / 1000, firstPaintTime: started, firstPaintAfterLoadTime: 0,
                      navigationType: 'Other', wasFetchedViaSpdy: true, wasNpnNegotiated: true, npnNegotiatedProtocol: 'h2',
                      wasAlternateProtocolAvailable: false, connectionInfo: 'h2'
                    };
                  }
                };
                ['getDetails', 'getIsInstalled', 'installState', 'runningState'].forEach(function (name) { native(chrome.app[name]); });
                native(chrome.csi);
                native(chrome.loadTimes);
                Object.defineProperty(window, 'chrome', { value: chrome, writable: true, configurable: false, enumerable: true });
              }

              var id = Math.random().toString(36).slice(2);
              var live = new Set();
              var seen = new WeakSet();
              var reported = false;
              var last = null;

              // Sites tell a phone's media controls what's playing, and what its buttons do, through
              // navigator.mediaSession. WebView leaves it out, so sites stay quiet; this puts it in place
              // before they look. Where it's there already, its buttons are noted on their way through.
              var handlers = {};
              var given = null;
              var soon = 0;
              function describeSoon() {
                clearTimeout(soon);
                soon = setTimeout(describe, 50);
              }
              function keep(action, handler) {
                if (handler) handlers[action] = handler; else delete handlers[action];
                describeSoon();
              }
              if (!('mediaSession' in navigator)) {
                var session = {
                  playbackState: 'none',
                  setActionHandler(action, handler) { keep(action, handler); },
                  setPositionState() {},
                  setMicrophoneActive() {},
                  setCameraActive() {},
                  get metadata() { return given; },
                  set metadata(value) { given = value || null; describeSoon(); }
                };
                ['setActionHandler', 'setPositionState', 'setMicrophoneActive', 'setCameraActive'].forEach(function (name) { native(session[name]); });
                var access = Object.getOwnPropertyDescriptor(session, 'metadata');
                native(access.get);
                native(access.set);
                var sessions = { get mediaSession() { return session; } };
                Object.defineProperty(Navigator.prototype, 'mediaSession', {
                  get: getter(sessions, 'mediaSession'), set: undefined, configurable: true, enumerable: true
                });
                if (!window.MediaMetadata) {
                  var MediaMetadata = function MediaMetadata(init) {
                    init = init || {};
                    this.title = String(init.title || '');
                    this.artist = String(init.artist || '');
                    this.album = String(init.album || '');
                    this.artwork = Array.prototype.map.call(init.artwork || [], function (a) {
                      return { src: String(a.src || ''), sizes: String(a.sizes || ''), type: String(a.type || '') };
                    });
                  };
                  native(MediaMetadata);
                  Object.defineProperty(window, 'MediaMetadata', { value: MediaMetadata, writable: true, configurable: true, enumerable: false });
                }
              } else {
                var real = navigator.mediaSession;
                var setHandler = real.setActionHandler.bind(real);
                real.setActionHandler = native(({ setActionHandler(action, handler) {
                  keep(action, handler);
                  try { setHandler(action, handler); } catch (e) {}
                } }).setActionHandler);
              }
              function act(action, extra) {
                var handler = handlers[action];
                if (!handler) return false;
                var details = { action: action };
                for (var key in extra || {}) details[key] = extra[key];
                try { handler(details); } catch (e) {}
                return true;
              }

              // What the phone's media controls show: the site's own media details where it gives them,
              // its largest cover among them, and the page's title and preview image otherwise
              function details() {
                var meta = navigator.mediaSession && navigator.mediaSession.metadata;
                var art = '', size = -1;
                if (meta && meta.artwork) {
                  Array.prototype.forEach.call(meta.artwork, function (a) {
                    var side = parseInt(String(a.sizes || '').split('x')[0], 10) || 0;
                    if (a.src && side > size) { size = side; art = a.src; }
                  });
                }
                var og = function (name) {
                  var tag = document.querySelector('meta[property="og:' + name + '"]');
                  return tag ? tag.content : '';
                };
                if (!art) art = og('image') || (last && last.poster) || '';
                try { art = art ? new URL(art, location.href).href : ''; } catch (e) { art = ''; }
                return {
                  title: (meta && meta.title) || document.title || og('title') || '',
                  artist: (meta && meta.artist) || '',
                  artwork: art,
                  tracks: !!(handlers.nexttrack || handlers.previoustrack)
                };
              }
              var sent = '';
              function describe() {
                var text = JSON.stringify(details());
                if (text === sent) return;
                sent = text;
                port.postMessage('$META' + text);
              }

              // How far along, so the phone can draw a seek bar; it moves the bar on by itself in between
              function position() {
                if (!last) return;
                port.postMessage('$POSITION' + JSON.stringify({
                  at: last.currentTime, length: isFinite(last.duration) ? last.duration : 0, rate: last.playbackRate
                }));
              }

              // Sites fill these in a moment after playing starts, and change them for the next track
              var ticks = 0;
              setInterval(function () {
                if (!reported) return;
                describe();
                if (++ticks % 5 === 0) position();
              }, 2000);

              function report() {
                var audible = false;
                live.forEach(function (el) {
                  if (el.paused || el.ended) { live.delete(el); return; }
                  if (!el.muted && el.volume > 0) { audible = true; last = el; }
                });
                if (audible) describe();
                if (audible !== reported) {
                  reported = audible;
                  port.postMessage(id + (audible ? ' 1' : ' 0'));
                }
              }
              function update(event) {
                if (!event.target.paused) live.add(event.target);
                report();
                if (event.target === last) position();
              }
              function watch(el) {
                if (seen.has(el)) return;
                seen.add(el);
                ['play', 'playing', 'pause', 'ended', 'emptied', 'volumechange', 'seeked', 'ratechange', 'durationchange'].forEach(function (name) {
                  el.addEventListener(name, update);
                });
              }

              // Media events don't bubble, and an element made in script may never join the page
              var play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = native(({ play() {
                watch(this);
                return play.apply(this, arguments);
              } }).play);
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

              // The buttons on the notification: the site's own where it has them, the media itself otherwise
              function jump(by) {
                if (!last) return;
                var end = isFinite(last.duration) ? last.duration : Infinity;
                last.currentTime = Math.max(0, Math.min(end, last.currentTime + by));
              }
              port.onmessage = function (event) {
                var command = String(event.data);
                if (command === 'pause') live.forEach(function (el) { el.pause(); });
                else if (command === 'play' && last) last.play();
                else if (command === 'previous') { if (!act('previoustrack')) jump(-10); }
                else if (command === 'next') { if (!act('nexttrack')) jump(10); }
                else if (command.indexOf('seek ') === 0) {
                  var to = parseFloat(command.slice(5));
                  if (isFinite(to) && !act('seekto', { seekTime: to }) && last) last.currentTime = to;
                }
              };
            })();
        """.trimIndent()
    }
}
