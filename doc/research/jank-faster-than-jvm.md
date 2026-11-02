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
  performance. (not yet implemented)"); the flag is parsed into `opts.direct_call` and nothing
  reads it (clone `cli.cpp:207`; `grep -rn direct_call src include` finds no other reader).
  The analyzer records every def's var -> value expression in a map no pass consumes
  (`analyze/processor.cpp:1260`, `processor.hpp:243`).
- The optimization level defaults to 0 for both codegen and the JIT (`cli.cpp:205`); `jank run`
  copies `-O` to the JIT level (`cli.cpp:528`) and the JIT hands it to clang as `-O0..-O3`
  (`jit/processor_dynamic.cpp:275`), one `ParseAndExecute` per top-level form
  (`processor_dynamic.cpp:594`). So `bb bench:programs jank-jit`, which runs `jank run` with no
  `-O`, measures `-O0` machine code; the probes here pass `-O3`. The IR pass that hoists var
  derefs runs only at `-O3` and gives up on any fn containing a loop (`ir/opt/hoist_var_derefs.cpp:28`);
  `codegen/optimize.cpp` (an LLVM pass pipeline) has no caller. clojure.core itself is compiled
  into the runtime library at `-O3` for Release builds (`CMakeLists.txt:144`, `:1123`), and
  `strings /usr/local/bin/jank` shows `-DJANK_CMAKE_BUILD_TYPE="Release"`.
- Values: an `object_ref` is one NaN-boxed word; integers in the i32 range and every double are
  inline, never allocated (`runtime/oref.hpp:64-110`, `core/make_box.hpp:82-100`); a larger
  integer is a 24-byte pointer-free GC box. So "boxing" in the arithmetic of this section means
  the two tag tests plus a type switch per operand (`visit.hpp:465-477`), not allocation.
  `runtime::add(object_ref, object_ref)` is a nested `visit_number_like` on both operands,
  `always_inline`/`flatten` (`core/math.hpp:70-93`); `add(primitive, primitive)` is an unchecked
  `l + r` (`math.hpp:172`); `inc` on a primitive is overflow-checked (`math.hpp:1192`).
- The 32-bit wrap seen in §3 (`(reduce + (range 1000000))` = `1783293664`) has its source here:
  a tagged small integer's payload is `i32` (`oref.hpp:2250`) and the small+small arm of `add`
  computes `typed_l->data + typed_r->data` in `int` before `make_box` range-checks it
  (`math.hpp:86`); a literal that fits i32 is typed C++ `int` (`analyze/cpp_util.cpp:621`) and a
  loop binding keeps that type (`cpp_util.cpp:1023`).
- A var deref is an atomic `thread_bound` test then an atomic root load (`runtime/var.cpp:146`,
  `:279`); `object_ref::call` is two tag tests then a virtual call (`oref.hpp:553`) into
  `jit_function::call`, which tests the arity pointer and calls through it (`jit_function.cpp:78`).
  Named self-recursion is lowered to a direct C call only for a fixed arity
  (`analyze/processor.cpp:476`, `codegen/cpp_processor.cpp:853`), with every argument already an
  `object_ref` (`processor.cpp:3521`): direct, but boxed.
- `=` has its `:inline` commented out ("TODO: Add template to support this", core.jank line 659),
  so every `(= i n)` is a var deref plus a dynamic call, while `+ - < inc dec ==` and keyword
  calls inline to `cpp/jank.runtime.*` (core.jank lines 1371, 1551, 1645; `processor.cpp:3478`).
- In the default lazy mode a defn is a `deferred_cpp_function` until first call, and every call
  through such a value takes a `recursive_mutex` (`obj/deferred_cpp_function.cpp:48`); each
  evaluation of a `fn` form allocates a fresh `jit_function` or `jit_closure` box
  (`codegen/api.cpp:428`, `:440`).
- `JANK_PRINT_CODEGEN=1` and `JANK_PRINT_IR=1|2` dump the generated C++ and the IR
  (`ir/processor.cpp:1010`): the cheapest probe for all of the above.

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

- **Mechanism.** The analyzer already types a `loop` local from an integer literal (as `int`,
  `cpp_util.cpp:621`, `:1023`) and a parameter from its `^long`/`^double` tag (the only `:tag`
  read in the analyzer, `processor.cpp:1642`); a `let` local takes its init's static type
  (`processor.cpp:2208`), and the inlined `cpp/jank.runtime.add` call carries its C++ return type
  (`processor.cpp:639`), so natives already flow through arithmetic. What stops them: every arity's
  tail is forced to `object_ref` (`processor.cpp:1766`, "erasure ... deferred to codegen"),
  `defn`'s `:rettag` has no consumer (core.jank line 779), `if` forces both branches boxed
  ("We can't (yet) guarantee that each branch of an if returns the same unboxed type",
  `processor.cpp:2503`), a `recur` to a fn re-boxes every parameter (`processor.cpp:271`), and
  the `needs_box` analysis is read once by the IR and never by codegen (`expression.hpp:198`,
  `ir/processor.cpp:390`). Extend that into a real local type inference:
  a `let`/`loop` local whose init is a literal, a typed parameter, or an arithmetic/comparison
  of typed operands gets the native type (`i64`/`f64`, never `int`); a fn whose every return
  expression is typed gets a native return and a second, native entry point
  (`fib_native(i64) -> i64`) that self-recursion and typed call sites use, with the boxing
  `object_ref` entry kept for dynamic callers. `Math/sqrt`-style calls become the C++ ones.
- **Cost attacked.** The type dispatch on every arithmetic operand and the convert on every
  typed entry and return (not allocation: small ints and doubles are inline): H0's carried loop is
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
  `add(primitive, primitive)` is an unchecked `l + r` under `-fwrapv` (`math.hpp:172`; `inc` is
  checked, `math.hpp:1192`). The existing `int` typing is already wrong (32-bit), and the small+small
  arm of `add` wraps at 2^31 (`math.hpp:86`, `oref.hpp:2250`): `(reduce + (range 1000000))` is
  `1783293664` on the installed jank. Any unboxed path must compute in `i64` with the check. The port has to keep `i64` and either check overflow or document the divergence as
  jank already does for `+`. `=` on mixed int/double, `nil` in a typed local (NPE on the JVM).
- **Owner.** Upstream jank (analyzer `processor.cpp` typing + `cpp_processor.cpp` emission);
  cohost only passes hints through (H1, done).

### S1-2. Direct calls to known vars, and a cheap variadic/arity path

- **Mechanism.** `-Odirect-call`, which the CLI lists as not implemented: when a call's head is
  a var whose root is a compiled fn in the same module graph (not `^:dynamic`, not
  rebound), emit the direct C++ call `ns$fn_arity(fn_obj, args...)` as fixed-arity
  self-recursion is emitted today (`cpp_processor.cpp:853`), from the analyzer's unused
  var -> value map (`processor.cpp:1260`), guarded by a per-var "redefined" flag the var's `bind_root`/`alter-var-root`
  sets (one load + predictable branch instead of deref + dynamic `call`). For variadic arities,
  emit a fixed-arity entry per call-site arity (`[x & ys]` called with two args gets
  `arity_2` that builds the rest seq once, inline) instead of the generic packing path that
  costs 108 ns per call today.
- **Cost attacked.** P2: 9-11 ns per var call (the atomic `thread_bound` test and root load,
  `var.cpp:146`/`:279`, then `object_ref::call`'s tag tests and virtual call, `oref.hpp:553`,
  `jit_function.cpp:78`) against 1.6 ns direct; 108 ns per variadic call; the JVM is at 0 ns
  over the loop for all of these (it inlines). `=` is such a call too (its `:inline` is
  commented out, core.jank line 659), so every `(= i n)` loop test in binary-trees,
  k-nucleotide, json, dispatch and nbody pays it. Which yardstick programs pay the rest: json (`parse` calls `skip`, `parse-string`, `parse-num` through vars in its inner loop;
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

- **Mechanism.** `add`/`lt`/`inc` are header-inline (`math.hpp`), but `get`, `call` (virtual),
  `deref`, `truthy`'s `dyn_cast`, the sequence and collection ops and clojure.core's own fns
  (compiled into `libjank-*-runtime.a` at build time, `CMakeLists.txt:1123`) are calls into the
  runtime library; `compile-module` builds one object per namespace (the file's forms wrapped in
  one fn, `context_dynamic.cpp:99`) at the given `-O` with `-ffunction-sections
  -fno-semantic-interposition` but no link-time optimization across objects or into the runtime,
  and the LLVM pass pipeline in `codegen/optimize.cpp` is never invoked. Build the runtime's hot entry points as LLVM bitcode (or make
  the small ones header-inline), compile modules with `-flto=thin`, and link the user's
  program as one LTO unit so the type switch inside `add(object_ref, object_ref)` and the
  arity dispatch inside `call` can be inlined and specialized at each site; then profile-guided
  optimization over the yardstick.
- **Cost attacked.** The out-of-line runtime call per primitive op and per fn call: P2's
  `call-cpp-objref` (1.6 ns, an inlinable C++ fn) against `call-var-same-ns` (9.3 ns); the
  boxed loop's 4 ns per `inc`+`<` pair. On the JVM every one of these is inlined by C2.
- **Expected effect.** P9 answered the first question: clang's level is worth 7-20x on jank's
  generated C++ (`JANK_O=0 bash dev/research/run-jank.sh ...`: fib-var 120222878 ns at `-O0`
  against 13479580 at `-O3` under the JIT, 144432754 against 6101027 under AOT; P2's loop-only
  146773370 against 19843686), so LLVM is doing the work inside each translation unit and the
  remaining lever is the cross-object part: the virtual `call`, `get`, the sequence ops and
  clojure.core's own compiled fns. That part's size is the gap between P2's `call-var-same-ns`
  (9.3 ns) and `call-cpp-objref` (1.6 ns) per call, and unknown for the collection ops until
  an LTO build exists. The ceiling for arithmetic is S1-1's C++ floor.
- **Probe.** P9 (done, above): the `-O0` vs `-O3` matrix with the runner's `JANK_O` knob; then one
  manual link of a probe module with `-flto` against a bitcode build of the runtime. The shipped
  package carries `libjank-static-runtime.a` and `libjank-dynamic-runtime.a` (no bitcode), so
  the LTO leg needs a source build of jank.
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
  per vector (pair-alloc 398224591 ns / 2e6) against the JVM's 10 ns. What that buys today: the
  literal is `_jank_vec(2, a, b)` (`cpp_processor.cpp:637`), which builds a stack
  `transient_vector`, `conj_in_place`s each element and `to_persistent()`s (`codegen/api.cpp:146`);
  immer's transient owner token is a 1-byte atomic GC allocation (`gc_transience_policy.hpp:50`),
  the first push copies the static empty tail into a full 32-slot leaf, 256 bytes plus header,
  whatever the element count (`immer/detail/rbts/rbtree.hpp:262`, `node.hpp:337`), and
  `to_persistent` allocates the `persistent_vector` box (`transient_vector.cpp:117`): three
  `GC_malloc`s and about 300 bytes per `[a b]`, every immer node a direct `GC_malloc` with no
  free-list layer (`immer/heap/gc_heap.hpp:105`, `heap_policy.hpp:26`). A persistent `conj` is a
  leaf copy plus a new box, two allocations (`persistent_vector.cpp:177`). Give jank a small
  vector representation (inline storage up to the 32-element tail size, one allocation, no
  tree node) and build the larger one from it, as Clojure's `PersistentVector` keeps a tail.
  The split between (a) and (b) is what probe P3b measures.
- **Cost attacked.** binary-trees: 25x the JVM at depth 14 (bt-14 218850983 vs 8727705 ns);
  the yardstick's depth-8 run hid it (jank-aot 1.75x the JVM, doc/PERF.md). Every program
  that allocates a collection per step: pipeline (a map per `assoc`), json (`conj` per
  character), nbody (a vector per body per step). 408 collections in one P3 run.
- **Expected effect.** Measured: the environment alone gives bt-14 174828601 -> 122085049 ns
  (1.43x; bdwgc's own doc on the divisor: "Decreasing it will appreciably decrease total
  collection time at the expense of space", `gc.h:385`; `THREAD_LOCAL_ALLOC` and `PARALLEL_MARK`
  are compiled into the shipped runtime archive per `nm`) with `GC_INITIAL_HEAP_SIZE=1G GC_FREE_SPACE_DIVISOR=1`, and `GC_ENABLE_INCREMENTAL`
  costs 4-6x (do not). The vector representation's share is unknown until P3b (`[i i]` against
  `(cons i nil)`, `{:a i}`, a `cpp/raw` struct: which allocation is the 200 ns).
- **Probe.** P3 and the env matrix (done); P3b (done): the allocation kinds side by side.
  `run-jank.sh p3b_alloc_kinds.jank`, 2e6 of each, per-item cost = median / 2e6:

  | case | JIT median ns | AOT median ns | per item (AOT) |
  |---|---:|---:|---:|
  | `[i i]` | 505846300 | 564562897 | 282 ns |
  | `(vector i i)` | 498298136 | 538864347 | 269 ns |
  | `{:a i}` | 243553708 | 243568011 | 122 ns |
  | `(cons i nil)` | 171815720 | 169006305 | 85 ns |
  | `(list i i)` (variadic) | 674527904 | 685245335 | 343 ns |
  | `(fn [] i)` (a closure object) | 277245812 | 266557725 | 133 ns |
  | `cpp/raw` `GC_MALLOC(16)` | 56104631 | 55561602 | 28 ns |
  | `cpp/raw` `GC_MALLOC(48)` | 86362897 | 77249979 | 39 ns |
  | `cpp/raw` `GC_MALLOC_ATOMIC(16)` | 48744722 | 50647882 | 25 ns |

  Reading: one bdwgc allocation is 25-40 ns on this box (the JVM's whole two-element vector,
  allocation included, is 10 ns: pair-alloc 20087465 ns / 2e6); a `[a b]` is about 280 ns, i.e.
  three allocations (about 100 ns, one of them a 256-byte leaf) plus the transient build, copy
  and box; a one-key map 122 ns; a cons 85 ns. Both layers matter: the allocator floor is 3-4x
  the JVM's per object, and the vector representation spends 3 objects and about 180 ns of
  work above that floor.
- **Semantic risk.** None for the collector's settings; the small vector must keep every
  vector behavior (nth, conj, seq, equality, hashing, transients, meta) identical.
- **Owner.** Upstream jank (runtime). cohost can only set the environment for its own process.

### S2-2. Chunked seqs and reduce fast paths

- **Mechanism.** core.jank's `reduce` says `; TODO: Chunking support.` and hands every
  collection to `cpp/jank.runtime.reduce` (core.jank line 1126), which walks
  `make_sequence_range` and makes a virtual `f.call` per element (`core/seq.cpp:901`); that
  range advances with the allocating `next()` (`sequence_range.hpp:39`), one new seq object per
  element, because the `next_in_place` branch is commented out (`sequence_range.hpp:169`;
  `object.hpp:567`: "Each call to next() allocates a new sequence"). `reduce-kv`, by contrast,
  loops directly over the immer data (`seq.cpp:926`), and `doseq` already uses
  `fresh-seq` + `next-in-place` (core.jank line 2742): the fast path exists, `reduce` does not
  take it. `lazy_sequence` locks a `recursive_mutex` on every `first`/`next`/`seq`
  (`obj/lazy_sequence.cpp:53`) and has no `next_in_place`; `range` locks one per step
  (`obj/range.cpp:352`); `integer_range` is not chunkable (`integer_range.hpp:293`, a TODO), so
  `(map f (range n))` takes the per-element `cons` path; `array_chunk::chunk_next` copies the
  whole chunk buffer per element consumed (`obj/array_chunk.cpp:41`, "This copying will be
  slow"); `seq` on a vector allocates a fresh seq and `next()` one per element
  (`persistent_vector.cpp:158`, `persistent_vector_sequence.cpp:124`). `map` and `filter` carry the
  `chunked-seq?` branch from Clojure (lines 2885, 3864) but `range` and vectors do not hand
  out chunked seqs, and `lazy-seq` is `cpp/jank.runtime.lazy_seq` of a fresh fn per element
  (line 2026; each `fn` form evaluation is a `make_box<jit_closure>`, `codegen/api.cpp:440`), and
  `integer_range::next()` allocates a new range object per step (`obj/integer_range.cpp:108`).
  First step, cheaper than chunking: finish the `fresh_seq` + `next_in_place` branch of
  `make_sequence_range` so `reduce`, `str`, `sort`, `vec`, `apply`, `count` and equality walk
  without allocating; then internal-reduce per collection; then 32-element chunks for `range`,
  vectors and the lazy-seq machinery (the
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
  - `sort` looks quadratic: 1e3 -> 1e4 is 240x (764014 -> 183016889 ns), 1e4 -> 1e5 is 80x,
    while `compare` itself is 14 ns (compare-1e6 13891221 ns). The sort is fine
    (`std::stable_sort` over a `native_vector<object_ref>`, `core/seq.cpp:1091-1124`); it
    returns a `native_vector_sequence`, whose `next()` is
    `make_box<native_vector_sequence>(data, n)` with `data` a `native_vector<object_ref>` held by
    value (`obj/native_vector_sequence.cpp:98`, header line 58): every step copies the whole
    vector, so any walk of a sorted result (`nth`, `reduce`, `into`, `seq`) is O(n^2):
    50000 steps x 1e5 copies for P5's `(nth (sort v) 50000)`. Fix: share the buffer (a
    `jtl::ref` or the immer vector) between the steps, as `next_in_place` already avoids the
    copy. P5c (`run-jank.sh p5c_sort_walk.jank`, 1e5 sorted; JIT / AOT medians, ns) separates
    the two: `sort-first` 18551515 / 18247162 and `sort-count` 11203190 / 19085335 (the sort
    itself), against `sort-nth-50k` 15442173129 / 16149965063, `sort-reduce` 30789704126 /
    33005631393 and `sort-vec-nth` 35417497929 / 35823935482 (the walk); `sort-nth-5k-of-1e4`
    80663625 / 108267494.
  - `apply str` over 1e4 strings is 19x (8214179 vs 425450 ns): `apply_to` walks the args
    twice with the allocating range (`core/call.cpp:18`) and `runtime::str` a third time
    (`seq.cpp:798`); one `fresh_seq` + `next_in_place` pass into a reserved `string_builder`.
    `string-nth` is 11x (93 ns per `nth` against 8): `nth` is `to_int`, a `visit_object`
    switch, then the virtual `nth` that re-checks and re-unboxes the index
    (`seq.cpp:639-686`, `persistent_string.cpp:145`) and boxes a character object per call;
    mirror `runtime::get`'s one-virtual-call shape (`seq.hpp:101`) with an `nth(object_ref, i64)`
    overload. (`nth` and `count` on strings are byte-indexed, `persistent_string.cpp:145`, `:217`:
    a semantics fact for non-ASCII input, outside this plan.)
  - A keyword's hash is recomputed from its symbol's strings on every use
    (`obj/keyword.cpp:45`, `obj/symbol.cpp:152`, `hash.cpp:129`) although `jtl::immutable_string`
    already caches its hash (`immutable_string.hpp:648`); persistent hash maps are `immer::map`
    keyed by `std::hash<object_ref>` -> `to_hash()` (`runtime/detail/type.hpp:53`), so every
    `get` on a map past the array-map size re-hashes the keyword (P4: get-1000 3.8x,
    hash-map-grow 3.5x). Array maps compare keyword keys by identity (`native_array_map.hpp:55`)
    but `assoc` on a full 8-pair array map promotes to a hash map even when the key already
    exists (`persistent_array_map.cpp:210`, a documented TODO), and every `assoc` on an array
    map clones the backing array and allocates a new box (`:221`): P4's `assoc-small` is 4.1x
    and `update-in`/`assoc-in` 5-7x. Vector and map hashes are recomputed on every call
    (`persistent_vector.cpp:115`, `base_persistent_map.cpp:132`; only `cons` caches,
    `cons.hpp:128`), and every collection box carries a `folly::Synchronized` lazy-meta word
    (`lazy_meta.hpp:38`).
  - `truthy` is about six branches and two header loads (`core/truthy.hpp:14-28`): `nil` and
    `false` are single globals (`obj/nil.cpp:117`, `obj/number.cpp:495`), so two word compares
    would do.
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

What runs when a program runs under cohost's stock core on jank (from the cohost reader and
the sources named):

- `bb boot:jank`, `bb bench:jank` and `bb bench:programs jank` run
  `jank --module-path <aot>:src run-main cohost.run -- --eval <program>` with no `-O`
  (`bb.edn`, `dev/bench_programs.clj:157`). cohost's own namespaces are AOT objects at `-O3`
  (`dev/perf/jank_aot_ensure.clj:68`), but the 550 booted `cohost.core` forms, the twelve
  libraries and the user's program go through the adapter's `:eval` (jank's `eval`,
  `adapter.cljc:293`, `boot.cljc:333`), i.e. the JIT, at the default `-O0` (`cli.cpp:205`).
  `run-main` accepts `-O` and copies it to the JIT (`cli.cpp:528`).
- The codemod applies core.clj's `:inline` fns itself (`jank_host.jank:1347` `:rewrite-inline?
  true`; `rewrite.cljc:1370`), so `(+ a b)` becomes `(. clojure.lang.Numbers (add a b))`, then
  the shim static `cohost.shim/Numbers-add`, which host-aliasing turns into jank's own
  `clojure.core/+` because the static's value IS that fn (`boot.cljc:169`, `shim.cljc:743`); jank's
  analyzer then inlines it to `cpp/jank.runtime.add`. A static whose value is a wrapper fn is
  not aliasable: `Numbers-minus` is `(fn ([x] (- x)) ([x y] (- x y)))` (`shim.cljc:743`), so
  `(- n 2)` is a cross-namespace dynamic call before jank's `-`.
- A `defn`'s recursion is a direct call (the fn name is in `*locals*`, `rewrite.cljc:868`; jank
  lowers named recursion directly). The stock `defn` emits `(def name (fn ...))` with no
  per-call wrapper (`core.clj:294-336`); macros are wrapped once at boot (`boot.cljc:150`).
- Every `RT-count`/`RT-nth`/`RT-conj`/`assoc` guards for a cohost type instance through
  `h-cohost-inst?`, a jank fn in the adapter namespace calling `cpp/cohost_obj.is_cohost`
  (`shim.cljc:345`, `:615`, `jank_host.jank:1302`): one cross-namespace call per collection
  op. `host-object?` computes `(type x)` (a name string on jank) and looks it up in an
  atom-held map per `(.m x ...)` call (`shim.cljc:2194`, `:2209`).
- jank has no `defprotocol`/`defrecord`/`deftype`/`reify` (core.jank line 7261 commented;
  no `deftype*`/`reify*` special forms at HEAD). cohost's type layer is one `cpp/raw` object
  type with a descriptor map (`jank_host.jank:316`); a protocol method is a variadic
  `(fn [& args] (protocol-dispatch P 'm args))` (`:860`) that finds the impl per call
  (`protocol-impl`: `@(:impls p)`, `type-key`, up to four `get`/`get-in`, a supers walk, `:694`)
  and `apply`s it (`:715`); a record method rebinds every field through `inst-field` calls
  (`:756`). The stock `defmulti` boots stock: `mf-method` is an atom deref, three `identical?`
  checks and a cache find per call (`shim.cljc:1139`).
- jank's `:natives` map is empty (`jank_host.jank` has no `:natives`; `adapter.cljc:344`
  default `{}`); the JVM has 66, jolt 63. Lane M's M-5 (Step 4) is the natives order.

The probe. `bash dev/research/run-cohost-jank.sh dev/research/p6_cohost_fib.clj` (the stock
core booted on jank, program through `--eval`; no `-O` as the bb tasks do, then `JANK_O=3`),
the JVM twin, and raw jank from P1 for the same source:

| case | cohost on jank, no `-O` (ns) | cohost on jank, `-O3` (ns) | JVM (ns) | raw jank JIT `-O3` / AOT (P1) |
|---|---:|---:|---:|---:|
| fib-stock (the suite's fib: stock `<` `+` `dec` `-`, stock `defn`) | 245827372 | 38481897 | 13354515 | 13479580 / 6101027 |
| fib-host-ops (jank's own `clojure.core/<` `+` `dec` `-`) | 268095089 | 29816492 | 13536284 | |
| fib-fn-def (`(def f (fn f [n] ...))`, stock ops) | 156045648 | 20871741 | 8525867 | |
| fib-host-ops-fn-def (both) | 175224337 | 20571980 | 11927258 | |
| loop-stock-5e6 (5e6 `inc` + `<`, no call) | 53033170 | 82 (folded) | 220 (folded) | 19843686 (P2 loop-only) |
| loop-host-ops-5e6 | 60345513 | 112 (folded) | 161 (folded) | |

Reading: `-O3` on the JIT is 6.4x on cohost's fib (245827372 -> 38481897 ns), 7.5x with
`def`+`fn`; at `-O3` the stock `inc`/`<` loop folds to a constant exactly as raw jank's does
(the stock arithmetic is jank's own after aliasing and inlining). With `-O3`, cohost's fib is
2.9x the JVM's and 2.9x raw jank JIT's; written as `(def f (fn ...))` it is 1.56x the JVM's.
The stock `defn` shape costs 1.84x over `def`+`fn` (38481897 vs 20871741) and the stock ops
1.3x over jank's own inside a `defn` (38481897 vs 29816492) but nothing inside a `def`+`fn`
(20871741 vs 20571980). The generated C++ (`JANK_PRINT_CODEGEN=1 jank ... -O3 run-main
cohost.run -- --eval ...`, §8.3) says why: the stock `defn` reaches jank as an unnamed fn
(`cohost_user$fn_1_1`), so each recursive `(fib-stock ...)` is a var deref plus a dynamic
`.call` (`v11 = var->deref(); v11.call(v10)`, twice per invocation), and `(- n 2)` is a third
var deref and dynamic call (the `Numbers-minus` wrapper); the `(def f (fn f ...))` shape keeps
its name and recurses by direct call (`cohost_user$fib_fn_def_9_1(fib_fn_def, v10)`). On raw
jank, `defn` names the fn (core.jank line 778), so raw jank already has the direct shape.

### S3-1. Compile what cohost evaluates at `-O3`: the JIT flag now, the snapshot next

- **Mechanism.** Add `-O3` to the `run-main cohost.run` command in `bb.edn` (`boot:jank`,
  `bench:jank`), `dev/bench_programs.clj` (`run-jank-cohost`, and `jank run` for the `jank-jit`
  target) and `dev/perf/jank_aot_ensure.clj`'s consumers; every form the codemod hands to
  jank's `eval` is then compiled by clang at `-O3` with the IR passes on. The cost moves to
  compile time: each first-use `jit eval_string` (13-31 per bench case, 12-34% of a case's
  loop, doc/perf/jank.md "Runtime profile") compiles longer, and the 550 core forms do at
  boot; that is exactly what lane D's snapshot (Step 6 stage 2, D-1/D-2) removes by compiling
  the booted core once at `-O3` ahead of time, so the two are one plan: the flag now, the
  snapshot to make it free.
- **Cost attacked.** `-O0` machine code for every stock-core fn and every user fn: the whole
  cohost column of Step 8 (geomean 10.34; fib 23.9x, tak 7.4x, spectral-norm 47.6x, dispatch
  11.2x, doc/PERF.md) and the stock-vs-own bench (geomean x4.09) were measured at `-O0` on the
  stock side while jank's own core is compiled into the binary at `-O3`/`-O2`
  (`CMakeLists.txt:144`, `:951`). The suite's `jank-jit` column is `-O0` too.
- **Expected effect.** Measured on fib: 6.4x (P6). On the rest: unknown until `bb bench:programs
  jank` and `bb bench:jank` are re-run with the flag (about an hour; lane C's suite re-measure
  should carry it). The boot: unknown until `bb boot:jank` is timed with the flag (P6b, queued).
- **Probe.** P6 (done, above); P6b: `boot-ms`/`libs-ms` with and without `-O3`.
- **Semantic risk.** None: the same C++, a different clang level. (`-O3` also turns on jank's
  `hoist_var_derefs`, which skips dynamic vars, `hoist_var_derefs.cpp:42`.)
- **Owner.** cohost: lane C (the bench commands) and lane D (the boot command and the snapshot).

### S3-2. Close the emission gaps: aliasable statics for every host op, and the `defn` shape

- **Mechanism.** (a) Every shim static that wraps a host op with the host's own semantics
  becomes the host fn itself so `host-aliases` fires and jank inlines it: `Numbers-minus`
  (`shim.cljc:743`) and its kin (`Numbers-multiply`, `Numbers-quotient`, `Util-equiv`'s arms,
  the `RT-*` that are plain `nth`/`count` on jank when no cohost instance is involved), either
  as `(def Numbers-minus -)` where the arities match, or by a jank-only `:inline` on the shim
  static that emits `(clojure.core/- x y)` for arity 2 (the codemod already carries
  `:rewrite-inline?`). (b) The cohost-instance guard as an inline tag check in the emitted
  form (`cpp/cohost_obj.is_cohost x` is a 1.6 ns `cpp/raw` call, P2; the guard today is a var
  call into the adapter namespace, 9-11 ns, plus the descriptor lookup) and `host-object?`
  keyed by object type integer instead of the `(type x)` string. (c) The fn's name: emit
  `(def name (fn* name ...))` for a `defn` on jank, as jank's own `defn` does, so
  self-recursion is a direct call instead of a var deref and dynamic call per recursive call
  (the C++ in §8.3; P6: 1.84x on fib). The dropped "plain defn" shortcut of doc/perf/jank.md
  "Codemod" was about boot time; this is about the emitted fn's shape.
- **Cost attacked.** fib-stock 38481897 against fib-fn-def 20871741 and fib-host-ops 29816492
  at `-O3` (P6); lane M's ~100 ns per entry (M-6); the collection-op guards on every
  `RT-count`/`RT-nth`/`RT-conj` (`shim.cljc:606-615`), i.e. the transient-vector, into,
  group-by, frequencies, set-build cases (x11-x19 stock vs own, doc/PERF.md "jank baseline")
  and json/k-nucleotide/pipeline in the yardstick.
- **Expected effect.** fib: up to 1.84x (P6, the `def`+`fn` shape); the collection cases:
  unknown until the guard is emitted inline and `bb bench:jank` re-run (lane M's Step 3 items
  measured by the bench's `xN` column).
- **Probe.** P6 (done); the codegen dump of the two fib shapes (done, §8.3);
  P12 (an hour): `RT-nth` with the guard inline as `cpp/cohost_obj.is_cohost` against the
  shipped shape, 5e6 calls.
- **Semantic risk.** The statics must keep the JVM's semantics where jank's op differs
  (`-` on a non-number, `/` on integers has no ratios on jank: keep the shim for those arms);
  the guard must still route cohost instances to their descriptor. Naming the fn makes an
  in-flight recursion ignore a redefinition of the var (`with-redefs` of `fib` while `fib`
  runs): the JVM goes through the var, raw jank already does not; a battery row should pin
  which one cohost on jank promises.
- **Owner.** cohost, lane M (shim and adapter, Step 3/4 territory).

### S3-3. Dispatch on jank: fixed-arity protocol methods with an inline cache, record fields by index, a multimethod cache

- **Mechanism.** `defprotocol` on jank emits, per method and per declared arglist, a
  fixed-arity fn `(fn ([this] ...) ([this a] ...))` whose body is: read the instance's
  descriptor once (`cpp/cohost_obj.descriptor_of`, a `cpp/raw` call), compare it with a
  per-method cache var holding the last descriptor and its impl (one `identical?`), and call
  the impl directly; on a miss run today's `protocol-impl` and refill the cache. Record
  method bodies read fields by index through one `cpp/raw` accessor instead of the
  `inst-field` var calls (`jank_host.jank:756`). The stock multimethod gets a one-entry
  cache of (dispatch value -> method) in the shim's `MultiFn` ahead of `mf-method`'s atom
  deref and cache find (`shim.cljc:1139`). jank's own `multi_function` does two dynamic
  calls plus a hierarchy deref and a HAMT get per call (`multi_function.cpp:33`, `:247`), so
  the same one-entry cache is the upstream twin.
- **Cost attacked.** dispatch under cohost on jank 24320671 ns for 20000 iterations, 1.2 us
  per protocol call + multimethod call + arithmetic, against the JVM's 2168401 (11.2x); the
  protocol bench case 163.10 ms against the JVM's 0.81 ms (about 200x) (doc/PERF.md); the
  variadic entry alone is 108 ns per call and a multimethod call 61 ns on raw jank (P2).
- **Expected effect.** The protocol call falls from a variadic call + `apply` + 4-6 lookups
  to a fixed-arity call + one `cpp/raw` read + one `identical?`: the order of P2's
  `call-var-same-ns` (9-11 ns) plus the impl's own call, against today's microseconds; the
  exact figure is unknown until P11.
- **Probe.** P11 (an hour): under cohost on jank, a protocol method called 1e6 times as
  shipped, against a hand-written fixed-arity fn that reads the descriptor through
  `cpp/cohost_obj.descriptor_of` and calls the impl from a cached pair.
- **Semantic risk.** `extend`/`extend-type` after the cache is filled must invalidate it
  (bump a protocol generation counter the cache compares); `satisfies?`, `extends?`, the
  `Object`/`nil` fallbacks and supers order stay in the slow path.
- **Owner.** cohost, lane M (the adapter's type layer, `:cohost-types`); the multimethod
  cache is a shim change on every host (byte-identical behavior, a shared-layer regression
  run) or a `:jank` arm.

## 5. The ranking

The nine, ranked by expected gain on the Step 8 programs against the JVM, breadth across
them, cost, and semantic risk. "Measured" means a probe above; "bounded" means the probe gives
the ceiling; "unknown" means only the named probe can say.

| rank | idea | gain on the yardstick | breadth | cost | semantic risk |
|---:|---|---|---|---|---|
| 1 | S3-1 `-O3` for what cohost evaluates (then the snapshot) | measured 6.4x on cohost's fib (P6); the whole cohost column was measured at `-O0` | every program on cohost; the `jank-jit` column too | one line per bb task; the snapshot is lane D's running order | none |
| 2 | S2-3 the pathological leaves (sorted-walk O(n^2), `=` not inlined, uncached keyword hash, array-map `assoc` demotion, `apply str`, string `nth`, `truthy`; the i32 `add` arm as a correctness blocker) | measured per leaf: sort walk 1000x, `apply str` 19x, string `nth` 11x, `=` a var call per test | json, k-nucleotide, pipeline, dispatch, every `(= i n)` loop; the stock core's worst bench cases | each a small upstream change | none (same results) |
| 3 | S2-2 allocation-free seq walks, internal reduce, chunked ranges and vector seqs | measured gaps: lazy map/filter 49x, reduce over a vector 10.5x, transduce 6x, into 3.8x (P5); reachable share unknown until P10 | pipeline, json, nbody, k-nucleotide's `gen`, every `reduce`/`map`/`filter` | upstream, medium (the in-place branch exists; `reduce-kv` is the model) | chunk granularity, as Clojure |
| 4 | S2-1 small vectors and the collector's settings | measured 25x on binary-trees at depth 14 and 25x on two-element vectors (P3); the env alone 1.43x | binary-trees, pipeline, json, nbody; every allocating program | upstream: settings small, the vector representation medium | none |
| 5 | S3-2 cohost's emission gaps: the fn's name, aliasable statics, the inline guard | measured 1.84x on fib (`def`+`fn` vs `defn`, P6); the guard's share unknown until P12 | every cohost program with recursion or collection ops | cohost, lane M, small | redefinition during recursion (pin it) |
| 6 | S1-1 native numeric locals and returns by type propagation | bounded 1.67x on fib (C++ floor, P1) and 8-10x on carried loops (H0); the hinted variants of 8 programs | the numeric half of the suite | upstream, large (analyzer + codegen) | overflow and the 32-bit `int` typing: fix first |
| 7 | S3-3 dispatch: fixed-arity protocol methods with an inline cache, record fields by index, a multimethod cache | dispatch 11.2x today; the entry alone 108 ns + `apply` + lookups (P2, P11) | one yardstick program (and the protocol/multimethod bench cases) | cohost, lane M, medium | `extend` after caching (a generation counter) |
| 8 | S1-2 direct var calls and fixed-arity variadic entries | bounded: (9.3 - 1.6) ns per cross-var call, ~100 ns per variadic call (P2); self-recursion is already direct | json's helper calls, pipeline's `assoc`/`update-in` | upstream, medium | redefinition reaching call sites (a guard flag) |
| 9 | S1-3 LTO across modules and into the runtime, then PGO | unknown: `-O3` already gives 7-20x per TU (P9); the cross-object part is unmeasured | AOT-only | needs a source build of jank (no bitcode shipped; LTO is disabled upstream for the JIT's symbol lookup, `CMakeLists.txt:18`) | none |

Why this order. 1 is measured, broad and nearly free, and it is the condition under which every
other number in the cohost column should be re-read. 2 is a list of small, certain fixes with
very large local ratios, several of which sit on the yardstick's hot paths (`=` in every loop
test; `nth` on strings in k-nucleotide and json; the sorted-walk bug in any program that
sorts). 3 and 4 are the two structural runtime gaps the probes found largest (49x and 25x); 3
ranks above 4 because its first step is finishing an existing branch and because `reduce`,
`map` and `filter` touch more programs than two-element vectors do. 5 is cohost's own, small,
measured. 6 has the largest numeric upside but is the costliest and the only one whose
semantics need a decision (overflow) and a prerequisite fix (the `int` typing), so it closes
the six. 7 to 9 are below the line for the reasons in §7.

## 6. The plan: the top six

### 1. `-O3` for everything cohost evaluates, then the snapshot (S3-1)

- **Hypothesis.** The cohost column of Step 8 is mostly `-O0` machine code; compiling the
  booted core and the user program at `-O3` closes most of the gap to raw jank JIT.
- **Probe and result so far.** P6 (`dev/research/p6_cohost_fib.clj` under
  `run-cohost-jank.sh`): fib-stock 245827372 ns without `-O`, 38481897 with `-O3` (6.4x); the
  5e6-iteration stock loop folds to a constant at `-O3` exactly as raw jank's. P6b
  (`p6b_boot_O.sh`): the boot's wall time with and without the flag, §8.4.
- **Implementation sketch.** Add `-O3` to the `run-main cohost.run` command in `bb.edn`
  (`boot:jank`, `bench:jank`, `boot:jank-aot-profile`), to `run-jank-cohost` and `run-jank-jit`
  in `dev/bench_programs.clj` (the `jank-jit` column is `-O0` today too), and to the
  `cohost` CLI's jank target. Then lane D's snapshot compiles the booted core at `-O3` once,
  so a fresh process pays neither the boot's JIT nor the 13-31 first-use compiles per program
  (doc/perf/jank.md "Runtime profile").
- **Done-bar.** `bb bench:programs jank` on the merged branch prints a cohost/JVM geomean
  below the first pass's 10.34 with every row that ran still `SAME`; `bb bench:jank` prints no
  case worse than before; `bb boot:jank` keeps 542/171/78/0, battery 143/145, libraries 61/61,
  and its `boot-ms` + `libs-ms` is recorded with the flag (P6b's cost) and after the snapshot.
- **Owner.** cohost: lane C (bench commands, the re-measure), lane D (boot command, snapshot).

### 2. The pathological leaves (S2-3)

- **Hypothesis.** A handful of local defects in the runtime and core.jank account for the
  largest raw-jank-vs-JVM ratios, and each is a small change with no semantic exposure.
- **Probe and result so far.** P5/P5b/P5c: sort of 1e5 is 18 ms, walking its result is 15-35 s
  (`native_vector_sequence::next` copies the vector); `apply str` 19x, string `nth` 11x,
  `conj` 5x; P4: keyword-keyed `get` on a 1,000-key map 3.8x, `assoc` on a small map 4.1x;
  P2: `=` is a var call (core.jank line 659), a variadic call 108 ns; P5: `(reduce + (range
  1000000))` = `1783293664` (the i32 `add` arm, `math.hpp:86`).
- **Implementation sketch.** Upstream, one change each: share the buffer in
  `native_vector_sequence::next` (and `array_chunk::chunk_next`); uncomment `=`'s `:inline`
  (and fix `assoc`'s `:inline-arities #{2}` which never matches its 3-arity, core.jank line
  467); cache keyword and symbol hashes (or route `hash::string` through
  `immutable_string::to_hash`); check for an existing key before demoting a full array map;
  one-pass `str`/`apply`; an `nth(object_ref, i64)` overload and a direct virtual `nth`;
  `truthy` as two word compares; compute the small+small `add` arm in `i64` with the overflow
  check `inc` already has.
- **Done-bar.** On the patched jank, `run-jank.sh p5c_sort_walk.jank` prints
  `sort-nth-50k-of-1e5` and `sort-reduce-1e5` within 3x of `sort-first-1e5`; `p5_seqs`'s
  `apply-str-1e4` and `string-nth-1e6` within 3x of the JVM twin; `p4_maps`'s `get-1000-1e6`
  within 2x; `(reduce + (range 1000000))` prints `499999500000`; the Step 8 rows for json,
  k-nucleotide and pipeline on jank-aot move down; the bench's sort/sort-by/str-build cases
  stop being the stock core's worst.
- **Owner.** Upstream jank (the drafts go under `doc/upstream/` like jank-01..10; the sorted
  walk and the i32 wrap are the first two).

### 3. Allocation-free seq walks, internal reduce, chunks (S2-2)

- **Hypothesis.** The seq machinery's per-element allocation and locking, not the user's
  code, is what makes `map`/`filter`/`reduce` 4-49x the JVM's; the in-place walk the runtime
  already half-implements removes most of it, internal reduce the rest.
- **Probe and result so far.** P5: lazy-count 1277837517 vs 25912086 ns (49x), reduce-vec
  55472886 vs 5307562 (10.5x), first-next-range 4.4x, transduce 6.1x, into 3.8x; the sources:
  `sequence_range.hpp:169` (in-place branch commented out), `seq.cpp:901` (reduce), `:926`
  (`reduce-kv`'s direct loop), `lazy_sequence.cpp:53` (mutex per step),
  `integer_range.hpp:293` (not chunkable). P10 (next): a `cpp/raw` index loop and a
  hand-chunked `map` against `reduce`/`map` in raw jank, to size the reachable share.
- **Implementation sketch.** Upstream: (1) finish `fresh_sequence_range` and use it in
  `reduce`, `str`, `sort`, `vec`, `apply_to`, `sequence_length`, equality; (2) a
  `visit_object` dispatch at the top of `runtime::reduce` with direct loops for vectors,
  lists, maps, `integer_range`, chunked seqs; (3) a lock-free realized fast path in
  `lazy_sequence` and `next_in_place` for it; (4) chunked `integer_range` and
  `persistent_vector_sequence` so core.jank's existing chunk branches in `map`/`filter` fire.
- **Done-bar.** `run-jank.sh p5_seqs.jank`: `reduce-vec-1e6` within 2x of the JVM twin,
  `lazy-count-1e6` within 5x, `transduce-1e6` and `into-map-1e6` within 2x; Step 8's pipeline
  and json on jank-aot below the JVM; the stock core's lazy-pipeline/transduce/into-vector
  bench cases move with the host side.
- **Owner.** Upstream jank.

### 4. Small vectors and the collector (S2-1)

- **Hypothesis.** A two-element vector costs three GC allocations and a 32-slot leaf; a
  small-vector representation plus a program-sized heap makes allocation-bound programs
  competitive with the JVM's bump allocator.
- **Probe and result so far.** P3: bt-14 218850983 vs 8727705 ns (25x), pair-alloc 2e6 498902205
  vs 20087465 (25x); P3 env: `GC_INITIAL_HEAP_SIZE=1G GC_FREE_SPACE_DIVISOR=1` 174828601 ->
  122085049 (1.43x), incremental 4-6x slower; P3b: a bdwgc allocation 25-40 ns, `[i i]` 282 ns,
  `(cons i nil)` 85 ns, `{:a i}` 122 ns.
- **Implementation sketch.** Upstream: set the heap and divisor in `jank_init` (or honor the
  environment and document it); a `small_vector` object (inline storage to 32, one
  allocation) that `_jank_vec` and `vector` build for <= 32 elements and `conj` grows into the
  immer tree past that, with every vector behavior; pointer-free allocation for leaf boxes
  that hold no references; typed descriptors for the hot box types so conservative marking
  scans fewer words. cohost can set the environment for its own process at once.
- **Done-bar.** `run-jank.sh p3_gc_alloc.jank`: `pair-alloc-2e6` within 3x of the JVM twin and
  `bt-14` within 3x; Step 8's binary-trees on jank-aot below the JVM at depth 8 and at depth
  14 (add the depth to the suite); no battery row changes.
- **Owner.** Upstream jank (the environment knobs: cohost's bb tasks, at once).

### 5. cohost's emission gaps (S3-2)

- **Hypothesis.** Three shapes the codemod emits on jank cost a dynamic call where raw jank
  has a direct one: an unnamed `defn` (recursion through the var), wrapper statics
  (`Numbers-minus`), and the cohost-instance guard as a cross-namespace call.
- **Probe and result so far.** P6 at `-O3`: fib-stock 38481897, fib-fn-def 20871741 (1.84x),
  fib-host-ops 29816492; the C++ in §8.3 shows the var derefs. P12 (next): `RT-nth` with the
  guard inline, 5e6 calls.
- **Implementation sketch.** In `rewrite.cljc`'s `defn`/`def`+`fn*` handling on jank, carry
  the name onto the `fn*`; in `shim.cljc`, make every static whose jank twin has the JVM's
  semantics the twin itself (`Numbers-minus` -> `-`, and the rest of the arithmetic family)
  so `host-aliases` fires; emit `cpp/cohost_obj.is_cohost` inline in the `RT-*` arms (a
  `:jank` branch) and key `host-object?` by the object-type integer. A battery row for
  redefinition during recursion.
- **Done-bar.** `JANK_O=3 run-cohost-jank.sh p6_cohost_fib.clj`: fib-stock within 1.1x of
  fib-fn-def; `bb bench:jank`'s transient-vector, into-vector, set-build, group-by,
  frequencies cases move toward x1.5 (jank-FAST's Step 4 done-bar); both batteries unchanged;
  the all-host regression unchanged for the shared `rewrite.cljc`/`shim.cljc` edits.
- **Owner.** cohost, lane M.

### 6. Native numeric locals and returns (S1-1)

- **Hypothesis.** With local type inference and a native entry per typed arity, jank's
  numeric programs run at the C++ floor, which P1 puts 1.67x below the boxed fn for fib and
  H0 puts 8-10x below for carried loops; the hinted half of the suite then beats the JVM's
  hinted rows instead of losing to them.
- **Probe and result so far.** P1: fib-cpp-native 3663884 vs fib-var 6101027 (AOT) vs
  fib-hinted-leaf 17595281; H0: 0.105-0.125 for carried loops, 1.24-1.52 for hinted leaves;
  the analyzer facts in §2 (tail forced boxed, `:rettag` unread, `if` forces boxing,
  `needs_box` unread by codegen). P7 (next): hand-written native entries for tak and nbody's
  `advance` through `cpp/raw`, the ceiling for those two programs.
- **Implementation sketch.** Upstream, in order: type integer literals `i64` (not `int`)
  and compute the small+small arm in `i64`; let `if` keep a native type when both branches
  have it; honor `:rettag`/infer the tail type and emit a second native entry per typed
  arity used by named recursion and typed call sites; keep the boxing entry for dynamic
  callers; overflow checks on the native arms (`inc` already has one). cohost's H1 already
  passes `^long`/`^double` through for looping arities; it would then pass them for leaves
  too (the H0 leaf loss disappears with a native return).
- **Done-bar.** `run-jank.sh p1_fib_floor.jank`: fib-hinted-leaf within 1.2x of
  fib-cpp-native; H0's called-leaf ratios below 1; Step 8's hinted rows on jank-aot below
  their unhinted rows and below the JVM's hinted rows for fib, tak, nbody, spectral-norm,
  mandelbrot; `(reduce + (range 1000000))` still `499999500000`; overflow on `+` of two
  longs throws as on the JVM (a new battery row).
- **Owner.** Upstream jank (analyzer and codegen); cohost widens H1 to leaves when it lands.

## 7. The other three

- **S3-3, dispatch on jank** (rank 7): it attacks one yardstick program (dispatch, 11.2x) and
  two bench cases; the fix is cohost-only and medium-sized, and jank itself has no protocols
  to improve, so it ranked below the broad upstream items. It stays the next cohost item
  after S3-2 (P11 under `dev/research/` measures the shipped shape).
- **S1-2, direct var calls and fixed-arity variadic entries** (rank 8): bounded by P2 at about
  8 ns per cross-var call and about 100 ns per variadic call, self-recursion is already direct,
  and the redefinition semantics need a guard design; the variadic part reappears inside S2-3
  (`list`, `assoc`, `update-in`) where it is cheaper to fix locally.
- **S1-3, LTO and PGO across the module graph** (rank 9): P9 showed clang already delivers
  7-20x per translation unit, the cross-object share is unmeasured, the shipped package has
  no bitcode, upstream disables LTO for the JIT's symbol lookup, and the gain would be
  AOT-only.

## 8. Appendix: the generated C++ the sessions cite

### 8.1 Raw jank, `jank compile-module research.cg -O3 --output-target cpp`

Source: `(defn fib [n] (if (< n 2) n (+ (fib (dec n)) (fib (- n 2)))))`,
`(defn sum-to [n] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc i)) acc)))`,
`(defn kw [m] (:a m))`, `(defn call-other [x] (research.cg/fib x))`. The bodies (the `#line`
directives removed):

```
extern "C" jank::runtime::object_ref research_cg$fib_16_1(jank::runtime::object_ref const fib, jank::runtime::object_ref n) {
auto v1(const_32);
auto &&v2(jank::runtime::lt(n, v1));
jank::runtime::oref<jank::runtime::object> v6{ };
if(v2){ v6 = std::move(n); } else {
auto &&v10(jank::runtime::dec(n));
auto const v11(research_cg$fib_16_1(fib, v10));
auto v12(const_32);
auto &&v13(jank::runtime::sub(n, v12));
auto const v14(research_cg$fib_16_1(fib, v13));
auto &&v15(jank::runtime::add(v11, v14));
v6 = std::move(v15); }
return v6; }

extern "C" jank::runtime::object_ref research_cg$sum_to_17_1(jank::runtime::object_ref const sum_to, jank::runtime::object_ref n) {
{ auto v2(const_33); auto v3(const_33);
jank::runtime::object_ref v4{ };
auto s5(v2); auto s6(v3);
while(true){
auto v10(s5); auto v11(s6);
auto &&v12(jank::runtime::lt(v10, n));
jank::runtime::object_ref v16{ };
if(v12){
auto &&v18(jank::runtime::inc(v10));
int v19 (static_cast<int>(v18));
auto &&v20(jank::runtime::add(v11, v10));
s5 = v19; s6 = v20; continue;
} else {
auto const v24(jank::runtime::convert<int>::into_object(v11));
v16 = std::move(v24); }
return v16; break; } } }

extern "C" jank::runtime::object_ref research_cg$kw_20_1(jank::runtime::object_ref const kw, jank::runtime::object_ref m) {
auto const v1(const_34);
auto &&v2(jank::runtime::get(m, v1));
return v2; }

extern "C" jank::runtime::object_ref research_cg$call_other_21_1(jank::runtime::object_ref const call_other, jank::runtime::object_ref x) {
auto const v1(research_cg_SLASH_fib_35->deref());
auto const v2(v1.call( x));
return v2; }
```

### 8.2 The probe commands

```
bash dev/research/run-jank.sh dev/research/p1_fib_floor.jank        # P1, JIT -O3 and AOT -O3
JANK_O=0 bash dev/research/run-jank.sh dev/research/p1_fib_floor.jank   # P9
clojure -M dev/research/run-jvm.clj dev/research/p1_fib_floor.clj
bash dev/research/queue.sh p1_fib_floor p2_call_path p3_gc_alloc p4_maps p5_seqs p5b_sort p5c_sort_walk p3b_alloc_kinds
bash dev/research/p3_gc_env.sh
bash dev/research/run-cohost-jank.sh dev/research/p6_cohost_fib.clj     # cohost on jank, no -O
JANK_O=3 bash dev/research/run-cohost-jank.sh dev/research/p6_cohost_fib.clj
bash dev/research/p6b_boot_O.sh
JANK_O=3 bash dev/research/run-cohost-jank.sh dev/research/p11_protocol.clj
```

### 8.3 Cohost on jank, `JANK_PRINT_CODEGEN=1`, the two fib shapes at `-O3`

`(defn fib-stock [n] ...)` through the stock core:

```
extern "C" jank::runtime::object_ref
cohost_user$fn_1_1(jank::runtime::object_ref const cohost_user$fn_1, jank::runtime::object_ref n)
{
  auto v1(2);
  auto &&v2(jank::runtime::lt(n, v1));
  jank::runtime::oref<jank::runtime::object> v6{};
  if(v2) { v6 = std::move(n); }
  else
  {
    auto &&v10(jank::runtime::dec(n));
    auto const v11(jank::runtime::var_ref{ reinterpret_cast<jank::runtime::var *>(0x7fad6131c600) }->deref());
    auto const v12(v11.call(v10));
    auto const v13(jank::runtime::obj::small_integer_ref{ 2 });
    auto const v14(jank::runtime::var_ref{ reinterpret_cast<jank::runtime::var *>(0x7fad68801680) }->deref());
    auto const v15(v14.call(n, v13));
    auto const v17(v11.call(v15));
    auto &&v18(jank::runtime::add(v12, v17));
    v6 = std::move(v18);
  }
  return v6;
}
```

`(def fib-fn-def (fn fib-fn-def [n] ...))` through the stock core:

```
extern "C" jank::runtime::object_ref
cohost_user$fib_fn_def_9_1(jank::runtime::object_ref const fib_fn_def, jank::runtime::object_ref n)
{ ...
    auto const v11(cohost_user$fib_fn_def_9_1(fib_fn_def, v10));
  ...
    auto const v15(cohost_user$fib_fn_def_9_1(fib_fn_def, v14));
```

`v11` is the var `fib-stock` (deref'd once, called twice); `v14` is the `cohost.shim/Numbers-minus`
wrapper. `<`, `dec`, `+` reached jank's inlined runtime ops in both shapes.
