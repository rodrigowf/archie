/* Remote console (spec 13 §1.4, inv02 F-38). LOAD-BEARING for devices without devtools
 * (iPad mini 2, A300M). Ported from frontend/index.html:5-26 and frontend-compat/index.html:11-32.
 *
 * HAND-WRITTEN ES5: this file is inlined into index.html by scripts/vite-plugin-html-target.ts and
 * is NOT transpiled. No let/const, arrow functions, template literals, or other ES2015+ syntax.
 * scripts/__tests__/remote-console.test.ts parses it with acorn at ecmaVersion 5.
 *
 * Changes from the old script:
 *  - console mirroring can be switched off (default: off on main, on for compat), via the
 *    `archie.remoteConsole` localStorage key ('1' / '0') or window.__archieRemoteConsole.setEnabled;
 *  - window `error` and `unhandledrejection` are always sent, even when mirroring is off;
 *  - rate limit: at most 60 messages per 10 s window; extra messages are counted and reported
 *    as one "dropped N" line when the next window opens;
 *  - each message is capped at 4 KB;
 *  - transport is XMLHttpRequest first (sendBeacon only as a fallback, and on pagehide): the
 *    2026-10-09 iPad session sent nothing at all, and an async XHR is the path every old WebKit
 *    delivers while the page is alive.
 *
 * Trace (only while mirroring is on, so on compat by default). Lets a device with no devtools
 * show what it is doing, even when the app itself throws nothing:
 *  - [boot]   once at load: user agent, URL, viewport — proves the logger runs on the device;
 *  - [click]  every click (capture phase), with a short descriptor of the target;
 *  - [tap]    a touch that produced no click within 600 ms (taps swallowed before React);
 *  - [nav]    every hash change;
 *  - [probe]  1 s after boot and after each [nav]: the element on top at 9 points of the viewport
 *             (finds invisible overlays that eat taps) plus the size of #root;
 *  - [stall]  the main thread was blocked for more than 2.5 s (logged when it recovers);
 *  - [unload] the page is going away (reload / navigation), sent by beacon.
 *
 * __REMOTE_CONSOLE_CONFIG__ is replaced by a JSON object literal at build time:
 *   { endpoint: string, prefix: string, defaultOn: boolean }
 */
(function (cfg) {
  'use strict';
  var w = window;
  if (w.__archieRemoteConsole) {
    return;
  }
  var STORAGE_KEY = 'archie.remoteConsole';
  var LIMIT = 60;
  var WINDOW_MS = 10000;
  var MAX_LEN = 4096;
  var STALL_TICK_MS = 1000;
  var STALL_MS = 2500;
  var TAP_MS = 600;
  var PROBE_DELAY_MS = 1000;
  var endpoint = cfg.endpoint || '/api/debug/log';
  var prefix = cfg.prefix || '';
  var enabled = !!cfg.defaultOn;
  var windowStart = 0;
  var inWindow = 0;
  var dropped = 0;
  var totalDropped = 0;
  var sent = 0;
  var busy = false;

  try {
    var stored = w.localStorage.getItem(STORAGE_KEY);
    if (stored === '1') {
      enabled = true;
    } else if (stored === '0') {
      enabled = false;
    }
  } catch (e) {
    /* storage unavailable: keep the default */
  }

  function now() {
    return new Date().getTime();
  }

  function beacon(body) {
    try {
      return !!(w.navigator && typeof w.navigator.sendBeacon === 'function' && w.navigator.sendBeacon(endpoint, body));
    } catch (e) {
      return false;
    }
  }

  function xhr(body) {
    try {
      if (typeof w.XMLHttpRequest !== 'function' && typeof w.XMLHttpRequest !== 'object') {
        return false;
      }
      var req = new w.XMLHttpRequest();
      req.open('POST', endpoint, true);
      req.setRequestHeader('Content-Type', 'application/json');
      req.send(body);
      return true;
    } catch (e) {
      return false;
    }
  }

  function post(level, msg, useBeacon) {
    var body = JSON.stringify({ level: level, msg: msg, ts: new Date().toISOString() });
    if (useBeacon) {
      if (!beacon(body)) {
        xhr(body);
      }
      return;
    }
    if (!xhr(body)) {
      beacon(body);
    }
  }

  function admit() {
    var t = now();
    if (t - windowStart >= WINDOW_MS) {
      windowStart = t;
      inWindow = 0;
      if (dropped > 0) {
        var n = dropped;
        dropped = 0;
        inWindow = 1;
        sent++;
        post('warn', prefix + '[remote-console] dropped ' + n + ' message(s) (rate limit ' + LIMIT + '/' + WINDOW_MS / 1000 + 's)');
      }
    }
    if (inWindow >= LIMIT) {
      dropped++;
      totalDropped++;
      return false;
    }
    inWindow++;
    return true;
  }

  function stringify(args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      var s;
      try {
        if (a instanceof Error) {
          s = a.stack ? a.name + ': ' + a.message + '\n' + String(a.stack) : a.name + ': ' + a.message;
        } else if (typeof a === 'object' && a !== null) {
          s = JSON.stringify(a);
        } else {
          s = String(a);
        }
      } catch (e) {
        s = '[unserializable]';
      }
      parts.push(s);
    }
    return parts.join(' ');
  }

  function send(level, msg, useBeacon) {
    if (busy) {
      return false;
    }
    busy = true;
    try {
      if (!admit()) {
        return false;
      }
      var text = prefix + String(msg);
      if (text.length > MAX_LEN) {
        text = text.substring(0, MAX_LEN) + '…[truncated ' + (text.length - MAX_LEN) + ' chars]';
      }
      sent++;
      post(level, text, useBeacon);
      return true;
    } finally {
      busy = false;
    }
  }

  var methods = ['log', 'warn', 'error', 'info'];
  for (var m = 0; m < methods.length; m++) {
    (function (name) {
      var original = w.console && w.console[name];
      if (typeof original !== 'function') {
        return;
      }
      w.console[name] = function () {
        original.apply(w.console, arguments);
        if (enabled) {
          send(name, stringify(arguments));
        }
      };
    })(methods[m]);
  }

  w.addEventListener('error', function (e) {
    var where = e && e.filename ? ' @ ' + e.filename + ':' + e.lineno + ':' + e.colno : '';
    var stack = e && e.error && e.error.stack ? '\n' + String(e.error.stack) : '';
    send('uncaught', (e && e.message ? e.message : 'error') + where + stack);
  });

  w.addEventListener('unhandledrejection', function (e) {
    var r = e ? e.reason : undefined;
    send('unhandledrejection', stringify([r]));
  });

  /* ---- trace (only while mirroring is on) ---- */

  function describe(el) {
    if (!el || !el.tagName) {
      return String(el);
    }
    var d = el.tagName.toLowerCase();
    if (el.id) {
      d += '#' + el.id;
    }
    var cls = typeof el.className === 'string' ? el.className : el.getAttribute && el.getAttribute('class');
    if (cls) {
      d += '.' + String(cls).split(/\s+/).slice(0, 2).join('.');
    }
    var label = el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title'));
    var text = label || (el.textContent ? String(el.textContent).replace(/\s+/g, ' ').replace(/^ | $/g, '') : '');
    if (text) {
      d += ' "' + text.substring(0, 40) + '"';
    }
    if (el.getBoundingClientRect) {
      var r = el.getBoundingClientRect();
      d += ' [' + Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ']';
    }
    return d;
  }

  function probe(reason) {
    if (!enabled || !w.document || typeof w.document.elementFromPoint !== 'function') {
      return;
    }
    var vw = w.innerWidth;
    var vh = w.innerHeight;
    var root = w.document.getElementById('root');
    var lines = ['[probe] ' + reason + ' ' + w.location.hash + ' viewport ' + vw + 'x' + vh + ' root ' + (root ? describe(root).replace(/^div#root /, '') : 'missing')];
    var fx = [0.04, 0.5, 0.96];
    var fy = [0.04, 0.5, 0.96];
    for (var iy = 0; iy < fy.length; iy++) {
      for (var ix = 0; ix < fx.length; ix++) {
        var x = Math.round(vw * fx[ix]);
        var y = Math.round(vh * fy[iy]);
        var el = null;
        try {
          el = w.document.elementFromPoint(x, y);
        } catch (e) {
          /* ignore */
        }
        lines.push('  @' + x + ',' + y + ' ' + describe(el));
      }
    }
    send('trace', lines.join('\n'));
  }

  function later(fn, ms) {
    if (typeof w.setTimeout === 'function') {
      w.setTimeout(fn, ms);
    }
  }

  var pendingTap = null;

  if (w.document && typeof w.document.addEventListener === 'function') {
    w.document.addEventListener(
      'click',
      function (e) {
        pendingTap = null;
        if (enabled) {
          send('trace', '[click] ' + describe(e && e.target));
        }
      },
      true
    );
    w.document.addEventListener(
      'touchend',
      function (e) {
        if (!enabled) {
          return;
        }
        var tap = { target: e && e.target };
        pendingTap = tap;
        later(function () {
          if (pendingTap === tap) {
            pendingTap = null;
            send('trace', '[tap] no click followed: ' + describe(tap.target));
          }
        }, TAP_MS);
      },
      true
    );
  }

  w.addEventListener('hashchange', function (e) {
    if (!enabled) {
      return;
    }
    var from = e && e.oldURL ? String(e.oldURL).replace(/^[^#]*/, '') : '?';
    send('trace', '[nav] ' + (from || '#') + ' -> ' + (w.location.hash || '#'));
    later(function () {
      probe('after nav');
    }, PROBE_DELAY_MS);
  });

  w.addEventListener('pagehide', function () {
    if (enabled) {
      send('trace', '[unload] ' + w.location.href, true);
    }
  });

  if (typeof w.setInterval === 'function') {
    var lastTick = now();
    w.setInterval(function () {
      var t = now();
      var gap = t - lastTick;
      lastTick = t;
      if (enabled && gap > STALL_MS) {
        send('warn', '[stall] main thread blocked ~' + (gap - STALL_TICK_MS) + ' ms (' + w.location.hash + ')');
      }
    }, STALL_TICK_MS);
  }

  if (enabled) {
    send('trace', '[boot] ' + (w.navigator ? w.navigator.userAgent : '?') + ' | ' + (w.location ? w.location.href : '?') + ' | ' + w.innerWidth + 'x' + w.innerHeight + ' dpr ' + (w.devicePixelRatio || 1));
    later(function () {
      probe('after boot');
    }, PROBE_DELAY_MS);
  }

  w.__archieRemoteConsole = {
    key: STORAGE_KEY,
    isEnabled: function () {
      return enabled;
    },
    setEnabled: function (on, persist) {
      enabled = !!on;
      if (persist !== false) {
        try {
          w.localStorage.setItem(STORAGE_KEY, enabled ? '1' : '0');
        } catch (e) {
          /* ignore */
        }
      }
    },
    send: function (level, msg) {
      return send(String(level), String(msg));
    },
    probe: function (reason) {
      probe(String(reason || 'manual'));
    },
    stats: function () {
      return { sent: sent, dropped: totalDropped };
    }
  };
})(__REMOTE_CONSOLE_CONFIG__);
