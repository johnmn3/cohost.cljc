# Choosing a runtime

*2026-11-02. Core and library batteries: the coordinator's check of cohost coord/b13 @ f9ee475,
whose code this release carries (max/integrate @ 6eec4e2, with the battery's rows reordered) plus the
jank sort fix (max/release-sort-fix @ bddd2d1: on apt jank, battery and libraries unchanged).
Speed: doc/PERF.md, "Programs against JVM Clojure" (cf1046e; jank's first
output from its snapshot: 5d02b93).*

`:cohost/target` in `deps.edn` names the runtime; `cohost :<target> ...` overrides it for one
command. The same source runs on each; what differs is the host underneath.

| target | runtime | core battery | library battery | install |
|---|---|---:|---:|---|
| `:jvm` | JVM Clojure (the real CLI) | 167/167 | 62/62 | the Clojure CLI |
| `:jolt` | jolt 0.8.23 (Chez Scheme) | 167/167 | 62/62 | `dev/install/jolt.sh` |
| `:jank` | jank 0.1 (C++/LLVM) | 165/167 | 62/62 | `dev/install/jank.sh` |
| `:cljs` | ClojureScript 1.12.42, self-hosted, on Node | 165/167 | 62/62 | Node |
| `:lpy` | basilisp 0.5.1 (Python) | 166/167 | 62/62 | `dev/install/basilisp.sh` |
| `:cljw` | ClojureWasm 1.15.4 | 165/167 | 62/62 | `dev/install/cljw.sh` |
| `:phel` | Phel 0.54.0 (PHP 8.5) | 166/167 | 62/62 | `dev/install/phel.sh` |
| `:lg` | let-go 1.13.0 (Go) | 165/167 | 62/62 | `dev/install/lg.sh` |
| `:cljrs` | clojurust 0.2.32 (Rust) | not wired into `cohost` | | `dev/install/cljrs.sh` |

The core battery checks behaviors of `clojure.core` against JVM Clojure 1.12's answers; the
library battery runs twelve `clojure.*` libraries the same way: clojure.walk, set, string, zip,
data, edn, template, test, math, stacktrace, repl and clojure.spec.alpha. On the
jolt-conformance corpus (4,942 rows) the stock core on jolt passes 4,306 (495 fail, 141 error);
jolt's own core passes 4,468 (2026-10-30, the same jolt).

`:jank` runs the jank package from jank's apt repository on Ubuntu, or jank's Homebrew tap on
macOS arm64 (`dev/install/jank.sh`; Homebrew asks you to trust the tap once:
`brew trust --formula jank-lang/jank/jank`), as it ships.
cohost also carries a patch series against jank under `upstream/jank/` for its own measurements
(`doc/PERF.md`, "Two jank binaries"); nothing selects it unless `JANK_BIN` names a build of it.

## What differs, in plain words

- **Numbers.** ClojureScript and basilisp have one number type: `(= 1 1.0)` is true there,
  and on ClojureScript `(/ 1 0)` is `##Inf` rather than an error.
  jank 0.1 computes `+` and `*` of two values that both fit in 32 bits in 32 bits, so a sum
  past 2^31 wraps there (the programs `pipeline` and `dispatch` give the JVM's answer modulo
  2^32 on jank).
- **Sorting on jank 0.1 (apt).** `sort` and `sort-by` are cohost's own there (jank's own sort result
  walks quadratically on that build; a boot probe picks), and the metadata of a sorted result is nil.
- **Characters.** jank's strings are bytes: under the C locale a character above ASCII reads
  back as `?`.
- **Regex groups.** ClojureWasm's and let-go's regex matchers give no group positions
  (`Matcher.start(g)`).
- **Equality of deftype instances** in let-go's sets is by identity.
- **`alter-var-root`** of a var the thread binds applies the fn to the binding, not the root, on
  ClojureWasm.
- **Redefining a fn during its own recursion**: on jank a closure-free fn keeps the original
  (jank's own `defn` answers the same; doc/REPORT.md).
- **Macros' `&env`** is ClojureScript's analyzer environment on ClojureScript.
- **Errors** say `Divide by zero` where the JVM's primitive division says `/ by zero`; jolt's
  error for calling nil is a ClassCastException.

## Startup and speed

How long one command of the README takes on each runtime (`cohost -M -m ...` in a new project),
measured on macOS arm64 on 2026-11-02 (doc/OOTB-MACOS.md, #19), caches warm:

| runtime | per command |
|---|---|
| `:jank` | 0.5 s with its core snapshot and the project's build (about 10 s without them; cohost builds both on first use) |
| `:jvm` (the reference: the real Clojure CLI) | about 1 s |
| `:jolt` | 1 to 2 s |
| `:cljs` | about 2 s (5 s for the first build) |
| `:lg` | 4 to 6 s (10 s the first time) |
| `:cljw` | about 17 s |
| `:lpy` | 19 to 25 s from source; its cached boot (built on first use) boots in 3.1 s (Linux, boot alone) |
| `:phel` | about 22 s |

Those per-command times are the time to run a program; the table below is the time to the
first line of output and the speed of the programs once running.


Measured on one 4-CPU box, one runtime at a time (doc/PERF.md): wall time to the first line
of output of a one-line program, and the geometric mean, over the suite's programs that ran,
of each program's time per call against JVM Clojure's.

| | first output | programs vs JVM (geomean) |
|---|---:|---:|
| JVM Clojure itself | 709 ms | 1 |
| `:jvm` through cohost's boot | 4617 ms | x1.39 |
| `:cljs` through cohost | 7336 ms | x10.8 |
| `:jank` through cohost, booted from its snapshot | 4293 ms | x18.3 |

cohost's own startup is its boot of `clojure.core` from source; on jank a snapshot of the
booted core loads instead ([How it works](08-how-it-works.md)). The other runtimes' columns
are being re-measured on the same method.
