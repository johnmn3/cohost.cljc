# cohost — roadmap

The spike (see `REPORT.md`) boots the stock `clojure/core.clj` on jolt,
basilisp and self-hosted ClojureScript through one pipeline: codemod →
shim → boot, over a host adapter contract. Everything below is the same
pipeline pointed at more JVM-Clojure source, plus the host facilities a
development environment needs. In the operator's words (2026-10-29): deps.edn
through the clinj method; most of the Clojure commons ecosystem with the Java
it uses shimmed; nREPL, a socket REPL and the normal Clojure dev host; and
shadow-cljs running on Node, basilisp, etc. rather than the JVM.

Phase 0 is done (2026-10-30); phases 1 to 4 are not built yet. Each phase
names what exists today that it builds on, what has to be written, and how it
is measured.

## 0. Finish the core — done

- **A loader.** `require` / `ns` / `use` / `import` / `alias` / `in-ns` boot
  a library from the source roots into its own namespace through the same
  codemod, shim and adapter (`cohost.boot/load-lib!`), with a per-library
  policy (`cohost.policy/lib-overrides`, empty today). Twelve `clojure.*`
  libraries load and are checked against JVM Clojure's answers on every host
  (`cohost.lib-battery`).
- **Protocols in libraries.** The host's `defprotocol` / `extend` /
  `extend-type` / `extend-protocol` are available before any library loads;
  JVM class names in extend positions (`Object`, `java.util.Set`) become each
  host's types; ClojureScript gets a run-time `extend`. Multimethods are
  stock (`defmulti` over a shim `clojure.lang.MultiFn`).
- **The corpus scoreboard.** `bb corpus:jolt` runs the stock core and jolt's
  own core over the corpus in separate processes and compares them.
- **Left for later:** booting `core_deftype.clj` itself (its `deftype` and
  `defprotocol` generate JVM interfaces and bytecode; the host's are used),
  chunked seqs, `core_print.clj`.

## 1. `deps.edn` — the clinj method

clinj runs the real, unmodified `clojure.tools.deps` on non-JVM hosts by
shimming the JDK surface it calls (`java.io.File`, `java.nio.file`,
`java.util.concurrent`, `java.net`) and substituting the Maven leaf with a
pure-Clojure resolver registered through tools.deps' own extension
multimethods. cohost inherits that stance with one difference: the
shims here are a runtime codemod rather than a build-time `.clj → .cljc` port,
so tools.deps' source loads as it ships.

- Vendor `tools.deps` + `tools.gitlibs` (EPL, unchanged, provenance banner).
- Grow the shim with the JDK packages they touch — clinj's `src/clinj/java/*`
  is the inventory: `File`, `Files`, `Path`, readers/writers, `Properties`,
  `ConcurrentHashMap`, `URL`/`URI`, `ProcessBuilder` for `git`.
- Host adapter capabilities for the effects those need: file IO, spawn, http.
- Port clinj's `clinj.mvn` (the POM/version resolver) or run it as is.
- Measure with Grenadine's differential corpus as clinj does: `calc-basis`
  on `:local`/`:git`/`:mvn` coordinates, diffed against JVM tools.deps.

Result: `(cohost.deps/calc-basis deps-map)` on jolt/basilisp/Node — a
classpath for the next phase.

## 2. The Clojure commons ecosystem

With a classpath and the loader, libraries load the way core.clj does. The
policy per library is mostly empty; the shim grows by the JDK surface the
library calls. The tiers are clinj's:

- T1, already portable (`.cljc`, pure Clojure): free.
- T2, JDK surface only (`clojure.string`, `clojure.set`, `clojure.walk`,
  `clojure.zip`, `clojure.data`, `clojure.edn`, `clojure.pprint`'s
  `cl-format`, `tools.cli`, `tools.reader`, `data.json`, `core.match`,
  `core.async`'s pure parts, `malli`, `instaparse`, `specter`): the shim's
  job — `java.lang.String` methods, `StringBuilder`, `java.util.regex`,
  `java.io.Writer`/`PushbackReader`, `java.util.Date`/`java.time` basics.
- T3, wraps a Java library (SnakeYAML, jsoup, Netty): a per-library
  host-native backend behind the same seam, opt-in.
- T4, JVM semantics (Swing, real threads, AOT): out of scope.

Measure with the libraries' own test suites run on the host; report pass
counts per library per host.

## 3. nREPL, socket REPL, the dev host

- Where it starts (2026-10-30): `cohost.boot/eval-str` is already a session
  — it reads each form of its input after the one before it ran, in the
  namespace that one left current, and keeps the namespace for the next
  call, as a REPL's evaluation loop does. What a REPL still needs
  from the loader: `load-string`, `load-file`, `load-reader` and a `require`
  that is not at the top level go to the host's loader today (the code they
  load sees the host's core); cohost.core needs its own, over `load-lib!`,
  `read-forms` and `eval-in` in the current namespace, before
  `clojure.main` or nREPL's `load-file` op can run through it.
- `clojure.main`'s REPL loop is pure Clojure over `*in*`/`*out*`,
  `read`, `eval`, `print` — bootable once the adapter exposes the host's
  stdin/stdout streams and a `Reader` shim exists (phase 1 brings
  `LineNumberingPushbackReader`).
- Socket REPL: `clojure.core.server` is small; it needs `java.net.ServerSocket`
  and threads — an adapter capability (`:accept-connections!`) per host
  (jolt has sockets via FFI, Node has `net`, basilisp has `socket`).
- nREPL: `nrepl/nrepl` is JVM-Clojure (bencode, sessions, middleware); its
  transport and session machinery lean on `java.net` and threads, the rest is
  Clojure. Load it through the loader with the phase-1 shims; the host's own
  nREPL (jolt ships one) is the fallback and the comparison.
- The dev host: `clojure.repl` (`doc`, `source`, `apropos`), `clojure.java.io`
  over the adapter's file IO, `clojure.stacktrace` over the adapter's
  exception introspection, `*1`/`*2`/`*e`.

Measure: CIDER / Calva connect and evaluate against a cohost nREPL on
each host; `clojure.main -r` over the socket REPL.

## 4. shadow-cljs off the JVM

shadow-cljs is JVM-Clojure (the build tool, the compiler driver, the dev
server, the REPL) around the ClojureScript compiler, which is itself
self-hostable. The target: run shadow-cljs' Clojure under cohost on
Node or basilisp, with the compiler the self-hosted `cljs.js` (the same one
the ClojureScript adapter already runs core.clj through).

- Prerequisites: phases 1–3 (deps resolution, a classpath, the JDK IO surface,
  threads/async as adapter capabilities, a socket for the dev server).
- The Closure compiler is Java; shadow's optimizations rely on it. First
  milestone is `:optimizations :none` / development builds (no Closure);
  release builds need either Closure's JS port or esbuild-class tooling
  behind the same seam.
- The file watcher, the websocket dev server and the REPL are host effects:
  adapter capabilities with Node and Python backends.

Measure: `shadow-cljs watch app` builds and hot-reloads a browser app from
Node with no JVM present.

## The constant across phases

One codemod, one shim, one adapter contract. Every phase adds shim surface and
adapter capabilities; none adds a parallel implementation of Clojure code
that already exists. The measurement is always the real artifact's own tests.
