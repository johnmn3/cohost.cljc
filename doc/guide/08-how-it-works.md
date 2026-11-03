# How cohost works

*2026-11-02.*

Every non-JVM Clojure (jolt, basilisp, ClojureScript, ...) re-implements
`clojure.core` by hand against a conformance corpus: broad, never total, and
subtly different at every edge. cohost takes the other route. It reads
Clojure's own `core.clj` (vendored unchanged from the 1.12.0 tag, EPL) and
runs it on the host, form by form, then loads `clojure.string`,
`clojure.walk`, `clojure.spec.alpha` and the rest the same way, each into its
own namespace:

1. a **codemod** (`cohost.rewrite`) turns the JVM interop the sources are
   written against — `(. clojure.lang.RT (cons x s))`,
   `(clojure.lang.Numbers/add x y)`, `(new clojure.lang.LazySeq f)`,
   `(.withMeta x m)`, `(instance? clojure.lang.IFn x)`,
   `(catch IllegalArgumentException e ...)`, a namespace's
   `(:import (java.util.regex Pattern Matcher))` — into calls on
2. a **shim** (`cohost.shim`) that maps the `clojure.lang` / `java.*`
   surface onto the host's own data structures: the host's vector *is* the
   `PersistentVector`, its seq *is* the `ISeq`, `RT/first` is the host's
   `first`. What the host lacks (iterators, a `java.util.regex.Matcher`,
   `StringBuilder`, `java.lang.Math`, class tokens, the JVM exception
   hierarchy, multimethods, refs) the shim builds from what it has;
3. a **boot and loader** (`cohost.boot`) that evals each form of core.clj
   into `cohost.core`, a namespace that refers nothing from the host's core,
   so every name core.clj defines is the stock one — `let`, `fn`, `defn`,
   destructuring, lazy seqs, transducers, `for`, `doseq`, `defmulti`,
   `binding`, `re-find` — straight from Rich's file; then boots each library
   a `require` names into its own namespace (`clojure.walk` into
   `cohost.walk`), honoring its `ns` form. Macros are wrapped so what they
   *emit* is rewritten too;
4. a **policy** (`cohost.policy`) that says which forms of core.clj are the
   JVM itself (proxies, gen-class, agents, the class loader, `java.io`) and
   are skipped, and which names are the host's facade over its runtime
   (printing, the reader, namespaces, `case`, `deftype`, protocols) and are
   taken from the host.

Underneath is a **host adapter contract** (`cohost.adapter`): one map of
named capabilities a clojure-like implements to plug in — read/eval in a
namespace, var and namespace surgery, four constructors, regex search
primitives, the host's `Math`, the type predicates behind the class tokens.
Adapters ship for **jolt**, **basilisp**, **ClojureScript (self-hosted, Node
and browser)**, **ClojureWasm**, **Phel**, **let-go**, **jank** (`hosts/jank_host.jank`) and
the **JVM** (as the reference host).

It is the method of [clinj](https://github.com/johnmn3/clinj) ("WINE, not an
emulator": shim the JDK surface the code calls, run the real source
unchanged) applied to the language's own core and libraries; the roadmap
(`doc/ROADMAP.md`) points the same loader at `tools.deps`, the wider Clojure
ecosystem, nREPL and shadow-cljs.

## Startup: booting, cached boots and the snapshot

Every run boots `clojure.core` from Clojure's source: read, rewrite, evaluate, form by form,
then each `clojure.*` library a program requires. That boot is cohost's startup cost (the
"first output" column in [Choosing a runtime](07-choosing-a-runtime.md)). Two things cut it
on jank, where compiling is the expensive part:

- **cohost's own namespaces ahead of time.** jank compiles cohost (the codemod, the shim, the
  adapter, the boot) to native objects once (`jank compile-module -O3`); the objects are
  rebuilt when cohost's sources or the jank binary change, and never loaded stale.
- **A snapshot of the booted core.** `bb boot:jank-snapshot` (in the cohost checkout) records
  what a boot evaluates and compiles it into modules; a later run loads the modules instead
  of booting (about 0.6 s), and boots as usual when the snapshot is stale. The `cohost`
  command and the benchmark use it when it is fresh.

## Performance keys

Seven capabilities of the host contract are performance switches, default off, turned on per
host where that host's bench says they pay (`doc/perf/HISTORY.md`, "Cross-host"):

- `:multimethod-cache?`: the shim's multimethod with a one-entry dispatch cache (JVM, jolt,
  ClojureWasm, Phel, ClojureScript on Node and in Chromium, jank).
- `:sort-host-compare?`: the host's compare when the comparator is the stock `compare`
  (let-go, Phel; jank always).
- Five that jank alone sets: `:native-hints` (which primitive hints the host compiles as native
  types), `:native-local-casts` (the host's conversion for a hinted `let`/`loop` local),
  `:defn-fn-name?` (a def's name carried onto its `fn*`, so a self-call is a direct call),
  `:static-aliases` (a shim static emitted as the host's own op: jank's `-`), `:core-aliases`
  (a stock core fn emitted as the host's own: jank's `not`).

`cohost.adapter/contract` carries each one's doc; a new host leaves them off until its own
bench says otherwise ([Adding a host](09-adding-a-host.md)).

## The C++ passthrough (jank)

The `:cpp?` capability (jank alone) lets user code use jank's own `cpp/` interop directly,
the way a JVM program uses Java interop. Four codemod rules, all under that one gate:

- A form headed by a `cpp/…` symbol keeps its head and has its arguments rewritten as
  program — except `cpp/raw`, whose arguments are C++ source text and pass verbatim. A
  bare `cpp/…` symbol likewise stands as written.
- A `catch` of a `cpp/…` class is emitted as a real sibling catch clause, in source
  order; the stock classes still fold into the one dispatch clause between them.
- A member call whose target is a `cpp/…` form, or a local hinted `^cpp/…`, is emitted
  as the host's own dot form — no shim dispatcher, no checked cast. The `^cpp/…` hint
  itself rides into the emitted form untouched.
- Two `ns` clauses are handed to the host, each as its own top-level form, the way
  jank's own core does it: `:include` becomes a top-level `(cpp/raw "#include <h>")`
  (a `./` name is quoted instead), whose text lands in the global prelude every later
  form sees, and `:refer-global` becomes a top-level `clojure.core/refer-global` call.
  On every other host the clauses are dropped silently.

Reader conditionals pick the `:jank` branch on jank (every other target still picks
`:clj`, then `:default`), so one file serves all hosts: the `cpp/` forms live under
`#?(:jank … :default …)`, and the battery carries rows for each rule in that shape.

## Where the code is

```
src/cohost/
  adapter.cljc       the host contract: capabilities, generic defaults, accessors
  rewrite.cljc       the codemod (JVM interop -> shim calls); host-independent
  shim.cljc          clojure.lang / java.* over the host's data structures
  policy.cljc        skip / take-from-host / replace, per form of core.clj;
                     per-library adjustments
  boot.cljc          the boot loop, macro wrapping, the loader, eval-str, reports
  battery.cljc       216 stock behaviors checked after a boot
  lib_battery.cljc   12 clojure.* libraries, checked against JVM Clojure's answers
  corpus.cljc        the conformance-corpus scoreboard
  corpus_report.cljc its report (no dependencies: babashka merges the two sides)
  run.cljc           -main: install adapter, boot, report, batteries
  cli.cljc           the cohost command's host-side half (bin/cohost writes its config)
  build.clj          cohost.build: a project's artifact per target (cohost -T:build)
  build_jank.clj     a project's app as one native jank executable (D-7)
  shim_names.clj     compile-time helper for the ClojureScript build
  hosts/
    auto.cljc        requires the adapter for the host reading the file
    java.clj         capabilities the JVM and jolt share (java.lang, regex, edn)
    jolt.clj         jolt
    basilisp.lpy     basilisp
    cljs.cljs        ClojureScript, self-hosted (cljs.js)
    jvm.clj          JVM Clojure (reference)
    cljw.clj         ClojureWasm (Zig)
    phel.phel        Phel (PHP 8.5)
    phel_host.php    the JVM classes Phel's adapter builds in PHP (Pattern, Math)
    lg.lg            let-go (Go)
    jank_host.jank   jank (C++/LLVM); the cohost_tag / cpp/raw block
resources/cohost/clojure-1.12.0/   core.clj & co., the clojure.* libraries and
                                   clojure.spec.alpha, unchanged (EPL-1.0)
dev/browser_boot.py  serves the browser build and runs it under Playwright
dev/corpus_jvm.clj   the corpus gap evaluated on JVM Clojure (bb corpus:jvm)
dev/phel_main.phel   bb boot:phel's entry (cohost.run's -main under phel run)
dev/lg_main.lg       bb boot:lg's entry (let-go has no -m)
dev/install/jank-src.sh  jank from source with upstream/jank/patches (measurement only)
doc/REPORT.md      the measurements
doc/ROADMAP.md     where this goes
LICENSE            0BSD for cohost's own code; the vendored sources keep EPL-1.0
NOTICE.md          what is whose
```
