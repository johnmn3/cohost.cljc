# jank faster than the JVM: the research plan (lane R, order R-1)

Status: IN PROGRESS (2026-11-02 ET). Branch `r/research-jank`, base `b286ba1`. Nothing under
`src/` changes here; probes live under `dev/research/`.

The question: how can Clojure programs run faster on jank than on the JVM, both the stock core
cohost boots on jank and the programs people write on it. The yardstick is `bench/programs/`
(jank-FAST Step 8): fib, tak, nbody, spectral-norm, mandelbrot, binary-trees, fannkuch, nsieve,
k-nucleotide, pipeline, dispatch, json, first-output, each unhinted and hinted, measured by
`bb bench:programs <target>` against JVM Clojure 1.12.

## 0. Method

Three brainstorming sessions, three ideas each, each session from its own angle (compiler and
codegen; runtime and data structures; the cohost layer). Every idea names the measured cost it
attacks (cited), the expected effect with its basis (a measurement or a citation, never an
invented number), the cheapest probe, the risk to Clojure semantics, and the owner. Probes run
here where they can; their output is quoted. Then the nine are ranked and the top six become
the plan.

Box: 4 CPUs, 15 GB, Ubuntu noble; jank `0.1-noble` (`jank compiler jank-0.1-alpha`, binary
`x86_64-unknown-linux-gnu-cf627abb…`), babashka 1.13.229, Clojure CLI 1.12.6.1673 (Clojure
1.12.0). The jank compiler's `.cpp` sources are not shipped; citations of the compiler are to a
shallow clone of jank-lang/jank at `f3d82d65` (2026-11-01), which may be newer than the installed
binary; citations of the runtime headers are to the installed tree
`/usr/local/lib/jank/0.1/include/jank/`.

Probe harness: `dev/research/harness.jank` (jank) and `dev/research/harness.clj` (JVM): each case
warms until 2 s have passed, then 5 timed calls, median of 5; results fold into a volatile sink.
`bash dev/research/run-jank.sh dev/research/<probe>.jank` runs a probe as JIT
(`jank run -O3 --eager`, the H0 way) and as AOT (`compile-module -O3` then `run-main`, the
`bb bench:programs jank-aot` way); `clojure -M dev/research/run-jvm.clj dev/research/<probe>.clj`
runs the twin. `bash dev/research/queue.sh <names>` runs them one at a time (the box has 4 CPUs
and timings overlap otherwise).

## 1. Measured facts this plan starts from (cited, not re-derived)

| fact | value | where |
|---|---|---|
| jank's own core, AOT `-O3`, against the JVM over the 10 programs that ran | geomean 0.705 (fib 0.80, tak 0.24, binary-trees 1.75, k-nucleotide 0.44, pipeline 1.34, json 0.23, first-output 1.00) | doc/PERF.md "Programs against JVM Clojure" (first pass; runs too short for the JVM JIT, lane C re-measures) |
| jank's own core, JIT, against the JVM | geomean 2.43 (the JIT's first-use compilation is inside the five timed calls) | same |
| cohost's stock core on jank against the JVM | geomean 10.34 (fib 23.9, tak 7.4, spectral-norm 47.6, binary-trees 4.1, k-nucleotide 2.2, pipeline 2.3, dispatch 11.2, json 1.8, first-output 26.0) | same |
| six programs ABSENT on raw jank | nbody, spectral-norm (`Math/sqrt`, `Math/round`), mandelbrot, fannkuch, nsieve (`use of undeclared identifier 'sNN'`), dispatch (`defprotocol`) | same |
| a `^long`/`^double` parameter carried by a loop inside the fn | hinted/plain 0.105 (double), 0.125 (long) | doc/perf/jank.md "Type hints (H0)" |
| a hinted leaf fn called from a loop | hinted/plain 1.24 (`inc`) to 1.52 (`+` on `jtl.i64`): the result is boxed on the way out | same |
| return hints and `let`/`loop` local hints | unused by the compiler (`ret_only` has no convert; `locals_hinted` and `locals_plain` emit the same C++) | same, "What `+`, `<`, and `inc` compile to" |
| one cross-module fn call on jank | ~100 ns per entry (274 ms over fib 30's 2.7M entries) | atom.broker reports/muse.md, M-6 (2026-11-02 08:04 ET) |
| stock core against jank's own core, 30 bench cases | geomean x4.09; regex x320, format x49.6, into-vector x30.3, sort-by x27.5, transduce x21.7, transient-vector x18.7, sort x15.2, str-build x15.5, set-build x11.1, group-by x10.5 | doc/PERF.md "jank baseline" |
| where the stock core's time goes in those cases | 63-83% inside the compiled stock and shim fns; 12-34% first-use JIT compilation (13-31 `jit eval_string` per case); the evaluator under 1% | doc/perf/jank.md "Runtime profile" |
| a protocol call on a record | jank's own core 163.10 ms, the stock core 166.05 ms; JVM Clojure 0.81 ms (about 200x) | doc/PERF.md "jank baseline" and the all-host table (line "protocol call on a record") |
| boot on jank | AOT default: boot-ms 11,848, libs-ms 7,758; JIT 44,163 / 36,215 | atom.broker BOARD.md scoreboard |
| boot profile | codemod 41%, library phase 44%, eval 4.7%, reading 4.3%; jank's profiler: `jit eval_string` 71% exclusive | doc/perf/jank.md "Boot profile" |
| AOT stage 2 (a snapshot of the booted core) | `compile-module` of the rewritten forms produces no module: vars interned at boot (`cohost.shim/is$clojure_lang_IMapEntry`, `PersistentList-creator`) do not resolve; 550 `cohost.core` forms, not 2,000 | doc/perf/jank.md "AOT stage 2 probe" |
| `-Odirect-call` | listed by `jank --help` as "Elides the dereferencing of vars for improved performance. (not yet implemented)" | `jank --help` on this box |

## 2. Session 1: the compiler and code generation

(pending)

## 3. Session 2: the runtime and data structures

(pending)

## 4. Session 3: the cohost layer

(pending)

## 5. The ranking

(pending)

## 6. The plan: the top six

(pending)

## 7. The other three

(pending)
