# cohost.cljc

**Boot the stock `clojure/core.clj` — and Clojure's own standard libraries —
inside a clojure-like.**

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
and browser)**, **ClojureWasm** and the **JVM** (as the reference host).

It is the method of [clinj](https://github.com/johnmn3/clinj) ("WINE, not an
emulator": shim the JDK surface the code calls, run the real source
unchanged) applied to the language's own core and libraries; the roadmap
(`doc/ROADMAP.md`) points the same loader at `tools.deps`, the wider Clojure
ecosystem, nREPL and shadow-cljs.

## Status (measured 2026-10-30)

| host | core.clj (791 top-level forms): stock / from host / natives / skipped / errors | core battery (139) | libraries (12, 61 cases) |
|---|---|---:|---:|
| jolt v0.8.23 | 466 / 199 / 63 / 63 / **0** | **139/139** | **61/61** |
| JVM Clojure 1.12.0 (reference adapter) | 465 / 202 / 66 / 58 / **0** | **139/139** | **61/61** |
| ClojureScript 1.12.42 self-hosted, Node 24 and Chromium | 518 / 87 / 46 / 140 / **0** | **137/139** | **61/61** |
| basilisp 0.5.1 | 529 / 130 / 36 / 96 / **0** | **138/139** | **61/61** |
| ClojureWasm v1.15.4 (2026-10-31) | 555 / 145 / 0 / 91 / **0** | **137/139** | **61/61** |

The core battery's misses are host facts:
- `(= 1 1.0)` is true in JavaScript and Python.
- On ClojureScript, a macro's `&env` is the analyzer's environment.
- On ClojureWasm, a regex Matcher has no group positions (`Matcher.start(g)`), and
  `alter-var-root` of a var the thread binds applies the fn to the binding, not the root.

"Natives" are the host's own fns standing in for stock ones (see below).

The twelve libraries are clojure.walk, set, string, zip, data, edn, template, test, math,
stacktrace, repl and clojure.spec.alpha, and every host loads all twelve. On the
jolt-conformance corpus (4,942 rows) the stock core passes 4,297 rows to jolt's own 4,468.

Performance against JVM Clojure and against each host's own core, before and after the
perf passes, is in `doc/PERF.md`.

`doc/REPORT.md` has the measurements, what each host lacked and how the shim
or the adapter answers it, the conformance-corpus scoreboard, and what is
deliberately left out.

## Run it

```bash
bb boot:jolt      # jolt on PATH
bb boot:lpy       # basilisp on PATH  (uv tool install basilisp)
bb boot:cljs      # ClojureScript, self-hosted on Node (needs the clojure CLI + node)
bb boot:browser   # ClojureScript, self-hosted in Chromium (Playwright for Python)
bb boot:jvm       # JVM Clojure as the host: the reference adapter
bb corpus:jolt path/to/corpus.edn         # a conformance corpus (jolt's test/chez/corpus.edn): stock core vs jolt's own
bb corpus:jvm                             # then: which rows the stock core fails JVM Clojure answers as expected
bb eval:jolt '(require (quote [clojure.string :as str]))' '(str/split "a,b" #",")'
bb lint:reader-order                      # cohost's #? arms: :jolt and :cljw ahead of :clj, nothing after :default
```

The five hosts in progress (`doc/hosts/PLAN.md`) have the same tasks: `bb boot:cljw`,
`boot:phel`, `boot:lg`, `boot:cljrs`, `boot:jank` and their `bench:` twins. Each one's
install is `dev/install/<host>.sh`.

Each boots core.clj, prints the boot report (booted / from host / skipped /
errors, every error's root cause, the interop the shim lacks), runs
`cohost.battery` — 133 stock behaviors evaluated in a namespace that sees only
the booted core — and then `cohost.lib-battery`: twelve `clojure.*`
libraries required through the loader, each case checked against the value
JVM Clojure 1.12.0 gives. `cohost.run/-main` takes `--verbose`, `--no-libs`
and `--eval src ...` (evaluate instead of the batteries) on every host, e.g.
`node target/cljs-node/main.js --eval "(binding [*print-length* 2] (pr-str (range)))"`.

## The `cohost` command (spike)

`bin/cohost` puts the Clojure CLI's face on any host: `cohost :cljs
-e "(+ 1 2)"` boots stock Clojure on ClojureScript and evaluates;
a `deps.edn` in the directory is resolved with tools.deps and its
namespaces load on the host through the ordinary loader — `-M -m`,
`-X`, `-Spath`, aliases and `-Sdeps` included. `:jvm` delegates to the
real CLI. Design, verified matrix and known gaps: `doc/CLI.md`.

## Use it

```clojure
(require 'cohost.hosts.auto)                ; installs this host's adapter
(require '[cohost.boot :as boot])

(boot/boot!)                                ; -> the report map
(boot/make-user-ns!)                        ; cohost.user sees only stock
(boot/eval-str "(->> (range 10) (map inc) (filter even?) (reduce +))")   ; 30
(boot/eval-str "(require '[clojure.set :as set])")                      ; the stock clojure.set
(boot/eval-str "(set/union #{1} #{2})")                                 ; #{1 2}
(boot/eval-str "(ns my.app (:require [clojure.string :as str])) (str/upper-case \"hi\")")
```

`cohost.core/map`, `cohost.core/let` and the rest are the stock definitions;
`(meta #'cohost.core/map)` carries core.clj's docstring and arglists.
`eval-str` reads each form of its source after the one before it ran, in the
namespace that one left current, as a REPL session does (a `::kw` after an
`ns` form is that namespace's), and keeps the namespace for the next call:
after the `ns` form above, `(boot/eval-str "*ns*")` is `my.app`. A
`require` (or an `ns` form's `:require`) of a library whose source is on the
source roots boots it stock — `clojure.X` into `cohost.X`, any other library
under its own name — and falls back to the host's own `require` otherwise.
`boot/add-source-root!` adds a directory to search; `boot/lib-report` says
what each library's load did.

## Layout

```
src/cohost/
  adapter.cljc       the host contract: capabilities, generic defaults, accessors
  rewrite.cljc       the codemod (JVM interop -> shim calls); host-independent
  shim.cljc          clojure.lang / java.* over the host's data structures
  policy.cljc        skip / take-from-host / replace, per form of core.clj;
                     per-library adjustments
  boot.cljc          the boot loop, macro wrapping, the loader, eval-str, reports
  battery.cljc       133 stock behaviors checked after a boot
  lib_battery.cljc   12 clojure.* libraries, checked against JVM Clojure's answers
  corpus.cljc        the conformance-corpus scoreboard
  corpus_report.cljc its report (no dependencies: babashka merges the two sides)
  run.cljc           -main: install adapter, boot, report, batteries
  shim_names.clj     compile-time helper for the ClojureScript build
  hosts/
    auto.cljc        requires the adapter for the host reading the file
    java.clj         capabilities the JVM and jolt share (java.lang, regex, edn)
    jolt.clj         jolt
    basilisp.lpy     basilisp
    cljs.cljs        ClojureScript, self-hosted (cljs.js)
    jvm.clj          JVM Clojure (reference)
    cljw.clj         ClojureWasm (Zig)
resources/cohost/clojure-1.12.0/   core.clj & co., the clojure.* libraries and
                                   clojure.spec.alpha, unchanged (EPL-1.0)
dev/browser_boot.py  serves the browser build and runs it under Playwright
dev/corpus_jvm.clj   the corpus gap evaluated on JVM Clojure (bb corpus:jvm)
doc/REPORT.md      the measurements
doc/ROADMAP.md     where this goes
LICENSE            0BSD for cohost's own code; the vendored sources keep EPL-1.0
NOTICE.md          what is whose
```

## Adding a host

Write one namespace that builds the capability map and calls
`cohost.adapter/install!`. `cohost.adapter/contract` lists every capability
with its arglist and doc; `defaults` the generic implementations; `required`
the ones with no default. `install!` refuses a map that lacks a required
capability or names one the contract does not (a misspelled key would
otherwise leave the generic default silently in place). Among the
capabilities a new host usually wants beyond the required ones:
`:user-type?` and `:invoke-member` (so `(.f x)` reaches a deftype field or a
reify method), `:interop-passthrough?` (true when the host has a java.*
surface of its own) or else `:native-method` (a method the shim lacks
reaches the object's own at run time), `:type-method-style` (`:python` for
basilisp-like hosts, `:protocols` where a JVM interface method is one of the
host's protocol methods, as on ClojureScript), `:new-exception` (the shim's
exceptions as the host's own, so they print as the JVM's), the `:iterator`
family (the host's own java.util.Iterator) and `:java-statics` (the host's
exact `Character` predicates). A host whose `format` is not the JVM's leaves
`:format` out: the shim's java.util.Formatter serves it.
`hosts/jolt.clj` (with `hosts/java.clj`) is the
shortest complete example; `hosts/cljs.cljs` the most involved (no macro
vars, no run-time namespace reflection, no run-time `extend`: the adapter
supplies all three from the analyzer state).

cohost's own source reads on every host. A new host's reader feature goes in
`dev/lint_reader_order.clj`'s list, and `bb lint:reader-order` checks every
`#?` in `src`. The host's catch-all, the symbol whose `catch` catches
everything, is written once, in `cohost.adapter/try-all`; cohost's own code
catches only through it. (The adapter's `:catch-all` is the same fact for the
code cohost evaluates.)

### Natives: the host's own fns, one at a time

The stock definitions are the reference. A host can still opt into its own
implementation of a stock fn, one name at a time, through `:natives`, a map
from qualified name to implementation:

```clojure
:natives {'clojure.core/frequencies frequencies   ; the host's own fn
          'clojure.core/group-by :host            ; the host core's var of that name
          'clojure.string/join clojure.string/join}
```

- **What happens:** `cohost.core`, or the stock library's namespace, gets the native
  instead of evaluating core.clj's definition. A name without an entry boots the stock
  one, so a host starts with `{}` and adds entries as they prove out.
- **What it costs:** a native has the host's semantics, not core.clj's. So every entry is
  gated by the battery, the jolt corpus where it applies, and the bench, which checks
  each stock answer against JVM Clojure's.
- **What you see:** the boot report counts natives, and their vars carry
  `:cohost/native`.
- **Chunked seqs:** `:chunks` is the same idea for these. A host that gives its chunk
  fns has the stock chunked branches (map, filter, concat, doseq, for) build and walk
  its own 32-element chunks.

`doc/perf/HANDOFF.md` says which natives each host has today and where the
remaining time goes.

## License and provenance

cohost's own work is given away freely; the code it loads keeps its original
copyright and license, untouched, as in
[clinj](https://github.com/johnmn3/clinj):

- **cohost's own code** (the codemod, the shim, the boot loop and loader, the
  adapters, the batteries, the docs) → **0BSD, no copyright claimed**
  (`LICENSE`).
- **The vendored Clojure sources** (`resources/cohost/clojure-1.12.0/`) →
  **EPL-1.0, unchanged** (`LICENSE-EPL-v10.html`): Clojure 1.12.0's
  `core.clj` and its companions, and `walk`, `set`, `string`, `zip`, `data`,
  `edn`, `template`, `test`, `math`, `stacktrace`, `repl`, from the release
  jar (commit `d4bb93f0d1ab2004f89c6ead1b32449fd7ed1a6d`), © Rich Hickey
  (`repl.clj` © Chris Houser); `clojure/spec/alpha.clj` and
  `clojure/spec/gen/alpha.clj` from clojure.spec.alpha 0.5.238 (the version
  Clojure 1.12.0 depends on), © Rich Hickey. cohost's 0BSD never touches them.

The vendored files are byte for byte those of the release jars. Every
transformation is mechanical and happens at load time; nothing in the vendored
tree is edited. Full provenance in [`NOTICE.md`](NOTICE.md).
