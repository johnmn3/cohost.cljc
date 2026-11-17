'use strict';
// The browser seat's cljs realm host: this page IS the realm, driven
// by cohost.cljs-pool's browser sister over HTTP (the node seat's
// stdio frames, moved to the one channel a page has). The server
// injects this script into the bootstream page it serves; the page
// needs no cohost knowledge of its own.
//
//   page -> driver  GET  /__cohost/poll    long-poll: the next eval
//                     answers {"id": n, "js": "..."} (204 = none yet)
//   page -> driver  POST /__cohost/result  {"id": n, "ok": true,
//                     "value": v} or {"id": n, "ok": false,
//                     "error": s}
//   page -> driver  GET  /__cohost/file?path=p   a goog load needs the
//                     file's contents; answered synchronously (a
//                     blocking XHR -- the eval that asked is on the
//                     stack), the node seat's file service over HTTP.
//
// The realm's prepared globals are the ones cohost.boot-cljs's
// preamble expects to find or to install: the Closure loader hooks
// (__cohostLoadFile / __cohostReadFile, here), the browser's own
// timer family, console and navigator (the preamble installs its
// scheduler hooks only when the engine lacks them). window/self are
// the page's own. The shim and the boot are the same ones the GraalJS
// and node seats run; only this host differs.
//
// deps.js is NOT this page's to fetch: the sister evals base.js and
// deps.js through the poll channel right after the preamble, as on
// the node seat, so CLOSURE_NO_DEPS is set before base.js arrives --
// base.js in a live document would otherwise try to load deps
// itself, through document machinery a cohost boot bypasses.
window.CLOSURE_NO_DEPS = true;

// Loader paths arrive as the page's loader builds them: with a live
// document, goog's basePath can carry the page's origin or the
// server's mount prefix. The driver's file service resolves plain
// classpath-relative paths (cohost.boot-cljs/goog-text), so undo
// that pollution here: anchor on the goog/ segment when present,
// else strip scheme+host, leading slashes and the interop prefix.
function cohostPath(p) {
  p = String(p);
  var i = p.indexOf('goog/');
  if (i >= 0) return p.slice(i);
  p = p.replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]+/i, '');
  p = p.replace(/^\/+/, '');
  if (p.indexOf('__cohost/') === 0) p = p.slice('__cohost/'.length);
  return p;
}

function fetchSync(path) {
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/__cohost/file?path=' + encodeURIComponent(cohostPath(path)), false);
  xhr.send(null);
  if (xhr.status === 200) return xhr.responseText;
  return null;
}

window.__cohostLoadFile = function (path, contents) {
  var text = contents;
  if (text === undefined || text === null) text = fetchSync(path);
  if (text === null || text === undefined) return false;
  (0, eval)(String(text));
  return true;
};
window.__cohostReadFile = function (path) {
  return fetchSync(path);
};

function jsonSafe(v) {
  if (v === null || v === undefined) return null;
  var t = typeof v;
  if (t === 'string' || t === 'number' || t === 'boolean') return v;
  return '#<' + t + '>';
}

function postResult(out) {
  return fetch('/__cohost/result', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(out),
    cache: 'no-store'
  });
}

function poll() {
  fetch('/__cohost/poll', {cache: 'no-store'}).then(function (r) {
    if (r.status === 204) { poll(); return null; }
    return r.json();
  }).then(function (msg) {
    if (!msg) return;
    var out;
    try {
      var v = (0, eval)(String(msg.js));
      out = {id: msg.id, ok: true, value: jsonSafe(v)};
    } catch (e) {
      out = {id: msg.id, ok: false, error: String((e && e.stack) || e)};
    }
    postResult(out).then(function () { poll(); }, function () { poll(); });
  }, function () {
    setTimeout(poll, 250);
  });
}
poll();
