# Choosing a runtime

*2026-11-02. Core battery: merged commit a1aeb5f. Library battery: f02a978 (Phel: 8967d0e, after
its `ns-interns` fix). Speed: doc/PERF.md, "Programs against JVM Clojure" (cf1046e; jank's first
output from its snapshot: 5d02b93).*

`:cohost/target` in `deps.edn` names the runtime; `cohost :<target> ...` overrides it for one
command. The same source runs on each; what differs is the host underneath.

| target | runtime | core battery | library battery | install |
|---|---|---:|---:|---|
| `:jvm` | JVM Clojure (the real CLI) | 146/146 | 62/62 | the Clojure CLI |
| `:jolt` | jolt 0.8.23 (Chez Scheme) | 146/146 | 62/62 | `dev/install/jolt.sh` |
| `:jank` | jank 0.1 (C++/LLVM) | 144/146 | 62/62 | `dev/install/jank.sh` |
| `:cljs` | ClojureScript 1.12.42, self-hosted, on Node | 144/146 | 62/62 | Node |
| `:lpy` | basilisp 0.5.1 (Python) | 145/146 | 62/62 | `dev/install/basilisp.sh` |
| `:cljw` | ClojureWasm 1.15.4 | 144/146 | 62/62 | `dev/install/cljw.sh` |
| `:phel` | Phel 0.54.0 (PHP 8.5) | 145/146 | 62/62 | `dev/install/phel.sh` |
| `:lg` | let-go 1.13.0 (Go) | 144/146 | 62/62 | `dev/install/lg.sh` |
| `:cljrs` | clojurust 0.2.32 (Rust) | not wired into `cohost` | | `dev/install/cljrs.sh` |

The core battery checks behaviors of `clojure.core` against JVM Clojure 1.12's answers; the
library battery runs twelve `clojure.*` libraries the same way.

## What differs, in plain words

- **Numbers.** ClojureScript and basilisp have one number type: `(= 1 1.0)` is true there.
  jank 0.1 computes `+` and `*` of two values that both fit in 32 bits in 32 bits, so a sum
  past 2^31 wraps there (the programs `pipeline` and `dispatch` give the JVM's answer modulo
  2^32 on jank).
- **Characters.** jank's strings are bytes: under the C locale a character above ASCII reads
  back as `?`.
- **Regex groups.** ClojureWasm's and let-go's regex matchers give no group positions
  (`Matcher.start(g)`).
- **Equality of deftype instances** in let-go's sets is by identity.
- **Macros' `&env`** is ClojureScript's analyzer environment on ClojureScript.
- **Errors** say `Divide by zero` where the JVM's primitive division says `/ by zero`; jolt's
  error for calling nil is a ClassCastException.

## Startup and speed

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
