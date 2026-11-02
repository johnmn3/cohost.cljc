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

What jank emits today, read from the C++ `jank compile-module research.cg -O3 --output-target cpp`
wrote for a four-fn probe (the dump is quoted in §8.1):

- A fn's self-recursion is a direct C++ call: `(fib (dec n))` inside `fib` is
  `research_cg$fib_16_1(fib, v10)`, no var deref, no dynamic dispatch.
- A call through any other var is a deref then a dynamic call: `(research.cg/fib x)` is
  `research_cg_SLASH_fib_35->deref()` followed by `v1.call( x)`.
- `<`, `+`, `-`, `inc`, `dec` on untyped values are out-of-line runtime calls on boxed objects:
  `jank::runtime::lt(n, v1)`, `jank::runtime::add(v11, v14)`; the var `+` is never consulted
  (the compiler resolves core arithmetic to the runtime op). A keyword call `(:a m)` is
  `jank::runtime::get(m, v1)`.
- A `loop` local started from an integer literal is typed as C++ `int` (`int v19
  (static_cast<int>(v18)); s5 = v19;` for `(loop [i 0 ...] (recur (inc i) ...))`), while the
  accumulator stays an `object_ref`. H0 saw the same `static_cast<int>` and its int32 wrap
  (`locals` result `-645128448`): the automatic native local is 32-bit, which is a semantics
  bug (Clojure's `long` is 64-bit) as much as an optimization.
- A `^long`/`^double` parameter is unboxed once on entry (`convert<long>::from_object`) and
  the fn's return is boxed (`into_object`); return hints and `let`/`loop` hints are ignored
  (doc/perf/jank.md H0, "What `+`, `<`, and `inc` compile to").
- `jank --help` lists `-Odirect-call` ("Elides the dereferencing of vars for improved
  performance. (not yet implemented)"). `jank print-cflags` shows the C++ is built with
  `-std=gnu++20 -fPIC -ffunction-sections -fdata-sections -fno-semantic-interposition -fwrapv`
  (and `-O0`/`-O2` among the flags); the optimization level for user modules is the `-O`
  given to `compile-module`.

The probes (medians of 5 after a 2 s warm-up; the box was shared with the source readers
during these runs, so absolute numbers carry noise of up to about 1.5x between runs: P1's JIT
`fib-var` printed 7581990 ns in one run and 13479580 ns in the queued run; the ratios inside
one run are the signal, and §8 re-measures the decisive cases alone):

`bash dev/research/run-jank.sh dev/research/p1_fib_floor.jank` (queued run):

| case | JIT median ns | AOT median ns | JVM median ns (`run-jvm.clj p1_fib_floor.clj`) |
|---|---:|---:|---:|
| fib-var (plain defn, fib 30) | 13479580 | 6101027 | 9069268 |
| fib-hinted-leaf (`^long n`, the recursion still boxes its return) | 22959818 | 17595281 | 7056832 (`^long` param and return: unboxed on the JVM) |
| fib-cpp-native (the same recursion in C++ through `cpp/raw`) | 4056029 | 3663884 | n/a |
| fib-local-volatile (the fn read from a volatile on every call) | 47574122 | 35482559 | 9473318 |

`bash dev/research/run-jank.sh dev/research/p2_call_path.jank` (5e6 calls of `(defn id1 [x] x)`
in a boxed loop; per-call cost is (case - loop-only) / 5e6):

| case | JIT median ns | per call, JIT | AOT median ns | JVM median ns |
|---|---:|---:|---:|---:|
| loop-only | 19843686 | | 22796641 | 27894623 |
| call-var-same-ns | 66478603 | 9.3 ns | 80456245 | 26698072 |
| call-var-other-ns | 76598975 | 11.4 ns | 71362755 | 24086899 |
| call-local (fn object in a local) | 56214429 | 7.3 ns | 55469488 | 24748202 |
| call-2 (two parameters) | 71433741 | 10.3 ns | 74444004 | 32771734 |
| call-variadic (`[x & ys]`) | 560280030 | 108 ns | 585666975 | 27761945 |
| call-cpp-objref (`cpp/raw` fn taking and returning `object_ref`) | 28029924 | 1.6 ns | 38220466 | n/a |
| call-multi (one-method defmulti) | 324417633 | 61 ns | 363483677 | 84258705 |
| call-volatile-fn | 142646782 | 24.6 ns | 147122664 | 29243081 |

Reading: the JVM inlines every plain call (same-ns, other-ns, local, variadic all land on its
loop's own cost, which is slower than jank's because it allocates `Long`s past the cache); jank
pays about 9-11 ns per var call, 1.6 ns for a direct C++ call, and 108 ns for a variadic call.
The loop itself is cheaper on jank than on the JVM (19.8 vs 27.9 ms for 5e6 boxed `inc` + `<`).

### S1-1. Native numeric locals and returns by type propagation (unboxed recursion and loops)

- **Mechanism.** The analyzer already types a `loop` local from an integer literal (as `int`)
  and a parameter from its `^long`/`^double` tag. Extend that into a real local type inference:
  a `let`/`loop` local whose init is a literal, a typed parameter, or an arithmetic/comparison
  of typed operands gets the native type (`i64`/`f64`, never `int`); a fn whose every return
  expression is typed gets a native return and a second, native entry point
  (`fib_native(i64) -> i64`) that self-recursion and typed call sites use, with the boxing
  `object_ref` entry kept for dynamic callers. `Math/sqrt`-style calls become the C++ ones.
- **Cost attacked.** Boxing on every arithmetic result and every return: H0's carried loop is
  0.105-0.125 of the boxed one (doc/perf/jank.md H0), and P1's C++ recursion runs fib 30 in
  3663884 ns against 6101027 ns for the boxed AOT fn and 17595281 ns for the hinted leaf (the
  hint loses today because the return boxes: H0's ratio 1.24-1.52).
- **Expected effect.** For fib: up to 6101027 / 3663884 = 1.67x (AOT), the C++ floor measured by
  P1; for loop-carried numeric code, up to the 8-10x of H0. On the yardstick this is the
  numeric half (fib, tak, nbody, spectral-norm, mandelbrot, nsieve, fannkuch, and the hinted
  variants, which H1 passes through for loops): the JVM's own hinted variants gain 0.48-0.84 on
  mandelbrot, fib, tak, nbody (doc/PERF.md "Hinted median / unhinted median on the JVM"), which
  is what jank's hinted rows lose today (fib-hinted/fib 1.74 on jank-aot).
- **Probe.** P1 (done): `fib-cpp-native` is the ceiling, `fib-hinted-leaf` the current loss.
  Next: `dev/research/p7_native_entry.jank`, a hand-written C++ native entry for tak and
  nbody's inner `advance` through `cpp/raw`, against the jank versions (an hour).
- **Semantic risk.** Overflow: Clojure's `+` on longs throws `ArithmeticException`; jank's
  native path is compiled with `-fwrapv` (wraps). The existing `int` local is already wrong
  (32-bit). The port has to keep `i64` and either check overflow or document the divergence as
  jank already does for `+`. `=` on mixed int/double, `nil` in a typed local (NPE on the JVM).
- **Owner.** Upstream jank (analyzer `processor.cpp` typing + `cpp_processor.cpp` emission);
  cohost only passes hints through (H1, done).

### S1-2. Direct calls to known vars, and a cheap variadic/arity path

- **Mechanism.** `-Odirect-call`, which the CLI lists as not implemented: when a call's head is
  a var whose root is a compiled fn in the same module graph (not `^:dynamic`, not
  rebound), emit the direct C++ call `ns$fn_arity(fn_obj, args...)` as self-recursion is
  emitted today, guarded by a per-var "redefined" flag the var's `bind_root`/`alter-var-root`
  sets (one load + predictable branch instead of deref + dynamic `call`). For variadic arities,
  emit a fixed-arity entry per call-site arity (`[x & ys]` called with two args gets
  `arity_2` that builds the rest seq once, inline) instead of the generic packing path that
  costs 108 ns per call today.
- **Cost attacked.** P2: 9-11 ns per var call against 1.6 ns direct; 108 ns per variadic call;
  the JVM is at 0 ns over the loop for all of these (it inlines). Which yardstick programs pay
  it: json (`parse` calls `skip`, `parse-string`, `parse-num` through vars in its inner loop;
  `chunks` is self-recursive), pipeline (`assoc`, `update-in`, `assoc-in`, `get-in` are var
  calls into clojure.core; `assoc` and `update-in` are variadic in Clojure), k-nucleotide and
  the numeric programs mostly self-recurse or loop (already direct).
- **Expected effect.** Bounded by the per-call figures: (9.3 - 1.6) ns per cross-var call and
  about 100 ns per variadic call. For json and pipeline the share is unknown until probe P8 (a
  count of var and variadic calls per iteration times these costs, against the program's
  median). For fib/tak/binary-trees: none (self-recursion is already direct).
- **Probe.** P2 (done) gives the per-call costs. P8: pipeline with `assoc`/`update-in` replaced
  by fixed-arity local copies (`(defn assoc2 [m k v] ...)` over `cpp/raw` or jank's own
  2-arity), one hour, under `dev/research/`.
- **Semantic risk.** Redefinition (`def` again, `with-redefs`, `alter-var-root`, REPL reload)
  must reach every direct call site: the guard flag handles it at one branch per call; a
  module compiled against an older arity shape must fall back to the dynamic call.
  `^:dynamic` vars stay dynamic. No change to argument evaluation order or arity errors.
- **Owner.** Upstream jank (codegen; the flag already exists in the CLI).

### S1-3. Inline the runtime into generated code: LTO across the module graph, then PGO

- **Mechanism.** Every `lt`/`add`/`get`/`call`/`deref` in generated C++ is a call into the
  runtime library; `compile-module` builds one object per namespace at `-O3` with
  `-ffunction-sections -fno-semantic-interposition` but no link-time optimization across
  objects or into the runtime. Build the runtime's hot entry points as LLVM bitcode (or make
  the small ones header-inline), compile modules with `-flto=thin`, and link the user's
  program as one LTO unit so the type switch inside `add(object_ref, object_ref)` and the
  arity dispatch inside `call` can be inlined and specialized at each site; then profile-guided
  optimization over the yardstick.
- **Cost attacked.** The out-of-line runtime call per primitive op and per fn call: P2's
  `call-cpp-objref` (1.6 ns, an inlinable C++ fn) against `call-var-same-ns` (9.3 ns); the
  boxed loop's 4 ns per `inc`+`<` pair. On the JVM every one of these is inlined by C2.
- **Expected effect.** Unknown until probe P9: compile P1/P2/P5 at `JANK_O=0` and `JANK_O=3`
  (if `-O3` already changes little, the generated code's time is inside the runtime calls, and
  inlining them is the lever; if `-O3` changes a lot, LLVM is already working and LTO adds the
  cross-object part). The ceiling for arithmetic is S1-1's C++ floor.
- **Probe.** P9 (an hour): the `-O0` vs `-O3` matrix with the runner's `JANK_O` knob; then one
  manual link of a probe module with `-flto` against a bitcode build of `libjank` if the
  shipped package carries one (`ls /usr/local/lib/jank/0.1/lib`).
- **Semantic risk.** None at the language level (a build-pipeline change); risk is build time
  and binary size, and that the JIT path (`jank run`) cannot use LTO, so the gain is AOT-only.
- **Owner.** Upstream jank (the AOT driver); cohost would only pass flags.

## 3. Session 2: the runtime and data structures

The probes (same harness; jank JIT / jank AOT / JVM medians, ns; ratio is AOT over JVM):

`run-jank.sh p3_gc_alloc.jank` and `run-jvm.clj p3_gc_alloc.clj`:

| case | JIT | AOT | JVM | AOT/JVM |
|---|---:|---:|---:|---:|
| bt-14 (binary-trees, depth 14) | 149724234 | 218850983 | 8727705 | 25.1 |
| pair-alloc-2e6 (2e6 two-element vectors) | 398224591 | 498902205 | 20087465 | 24.8 |
| box-double-5e6 (5e6 `+` on doubles) | 3652738 | 3824439 | 3596209 | 1.06 |
| box-long-5e6 (5e6 `+` on longs) | 70 | 40 | 1771388 | folded to a constant on jank |

`bash dev/research/p3_gc_env.sh` (the AOT module under bdwgc's environment knobs, one run each;
`GC_PRINT_STATS=1` counted 408 collections during the default run):

| env | bt-14 | pair-alloc-2e6 |
|---|---:|---:|
| default | 174828601 | 382642173 |
| `GC_INITIAL_HEAP_SIZE=1G` | 133928351 | 438212510 |
| `GC_FREE_SPACE_DIVISOR=1` | 163042725 | 393155134 |
| both | 122085049 (first run 2088171009) | 430749986 |
| `GC_MARKERS=1` | 251888852 | 416430715 |
| `GC_ENABLE_INCREMENTAL=1` | 728515125 | 2454341234 |

`run-jank.sh p4_maps.jank` and `run-jvm.clj p4_maps.clj` (1e6 operations unless the name says):

| case | JIT | AOT | JVM | AOT/JVM |
|---|---:|---:|---:|---:|
| assoc-small-1e6 (one keyword into a 3-key map) | 176129605 | 148957299 | 36658901 | 4.1 |
| assoc-in-1e6 | 919143050 | 909495497 | 124226260 | 7.3 |
| update-in-1e6 (`(fnil + 0)`) | 1178152203 | 995656084 | 197107660 | 5.1 |
| get-in-1e6 | 182920727 | 154833446 | 52559637 | 2.9 |
| kw-lookup-5e6 (`(:a m)`) | 61266314 | 71969664 | 44774448 | 1.6 |
| map-lookup-5e6 (`(m :a)`) | 60353072 | 73912634 | 24408948 | 3.0 |
| get-1000-1e6 (a 1,000-key map) | 81204560 | 108789272 | 28271240 | 3.8 |
| hash-map-grow-1e5 | 80959397 | 86823419 | 25056966 | 3.5 |
| pipeline-2000 (the yardstick program at its size) | 3020708 | 2861345 | 958740 | 3.0 |
| pipeline-20000 | 42217727 | 39690346 | 7027738 | 5.6 |

`run-jank.sh p5_seqs.jank` and `run-jvm.clj p5_seqs.clj`:

| case | JIT | AOT | JVM | AOT/JVM |
|---|---:|---:|---:|---:|
| reduce-range-1e6 | 68754503 | 51607376 | 13819252 | 3.7 |
| reduce-vec-1e6 | 66340786 | 55472886 | 5307562 | 10.5 |
| first-next-range-1e6 | 74654181 | 78106936 | 17601306 | 4.4 |
| lazy-count-1e6 (`count (filter odd? (map inc (range n)))`) | 1288721967 | 1277837517 | 25912086 | 49.3 |
| transduce-1e6 | 96124179 | 71842192 | 11725907 | 6.1 |
| into-map-1e6 | 82541698 | 85770438 | 22690137 | 3.8 |
| frequencies-1e5 | 12521267 | 8560908 | 5194439 | 1.6 |
| string-nth-1e6 | 71859400 | 92870199 | 8406315 | 11.0 |
| apply-str-1e4 | 5902609 | 8214179 | 425450 | 19.3 |
| conj-vec-1e6 | 175263551 | 181328909 | 35229732 | 5.1 |
| transient-vec-1e6 | 22127667 | 13753744 | 16547525 | 0.83 |
| sort-1e5 | 9594502700 | 9399588860 | 8103593 | 1160 |
| sort-by-1e5 | 10241528066 | 10549239337 | 23915541 | 441 |

`run-jank.sh p5b_sort.jank` (sort's scaling; `compare` alone):

| case | JIT | AOT |
|---|---:|---:|
| compare-1e6 (`(compare i 500000)`) | 14227416 | 13891221 |
| sort-1e3 | 896123 | 764014 |
| sort-1e4 | 201193223 | 183016889 |
| sort-list-1e4 | 201561129 | 196992952 |
| sort-1e5 | 16247594966 | 14639408712 |

Two correctness facts fell out: jank's `(reduce + (range 1000000))` printed `1783293664`, the JVM
`499999500000`; `first-next-range` and `transduce` wrapped the same way (the sum modulo 2^32:
the loop-local `int` typing of §2 reaches `+`), so every "faster" claim below assumes 64-bit
arithmetic is restored upstream first. `box-long-5e6` was folded to a constant by LLVM (the
result is right; the loop has no store), so small-integer `+` in jank is not a heap allocation.

### S2-1. Allocation: small persistent vectors and the collector

- **Mechanism.** Two layers. (a) The allocator: jank calls `GC_init()` with
  `GC_set_all_interior_pointers(1)` and nothing else (clone `src/cpp/jank/c_api.cpp`, the
  `jank_init` body: "The GC needs to initialized even before arg parsing ... It can still be
  configured later"); so the heap starts at bdwgc's default and grows by its default divisor.
  Set a program-sized initial heap and a lower free-space divisor from the runtime (or the
  AOT driver), keep parallel marking, and use pointer-free allocation (`GC_MALLOC_ATOMIC`)
  for boxes that hold no references (reals, big integers, strings' bytes). (b) The vector:
  a two-element `[a b]` is the yardstick's binary-trees node; its cost today is about 200 ns
  per vector (pair-alloc 398224591 ns / 2e6) against the JVM's 10 ns. Give jank a small
  vector representation (inline storage up to the 32-element tail size, one allocation, no
  tree node) and build the larger one from it, as Clojure's `PersistentVector` keeps a tail.
  The split between (a) and (b) is what probe P3b measures.
- **Cost attacked.** binary-trees: 25x the JVM at depth 14 (bt-14 218850983 vs 8727705 ns);
  the yardstick's depth-8 run hid it (jank-aot 1.75x the JVM, doc/PERF.md). Every program
  that allocates a collection per step: pipeline (a map per `assoc`), json (`conj` per
  character), nbody (a vector per body per step). 408 collections in one P3 run.
- **Expected effect.** Measured: the environment alone gives bt-14 174828601 -> 122085049 ns
  (1.43x) with `GC_INITIAL_HEAP_SIZE=1G GC_FREE_SPACE_DIVISOR=1`, and `GC_ENABLE_INCREMENTAL`
  costs 4-6x (do not). The vector representation's share is unknown until P3b (`[i i]` against
  `(cons i nil)`, `{:a i}`, a `cpp/raw` struct: which allocation is the 200 ns).
- **Probe.** P3 and the env matrix (done); P3b (queued): the allocation kinds side by side.
- **Semantic risk.** None for the collector's settings; the small vector must keep every
  vector behavior (nth, conj, seq, equality, hashing, transients, meta) identical.
- **Owner.** Upstream jank (runtime). cohost can only set the environment for its own process.

### S2-2. Chunked seqs and reduce fast paths

- **Mechanism.** core.jank's `reduce` says `; TODO: Chunking support.` and hands every
  collection to `cpp/jank.runtime.reduce` (core.jank line 1126); `map` and `filter` carry the
  `chunked-seq?` branch from Clojure (lines 2885, 3864) but `range` and vectors do not hand
  out chunked seqs, and `lazy-seq` is `cpp/jank.runtime.lazy_seq` of a fresh fn per element
  (line 2026). Add 32-element chunks to `range`, vectors and the lazy-seq machinery (the
  `chunk-buffer`/`chunk-cons` plumbing exists), and give `reduce`/`transduce`/`into` an
  internal-reduce dispatch per collection type (an index loop for vectors, a counter loop for
  ranges, a per-chunk loop for chunked seqs) so the seq objects are never allocated.
- **Cost attacked.** lazy-count 49x the JVM (1277837517 vs 25912086 ns), reduce over a vector
  10.5x, over a range 3.7x, first/next over a range 4.4x, transduce 6.1x, into 3.8x. In the
  yardstick: pipeline (`reduce` over `(range n)`), json (`map` + `reduce` in `checksum`,
  `into` in `chunks`), k-nucleotide (the `nth` loop is fine; `gen` conj's), nbody (`reduce`,
  `mapv`).
- **Expected effect.** The JVM's chunked path is the comparison column above; the reachable
  share is unknown until P10 (a hand-chunked loop and an index-loop reduce over a vector in
  raw jank, against `reduce`/`filter`).
- **Probe.** P10 (an hour): `(loop [i 0 ...] (nth v i))` and a `cpp/raw` index loop against
  `(reduce + v)`; a 32-at-a-time `map` over a range against the lazy one.
- **Semantic risk.** Chunk-granularity laziness (32 elements realized at once), exactly
  Clojure's documented behavior; `reduce` on a seq with side effects keeps its order.
- **Owner.** Upstream jank (core.jank and the sequence runtime).

### S2-3. The pathological leaves: sort, string building, string `nth`, variadic and multimethod calls

- **Mechanism.** Each is a local fix in the runtime or core.jank, not a design:
  - `sort` is quadratic or worse: 1e3 -> 1e4 is 240x (764014 -> 183016889 ns), 1e4 -> 1e5 is
    80x; `compare` itself is 14 ns (compare-1e6 13891221 ns). HEAD's `seq.cpp` sorts a
    `native_vector<object_ref>` with `std::stable_sort` (clone lines 1091-1124), which cannot
    scale that way, so the installed `0.1-alpha` has an older sort or `make_sequence_range`
    re-walks the sequence per element; either way: sort into a native vector, once.
  - `apply str` over 1e4 strings is 19x (8214179 vs 425450 ns): build into one reserved
    buffer; `string-nth` is 11x (93 ns per `nth` against 8): index bytes directly when the
    string is ASCII or keep a code-point index.
  - A variadic call costs 108 ns and a multimethod call 61 ns (P2, §2): emit fixed arities
    for the call-site arity and cache the last dispatch value -> method per call site
    (`isa?` re-check only on a hierarchy change).
- **Cost attacked.** The stock core's worst bench cases run on exactly these leaves: sort
  x15.2, sort-by x27.5, str-build x15.5 against jank's own core (doc/PERF.md "jank
  baseline"), and jank's own sort of 1e5 is 1160x the JVM. In the yardstick: json
  (`apply str`, `nth` on a string), k-nucleotide (`nth` on a string), dispatch
  (multimethod), pipeline (`assoc`/`update-in` are variadic).
- **Expected effect.** sort: from quadratic to n log n (the JVM column: 8103593 ns at 1e5,
  which `std::stable_sort` over 1e5 boxed compares at 14 ns each would match within 2x);
  the others: the measured per-op gaps above are the ceiling (19x, 11x, 108 ns, 61 ns).
- **Probe.** P5, P5b, P2 (done). Confirming the sort cause needs the installed binary's
  `sort` source (not shipped); `jank --profile` of `sort-1e4` would show which runtime scope
  holds the time (an hour).
- **Semantic risk.** None: same results, same stability of `sort`.
- **Owner.** Upstream jank. (cohost's natives for jank, Step 4, must not opt into jank's
  `sort` until it is fixed: the stock `sort` at x15 is still faster than a quadratic host sort
  at 1e5.)

## 4. Session 3: the cohost layer

(pending)

## 5. The ranking

(pending)

## 6. The plan: the top six

(pending)

## 7. The other three

(pending)
