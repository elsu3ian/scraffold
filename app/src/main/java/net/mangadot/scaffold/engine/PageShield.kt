package net.mangadot.scaffold.engine

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.webkit.ScriptHandler
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
    private val scriptHandlers = ConcurrentHashMap<String, ScriptHandler>()

    /** For a page about to load [url], on the main thread. */
    @SuppressLint("RequiresFeature")
    fun prepare(url: String) {
        if (!enabled || ShieldSites.isOff(webView.context, Uri.parse(url).host)) return
        val origin = originOf(url) ?: return
        if (origin in scriptedOrigins) return
        val script = engine.cosmetics(url, waitForLists = false)?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).optString("script") }.orEmpty()
        if (script.isEmpty()) return
        scriptedOrigins += origin
        scriptHandlers[origin] = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
        Log.i("Shield", "Scriptlets ready for $origin")
    }

    /** After a site's switch changes, on the main thread: its scriptlets stop from the next page on. */
    fun sitesChanged() {
        scriptHandlers.entries.removeIf { (origin, handler) ->
            if (!ShieldSites.isOff(webView.context, Uri.parse(origin).host)) return@removeIf false
            handler.remove()
            scriptedOrigins -= origin
            true
        }
    }

    // A frame goes by its own site and by the page it's in
    private fun siteOff(url: String): Boolean =
        ShieldSites.isOff(webView.context, Uri.parse(url).host) || ShieldSites.isOff(webView.context, webView.shieldStats.top)

    private inner class Bridge {
        @JavascriptInterface
        fun page(url: String): String {
            if (!enabled || siteOff(url)) return ""
            // Empty while the engine is still being built
            val found = engine.cosmetics(url, waitForLists = true)?.takeIf { it.isNotEmpty() } ?: return ""
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
            if (!enabled || siteOff(url)) return ""
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

        /**
         * Rules a stylesheet can't express, like `:has-text()` or `:upward()`, run against the page
         * and again as it changes, at most every so often. Each selector becomes steps that turn one
         * set of elements into the next, with what matches at the end hidden, restyled or removed.
         * Hiding and styling go through marks the page's stylesheet points at, so an element that stops
         * matching shows again. Uses `css`, `apply` and `later` from [script].
         */
        private val PROCEDURAL = """
    var rules = data.procedural;
    var mark = '_' + Math.random().toString(36).slice(2, 10);
    var OPERATOR = /^(has-text|contains|-abp-contains|has|-abp-has|not|matches-attr|matches-css|matches-css-before|matches-css-after|matches-path|min-text-length|upward|nth-ancestor|xpath|watch-attr|others)\(/;
    var watchAll = false;
    var watched = [];
    var styles = [];

    // The index of the bracket closing the one at open, past escapes and nested pairs
    var closing = function (text, open, close) {
      var depth = 0;
      for (var i = open; i < text.length; i++) {
        var c = text[i];
        if (c === '\\') i++;
        else if (c === text[open]) depth++;
        else if (c === close && --depth === 0) return i;
      }
      return -1;
    };
    // Splits a selector into plain CSS and the operators between it, in adblock-rust's form
    var parse = function (text) {
      var steps = [];
      var plain = '';
      for (var i = 0; i < text.length;) {
        var c = text[i];
        var found = c === ':' && OPERATOR.exec(text.slice(i + 1));
        if (found) {
          var open = i + 1 + found[1].length;
          var end = closing(text, open, ')');
          if (end < 0) return null;
          var step = operator(found[1], text.slice(open + 1, end));
          if (step === null) return null;
          if (step === false) {
            plain += text.slice(i, end + 1);
          } else {
            if (plain) steps.push({ type: 'css-selector', arg: plain });
            plain = '';
            steps.push(step);
          }
          i = end + 1;
        } else if (c === '[' || c === '(') {
          var shut = closing(text, i, c === '[' ? ']' : ')');
          if (shut < 0) return null;
          plain += text.slice(i, shut + 1);
          i = shut + 1;
        } else {
          plain += c === '\\' ? text.substr(i++, 2) : c;
          i++;
        }
      }
      if (plain) steps.push({ type: 'css-selector', arg: plain });
      return steps;
    };
    // False leaves :has() and :not() of plain CSS to the browser
    var operator = function (name, arg) {
      if (name === 'has' || name === '-abp-has' || name === 'not') {
        var inner = parse(arg);
        if (!inner || !inner.length) return null;
        var plain = inner.length === 1 && inner[0].type === 'css-selector';
        if (plain && name !== '-abp-has') return false;
        if (name === 'not') return { type: 'not', arg: inner };
        // Inside :has(), a selector with no combinator looks among descendants
        if (inner[0].type !== 'css-selector') inner.unshift({ type: 'css-selector', arg: ' *' });
        else if (!/^\s*[>+~]/.test(inner[0].arg)) inner[0].arg = ' ' + inner[0].arg;
        return { type: 'has', arg: inner };
      }
      if (name === 'contains' || name === '-abp-contains') name = 'has-text';
      if (name === 'nth-ancestor') name = 'upward';
      return { type: name, arg: arg };
    };

    var unique = function (list) { return Array.from(new Set(list)); };
    var each = function (nodes, find) {
      var out = [];
      for (var i = 0; i < nodes.length; i++) out.push.apply(out, find(nodes[i]));
      return unique(out);
    };
    // Plain CSS after an operator carries on from each element: a combinator, or more of its own selector
    var from = function (element, css) {
      var parent = element.parentElement;
      if (!parent) return element === document.documentElement ? Array.from(document.querySelectorAll(':root' + css)) : [];
      var place = Array.prototype.indexOf.call(parent.children, element) + 1;
      return Array.from(parent.querySelectorAll(':scope > :nth-child(' + place + ')' + css));
    };
    // Text between slashes is a regular expression; anything else is looked for as it is, or must equal it
    var matcher = function (pattern, whole) {
      var regex = /^\/(.*)\/([dgimsuy]*)$/.exec(pattern.trim());
      if (regex) {
        var compiled = new RegExp(regex[1], regex[2].replace('g', ''));
        return function (text) { return compiled.test(text); };
      }
      return whole ? function (text) { return text === pattern; } : function (text) { return text.indexOf(pattern) !== -1; };
    };
    var unquote = function (text) {
      text = text.trim();
      return /^(["']).*\1$/.test(text) ? text.slice(1, -1) : text;
    };
    var styleOf = function (arg, pseudo) {
      var parts = /^\s*(?:(before|after)\s*,\s*)?([\w-]+)\s*:\s*([\s\S]*)$/.exec(arg);
      if (!parts) throw new Error('Bad style test');
      var test = matcher(unquote(parts[3]), true);
      var where = parts[1] ? '::' + parts[1] : pseudo;
      return function (nodes) {
        return nodes.filter(function (node) { return test(getComputedStyle(node, where).getPropertyValue(parts[2]).trim()); });
      };
    };

    var STEPS = {
      'css-selector': function (arg) {
        return function (nodes) {
          return nodes ? each(nodes, function (node) { return from(node, arg); }) : Array.from(document.querySelectorAll(arg));
        };
      },
      'has-text': function (arg) {
        var test = matcher(arg, false);
        return function (nodes) { return nodes.filter(function (node) { return test(node.textContent); }); };
      },
      'min-text-length': function (arg) {
        var least = parseInt(arg, 10);
        return function (nodes) { return nodes.filter(function (node) { return node.textContent.length >= least; }); };
      },
      'matches-css': function (arg) { return styleOf(arg, null); },
      'matches-css-before': function (arg) { return styleOf(arg, '::before'); },
      'matches-css-after': function (arg) { return styleOf(arg, '::after'); },
      'matches-attr': function (arg) {
        var parts = /^\s*("[^"]*"|\/(?:\\.|[^\/])*\/[a-z]*|[^=]+?)\s*(?:=\s*([\s\S]*?))?\s*$/.exec(arg);
        if (!parts) throw new Error('Bad attribute test');
        var name = matcher(unquote(parts[1]), true);
        var value = parts[2] === undefined ? function () { return true; } : matcher(unquote(parts[2]), true);
        return function (nodes) {
          return nodes.filter(function (node) {
            for (var i = 0; i < node.attributes.length; i++) {
              if (name(node.attributes[i].name) && value(node.attributes[i].value)) return true;
            }
            return false;
          });
        };
      },
      'matches-path': function (arg) {
        var test = matcher(arg, false);
        return function (nodes) { return test(location.pathname + location.search) ? nodes : []; };
      },
      upward: function (arg) {
        var steps = /^\s*\d+\s*$/.test(arg) ? parseInt(arg, 10) : 0;
        return function (nodes) {
          return unique(nodes.map(function (node) {
            if (!steps) return node.parentElement && node.parentElement.closest(arg);
            for (var i = 0; i < steps && node; i++) node = node.parentElement;
            return node;
          }).filter(Boolean));
        };
      },
      xpath: function (arg) {
        var expression = document.createExpression(arg, null);
        return function (nodes) {
          return each(nodes || [document], function (node) {
            var found = expression.evaluate(node, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
            var out = [];
            for (var i = 0; i < found.snapshotLength; i++) {
              if (found.snapshotItem(i).nodeType === 1) out.push(found.snapshotItem(i));
            }
            return out;
          });
        };
      },
      has: function (arg) {
        var inner = compile(arg);
        return function (nodes) { return nodes.filter(function (node) { return run(inner, [node]).length > 0; }); };
      },
      not: function (arg) {
        var inner = compile(arg);
        return function (nodes) { return nodes.filter(function (node) { return run(inner, [node]).length === 0; }); };
      },
      'watch-attr': function (arg) {
        var names = arg.split(',').map(function (name) { return name.trim(); }).filter(Boolean);
        if (names.length) watched.push.apply(watched, names);
        else watchAll = true;
        return function (nodes) { return nodes; };
      },
      // Everything outside what matched, leaving its ancestors and its own contents be
      others: function () {
        return function (nodes) {
          if (!nodes.length || !document.body) return [];
          var kept = new Set(nodes);
          var path = new Set();
          nodes.forEach(function (node) {
            for (var up = node.parentElement; up; up = up.parentElement) path.add(up);
          });
          var out = [];
          var walk = function (element) {
            for (var child = element.firstElementChild; child; child = child.nextElementSibling) {
              if (kept.has(child)) continue;
              if (path.has(child)) walk(child);
              else out.push(child);
            }
          };
          if (path.has(document.body)) walk(document.body);
          return out;
        };
      }
    };

    var compile = function (selector) {
      var steps = [];
      selector.forEach(function (step) {
        // Without css-validation, adblock-rust hands over a procedural selector as one piece of "CSS"
        var parts = step.type === 'css-selector' && OPERATOR_ANYWHERE.test(step.arg) ? parse(step.arg) : [step];
        if (!parts) throw new Error('Bad selector');
        parts.forEach(function (part) {
          var make = STEPS[part.type];
          if (!make) throw new Error('Unknown ' + part.type);
          var made = make(part.arg);
          made.document = part.type === 'css-selector' || part.type === 'xpath';
          steps.push(made);
        });
      });
      return steps;
    };
    var OPERATOR_ANYWHERE = /:(has-text|contains|-abp-contains|-abp-has|matches-attr|matches-css|matches-css-before|matches-css-after|matches-path|min-text-length|upward|nth-ancestor|xpath|watch-attr|others)\(/;
    // Null stands for the whole document, which only CSS and XPath start from; the rest start from every element
    var run = function (steps, nodes) {
      for (var i = 0; i < steps.length; i++) {
        if (nodes === null && !steps[i].document) nodes = Array.from(document.querySelectorAll('body *'));
        nodes = steps[i](nodes);
        if (!nodes.length) break;
      }
      return nodes || [];
    };

    var marks = [mark];
    var actions = [];
    rules.forEach(function (rule) {
      try {
        var action = rule.action || { type: 'hide' };
        var selector = rule.selector || [];
        var plain = selector.length === 1 && selector[0].type === 'css-selector' && !OPERATOR_ANYWHERE.test(selector[0].arg);
        // Restyling plain CSS needs no script
        if (plain && action.type === 'style') {
          css += selector[0].arg + '{' + action.arg + '}';
          return;
        }
        var done = { steps: compile(selector), type: action.type, arg: action.arg };
        if (action.type === 'hide') done.mark = mark;
        if (action.type === 'style') {
          done.mark = mark + 's' + styles.length;
          styles.push('[' + done.mark + ']{' + action.arg + '}');
          marks.push(done.mark);
        }
        actions.push(done);
      } catch (e) {}
    });
    css += '[' + mark + ']{display:none!important}' + styles.join('');
    apply();
    if (!actions.length) return;

    var marked = Object.create(null);
    var pass = function () {
      var now = Object.create(null);
      marks.forEach(function (name) { now[name] = new Set(); });
      actions.forEach(function (action) {
        var found;
        try { found = run(action.steps, null); } catch (e) { return; }
        found.forEach(function (node) {
          if (action.mark) now[action.mark].add(node);
          else if (action.type === 'remove') node.remove();
          else if (action.type === 'remove-attr') node.hasAttribute(action.arg) && node.removeAttribute(action.arg);
          else if (action.type === 'remove-class') node.classList.contains(action.arg) && node.classList.remove(action.arg);
        });
      });
      marks.forEach(function (name) {
        (marked[name] || new Set()).forEach(function (node) {
          if (!now[name].has(node)) node.removeAttribute(name);
        });
        now[name].forEach(function (node) {
          if (!node.hasAttribute(name)) node.setAttribute(name, '');
        });
      });
      marked = now;
    };

    // Runs again once the page settles after a change, waiting longer when a pass is slow
    var timer = 0;
    var gap = 100;
    var due = 0;
    var go = function () {
      timer = 0;
      var started = Date.now();
      pass();
      gap = Math.min(2000, Math.max(100, (Date.now() - started) * 10));
      due = Date.now() + gap;
    };
    var soon = function () {
      if (!timer) timer = later(go, Math.max(0, due - Date.now()));
    };
    var options = { childList: true, subtree: true };
    if (watchAll || watched.length) {
      options.attributes = true;
      if (!watchAll) options.attributeFilter = watched;
    }
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var name = records[i].attributeName;
        if (!name || name.indexOf(mark) !== 0) return soon();
      }
    }).observe(document, options);
    document.addEventListener('DOMContentLoaded', soon, { once: true });
    soon();
"""

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
  if (data.procedural && data.procedural.length) (function () {
$PROCEDURAL
  })();
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
