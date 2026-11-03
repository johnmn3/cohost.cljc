# jank-MAX: cohosted Clojure on jank faster than the JVM (impl plan, 2026-11-02 ET)

The research is `doc/research/jank-faster-than-jvm.md` (R-1) and the eleven upstream drafts
`doc/upstream/jank-11..21` (R-2). This plan turns the six recommendations into lanes a fleet
of agents can run in parallel. Coordination: `coord/cohost-max/` in johnmn3/atom.broker
(protocol, board, orders, reports). Base: `claude/youthful-euler-gquikf @ c499930`.

## 0. The goal and the scoreboard

Goal: `bb bench:programs jank` (cohost's stock core booted on jank, every Step 8 program)
below 1.0 against JVM Clojure 1.12 on the geometric mean, with every row `SAME`. The first
pass measured 10.34 (doc/PERF.md); jank's own core AOT measured 0.71, which is the floor
cohost can approach, and the upstream lanes move that floor too.

| measure | command | now | target |
|---|---|---:|---:|
| programs, cohost on jank / JVM, geomean | `bb bench:programs jank` and `jvm` (lane C's method: -O3, median of 5, depth-14 binary-trees, the overflow sentinel) | 15.213 over 16 (UNPATCHED apt, X1-5); 6.648 over 20 (PATCHED, 18 patches, X1-4) | < 1.0 |
| programs, jank's own core / JVM, geomean | `bb bench:programs jank-aot` | 7.095 over 14 (UNPATCHED); 1.719 over 17 (PATCHED) | < 0.5 |
| stock core / jank's own core, 30 bench cases, geomean | `bb bench:jank` | x1.77 UNPATCHED apt, x2.12 PATCHED (X1-3) | < x1.5, no case above x1.5 |
| startup | `boot-ms` + `libs-ms` from the snapshot; `first-output` | snapshot boot 456 ms on the coordinator's box | within jolt's range (2.5 s boot) |
| correctness | `bb boot:jank` battery and libraries; every other host's batteries; the jolt corpus | 157/159, 62/62 (max/collate, 2026-11-02); others unchanged | never lower |
| the upstream repros | `bash dev/research/upstream/run.sh <NN>` on the patched jank | jank-11..21 REPRODUCED | each draft's "Expected" |

RULING 2026-11-02 6:2x PM ET: the headline is the UNPATCHED column; the patched column is shown beside it; no PRs to jank. The first pass's 0.71 was another box with the JVM warm.

Every number in a report is from a command run that session, quoted as printed. An estimate is
never a number in a report. Every order names its expected scoreboard delta with its basis (a
probe in the research doc, or "unknown until <command>").

## 1. How the fleet is laid out

Two kinds of lane, two code bases, one board.

- **U lanes change jank** (the compiler and runtime). They work on a clone of jank-lang/jank at
  the pinned sha `f3d82d65` and deliver **patches** into cohost's repo under
  `upstream/jank/patches/` (one `NN-name.patch` per draft, a `series` file in apply order),
  with the draft's repro run before and after on the patched build. Lane U0 builds that jank
  from source and gives every other lane the build script and the `JANK_BIN` knob, so patches
  are measured on the real cohost benches. If the user forks jank-lang/jank, the same patches
  become branches `max/<lane>` there for filing; the patch files stay the record either way.
- **X lanes change cohost** (the shared layer, the jank adapter, the bench). Every shared-layer
  change ships behind a capability with a default that leaves every other host byte-identical,
  and lane X3 then turns it on per host where that host's bench says it pays: that is how the
  gains reach jolt, ClojureScript, basilisp, ClojureWasm, Phel, let-go and the JVM adapter too.
- **The in-flight orders of `coord/cohost/` are part of this plan** and stay where they are:
  D-3 (-O3 on every jank command), D-4 (GC environment), M-5 (natives), M-9 (the fn name,
  aliasable statics), C-1 (the bench method with -O3), R-3 (the dispatch design), R-4 (cohost-side
  workarounds). The board below lists them with their plan item so nobody duplicates them; new
  lanes never edit their files.

Rules every lane keeps (from doc/hosts/PLAN.md and jank-FAST.md): no edits under `resources/`;
host facts go to doc/REPORT.md with a battery row, never a weakened row; one unit, one commit,
pushed, with the before/after numbers in the message; a shared-layer change (`src/cohost/*.cljc`,
`bb.edn`) says so in its commit and the coordinator runs every host before merging; long runs
in the background; installs (`dev/install/*.sh`, and U0's `jank-src.sh`) need the user's
approval on each box; everything pushed, nothing only local.

## 2. The lanes

Each lane: what it owns (files no other lane edits), its orders in sequence, the done-bar, the
expected delta with its basis. Orders are dispatched in `coord/cohost-max/orders/<lane>.md`.

### U0, jank-build (wave 0, the critical path of every U lane)

- Owns: `dev/install/jank-src.sh` (new), `upstream/jank/` (the `series`, `README.md`, the
  pinned sha), the `JANK_BIN` knob in `bb.edn`'s jank tasks, `dev/bench_programs.clj`'s jank
  targets, `dev/research/run-jank.sh`, `run-cohost-jank.sh`, `upstream/run.sh`,
  `dev/perf/jank_aot_ensure.clj` (the stamp must include the binary's version, it already does).
- U0-1: build jank-lang/jank at `f3d82d65` from source on an agent box (the book's build page;
  record every dependency and the wall time), install under `/usr/local/lib/jank-dev`, and make
  every cohost jank command take `JANK_BIN` (default `jank`). Apply `upstream/jank/patches/series`
  in the build. Done-bar: `JANK_BIN=/usr/local/lib/jank-dev/bin/jank bb boot:jank` prints the
  same three lines as the apt binary; `bash dev/research/upstream/run.sh 12-i32-add` runs on it;
  the build's wall time and disk in `upstream/jank/README.md`. Delta: none; it unblocks U1-U7.
- U0-2: a one-command re-build-with-patches (`bash dev/install/jank-src.sh --patches`) that
  other lanes run after pushing a patch, with its price recorded.

### U1, numbers (wave 0)

- Owns (in the patch): `include/cpp/jank/runtime/core/math.hpp` (the small+small arms of add,
  sub, mul, and the comparison arms), `src/cpp/jank/analyze/cpp_util.cpp` (integer literal
  typing), `src/cpp/jank/analyze/processor.cpp` (loop/let binding types from literals).
- U1-1: jank-12 and jank-21: compute the small-integer arms in `i64`; type integer literals and
  literal-initialized `let`/`loop` locals as `i64`, never `int`; `+`, `-`, `*` throw on `i64`
  overflow as `inc` does (Clojure's semantics; `+'` promotes, unchanged). Done-bar: the two
  repros print their "Expected" lines; `run.sh 20-native-returns`'s fib numbers are not worse
  than before (the arm must stay inlined); cohost's battery and libraries unchanged on the
  patched build. Delta: correctness; every numeric lane depends on it.

### U2, leaves (wave 0)

- Owns (in the patch): `src/cpp/jank/runtime/obj/native_vector_sequence.{hpp,cpp}`,
  `obj/array_chunk.cpp`, `src/jank/clojure/core.jank` (the `:inline` metadata of `=` and
  `assoc` only), `obj/keyword.{hpp,cpp}`, `obj/symbol.{hpp,cpp}`, `hash.cpp`.
- U2-1: jank-11: `native_vector_sequence::next` and `array_chunk::chunk_next` share the buffer.
  Done-bar: `run.sh 11-sorted-walk` prints `sort-nth-half` and `sort-reduce` within 3x of
  `sort-first` at every size. Delta: the sort/sort-by bench cases from x15/x27 toward x1
  (doc/PERF.md "jank baseline"); any program that sorts.
- U2-2: jank-13: uncomment `=`'s `:inline`; fix `assoc`'s `:inline-arities`. Done-bar:
  `run.sh 13-eq-inline` prints `loop-with-=` within 1.2x of `loop-with-==`; `assoc` of three
  args reaches `cpp/jank.runtime.assoc` (the C++ dump shows no var deref). Delta: 13 ns per `=`
  test (jank-13), in every loop of binary-trees, k-nucleotide, json, dispatch.
- U2-3: jank-14: cache the keyword's and symbol's hash. Done-bar: `run.sh 14-keyword-hash`
  prints the long-keyword `get` within 1.1x of the short one. Delta: P4's `get-1000` 3.8x and
  `hash-map-grow` 3.5x move; pipeline, json.

### U3, walks (wave 0, then U4 in the same lane)

- Owns (in the patch): `include/cpp/jank/runtime/sequence_range.hpp`,
  `src/cpp/jank/runtime/sequence_range.cpp`, the walkers in `runtime/core/seq.cpp` (`reduce`,
  `str`, `sort`, `vec`, `sequence_length`, equality; `apply_to` in `core/call.cpp`),
  `obj/lazy_sequence.{hpp,cpp}`, `obj/range.cpp`.
- U3-1: jank-15 part one, jank-16: finish `fresh_sequence_range` (`fresh_seq` once, then
  `next_in_place`) and use it in every runtime walker; `lazy_sequence` takes no lock once
  realized and gains `next_in_place`; `range` the same. Done-bar: `run.sh 15-reduce-walk` prints
  `reduce + over 1e6 vector` within 1.5x of `reduce-kv`; `run.sh 16-lazy-mutex` prints `first on
  a realized lazy seq` within 1.5x of `first on a list`. Delta: P10 (reduce 50-77 ms against a
  27-33 ms indexed loop).
- U4-1 (same lane, after U3-1): jank-15 part two, jank-17: internal reduce per collection in
  `runtime::reduce` (vector, list, maps, `integer_range`, chunked seqs), `chunkable` on
  `integer_range` and vector seqs so core.jank's `map`/`filter` chunk branches fire. Done-bar:
  `run.sh 17-range-chunks` prints `chunked-seq?` true for `(range 10)` and a vector seq, the
  lazy pipeline within 5x of the loop; `run.sh 15-reduce-walk`'s `reduce + v` within 1.5x of
  the `nth` loop. Delta: P10's ceiling (12-18x below today's `reduce`; the lazy pipeline 70-230x
  above the chunked loop); pipeline, json, nbody, k-nucleotide.

### U5, vectors and the collector (wave 0)

- Owns (in the patch): `src/cpp/jank/codegen/api.cpp` (`_jank_vec`), `obj/persistent_vector.*`,
  `obj/transient_vector.*`, a new `obj/small_vector.*` if that is the shape, `src/cpp/jank/c_api.cpp`
  (GC init), `runtime/detail/type.hpp`.
- U5-1: jank-18: a small-vector representation (inline storage to 32, one allocation) for
  literals and `vector` of up to 32 elements, growing into the immer tree past that, with every
  vector behavior (nth, conj, seq, equality, hash, transients, meta). Done-bar: `run.sh
  18-small-vectors` prints `[i i] 2e6` within 3x of `GC_MALLOC(16) 2e6`; binary-trees depth 14
  within 3x of the JVM's 8.7 ms; cohost's batteries unchanged on the patched build. Delta: P3's
  25x on binary-trees and pair-alloc.
- U5-2: jank-19: measured collector defaults (initial heap, divisor, parallel marking kept,
  incremental never default), typed descriptors for the hot box types. Done-bar: the collection
  count of `run.sh 18-small-vectors` under `GC_PRINT_STATS=1` falls, bt-14 does not rise, resident
  memory of `bb boot:jank` within 2x. Delta: unknown until measured (the env knobs moved both
  ways in R-2).

### U6, native numerics (wave 1, after U1-1 lands)

- Owns (in the patch): `src/cpp/jank/analyze/processor.cpp` (fn arity tails, `if`, `let`/`loop`
  typing, `:rettag`), `src/cpp/jank/codegen/cpp_processor.cpp` (a native entry per typed arity),
  `src/cpp/jank/ir/*` as needed.
- U6-1: jank-20: honor or infer a native return type and emit a second native entry per typed
  arity, used by named recursion and typed call sites; native `let`/`loop` locals from typed
  inits; `if` keeps a native type when both branches have it; overflow checked. Done-bar:
  `run.sh 20-native-returns` prints `add-hinted` no slower than `add-plain` and `fib 30 ^long
  param` within 1.5x of the C++ fib; `run-jank.sh p7_native_entry` prints tak with `^long`
  within 1.5x of the C++ tak and the nbody kernel with native locals within 2x of the C++ kernel;
  cohost's H1 battery rows unchanged. Delta: P7 (tak 3.4-4.2x, the double kernel 20-25x); the
  hinted half of the suite.

### U7, calls (wave 2)

- Owns (in the patch): `obj/multi_function.cpp` (a one-entry cache ahead of the HAMT get),
  `runtime/core/truthy.hpp`, `codegen/cpp_processor.cpp` (fixed-arity variadic entries),
  `-Odirect-call` (the analyzer's unused var map).
- U7-1: the multimethod cache and `truthy` as two word compares. Done-bar: P2's `call-multi`
  within 2x of `call-var-same-ns`. U7-2: `-Odirect-call` with a per-var redefinition guard;
  fixed-arity entries for variadic fns at the call-site arity. Done-bar: P2's `call-var-other-ns`
  within 2x of `call-cpp-objref`, `call-variadic` within 3x of `call-2`. Delta: bounded by P2
  (8 ns per cross-var call, 100 ns per variadic call, 55 ns per multimethod call).

### X1, bench and sentinels (wave 0; coordinates with C-1)

- Owns: `bench/programs/` additions only (`binary_trees` at depth 14 as a second size,
  `sum_overflow.clj` whose result exceeds 2^31 so `SAME` catches the i32 wrap, a `sort` program),
  `dev/bench_programs.clj`'s size table, doc/PERF.md's "Programs" section for the new rows.
  C-1 owns the driver's method and the -O3 flag; X1 rebases on C-1's branch.
- X1-1: the three programs on every target, with their JVM results; the suite's table re-run
  on jank-aot and cohost-on-jank. Done-bar: `bb bench:programs jvm` and `jank-aot` print the
  new rows; the sentinel prints `DIFFER` on today's jank (the wrap) and `SAME` on U1's build.

### X2, dispatch (wave 1, after R-3's design and M-9's merge)

- Owns: `src/cohost/hosts/jank_host.jank` (the protocol/record/type section: `defprotocol`,
  `defrecord`, `protocol-dispatch`, `protocol-impl`, `method-fns-form`), `src/cohost/shim.cljc`
  (the `MultiFn` section only, behind capability `:multimethod-cache?`).
- X2-1: R-3's design: fixed-arity protocol method entries with an inline cache keyed by the
  cohost type tag, a generation counter bumped by `extend`; record fields by index. Done-bar:
  `JANK_O=3 run-cohost-jank.sh p11_protocol.clj`: `proto-call-1e6` within 3x of `fn-call-1e6`;
  the dispatch program under cohost within 3x of the JVM; batteries unchanged.
- X2-2: the multimethod one-entry cache in the shim (shared layer, capability default off).
  Done-bar: `multi-call-1e6` within 3x of `fn-call-1e6` on jank; X3 turns it on per host.

### X3, cross-host sweep (wave 1, continuous)

- Owns: the per-host adapter maps' capability keys (`src/cohost/hosts/*.{cljc,jank,...}`, one
  key at a time) and `src/cohost/policy.cljc`'s per-host overrides; never the shared code.
- X3-n: for each shared-layer change that lands behind a capability (the fn name on `defn`, the
  multimethod cache, aliasable statics, natives patterns, the `let`/`loop` shortcut), measure it
  on each other host with `bb bench:<host> --quick` (median of 3) and turn the key on where no
  case gets worse and the geomean improves; run that host's battery and libraries. Done-bar per
  host: the bench table before/after in doc/PERF.md, batteries unchanged. Needs the hosts
  installed (dev/install/*.sh; the user's approval per box). Delta: the multimethod case is x5.0
  on the JVM, x4.2 Node, x3.8 Chromium, x3.7 basilisp today (doc/PERF.md): the first candidate.

### X4, workarounds (wave 1, after R-4)

- Owns: the natives map of the jank adapter (`:natives`, coordinated with M-5's list), new
  `:jank` arms in `src/cohost/shim.cljc` named by R-4, `cpp/raw` helpers in `jank_host.jank`.
- X4-1: implement the workarounds R-4 found that pay (expected: `sort` through a native vector
  into a vector, `reduce` over vectors via `reduce-kv`, `apply str` through one builder), each
  behind the jank adapter only, each measured by `bb bench:jank` and the Step 8 programs it
  touches. They are removed when the matching upstream patch lands (U2, U3).

### Coordinator (this session)

Owns `coord/cohost-max/*` except `reports/`, the integration branch `max/main` in cohost (merged
over `claude/youthful-euler-gquikf` as that moves), and the landing validation: on every DONE,
re-run the done-bar's commands, run every host's battery for a shared-layer change, merge, update
the board, write the next order. The scoreboard commands run on the coordinator's box after each
merge wave.

## 3. Waves and dependencies

```
wave 0 (now, all parallel):  U0 build | U1 numbers | U2 leaves | U3 walks | U5 vectors | X1 sentinels
                             (and, under coord/cohost: D-3 -O3, D-4 GC, M-5 natives, M-9 names, C-1 bench, R-3 design, R-4 workarounds)
wave 1 (as prerequisites land):
   U4 reduce+chunks   <- U3-1 (same lane)
   U6 native numerics <- U1-1 merged (the i64 arms are the semantics it compiles to)
   X2 dispatch        <- R-3 design pushed, M-9 merged (jank_host.jank)
   X3 cross-host      <- each capability-gated change as it merges (M-9's name, X2-2's cache, ...)
   X4 workarounds     <- R-4 pushed
wave 2:                      U7 calls | X3 continues | the snapshot default with -O3 (D side) | LTO/PGO exploration (optional, needs U0)
```

The upstream patches reach cohost's benches only through U0's build; until U0-1 lands, U lanes
deliver the patch, the draft's repro before/after on their own build if they have one, and the
unit evidence (the repro script's numbers), and the coordinator measures on the U0 build.

## 4. What "faster than the JVM" needs, in order of certainty

1. -O3 everywhere and the snapshot (D-3, D-1/D-2): measured 6.4x on fib; turns the 10.34 into a
   number closer to raw jank's. Lands first.
2. The fn name and statics (M-9): 1.84x on fib under -O3.
3. The upstream leaves (U2): sort, `=`, keyword hashes: large local ratios, every program touches
   one of them.
4. The walks and reduce (U3, U4): the collection half of the suite; the ceiling is 12-18x on
   `reduce`.
5. Small vectors (U5): binary-trees 25x, allocation everywhere.
6. Native numerics (U6, after U1): the numeric half; 3-25x ceilings measured.
7. Dispatch (X2): the dispatch program 11x; and the multimethod cache is the first cross-host win.

Expected arithmetic, with the basis: raw jank AOT already sits at 0.71 of the JVM over the ten
programs that ran; cohost's overhead over raw jank JIT at -O3 is 2.9x on fib (P6) and falls to
1.5x with the fn name; U2-U6 move both the floor and cohost. The number that proves it is the
scoreboard's first row on the merged branch, not this paragraph.
