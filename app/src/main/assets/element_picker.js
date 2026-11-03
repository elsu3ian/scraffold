// Scraffold's element picker. Injected on demand by ElementPicker.kt, which handles the touches and
// the bar, and calls in with positions as fractions of the page's visible area.
(function () {
  var NAME = '__PICKER__';
  if (window[NAME]) return;

  var HOVER = 'rgba(6, 182, 212, 0.9)';
  var MATCH = 'rgba(244, 63, 94, 0.9)';
  var MATCH_FILL = 'rgba(244, 63, 94, 0.22)';
  var MAX_BOXES = 60;

  var host = document.createElement('div');
  var root = host.attachShadow({ mode: 'closed' });
  var hover = null;
  var target = null;
  var narrower = [];
  var selector = '';
  var matches = [];
  var boxes = [];
  var previewSheet = null;
  var frame = 0;

  var set = function (element, styles) {
    for (var key in styles) element.style.setProperty(key, styles[key], 'important');
  };
  set(host, {
    position: 'fixed', left: '0', top: '0', width: '0', height: '0', overflow: 'visible',
    'pointer-events': 'none', 'z-index': '2147483647', display: 'block', margin: '0', padding: '0', border: '0'
  });

  var box = function (color, fill) {
    var element = document.createElement('div');
    set(element, {
      position: 'fixed', 'box-sizing': 'border-box', border: '2px solid ' + color,
      background: fill || 'transparent', 'pointer-events': 'none', display: 'none', 'border-radius': '2px'
    });
    root.appendChild(element);
    return element;
  };
  var hoverBox = box(HOVER);

  var place = function (element, of) {
    var rect = of && of.isConnected ? of.getBoundingClientRect() : null;
    if (!rect || (rect.width === 0 && rect.height === 0)) {
      element.style.setProperty('display', 'none', 'important');
      return;
    }
    set(element, {
      display: 'block', left: rect.left + 'px', top: rect.top + 'px',
      width: rect.width + 'px', height: rect.height + 'px'
    });
  };

  var draw = function () {
    frame = 0;
    if (!host.isConnected) (document.documentElement || document).appendChild(host);
    place(hoverBox, target ? null : hover);
    while (boxes.length < Math.min(matches.length, MAX_BOXES)) boxes.push(box(MATCH, MATCH_FILL));
    for (var i = 0; i < boxes.length; i++) place(boxes[i], matches[i] || null);
  };
  var redraw = function () {
    if (!frame) frame = requestAnimationFrame(draw);
  };

  var at = function (fx, fy) {
    var view = window.visualViewport;
    var x = (view ? view.offsetLeft + fx * view.width : fx * window.innerWidth);
    var y = (view ? view.offsetTop + fy * view.height : fy * window.innerHeight);
    var element = document.elementFromPoint(x, y);
    // Inside open shadow roots, the element itself rather than its host
    while (element && element.shadowRoot) {
      var inner = element.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === element) break;
      element = inner;
    }
    if (element === document.documentElement || element === document.body) return null;
    return element;
  };

  // Names that frameworks and build tools make up, or that a page swaps as it's used
  var STATE = /^(is-|has-)|^(active|selected|hover|focus|focused|open|opened|show|shown|visible|hidden|loaded|loading|sticky|fixed|in|fade)$/i;
  var GENERATED = /^(css|sc|jsx|svelte|emotion|styled|tw|ng|ember|react|mui|radix|headlessui)-/i;
  var random = function (name) {
    if (GENERATED.test(name)) return true;
    var parts = name.split(/[-_]+/);
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      var digits = (part.match(/\d/g) || []).length;
      var letters = part.length - digits;
      if (digits >= 4) return true;
      if (part.length >= 5 && digits >= 2 && letters >= 2) return true;
      // Short mixed-case hashes, like bdVaJa
      if (part.length >= 5 && part.length <= 10 && /[a-z]/.test(part) && (part.slice(1).match(/[A-Z]/g) || []).length >= 2) return true;
    }
    return false;
  };
  var stableId = function (id) {
    return !!id && id.length <= 48 && !/\s/.test(id) && !/^:|:$/.test(id) && !random(id);
  };
  var stableClass = function (name) {
    return name.length >= 2 && name.length <= 48 && !STATE.test(name) && !random(name);
  };
  var count = function (css) {
    try { return document.querySelectorAll(css).length; } catch (e) { return 0; }
  };

  var AD_ATTRIBUTE = /^data-.*(^|[-_])(ad|ads|adunit|adslot|advert|advertisement|sponsor|sponsored|banner)([-_]|$)/i;

  // An element as a selector of its own: its id, or its tag with the rarest of its lasting classes
  var token = function (element) {
    var tag = CSS.escape(element.localName);
    if (stableId(element.id)) return { css: '#' + CSS.escape(element.id), anchor: true, weak: false };
    var classes = [];
    for (var i = 0; i < element.classList.length; i++) {
      var name = element.classList[i];
      if (stableClass(name)) classes.push(name);
    }
    classes.sort(function (a, b) {
      return document.getElementsByClassName(a).length - document.getElementsByClassName(b).length;
    });
    var css = tag + classes.slice(0, 2).map(function (name) { return '.' + CSS.escape(name); }).join('');
    if (!classes.length) {
      for (var j = 0; j < element.attributes.length; j++) {
        var attribute = element.attributes[j].name;
        if (AD_ATTRIBUTE.test(attribute)) {
          css += '[' + CSS.escape(attribute) + ']';
          break;
        }
      }
    }
    return { css: css, anchor: false, weak: css === tag };
  };

  // A tag on its own says little, so it gets its place among its parent's children of that tag
  var segment = function (element, found) {
    if (!found.weak || !element.parentElement) return found.css;
    var same = 0;
    var position = 0;
    var siblings = element.parentElement.children;
    for (var i = 0; i < siblings.length; i++) {
      if (siblings[i].localName !== element.localName) continue;
      same++;
      if (siblings[i] === element) position = same;
    }
    return same > 1 ? found.css + ':nth-of-type(' + position + ')' : found.css;
  };

  // Climbs only as far as it takes to single the element out, through ancestors with ids or classes
  var describe = function (element) {
    var first = token(element);
    var css = segment(element, first);
    var weak = first.weak;
    var anchored = first.anchor;
    var node = element;
    for (var depth = 0; depth < 12 && !anchored && count(css) > 1; depth++) {
      node = node.parentElement;
      if (!node || node === document.body || node === document.documentElement) {
        if (weak) css = 'body > ' + css;
        break;
      }
      var found = token(node);
      if (found.weak && !weak) continue;
      css = segment(node, found) + (weak ? ' > ' : ' ') + css;
      weak = found.weak;
      anchored = found.anchor;
    }
    return css;
  };

  var site = function () {
    return location.hostname.replace(/^www\./, '');
  };

  var preview = function (on) {
    if (on && !previewSheet) {
      previewSheet = new CSSStyleSheet();
      document.adoptedStyleSheets = document.adoptedStyleSheets.concat([previewSheet]);
    }
    if (previewSheet) {
      try { previewSheet.replaceSync(on && selector ? selector + '{display:none!important}' : ''); } catch (e) {}
    }
  };
  var previewing = false;

  var state = function () {
    return {
      selector: selector,
      count: selector ? count(selector) : 0,
      site: site(),
      canNarrow: narrower.length > 0,
      canWiden: !!(target && target.parentElement && target.parentElement !== document.body &&
        target.parentElement !== document.documentElement),
      previewing: previewing
    };
  };

  var choose = function (element) {
    target = element;
    selector = element ? describe(element) : '';
    try { matches = selector ? Array.prototype.slice.call(document.querySelectorAll(selector), 0, MAX_BOXES) : []; } catch (e) { matches = []; }
    if (previewing) preview(true);
    redraw();
    return state();
  };

  var same = function (a, b) {
    var x = a.getBoundingClientRect();
    var y = b.getBoundingClientRect();
    return Math.abs(x.left - y.left) < 1 && Math.abs(x.top - y.top) < 1 &&
      Math.abs(x.width - y.width) < 1 && Math.abs(x.height - y.height) < 1;
  };

  var stop = function () {
    preview(false);
    if (previewSheet) {
      document.adoptedStyleSheets = document.adoptedStyleSheets.filter(function (sheet) { return sheet !== previewSheet; });
    }
    window.removeEventListener('scroll', redraw, true);
    window.removeEventListener('resize', redraw);
    if (window.visualViewport) window.visualViewport.removeEventListener('scroll', redraw);
    host.remove();
    try { delete window[NAME]; } catch (e) {}
  };

  window.addEventListener('scroll', redraw, true);
  window.addEventListener('resize', redraw);
  if (window.visualViewport) window.visualViewport.addEventListener('scroll', redraw);
  draw();

  Object.defineProperty(window, NAME, {
    configurable: true,
    enumerable: false,
    value: {
      hover: function (fx, fy) {
        if (!target) {
          hover = at(fx, fy);
          redraw();
        }
        return true;
      },
      pick: function (fx, fy) {
        narrower = [];
        hover = null;
        return choose(at(fx, fy));
      },
      wider: function () {
        if (!target) return state();
        var next = target.parentElement;
        // Steps that wouldn't show any change go by in one
        while (next && next.parentElement && next.parentElement !== document.body && same(next, target)) next = next.parentElement;
        if (!next || next === document.body || next === document.documentElement) return state();
        narrower.push(target);
        return choose(next);
      },
      narrower: function () {
        return narrower.length ? choose(narrower.pop()) : state();
      },
      preview: function (on) {
        previewing = !!on;
        preview(previewing);
        redraw();
        return state();
      },
      state: state,
      // Hides what the rule covers right away; the engine has it from the next page on
      save: function () {
        var saved = state();
        stop();
        if (saved.selector) {
          var sheet = new CSSStyleSheet();
          try {
            sheet.replaceSync(saved.selector + '{display:none!important}');
            document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
          } catch (e) {}
        }
        return saved;
      },
      stop: function () {
        stop();
        return true;
      }
    }
  });
})();
