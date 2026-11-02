// Scraffold's userscript runtime. Wrapped per script; returns nothing to the page.
var __sfboot = function (cfg, main) {
  // Captured now, before any page code can run, so the token never reaches the page
  var bridgeCall = window.__scraffoldGM ? window.__scraffoldGM.call.bind(window.__scraffoldGM) : null;
  var token = cfg.token;
  var call = function (op, args) {
    return bridgeCall ? bridgeCall(token, op, JSON.stringify(args || [])) : null;
  };
  // Commands belong to the page that registered them
  if (window.top === window.self) call('menuReset');

  var url = location.href;
  var hits = function (r) { return new RegExp(r, 'i').test(url); };
  if (!cfg.includes.some(hits) || cfg.excludes.some(hits)) return;
  var isTop = window.top === window.self;
  if (cfg.noframes && !isTop) return;

  var granted = function (name) {
    return cfg.grants.indexOf(name) !== -1 || cfg.grants.indexOf(name.replace('GM_', 'GM.')) !== -1;
  };

  var listeners = {};
  var listenerId = 0;
  var menuHandlers = {};
  var menuId = 0;
  var requests = {};
  var requestId = 0;

  Object.defineProperty(window, cfg.callback, {
    enumerable: false,
    value: function (kind, id, payload) {
      if (kind === 'menu') {
        if (menuHandlers[id]) menuHandlers[id]();
      } else if (kind === 'xhr') {
        finishRequest(id, payload);
      }
    }
  });

  var api = {};

  var readValue = function (key, fallback) {
    var raw = call('getValue', [String(key)]);
    if (raw === null || raw === undefined) return fallback;
    try { return JSON.parse(raw); } catch (e) { return fallback; }
  };
  api.GM_getValue = readValue;
  api.GM_setValue = function (key, value) {
    var old = readValue(key);
    call('setValue', [String(key), JSON.stringify(value === undefined ? null : value)]);
    notify(String(key), old, value);
  };
  api.GM_deleteValue = function (key) {
    var old = readValue(key);
    call('deleteValue', [String(key)]);
    notify(String(key), old, undefined);
  };
  api.GM_listValues = function () {
    try { return JSON.parse(call('listValues') || '[]'); } catch (e) { return []; }
  };
  var notify = function (key, oldValue, newValue) {
    Object.keys(listeners).forEach(function (id) {
      if (listeners[id].key === key) {
        try { listeners[id].fn(key, oldValue, newValue, false); } catch (e) { console.error(e); }
      }
    });
  };
  api.GM_addValueChangeListener = function (key, fn) {
    var id = ++listenerId;
    listeners[id] = { key: String(key), fn: fn };
    return id;
  };
  api.GM_removeValueChangeListener = function (id) { delete listeners[id]; };

  api.GM_addStyle = function (css) {
    var style = document.createElement('style');
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
    return style;
  };
  api.GM_addElement = function (parent, tag, attributes) {
    if (typeof parent === 'string') { attributes = tag; tag = parent; parent = null; }
    var element = document.createElement(tag);
    Object.keys(attributes || {}).forEach(function (name) {
      if (name === 'textContent') element.textContent = attributes[name];
      else element.setAttribute(name, attributes[name]);
    });
    (parent || document.head || document.documentElement).appendChild(element);
    return element;
  };
  api.GM_log = function () { console.log.apply(console, ['[' + cfg.info.script.name + ']'].concat([].slice.call(arguments))); };

  api.GM_getResourceText = function (name) { return call('resourceText', [String(name)]); };
  api.GM_getResourceURL = function (name) { return call('resourceUrl', [String(name)]); };
  api.GM_openInTab = function (target) {
    call('openInTab', [String(target)]);
    return { close: function () {}, closed: false, onclose: null };
  };
  api.GM_setClipboard = function (text) { call('setClipboard', [String(text)]); };
  api.GM_notification = function (details, title) {
    var text = typeof details === 'string' ? details : (details && details.text) || '';
    var heading = typeof details === 'string' ? title : details && details.title;
    call('notification', [heading ? heading + ': ' + text : text]);
  };
  api.GM_download = function (details, name) {
    var target = typeof details === 'string' ? details : details.url;
    var file = typeof details === 'string' ? name : details.name;
    call('download', [String(target), String(file || '')]);
  };
  api.GM_registerMenuCommand = function (caption, handler) {
    if (!isTop) return 0;
    var id = ++menuId;
    menuHandlers[id] = handler;
    call('menuAdd', [id, String(caption), cfg.callback]);
    return id;
  };
  api.GM_unregisterMenuCommand = function (id) {
    delete menuHandlers[id];
    call('menuRemove', [id]);
  };

  var fire = function (details, name, response) {
    if (typeof details[name] === 'function') {
      try { details[name](response); } catch (e) { console.error(e); }
    }
  };
  var finishRequest = function (id, payload) {
    var details = requests[id];
    if (!details) return;
    delete requests[id];
    var response = JSON.parse(payload);
    var type = details.responseType;
    if (response.bodyBase64 !== undefined) {
      var binary = atob(response.bodyBase64);
      var bytes = new Uint8Array(binary.length);
      for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      response.response = type === 'blob' ? new Blob([bytes]) : bytes.buffer;
      delete response.bodyBase64;
    } else {
      response.response = response.responseText;
      if (type === 'json') {
        try { response.response = JSON.parse(response.responseText); } catch (e) { response.response = null; }
      }
    }
    response.readyState = 4;
    fire(details, response.kind === 'load' ? 'onload' : (response.kind === 'timeout' ? 'ontimeout' : 'onerror'), response);
    fire(details, 'onloadend', response);
  };
  api.GM_xmlhttpRequest = function (details) {
    var id = ++requestId;
    requests[id] = details;
    call('xhr', [id, cfg.callback, JSON.stringify({
      method: details.method || 'GET',
      url: new URL(details.url, location.href).href,
      headers: details.headers || {},
      data: details.data === undefined ? null : String(details.data),
      binary: details.responseType === 'blob' || details.responseType === 'arraybuffer',
      anonymous: !!details.anonymous,
      timeout: details.timeout || 0
    })]);
    return { abort: function () { delete requests[id]; } };
  };

  var GM = { info: cfg.info };
  var names = Object.keys(api);
  names.forEach(function (name) {
    if (!granted(name)) { api[name] = undefined; return; }
    var fn = api[name];
    GM[name.replace('GM_', '')] = function () {
      var args = arguments;
      return new Promise(function (resolve, reject) {
        try { resolve(fn.apply(null, args)); } catch (e) { reject(e); }
      });
    };
  });
  if (GM.xmlhttpRequest) {
    GM.xmlHttpRequest = GM.xmlhttpRequest;
  }
  api.GM_info = cfg.info;
  api.GM = GM;
  api.unsafeWindow = window;

  var ran = false;
  var run = function () {
    if (ran) return;
    ran = true;
    try {
      main(api);
    } catch (e) {
      console.error('[Scraffold] ' + cfg.info.script.name, e);
    }
  };

  var whenElement = function (ready) {
    if (ready()) { run(); return; }
    var observer = new MutationObserver(function () {
      if (ready()) { observer.disconnect(); run(); }
    });
    observer.observe(document, { childList: true, subtree: true });
  };

  switch (cfg.runAt) {
    case 'document-start':
      // Nothing exists yet this early: scripts expect at least the root element
      whenElement(function () { return document.documentElement; });
      break;
    case 'document-body':
      whenElement(function () { return document.body; });
      break;
    case 'document-end':
      if (document.readyState !== 'loading') run();
      else document.addEventListener('DOMContentLoaded', run, { once: true });
      break;
    default:
      if (document.readyState === 'complete') run();
      else window.addEventListener('load', run, { once: true });
      document.addEventListener('DOMContentLoaded', function () { setTimeout(run, 2000); }, { once: true });
  }
};
