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
// family (the preamble installs its scheduler hooks only when the
// engine lacks them), and a navigator stub. window/self are the
// preamble's to set. The shim and the boot are the same ones the
// GraalJS seat runs; only this host differs.
//
// Two seats' worth of programs run here, and the host serves both:
// a form-at-a-time guest (the parity proofs), whose evals are the
// only thing that ever happens; and a resident program (a member:
// timers, promise continuations, a claim pump on the event loop),
// whose life runs BETWEEN evals. So the frame reader is async while
// the seat is idle -- stdin 'data' feeds the same buffer the
// synchronous file-service reader drains mid-eval -- and the stream
// is paused for the length of an eval, when no 'data' event could
// fire anyway. A file answer can only be written after its request,
// so no answer byte can sit in the stream's own buffer when a
// synchronous read starts.
//
// The realm's console writes to stderr, never stdout: stdout is the
// protocol stream (a realm println on it would desync the framing),
// and the driver already files the realm's stderr -- a resident
// program's own log lines ride it.
//
// Node interop: when the seat supplies a require root (argv[2], the
// app directory), the realm also gets a require anchored at that root
// (module.createRequire over its package.json, so the realm resolves
// the app's node_modules exactly as a node process started there
// would), process, and Buffer. Without a root the realm stays bare,
// as before.
const fs = require('fs');
const vm = require('vm');
const util = require('util');

// --- frames ------------------------------------------------------------

function writeFrame(s) {
  const b = Buffer.from(s, 'utf8');
  const h = Buffer.from(b.length + '\n', 'utf8');
  let off = 0;
  while (off < h.length) off += fs.writeSync(1, h, off, h.length - off);
  off = 0;
  while (off < b.length) off += fs.writeSync(1, b, off, b.length - off);
}

// One input buffer, two readers: the async pump (idle) and the
// synchronous file service (mid-eval) both take frames from it.
let inbuf = Buffer.alloc(0);

function takeFrame() {
  const nl = inbuf.indexOf(10);
  if (nl < 0) return null;
  const len = parseInt(inbuf.toString('utf8', 0, nl), 10);
  if (inbuf.length < nl + 1 + len) return null;
  const s = inbuf.toString('utf8', nl + 1, nl + 1 + len);
  inbuf = inbuf.subarray(nl + 1 + len);
  return s;
}

const syncChunk = Buffer.alloc(65536);
const sleepCell = new Int32Array(new SharedArrayBuffer(4));
function readSyncAvailable() {
  for (;;) {
    try {
      return fs.readSync(0, syncChunk, 0, syncChunk.length, null);
    } catch (e) {
      // stdin is non-blocking once the async pump has listened on
      // it: the driver's answer is on its way -- wait a beat, retry
      if (e && e.code === 'EAGAIN') {
        Atomics.wait(sleepCell, 0, 0, 5);
        continue;
      }
      throw e;
    }
  }
}
function readSyncFrame() {
  for (;;) {
    const s = takeFrame();
    if (s !== null) return s;
    const n = readSyncAvailable();
    if (n === 0) return null;
    inbuf = Buffer.concat([inbuf, syncChunk.subarray(0, n)]);
  }
}

function requestFile(path) {
  writeFrame(JSON.stringify({op: 'file', path: path}));
  const frame = readSyncFrame();
  if (frame === null) return null;
  const msg = JSON.parse(frame);
  return (msg.text === null || msg.text === undefined) ? null : msg.text;
}

// --- the realm -----------------------------------------------------------

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
const stderrConsole = {};
for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
  stderrConsole[level] = function (...args) {
    fs.writeSync(2, util.format(...args) + '\n');
  };
}
realm.console = stderrConsole;
realm.navigator = {userAgent: 'Node (cohost cljs realm)'};
// The platform globals a fresh vm context does NOT carry: they are
// properties of node's main context, not V8 intrinsics of a new one
// (measured on node 24: a bare context has SharedArrayBuffer, Atomics
// and WebAssembly, but no TextEncoder, URL, fetch, ...). The shim's
// contract is that a realm provides the standard globals of their
// names, so the host lends its own -- the same move as the timers and
// console above. Only names the realm lacks and the host has are
// lent; a global node itself lacks stays absent, and the shim's
// typeof guards keep reading it as undefined.
for (const name of ['TextEncoder', 'TextDecoder', 'URL', 'Blob',
                    'MessageChannel', 'crypto', 'atob', 'btoa',
                    'setImmediate', 'clearImmediate', 'fetch',
                    'AbortController', 'queueMicrotask',
                    'structuredClone', 'performance']) {
  if (typeof realm[name] === 'undefined' && typeof globalThis[name] !== 'undefined') {
    realm[name] = globalThis[name];
  }
}

const requireRoot = process.argv[2];
if (requireRoot) {
  const path = require('path');
  realm.require = require('module').createRequire(path.join(requireRoot, 'package.json'));
  realm.process = process;
  realm.Buffer = Buffer;
}

function jsonSafe(v) {
  if (v === null || v === undefined) return null;
  const t = typeof v;
  if (t === 'string' || t === 'number' || t === 'boolean') return v;
  return '#<' + t + '>';
}

// --- the eval pump ---------------------------------------------------------

let busy = false;
let sawEof = false;

function pump() {
  if (busy) return;
  const frame = takeFrame();
  if (frame === null) {
    if (sawEof) process.exit(0);
    return;
  }
  const msg = JSON.parse(frame);
  if (typeof msg.eval === 'string') {
    busy = true;
    process.stdin.pause();
    try {
      const v = vm.runInContext(msg.eval, realm);
      writeFrame(JSON.stringify({ok: true, value: jsonSafe(v)}));
    } catch (e) {
      writeFrame(JSON.stringify({ok: false, error: String((e && e.stack) || e)}));
    }
    busy = false;
    process.stdin.resume();
    pump();
  }
}

process.stdin.on('data', (data) => { inbuf = Buffer.concat([inbuf, data]); pump(); });
process.stdin.on('end', () => { sawEof = true; pump(); });
process.stdin.on('error', () => process.exit(1));
