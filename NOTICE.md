# Provenance & attribution

cohost boots Clojure's own `clojure/core.clj` on hosts other than the JVM. It
is a **host-portability layer**, not a fork of Clojure and not a
reimplementation of its core: the definitions it boots are Clojure's, read
from the release sources. This file documents exactly what is whose.

## cohost's own code — 0BSD, no copyright claimed

Everything outside `resources/cohost/clojure-1.12.0/` is original work,
released under **0BSD** (see `LICENSE`) with no copyright claim:

- `src/cohost/rewrite.cljc` — the codemod: JVM interop in a form becomes calls
  on the shim, as the form loads.
- `src/cohost/shim.cljc` — `clojure.lang` / `java.*` written over each host's
  own data structures, a java.util.Formatter among them.
- `src/cohost/boot.cljc`, `src/cohost/policy.cljc` — the boot loop, the loader,
  and the per-form policy (boot the stock definition, take the host's, or skip
  it).
- `src/cohost/adapter.cljc`, `src/cohost/hosts/**` — the host adapter contract
  and the adapters for jolt, basilisp, ClojureScript and JVM Clojure.
- `src/cohost/battery.cljc`, `src/cohost/lib_battery.cljc`,
  `src/cohost/corpus.cljc`, `src/cohost/corpus_report.cljc`,
  `src/cohost/run.cljc`, `src/cohost/shim_names.clj` — the batteries (their
  expected values are JVM Clojure 1.12.0's answers, recorded by running it),
  the corpus scoreboard and the runner.
- `dev/**`, `doc/**`, `README.md`, and the build files (`bb.edn`, `deps.edn`,
  `cljs-node.edn`, `cljs-browser.edn`, `index.html`, `package.json`).

## Vendored, unmodified — Clojure's sources, EPL-1.0

`resources/cohost/clojure-1.12.0/` holds the real upstream files, byte for
byte:

- From the **Clojure 1.12.0** release jar (`org.clojure/clojure 1.12.0`, tag
  `clojure-1.12.0`, commit `d4bb93f0d1ab2004f89c6ead1b32449fd7ed1a6d`):
  `clojure/core.clj`, `clojure/core/protocols.clj`, `clojure/core_deftype.clj`,
  `clojure/core_print.clj`, and the libraries `clojure/walk.clj`, `set.clj`,
  `string.clj`, `zip.clj`, `data.clj`, `edn.clj`, `template.clj`, `test.clj`,
  `math.clj`, `stacktrace.clj` and `repl.clj`.
- From the **clojure.spec.alpha 0.5.238** release jar (the version Clojure
  1.12.0 depends on): `clojure/spec/alpha.clj` and `clojure/spec/gen/alpha.clj`.

Their headers say **Copyright (c) Rich Hickey. All rights reserved.**
(`clojure/repl.clj`: **Copyright (c) Chris Houser, Dec 2008. All rights
reserved.**) and license them under the **Eclipse Public License 1.0**, whose
text is vendored beside them as `LICENSE-EPL-v10.html`.

**The upstream license passes through unchanged.** cohost imposes nothing on
this code; its 0BSD grant does not touch it. cohost edits no file: its codemod
rewrites each form in memory as the form loads (`src/cohost/rewrite.cljc`), so
the files on disk stay identical to the release jars. To check, compare each
one with the same path in `clojure-1.12.0.jar` or `spec.alpha-0.5.238.jar`.

## Not included: the conformance corpus

`bb corpus:jolt` reads jolt's `test/chez/corpus.edn`
(https://github.com/jolt-lang/jolt, EPL-2.0) from a path you give it. No part
of that corpus is in this repository.
