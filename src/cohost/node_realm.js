'use strict';
// The node seat's cljs realm host: a vm context whose global is the
// realm, driven by cohost.cljs-pool's node sister over stdio with
// length-prefixed JSON frames ("<byte-count>\n<utf8 bytes>"):
//
//   driver -> host  {"eval": "<js>"}          eval in the realm
//   host -> driver  {"ok": true, "value": v}  the completion value
//                   {"ok": false, "error": s}
//                   {"op": "file", "path": p} a goog load needs the
//                     file's contents; the driver answers with one
//                     frame {"op": "file", "text": s|null}, which
//                     this host reads synchronously -- the eval that
//                     asked is still on the stack -- before going on.
//
// The realm's prepared globals are the ones cohost.boot-cljs's
// preamble expects to find or to install: the Closure loader hooks
// (__cohostLoadFile / __cohostReadFile, here), the host's own timer
// family and console (the preamble installs its scheduler hooks only
// when the engine lacks them), and a navigator stub. window/self are
// the preamble's to set. The shim and the boot are the same ones the
// GraalJS seat runs; only this host differs.
const fs = require('fs');
const vm = require('vm');

function readFrame() {
  let header = '';
  const one = Buffer.alloc(1);
  for (;;) {
    const n = fs.readSync(0, one, 0, 1, null);
    if (n === 0) return null;
    const c = one.toString('utf8');
    if (c === '\n') break;
    header += c;
  }
  const len = parseInt(header, 10);
  const buf = Buffer.alloc(len);
  let off = 0;
  while (off < len) {
    const n = fs.readSync(0, buf, off, len - off, null);
    if (n === 0) break;
    off += n;
  }
  return buf.toString('utf8');
}

function writeFrame(s) {
  const b = Buffer.from(s, 'utf8');
  const h = Buffer.from(b.length + '\n', 'utf8');
  let off = 0;
  while (off < h.length) off += fs.writeSync(1, h, off, h.length - off);
  off = 0;
  while (off < b.length) off += fs.writeSync(1, b, off, b.length - off);
}

function requestFile(path) {
  writeFrame(JSON.stringify({op: 'file', path: path}));
  const frame = readFrame();
  if (frame === null) return null;
  const msg = JSON.parse(frame);
  return (msg.text === null || msg.text === undefined) ? null : msg.text;
}

const realm = vm.createContext({});
realm.__cohostLoadFile = function (path, contents) {
  let text = contents;
  if (text === undefined || text === null) text = requestFile(String(path));
  if (text === null || text === undefined) return false;
  vm.runInContext(String(text), realm, {filename: String(path)});
  return true;
};
realm.__cohostReadFile = function (path) {
  return requestFile(String(path));
};
realm.setTimeout = setTimeout;
realm.clearTimeout = clearTimeout;
realm.setInterval = setInterval;
realm.clearInterval = clearInterval;
realm.console = console;
realm.navigator = {userAgent: 'Node (cohost cljs realm)'};

function jsonSafe(v) {
  if (v === null || v === undefined) return null;
  const t = typeof v;
  if (t === 'string' || t === 'number' || t === 'boolean') return v;
  return '#<' + t + '>';
}

let frame;
while ((frame = readFrame()) !== null) {
  const msg = JSON.parse(frame);
  if (typeof msg.eval === 'string') {
    try {
      const v = vm.runInContext(msg.eval, realm);
      writeFrame(JSON.stringify({ok: true, value: jsonSafe(v)}));
    } catch (e) {
      writeFrame(JSON.stringify({ok: false, error: String((e && e.stack) || e)}));
    }
  }
}
