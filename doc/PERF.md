# Performance

This file measures how fast the stock core.clj runs on each host through cohost, in two
ways: against JVM Clojure, and against each host's own hand-written core. It also records
what the perf work changed. Everything was measured on 2026-10-30, US Eastern time, on one
4-CPU box, one host at a time, each commit in its own worktree:

- **Before:** commit `4ce56eb`, from 2:28 to 3:13 PM ET.
- **After:** commit `3e59a25`, the landed perf work, from 3:50 to 4:07 PM ET.

## Summary

All figures are geometric means over the 29 cases.

**Speed-up of the stock core, before over after:**

| JVM | jolt | basilisp (1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---:|---:|---:|---:|---:|
| **×4.4** | **×12.3** | **×2.6** | **×10.3** | **×9.6** |

**cohost's stock core against JVM Clojure's own core, at the same size:**

| | JVM | jolt | basilisp (1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|
| before | ×5.0 | ×46.4 | ×7149 | ×44.3 | ×33.1 |
| after | **×1.1** | **×3.7** | **×2778** | **×4.2** | **×3.4** |

**cohost's stock core against each host's own core, on that host:**

| | JVM | jolt | basilisp (1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|
| before | ×5.0 | ×17.4 | ×5.3 | ×16.2 | ×14.2 |
| after | **×1.1** | **×1.4** | **×2.0** | **×1.4** | **×1.3** |

basilisp's own core is about 1,350× slower than JVM Clojure's on these cases. Its row
against JVM Clojure therefore measures Python more than cohost; its row against its own
core is the one that measures cohost.

**Boot of core.clj:**

| JVM | jolt | basilisp | ClojureScript, Node | ClojureScript, Chromium |
|---:|---:|---:|---:|---:|
| 1.7 s → 1.3 s | 3.4 s → 2.5 s | 178.8 s → 66.4 s | 6.7 s → 6.3 s | 5.7 s → 4.5 s |

**Natives:** a host can opt a stock fn into its own implementation (pass 2, below). Where it
has, the stock core's time for that fn is the host's own. Natives in clojure.core:

| JVM | jolt | basilisp | ClojureScript (Node and Chromium) |
|---:|---:|---:|---:|
| 66 | 63 | 36 | 46 |

**Largest single moves, cohost on that host against JVM Clojure:**

| case | host | before | after |
|---|---|---:|---:|
| `clojure.string/join` | JVM | ×533.5 | ×1.0 |
| `clojure.string/join` | jolt | ×1772.1 | ×4.8 |
| loop/recur arithmetic | ClojureScript, Node | ×512.8 | ×1.6 |
| loop/recur arithmetic | ClojureScript, Chromium | ×320.5 | ×1.1 |
| `re-seq` | JVM | ×149.9 | ×1.0 |
| `sort-by` a key of maps | jolt | ×570.9 | ×4.4 |
| `into []` with a map transducer | ClojureScript, Node | ×274.6 | ×2.2 |

What caused them:
- **Natives:** `clojure.string/join`, `re-seq` and `sort-by` are natives on the JVM, jolt
  and ClojureScript. `into` is one on ClojureScript.
- **Loop arithmetic on ClojureScript** gained from two pass-1 changes:
  - core.clj's `:inline` expansions are applied at rewrite time;
  - host aliases emit a shim static that is the host's own fn as the host's symbol.

**Largest gaps left, the stock core against the host's own core:**

| case | where | after |
|---|---|---:|
| `conj!` into a transient vector | ClojureScript, Node; Chromium | ×16.9; ×11.6 |
| a multimethod call | JVM; Node; Chromium; basilisp | ×5.0; ×4.2; ×3.8; ×3.7 |
| `transduce` | Node; jolt; Chromium | ×4.0; ×3.2; ×3.0 |
| `count`, `filter`, `map` over a range | jolt | ×3.9 |
| `re-seq`; `format`; `sort-by` | basilisp | ×37.4; ×23.2; ×10.5 |

**jolt's `JOLT_TRACE`:** a jolt run with `JOLT_TRACE=0` measured the same as the default
(geometric mean ×1.0; boot 2.5 s → 2.3 s). Its results are in `doc/perf/after-jolt-trace0/`.

## The new hosts (doc/hosts/PLAN.md)

ClojureWasm was measured 2026-10-31 between 12:56 and 1:02 AM ET on commit `de0ca96`, on
one 4-CPU box; the JVM run was made on the same box just before, so the ratios compare like
with like. Phel was measured on the same box 2026-10-31 between 5:37 and 5:41 AM ET, on
`9ab825b` with this commit's bench changes, against that JVM run; let-go between 7:46 and
7:49 AM ET, on `4250439` with its commit's regex change. No host had natives. The files are in `doc/perf/new-hosts/`; `bb bench:report doc/perf/new-hosts`
prints the full tables.

| | ClojureWasm v1.15.4 | Phel v0.54.0 (PHP 8.5) | let-go v1.13.0 (Go) |
|---|---:|---:|---:|
| the stock core against JVM Clojure's own core | ×47.3 | ×137.9 | ×84.9 |
| the stock core against the host's own core | **×5.1** | **×4.3** | **×6.9** |
| the host's own core against JVM Clojure | ×9.2 | ×35.6 | ×12.3 |
| boot of core.clj (JVM Clojure: 1.1 s) | 86.3 s | 41.0 s | 11.8 s |
| the twelve libraries | 2.1 s | 13.3 s | 5.9 s |
| natives | 0 | 0 | 0 |

**ClojureWasm's largest gaps, the stock core against its own:**

| case | ratio |
|---|---:|
| `sort-by` a key of maps | ×129.0 |
| `sort` numbers | ×101.4 |
| `count`, `filter`, `map` over a range | ×69.2 |
| `conj!` into a transient vector | ×65.6 |
| `re-seq` | ×48.1 |
| `apply str` | ×40.1 |
| `clojure.string/join` | ×33.2 |

All seven are cases where another host's natives closed the gap:
- `sort` and `sort-by`: cljw's own `sort` takes 600 ms for 20,000 numbers when it is
  given a comparator fn and 27 ms without one, and the stock `sort` passes one;
- `re-seq`: each match walks a fresh matcher forward, because cljw's `Matcher.find`
  takes no start index;
- the others: lazy seqs, transients and strings go through the shim.

The boot is slow because cljw evaluates each of core.clj's forms through its analyzer
and the codemod. Its share of the time has not been measured.

**Phel's largest gaps, the stock core against its own:**

| case | ratio |
|---|---:|
| `re-seq` | ×216.1 |
| `sort-by` a key of maps | ×76.8 |
| `clojure.string/join` | ×72.8 |
| `format` | ×15.0 |
| `partition` 3 | ×13.1 |
| `distinct` over `mapcat` | ×10.1 |
| `group-by` | ×8.8 |

- `re-seq`: the stock `re-seq` walks the shim's Matcher, one `Pattern::find` call and
  one vector per match, where Phel's own is one `preg_match_all`.
- `format`: Phel's own is PHP's `sprintf`; the stock core's boots over the shim's
  Formatter, which reads the format string itself.
- the others have not been measured apart.

Two cases have no host side: their source is not Phel (Phel's `for` takes
`[x :in coll]`, and its `defrecord` methods see no fields), so the stock core's time
stands alone. Phel's own `count` of a 100,000-item lazy pipeline, and its `sort`,
`frequencies` and `distinct` at the bench's sizes, recurse past PHP's default 8 MB C
stack and segfault; `bb bench:phel` runs Phel with a 64 MB stack (`ulimit -s 65536`).

**let-go's largest gaps, the stock core against its own:**

| case | ratio |
|---|---:|
| `format` | ×148.1 |
| `re-seq` | ×110.5 |
| `apply str` | ×89.4 |
| `conj!` into a transient vector | ×51.9 |
| `get` from a 1,000-key map | ×37.3 |
| `frequencies` | ×28.8 |
| build a set | ×25.5 |

- `format`: let-go's own is Go's `Sprintf`; the stock core's boots over the shim's
  Formatter, which reads the format string itself.
- `re-seq`: let-go's regexes report no positions, so the adapter finds a search's
  matches once, by a replace that marks each, and every `find` of the stock `re-seq` is
  a binary search over them; the first measure, a scan from the first match on every
  `find`, was ×3034.
- `apply str`: core.clj's `str` is booted (let-go's prints an exception as `#error
  {...}`), and builds through the shim's StringBuilder.
- the others have not been measured apart.

## jank baseline (jank-FAST Step 2)

Measured 2026-11-01 ET on one 4-CPU box, alone on the box, `bb bench:jank` (full, not
--quick) on muse/jank-step2 @ 573ee4d5 -- the M-2 tip (54bddac8) plus this step's bench.cljc
fix, which gives the protocol case a host side; the bench's own EDN records the git as
54bddac-dirty for that reason. Boot: errors 0 (stock 542 / host 171 / skipped 78);
boot-ms 54,619 and libs-ms 44,080 in the bench's own run. Every case ran on both sides;
no "provides no" errors. Times are median ms per call at the case's full size; the ratio
is the stock core's time over the host's own core's time, so above 1 the stock core loses.
Geometric mean of the ratio over the 30 cases: x4.09. This is the baseline column
jank-FAST Steps 3 and 4 are measured against; the five worst cases are profiled in
doc/perf/jank.md, "Runtime profile".

| case | what it does | host (jank's own core) | baseline (the stock core) | ratio |
|---|---|---:|---:|---:|
| fn-call | a defn called in a loop | 9.26 | 9.49 | 1.03 |
| loop-arith | loop/recur arithmetic | 3.33 | 3.35 | 1.01 |
| hinted-loop | a ^long fn whose loop compares against the parameter | 2.90 | 2.90 | 1.00 |
| reduce-range | reduce + over a range | 11.41 | 15.69 | 1.38 |
| lazy-pipeline | count, filter, map over a range | 151.40 | 494.19 | 3.26 |
| transduce | transduce (comp (map inc) (filter odd?)) | 6.94 | 150.51 | 21.68 |
| into-vector | into [] with a map transducer | 6.51 | 197.52 | 30.32 |
| conj-vector | reduce conj [] (persistent vector) | 44.16 | 99.34 | 2.25 |
| transient-vector | conj! into a transient vector | 8.63 | 161.03 | 18.66 |
| assoc-map | assoc into a map | 78.68 | 110.42 | 1.40 |
| map-lookup | get from a 1,000-key map | 23.98 | 51.12 | 2.13 |
| keyword-lookup | keyword lookup on small maps | 26.47 | 20.80 | 0.79 |
| destructure | map destructuring in fn args | 29.45 | 185.24 | 6.29 |
| frequencies | frequencies | 88.70 | 492.15 | 5.55 |
| group-by | group-by into 31 groups | 34.28 | 360.95 | 10.53 |
| sort | sort numbers | 22.09 | 336.35 | 15.23 |
| sort-by | sort-by a key of maps | 15.60 | 428.28 | 27.46 |
| set-build | build a set | 18.91 | 209.42 | 11.08 |
| distinct | distinct over mapcat | 406.58 | 851.23 | 2.09 |
| partition | partition 3 | 133.07 | 367.66 | 2.76 |
| lazy-iterate | nth of a lazy iterate | 7.86 | 42.26 | 5.38 |
| atom-swap | swap! an atom | 14.81 | 97.89 | 6.61 |
| protocol | protocol call on a record | 163.10 | 166.05 | 1.02 |
| multimethod | multimethod call | 40.87 | 95.66 | 2.34 |
| equality | = of two equal vectors | 1.29 | 1.27 | 0.99 |
| str-build | apply str over numbers | 9.89 | 152.86 | 15.46 |
| string-join | clojure.string/join | 2701.27 | 110.34 | 0.04 |
| regex | re-seq over a string of numbers | 4.35 | 1392.67 | 320.11 |
| format | format | 4.77 | 236.31 | 49.58 |
| pr-str | pr-str of small maps | 1.45 | 1.45 | 1.00 |

## Method

`cohost.bench` (`src/cohost/bench.cljc`) holds 29 cases, each a small program
over clojure.core:

- lazy sequences and transducers;
- persistent and transient collections;
- lookups and destructuring;
- sorting and grouping;
- atoms, protocols and multimethods;
- strings, regexes, `format` and printing.

The sizes are fixed and the same on every host; most cases walk 20,000–200,000
elements. basilisp runs every case at 1/20 of its size and is read against JVM
Clojure at that size.

On each host, one process boots core.clj through cohost and compiles every case twice:

- one copy runs in a namespace that sees only the booted stock core;
- the other runs in a namespace that sees the host's own core.

Each side is measured in three steps:

1. **Settle:** before any case is timed, every case runs on both sides, untimed, for
   250 ms each. A host with a JIT is still compiling the boot's code for seconds after it,
   and the first cases timed would otherwise pay for that.
2. **Warm-up:** each thunk is called once cold, then repeatedly for 500 ms.
3. **Timing:** each thunk is timed until at least 3 calls and 1 s are spent. The number
   reported is the median call.

Both sides must return the same value. A stock answer counts against JVM
Clojure only when it equals JVM Clojure's own answer.

On the JVM the host's own core is JVM Clojure, so one run gives both the
baseline and cohost-on-the-JVM.

How the before-numbers were taken:
- **The JVM, jolt, Node and Chromium** ran `4ce56eb`'s code with the bench harness of a
  later commit, `19dbd1a` (its `bench.cljc` and `run.cljc`), which adds the settle step. Both sides are timed the same way.
- **basilisp** ran `4ce56eb` as it was, without the settle step. basilisp has no JIT.

Every after-result records its commit (`:git`), except Chromium's, whose page takes no
flags. The Chromium run used the same `3e59a25` worktree.

## What changed

- **Pass 1** removed needless costs from:
  - the codemod's output;
  - the shim;
  - the adapters;
  - the boot loop.
- **Pass 2** added `:natives`: a host opts into its own implementation of a stock fn, one
  name at a time. The stock definition stays the fallback.
- **The last round (`3e59a25`)** implemented every item left on the plan:
  - each form is walked once, with type hints carried into macro expansions;
  - regex groups are computed lazily;
  - rewrite memos;
  - prebuilt `instance?` predicates and missing-method dispatchers;
  - host reducers for `+` and `*`;
  - StringBuilder typing;
  - natives on every host.

`doc/perf/HANDOFF.md` lists each change.

## Tables

### The stock core, before and after

Median ms per call before → after on each host, and the speed-up (before over after).

| case | JVM Clojure 1.12.0 | jolt v0.8.23 | basilisp 0.5.1 (at 1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|
| a defn called in a loop | 0.43 → 0.44 ×0.98 | 11.8 → 1.33 ×8.9 | 277 → 219 ×1.3 | 17.1 → 0.98 ×17.5 | 9.70 → 0.70 ×13.9 |
| loop/recur arithmetic | 0.09 → 0.09 ×1.0 | 27.9 → 0.51 ×55.0 | 625 → 449 ×1.4 | 45.0 → 0.14 ×324.6 | 28.1 → 0.10 ×281.0 |
| reduce + over a range | 1.68 → 1.74 ×0.96 | 9.32 → 4.51 ×2.1 | 531 → 505 ×1.1 | 9.59 → 3.96 ×2.4 | 7.10 → 3.50 ×2.0 |
| count, filter, map over a range | 120 → 4.15 ×28.9 | 1150 → 22.5 ×51.0 | 7746 → 951 ×8.1 | 1136 → 20.5 ×55.4 | 979 → 18.2 ×53.8 |
| transduce (comp (map inc) (filter odd?)) | 29.1 → 3.12 ×9.3 | 308 → 21.7 ×14.2 | 2263 → 776 ×2.9 | 286 → 16.5 ×17.3 | 257 → 13.3 ×19.3 |
| into [] with a map transducer | 46.8 → 2.17 ×21.6 | 648 → 19.0 ×34.1 | 3941 → 558 ×7.1 | 521 → 4.65 ×112.1 | 471 → 4.60 ×102.5 |
| reduce conj [] (persistent vector) | 3.32 → 3.39 ×0.98 | 10.2 → 8.51 ×1.2 | 290 → 322 ×0.90 | 13.4 → 13.7 ×0.98 | 9.90 → 9.30 ×1.1 |
| conj! into a transient vector | 43.5 → 3.45 ×12.6 | 604 → 15.7 ×38.5 | 4026 → 619 ×6.5 | 534 → 41.9 ×12.7 | 432 → 32.4 ×13.3 |
| assoc into a map | 11.1 → 10.7 ×1.0 | 24.4 → 24.1 ×1.0 | 194 → 182 ×1.1 | 33.1 → 30.9 ×1.1 | 24.1 → 18.6 ×1.3 |
| get from a 1,000-key map | 3.55 → 3.81 ×0.93 | 32.4 → 21.2 ×1.5 | 1249 → 1078 ×1.2 | 45.4 → 17.2 ×2.6 | 28.2 → 14.7 ×1.9 |
| keyword lookup on small maps | 2.30 → 2.36 ×0.98 | 368 → 4.53 ×81.2 | 3112 → 301 ×10.4 | 448 → 7.21 ×62.2 | 316 → 7.05 ×44.8 |
| map destructuring in fn args | 25.3 → 3.35 ×7.5 | 620 → 19.3 ×32.2 | 4257 → 410 ×10.4 | 624 → 20.8 ×30.0 | 514 → 18.2 ×28.3 |
| frequencies | 112 → 6.14 ×18.3 | 1218 → 37.2 ×32.7 | 7735 → 1487 ×5.2 | 1142 → 32.9 ×34.7 | 973 → 28.6 ×34.0 |
| group-by into 31 groups | 62.3 → 6.62 ×9.4 | 821 → 32.4 ×25.4 | 5359 → 1476 ×3.6 | 667 → 29.3 ×22.8 | 511 → 24.7 ×20.7 |
| sort numbers | 52.3 → 12.3 ×4.3 | 282 → 34.1 ×8.3 | 2130 → 667 ×3.2 | 271 → 30.4 ×8.9 | 226 → 23.2 ×9.7 |
| sort-by a key of maps | 46.2 → 4.84 ×9.6 | 3761 → 23.8 ×157.7 | 489 → 119 ×4.1 | 233 → 39.5 ×5.9 | 162 → 30.4 ×5.3 |
| build a set | 51.0 → 4.78 ×10.7 | 656 → 36.2 ×18.1 | 5048 → 1230 ×4.1 | 569 → 16.8 ×33.8 | 455 → 14.1 ×32.3 |
| distinct over mapcat | 105 → 33.0 ×3.2 | 733 → 55.4 ×13.2 | 4582 → 450 ×10.2 | 979 → 176 ×5.6 | 790 → 127 ×6.2 |
| partition 3 | 27.8 → 9.18 ×3.0 | 214 → 8.34 ×25.6 | 1684 → 922 ×1.8 | 332 → 36.8 ×9.0 | 238 → 29.3 ×8.1 |
| nth of a lazy iterate | 0.91 → 0.96 ×0.95 | 6.91 → 4.25 ×1.6 | 246 → 202 ×1.2 | 18.1 → 4.75 ×3.8 | 8.20 → 4.10 ×2.0 |
| swap! an atom | 7.93 → 2.03 ×3.9 | 182 → 4.42 ×41.2 | 889 → 593 ×1.5 | 99.5 → 9.94 ×10.0 | 62.0 → 6.50 ×9.5 |
| protocol call on a record | 0.78 → 0.79 ×0.99 | 9.90 → 6.68 ×1.5 | 228 → 159 ×1.4 | 9.68 → 2.31 ×4.2 | 5.40 → 1.70 ×3.2 |
| multimethod call | 14.1 → 7.21 ×2.0 | 83.7 → 16.2 ×5.2 | 1301 → 550 ×2.4 | 151 → 36.4 ×4.2 | 117 → 29.7 ×4.0 |
| = of two equal vectors | 2.14 → 2.32 ×0.92 | 0.65 → 0.66 ×0.98 | 0.60 → 0.63 ×0.96 | 0.95 → 0.91 ×1.0 | 0.90 → 0.90 ×1.0 |
| apply str over numbers | 9.70 → 1.08 ×9.0 | 82.8 → 2.74 ×30.2 | 599 → 84.7 ×7.1 | 116 → 3.52 ×32.8 | 86.4 → 3.10 ×27.9 |
| clojure.string/join | 365 → 0.67 ×545.6 | 1213 → 3.18 ×382.0 | 191 → 82.7 ×2.3 | 43.3 → 1.86 ×23.3 | 37.8 → 1.70 ×22.2 |
| re-seq over a string of numbers | 56.5 → 0.38 ×147.3 | 58.4 → 1.39 ×41.9 | 262 → 214 ×1.2 | 39.4 → 0.74 ×53.0 | 29.8 → 0.60 ×49.7 |
| format | 5.13 → 2.49 ×2.1 | 26.2 → 4.89 ×5.4 | 1029 → 867 ×1.2 | 104 → 73.5 ×1.4 | 95.2 → 49.3 ×1.9 |
| pr-str of small maps | 3.56 → 3.63 ×0.98 | 5.89 → 5.67 ×1.0 | 2.06 → 2.06 ×1.0 | 7.93 → 7.99 ×0.99 | 6.50 → 6.90 ×0.94 |
| **geometric mean speed-up** | ×4.4 | ×12.3 | ×2.6 | ×10.3 | ×9.6 |
| boot | 1.7 s → 1.3 s | 3.4 s → 2.5 s | 178.8 s → 66.4 s | 6.7 s → 6.3 s | 5.7 s → 4.5 s |

### Against JVM Clojure: the stock core on each host

Median ms per call; × is the stock core's time over JVM Clojure's own at the same size.

| case | JVM Clojure | cohost on JVM Clojure 1.12.0 | cohost on jolt v0.8.23 | cohost on basilisp 0.5.1 (at 1/20 size) | cohost on ClojureScript, Node | cohost on ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|---:|
| a defn called in a loop | 0.44 | 0.44 ×1.0 | 1.33 ×3.0 | 219 ×9483.5 | 0.98 ×2.2 | 0.70 ×1.6 |
| loop/recur arithmetic | 0.09 | 0.09 ×1.0 | 0.51 ×5.8 | 449 ×99259.5 | 0.14 ×1.6 | 0.10 ×1.1 |
| reduce + over a range | 1.70 | 1.74 ×1.0 | 4.51 ×2.7 | 505 ×6242.8 | 3.96 ×2.3 | 3.50 ×2.1 |
| count, filter, map over a range | 3.35 | 4.15 ×1.2 | 22.5 ×6.7 | 951 ×5972.7 | 20.5 ×6.1 | 18.2 ×5.4 |
| transduce (comp (map inc) (filter odd?)) | 1.87 | 3.12 ×1.7 | 21.7 ×11.6 | 776 ×8934.7 | 16.5 ×8.8 | 13.3 ×7.1 |
| into [] with a map transducer | 2.08 | 2.17 ×1.0 | 19.0 ×9.1 | 558 ×5020.5 | 4.65 ×2.2 | 4.60 ×2.2 |
| reduce conj [] (persistent vector) | 3.23 | 3.39 ×1.0 | 8.51 ×2.6 | 322 ×2060.1 | 13.7 ×4.2 | 9.30 ×2.9 |
| conj! into a transient vector | 1.77 | 3.45 ×1.9 | 15.7 ×8.8 | 619 ×7097.9 | 41.9 ×23.6 | 32.4 ×18.3 |
| assoc into a map | 11.0 | 10.7 ×0.97 | 24.1 ×2.2 | 182 ×638.1 | 30.9 ×2.8 | 18.6 ×1.7 |
| get from a 1,000-key map | 3.73 | 3.81 ×1.0 | 21.2 ×5.7 | 1078 ×6565.0 | 17.2 ×4.6 | 14.7 ×4.0 |
| keyword lookup on small maps | 2.28 | 2.36 ×1.0 | 4.53 ×2.0 | 301 ×3158.6 | 7.21 ×3.2 | 7.05 ×3.1 |
| map destructuring in fn args | 2.30 | 3.35 ×1.5 | 19.3 ×8.4 | 410 ×4275.0 | 20.8 ×9.0 | 18.2 ×7.9 |
| frequencies | 6.26 | 6.14 ×0.98 | 37.2 ×5.9 | 1487 ×4675.1 | 32.9 ×5.3 | 28.6 ×4.6 |
| group-by into 31 groups | 6.41 | 6.62 ×1.0 | 32.4 ×5.1 | 1476 ×4303.6 | 29.3 ×4.6 | 24.7 ×3.9 |
| sort numbers | 12.3 | 12.3 ×1.0 | 34.1 ×2.8 | 667 ×2032.3 | 30.4 ×2.5 | 23.2 ×1.9 |
| sort-by a key of maps | 5.47 | 4.84 ×0.88 | 23.8 ×4.4 | 119 ×671.5 | 39.5 ×7.2 | 30.4 ×5.6 |
| build a set | 4.77 | 4.78 ×1.0 | 36.2 ×7.6 | 1230 ×4144.0 | 16.8 ×3.5 | 14.1 ×3.0 |
| distinct over mapcat | 31.1 | 33.0 ×1.1 | 55.4 ×1.8 | 450 ×416.6 | 176 ×5.6 | 127 ×4.1 |
| partition 3 | 8.90 | 9.18 ×1.0 | 8.34 ×0.94 | 922 ×2108.2 | 36.8 ×4.1 | 29.3 ×3.3 |
| nth of a lazy iterate | 0.95 | 0.96 ×1.0 | 4.25 ×4.5 | 202 ×4779.5 | 4.75 ×5.0 | 4.10 ×4.3 |
| swap! an atom | 2.00 | 2.03 ×1.0 | 4.42 ×2.2 | 593 ×6315.1 | 9.94 ×5.0 | 6.50 ×3.2 |
| protocol call on a record | 0.81 | 0.79 ×0.98 | 6.68 ×8.2 | 159 ×4629.0 | 2.31 ×2.8 | 1.70 ×2.1 |
| multimethod call | 1.43 | 7.21 ×5.0 | 16.2 ×11.3 | 550 ×8804.7 | 36.4 ×25.4 | 29.7 ×20.7 |
| = of two equal vectors | 2.33 | 2.32 ×1.0 | 0.66 ×0.28 | 0.63 ×7.9 | 0.91 ×0.39 | 0.90 ×0.39 |
| apply str over numbers | 0.90 | 1.08 ×1.2 | 2.74 ×3.0 | 84.7 ×2162.0 | 3.52 ×3.9 | 3.10 ×3.4 |
| clojure.string/join | 0.66 | 0.67 ×1.0 | 3.18 ×4.8 | 82.7 ×2708.1 | 1.86 ×2.8 | 1.70 ×2.6 |
| re-seq over a string of numbers | 0.38 | 0.38 ×1.0 | 1.39 ×3.6 | 214 ×11831.7 | 0.74 ×1.9 | 0.60 ×1.6 |
| format | 2.49 | 2.49 ×1.0 | 4.89 ×2.0 | 867 ×7239.5 | 73.5 ×29.5 | 49.3 ×19.8 |
| pr-str of small maps | 3.67 | 3.63 ×0.99 | 5.67 ×1.5 | 2.06 ×11.7 | 7.99 ×2.2 | 6.90 ×1.9 |
| **geometric mean** | | ×1.1 | ×3.7 | ×2778.2 | ×4.2 | ×3.4 |

### Each host's own core against the stock core booted on it

Median ms per call: the host's own core / the stock core, and the stock core's time over the own core's.

| case | JVM Clojure 1.12.0 | jolt v0.8.23 | basilisp 0.5.1 (at 1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|
| a defn called in a loop | 0.44 / 0.44 ×1.0 | 1.63 / 1.33 ×0.82 | 129 / 219 ×1.7 | 1.32 / 0.98 ×0.74 | 0.70 / 0.70 ×1.0 |
| loop/recur arithmetic | 0.09 / 0.09 ×1.0 | 0.51 / 0.51 ×1.0 | 275 / 449 ×1.6 | 0.14 / 0.14 ×1.0 | 0.10 / 0.10 ×1.0 |
| reduce + over a range | 1.70 / 1.74 ×1.0 | 3.85 / 4.51 ×1.2 | 442 / 505 ×1.1 | 3.78 / 3.96 ×1.0 | 3.30 / 3.50 ×1.1 |
| count, filter, map over a range | 3.35 / 4.15 ×1.2 | 5.72 / 22.5 ×3.9 | 643 / 951 ×1.5 | 9.37 / 20.5 ×2.2 | 9.80 / 18.2 ×1.9 |
| transduce (comp (map inc) (filter odd?)) | 1.87 / 3.12 ×1.7 | 6.81 / 21.7 ×3.2 | 389 / 776 ×2.0 | 4.17 / 16.5 ×4.0 | 4.50 / 13.3 ×3.0 |
| into [] with a map transducer | 2.08 / 2.17 ×1.0 | 10.4 / 19.0 ×1.8 | 569 / 558 ×0.98 | 4.12 / 4.65 ×1.1 | 4.50 / 4.60 ×1.0 |
| reduce conj [] (persistent vector) | 3.23 / 3.39 ×1.0 | 8.61 / 8.51 ×0.99 | 346 / 322 ×0.93 | 10.4 / 13.7 ×1.3 | 7.90 / 9.30 ×1.2 |
| conj! into a transient vector | 1.77 / 3.45 ×1.9 | 4.48 / 15.7 ×3.5 | 512 / 619 ×1.2 | 2.49 / 41.9 ×16.9 | 2.80 / 32.4 ×11.6 |
| assoc into a map | 11.0 / 10.7 ×0.97 | 21.4 / 24.1 ×1.1 | 133 / 182 ×1.4 | 29.2 / 30.9 ×1.1 | 19.3 / 18.6 ×0.96 |
| get from a 1,000-key map | 3.73 / 3.81 ×1.0 | 11.2 / 21.2 ×1.9 | - / - - | 11.6 / 17.2 ×1.5 | 10.3 / 14.7 ×1.4 |
| keyword lookup on small maps | 2.28 / 2.36 ×1.0 | 4.53 / 4.53 ×1.0 | 413 / 301 ×0.73 | 7.40 / 7.21 ×0.97 | 6.60 / 7.05 ×1.1 |
| map destructuring in fn args | 2.30 / 3.35 ×1.5 | 7.09 / 19.3 ×2.7 | 402 / 410 ×1.0 | 13.3 / 20.8 ×1.6 | 13.7 / 18.2 ×1.3 |
| frequencies | 6.26 / 6.14 ×0.98 | 27.2 / 37.2 ×1.4 | 748 / 1487 ×2.0 | 26.8 / 32.9 ×1.2 | 26.2 / 28.6 ×1.1 |
| group-by into 31 groups | 6.41 / 6.62 ×1.0 | 22.2 / 32.4 ×1.5 | 704 / 1476 ×2.1 | 24.3 / 29.3 ×1.2 | 22.7 / 24.7 ×1.1 |
| sort numbers | 12.3 / 12.3 ×1.0 | 29.2 / 34.1 ×1.2 | 175 / 667 ×3.8 | 26.1 / 30.4 ×1.2 | 20.9 / 23.2 ×1.1 |
| sort-by a key of maps | 5.47 / 4.84 ×0.88 | 24.2 / 23.8 ×0.98 | 11.4 / 119 ×10.5 | 43.8 / 39.5 ×0.90 | 31.3 / 30.4 ×0.97 |
| build a set | 4.77 / 4.78 ×1.0 | 11.8 / 36.2 ×3.1 | 516 / 1230 ×2.4 | 11.6 / 16.8 ×1.5 | 10.3 / 14.1 ×1.4 |
| distinct over mapcat | 31.1 / 33.0 ×1.1 | 54.4 / 55.4 ×1.0 | 438 / 450 ×1.0 | 178 / 176 ×0.99 | 118 / 127 ×1.1 |
| partition 3 | 8.90 / 9.18 ×1.0 | 8.36 / 8.34 ×1.0 | 933 / 922 ×0.99 | 40.6 / 36.8 ×0.91 | 30.3 / 29.3 ×0.97 |
| nth of a lazy iterate | 0.95 / 0.96 ×1.0 | 1.72 / 4.25 ×2.5 | 25.1 / 202 ×8.1 | 3.62 / 4.75 ×1.3 | 2.90 / 4.10 ×1.4 |
| swap! an atom | 2.00 / 2.03 ×1.0 | 4.98 / 4.42 ×0.89 | 420 / 593 ×1.4 | 7.08 / 9.94 ×1.4 | 5.70 / 6.50 ×1.1 |
| protocol call on a record | 0.81 / 0.79 ×0.98 | 6.49 / 6.68 ×1.0 | 129 / 159 ×1.2 | 2.78 / 2.31 ×0.83 | 1.80 / 1.70 ×0.94 |
| multimethod call | 1.43 / 7.21 ×5.0 | 9.83 / 16.2 ×1.7 | 151 / 550 ×3.7 | 8.75 / 36.4 ×4.2 | 7.80 / 29.7 ×3.8 |
| = of two equal vectors | 2.33 / 2.32 ×1.0 | 0.66 / 0.66 ×1.0 | 0.61 / 0.63 ×1.0 | 0.94 / 0.91 ×0.97 | 0.90 / 0.90 ×1.0 |
| apply str over numbers | 0.90 / 1.08 ×1.2 | 2.66 / 2.74 ×1.0 | 83.1 / 84.7 ×1.0 | 3.41 / 3.52 ×1.0 | 3.20 / 3.10 ×0.97 |
| clojure.string/join | 0.66 / 0.67 ×1.0 | 3.04 / 3.18 ×1.0 | 59.9 / 82.7 ×1.4 | 1.92 / 1.86 ×0.97 | 1.80 / 1.70 ×0.94 |
| re-seq over a string of numbers | 0.38 / 0.38 ×1.0 | 1.41 / 1.39 ×0.99 | 5.71 / 214 ×37.4 | 0.73 / 0.74 ×1.0 | 0.60 / 0.60 ×1.0 |
| format | 2.49 / 2.49 ×1.0 | 5.25 / 4.89 ×0.93 | 37.4 / 867 ×23.2 | - / - - | - / - - |
| pr-str of small maps | 3.67 / 3.63 ×0.99 | 5.75 / 5.67 ×0.99 | 2.07 / 2.06 ×1.0 | 7.93 / 7.99 ×1.0 | 6.80 / 6.90 ×1.0 |
| **geometric mean** | ×1.1 | ×1.4 | ×2.0 | ×1.4 | ×1.3 |
| own core vs JVM Clojure (geo. mean) | ×1.0 | ×2.7 | ×1346.7 | ×2.9 | ×2.4 |

### Boot

| host | core.clj boot (791 forms) | the twelve libraries |
|---|---:|---:|
| JVM Clojure 1.12.0 | 1.3 s | 1.5 s |
| jolt v0.8.23 | 2.5 s | 2.5 s |
| basilisp 0.5.1 | 66.4 s | 47.6 s |
| ClojureScript, Node | 6.3 s | 6.3 s |
| ClojureScript, Chromium | 4.5 s | 4.1 s |

### Cases with a side missing (an error, or a different answer)

- basilisp 0.5.1, get from a 1,000-key map: the host's own core throws: maximum recursion depth exceeded
- ClojureScript, Node, format: the host's own core throws: Cannot read properties of undefined (reading 'call')
- ClojureScript, Chromium, format: the host's own core throws: Cannot read properties of undefined (reading 'call')

## Reproduce

Run the benches one host at a time:

```bash
bb bench:jvm
```

Then `bb bench:jolt`, `bb bench:cljs` and `bb bench:browser`. basilisp runs at 1/20
size, against JVM Clojure at the same size:

```bash
bb bench:jvm --scale=0.05
```

```bash
bb bench:lpy --scale=0.05
```

Then the tables. `doc/perf/before` and `doc/perf/after` hold this file's runs.

```bash
bb bench:report target
```

```bash
bb bench:report --compare doc/perf/before doc/perf/after
```

## Programs against JVM Clojure

jank-FAST Step 8. Programs in `bench/programs/`, driver `dev/bench_programs.clj`:

- `bb bench:programs <target> [program[:size],...]` measures one target (all programs, or the listed ones, at
  their default size or the given one) and records each row in `target/bench-programs/results/<target>.edn`.
- `bb bench:programs all [program,...]` runs every target in order.
- `bb bench:programs table [target ...]` prints the tables below from those records.

### Method (C-1, 2026-11-02)

- One process per program and target. The first call of `-run` is `cold` (the first call in a fresh process).
  Warm-up continues until the median of the last 5 calls moves under 2% from the median before the newest call, or
  20 calls, or 10 s of warm-up, whichever comes first (the cold call counts). Then 10 timed calls: `median` (mean of
  the middle two), `min`, `max`.
- Sizes: one warm JVM call takes 100-1000 ms (`bb bench:programs jvm`: 87-370 ms per call, below). The size is a
  string argument parsed inside `-run` on every call.
- The clock is each core's own, in nanoseconds: `System/nanoTime` on the JVM; on every cohost target the program's
  `(System/nanoTime)`, which cohost rewrites to the shim's `System-nanoTime`, the adapter's `:nano-time`; jank's own
  core `cpp/jank.runtime.current_time`; cljs and Chromium `js/performance.now` (Chromium rounds it to 0.1 ms);
  basilisp `time/perf_counter_ns`; Phel `php/hrtime`.
- No volatile or atom in the harness: the result of every call is folded into a sink carried by the loop and
  printed at the end, and every timed call's result is checked against the first call's. The harness's inner loop
  binds no name of its outer loop (jank 0.1 fails C++ codegen on that, below).
- A result is compared with the JVM's at the same size: `DIFFER` is a different answer. `ABSENT` is the host's
  error, printed in full below the tables.
- `first-output` is process wall time until stdout has `ok`: one cold process, one discarded, then 10 (median,
  min-max).
- cljs own core is compiled as shipped (`cljs.main -t node -O simple`, run by `node`); `browser` is the same
  program compiled `-t browser -O simple` and loaded in headless Chromium (Playwright), console printing on.
  `browser-cohost` has no runner: `src/cohost/run_browser.cljs` runs the battery or `?bench` only and has no
  `--eval`.
- jank optimization level, per column (C-1 addendum): `jank-jit` is `jank -O3 run`; `jank-aot` is
  `jank compile-module -O3`, then `run-main`; `jank` (cohost on jank) is `jank -O3 --module-path <cohost's -O3 AOT
  modules>:src run-main cohost.run -- --eval <program>`, so the boot's JIT and the program's forms compile at -O3.
  first-output uses the same commands.
- Hinted programs carry the hints a JVM programmer writes for primitive math: `^long`/`^double` on parameters,
  `^long` on the return of `fib` and `tak`, and `(double x)`/`(long x)` coercion of locals (a `^double` tag on a
  local bound to an Object makes no primitive local on the JVM). `json` round-trips its sample `n` times.

Measured 2026-11-02, 8:48 AM to 11:24 AM ET (jank-jit and cohost on jank at -O3: 10:54-11:24 AM), one 4-CPU box, one target at a time, nothing else running, cohost
`c/bench-programs` (base `b286ba1`). The sections after this one ("Basilisp, ClojureWasm, Phel, let-go",
"ClojureScript Node", "Chromium", "Still running at close") are G-13's runs with the old method (one warm-up, five
calls of 0.03-80 ms); the coordinator's all-host rerun with this driver replaces them. Hosts this box lacks (jolt,
basilisp, ClojureWasm, Phel, let-go, clojurust) are not in these tables.

### Tables

`bb bench:programs table jvm jvm-cohost jank-jit jank-aot jank cljs cljs-cohost browser`. `jank` is cohost on jank (AOT boot, G-12); `jank-jit` and `jank-aot` are jank's own core (`jank run`; `compile-module -O3`, then `run-main`). `browser` is cljs's own core in headless Chromium.

Median ms of 10 timed calls (xJVM: median / JVM median).

| program | size | jvm | jvm-cohost | jank-jit | jank-aot | jank | cljs | cljs-cohost | browser |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| fib | 36 | 139.164 | 155.234 (x1.12) | 109.448 (x0.79) | 136.690 (x0.98) | 459.506 (x3.30) | 239.783 (x1.72) | 261.039 (x1.88) | 181.100 (x1.30) |
| fib-hinted | 36 | 86.788 | ABSENT | 275.183 (x3.17) | 164.436 (x1.89) | 3436.371 (x39.60) | 253.498 (x2.92) | 3022.310 (x34.82) | 150.150 (x1.73) |
| tak | 30 20 10 | 370.015 | 305.499 (x0.83) | 758.011 (x2.05) | 568.600 (x1.54) | 1488.983 (x4.02) | ABSENT | 1222.172 (x3.30) | ABSENT |
| tak-hinted | 30 20 10 | 160.435 | ABSENT | 1296.105 (x8.08) | 1075.946 (x6.71) | 18396.719 (x114.67) | ABSENT | 17801.744 (x110.96) | ABSENT |
| nbody | 50000 | 201.169 | 299.999 (x1.49) | ABSENT | ABSENT | ABSENT | 879.060 (x4.37) | 1568.248 (x7.80) | 526.800 (x2.62) |
| nbody-hinted | 50000 | 199.261 | ABSENT | ABSENT | ABSENT | ABSENT | 834.527 (x4.19) | 1653.455 (x8.30) | 517.100 (x2.60) |
| spectral-norm | 600 | 234.551 | 322.950 (x1.38) | ABSENT | ABSENT | 3290.174 (x14.03) | 1740.105 (x7.42) | 2812.995 (x11.99) | 1090.450 (x4.65) |
| spectral-norm-hinted | 600 | 118.189 | ABSENT | ABSENT | ABSENT | 5252.280 (x44.44) | 1790.396 (x15.15) | 4490.841 (x38.00) | 952.600 (x8.06) |
| mandelbrot | 1500 | 171.545 | 177.148 (x1.03) | ABSENT | ABSENT | ABSENT | 2504.099 (x14.60) | 4701.288 (x27.41) | 1986.250 (x11.58) |
| mandelbrot-hinted | 1500 | 172.394 | ABSENT | ABSENT | ABSENT | ABSENT | 2435.867 (x14.13) | 4487.109 (x26.03) | 1923.250 (x11.16) |
| binary-trees | 18 | 279.690 | 258.124 (x0.92) | 4016.127 (x14.36) | 4030.292 (x14.41) | 3439.206 (x12.30) | 912.235 (x3.26) | 1388.831 (x4.97) | 481.200 (x1.72) |
| binary-trees-hinted | 18 | 249.042 | ABSENT | 3781.694 (x15.18) | 3781.119 (x15.18) | 4594.999 (x18.45) | 854.270 (x3.43) | 2917.456 (x11.71) | 513.700 (x2.06) |
| fannkuch | 8 | 188.514 | 167.783 (x0.89) | ABSENT | ABSENT | ABSENT | 692.804 (x3.68) | 864.746 (x4.59) | 477.550 (x2.53) |
| fannkuch-hinted | 8 | 150.001 | 178.316 (x1.19) | ABSENT | ABSENT | ABSENT | 738.551 (x4.92) | 920.872 (x6.14) | 471.350 (x3.14) |
| nsieve | 20 | 237.117 | 369.284 (x1.56) | ABSENT | ABSENT | ABSENT | 821.985 (x3.47) | 1714.090 (x7.23) | 428.800 (x1.81) |
| nsieve-hinted | 20 | 243.300 | 373.298 (x1.53) | ABSENT | ABSENT | ABSENT | 749.941 (x3.08) | 1799.450 (x7.40) | 443.900 (x1.82) |
| k-nucleotide | 2000000 | 154.312 | 135.902 (x0.88) | 717.094 (x4.65) | 699.293 (x4.53) | 1652.495 (x10.71) | 836.140 (x5.42) | 1449.917 (x9.40) | 525.500 (x3.41) |
| pipeline | 500000 | 159.547 | 161.266 (x1.01) | 1235.519 DIFFER | 1280.717 DIFFER | 1568.218 DIFFER | 705.978 (x4.42) | 869.912 (x5.45) | 421.800 (x2.64) |
| dispatch | 5000000 | 158.637 | 710.070 (x4.48) | ABSENT | ABSENT | 8413.152 DIFFER | 683.848 (x4.31) | 3502.906 (x22.08) | 448.850 (x2.83) |
| json | 30000 | 218.450 | 257.687 (x1.18) | 2189.466 (x10.02) | 1974.701 (x9.04) | 6433.241 (x29.45) | 514.319 DIFFER | 2026.651 (x9.28) | 291.200 DIFFER |
| first-output | - | 708.823 | 4616.659 (x6.51) | 678.953 (x0.96) | 650.275 (x0.92) | 20101.329 (x28.36) | 116.127 (x0.16) | 7335.602 (x10.35) | 717.774 (x1.01) |

min-max of the 10 timed calls / the cold call (the first call in a fresh process), ms.

| program | jvm | jvm-cohost | jank-jit | jank-aot | jank | cljs | cljs-cohost | browser |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fib | 121.893-173.004 / 233.909 | 134.460-175.644 / 242.638 | 109.003-175.848 / 2037.889 | 91.708-148.784 / 95.254 | 432.536-567.998 / 2145.138 | 217.159-295.926 / 229.979 | 248.599-324.140 / 283.481 | 148.000-254.800 / 164.400 |
| fib-hinted | 73.411-106.860 / 84.710 |  | 234.601-328.591 / 1757.686 | 158.890-175.891 / 168.631 | 3357.095-3598.962 / 4934.339 | 222.023-302.680 / 241.763 | 2883.344-3108.095 / 2832.468 | 138.900-271.800 / 204.500 |
| tak | 349.317-449.223 / 409.261 | 257.935-367.386 / 497.106 | 588.823-840.599 / 2422.257 | 552.627-615.240 / 584.227 | 1393.540-1532.935 / 2987.238 |  | 1182.877-1416.299 / 1190.151 |  |
| tak-hinted | 145.241-193.638 / 204.114 |  | 1208.900-1426.532 / 1420.880 | 954.089-1289.832 / 1083.634 | 17867.856-18757.106 / 19717.923 |  | 17523.027-18595.653 / 17385.173 |  |
| nbody | 189.382-228.212 / 443.707 | 288.089-317.808 / 729.456 |  |  |  | 759.902-965.781 / 713.572 | 1466.478-1904.077 / 1629.451 | 521.600-683.000 / 735.700 |
| nbody-hinted | 192.349-238.456 / 365.860 |  |  |  |  | 640.057-972.742 / 592.664 | 1513.893-1771.034 / 1774.427 | 491.700-627.700 / 621.900 |
| spectral-norm | 218.567-260.318 / 408.983 | 314.728-415.629 / 606.467 |  |  | 3055.631-3725.200 / 11902.784 | 1631.269-1874.363 / 1488.645 | 2642.144-3133.513 / 2649.015 | 917.700-1239.000 / 1124.400 |
| spectral-norm-hinted | 113.866-123.326 / 180.562 |  |  |  | 5023.124-5461.202 / 13629.194 | 1508.544-2007.471 / 1427.908 | 4238.068-4783.363 / 4657.268 | 883.400-1103.300 / 963.000 |
| mandelbrot | 164.407-188.132 / 225.656 | 170.093-213.945 / 341.477 |  |  |  | 2345.535-2718.797 / 2343.588 | 4118.100-4883.892 / 4499.872 | 1805.900-2574.000 / 2195.000 |
| mandelbrot-hinted | 169.392-194.851 / 208.608 |  |  |  |  | 2250.830-2898.689 / 2143.212 | 4096.782-4890.160 / 4484.662 | 1804.400-2175.900 / 1898.500 |
| binary-trees | 248.818-360.795 / 404.257 | 225.865-310.096 / 395.313 | 3743.478-4406.921 / 6964.655 | 3830.185-4288.654 / 3999.799 | 3135.028-3635.705 / 8295.135 | 861.994-1028.158 / 1099.092 | 1248.214-1543.078 / 1853.716 | 443.400-496.400 / 572.200 |
| binary-trees-hinted | 227.488-305.945 / 368.497 |  | 3594.718-4196.401 / 5610.184 | 3680.953-4232.837 / 4055.209 | 4311.672-5045.489 / 8956.335 | 815.488-1033.283 / 1101.454 | 2745.428-3024.936 / 3141.776 | 446.900-586.900 / 549.300 |
| fannkuch | 169.603-2159.668 / 443.924 | 152.306-330.297 / 348.516 |  |  |  | 659.441-817.213 / 902.760 | 829.870-1031.310 / 1047.781 | 438.800-515.600 / 571.200 |
| fannkuch-hinted | 141.871-181.190 / 477.881 | 162.338-420.674 / 487.901 |  |  |  | 689.928-858.307 / 950.861 | 834.170-978.794 / 975.421 | 437.600-547.800 / 585.600 |
| nsieve | 165.181-345.449 / 327.814 | 258.366-512.643 / 620.980 |  |  |  | 675.585-879.620 / 821.358 | 1554.773-1880.519 / 1948.007 | 373.400-597.200 / 478.700 |
| nsieve-hinted | 154.964-453.000 / 277.746 | 232.154-456.271 / 487.100 |  |  |  | 708.951-1021.247 / 940.211 | 1557.190-2236.151 / 2035.877 | 388.100-646.100 / 457.400 |
| k-nucleotide | 133.901-234.418 / 280.684 | 128.940-148.615 / 313.342 | 624.057-791.882 / 1249.509 | 632.507-769.656 / 851.177 | 1505.480-1920.007 / 5004.154 | 772.332-938.589 / 897.334 | 1292.041-1518.269 / 1310.404 | 463.200-618.200 / 542.100 |
| pipeline | 140.442-187.292 / 331.187 | 149.109-242.927 / 271.219 | 1155.889-1496.229 / 3199.306 | 1203.132-1331.695 / 1195.588 | 1447.958-1660.159 / 7665.890 | 596.955-871.403 / 640.547 | 796.085-977.524 / 942.784 | 385.800-530.500 / 466.100 |
| dispatch | 149.159-231.840 / 302.659 | 632.955-726.764 / 787.209 |  |  | 8238.488-8682.587 / 14029.796 | 592.807-864.816 / 809.393 | 3261.834-3691.508 / 3649.045 | 443.000-641.600 / 608.300 |
| json | 210.395-292.018 / 554.421 | 236.281-310.498 / 793.939 | 2102.671-2292.437 / 6517.639 | 1904.445-2061.242 / 2119.903 | 6207.318-7309.046 / 14644.056 | 465.062-599.325 / 460.360 | 1909.693-2193.864 / 2066.897 | 284.400-345.100 / 527.900 |
| first-output | 651.416-1118.000 / 652.451 | 4168.637-6703.454 / 4110.573 | 630.919-753.876 / 649.077 | 598.728-749.510 / 673.279 | 18653.374-22290.783 / 20154.912 | 107.709-194.537 / 108.283 | 6951.264-7670.996 / 7531.770 | 673.702-800.678 / 670.673 |

- jvm-cohost: geomean xJVM 1.390 over 15 programs (unhinted 1.396 over 13, hinted 1.351 over 2)
- jank-jit: geomean xJVM 4.194 over 9 programs (unhinted 3.179 over 6, hinted 7.300 over 3)
- jank-aot: geomean xJVM 3.780 over 9 programs (unhinted 3.058 over 6, hinted 5.778 over 3)
- jank: geomean xJVM 18.283 over 11 programs (unhinted 11.080 over 7, hinted 43.926 over 4)
- cljs: geomean xJVM 4.029 over 18 programs (unhinted 3.338 over 11, hinted 5.414 over 7)
- cljs-cohost: geomean xJVM 10.774 over 21 programs (unhinted 7.604 over 13, hinted 18.984 over 8)
- browser: geomean xJVM 2.873 over 18 programs (unhinted 2.604 over 11, hinted 3.354 over 7)
- hinted/unhinted median on jvm: fib 0.624, tak 0.434, nbody 0.991, spectral-norm 0.504, mandelbrot 1.005, binary-trees 0.890, fannkuch 0.796, nsieve 1.026
- hinted/unhinted median on jvm-cohost: fannkuch 1.063, nsieve 1.011
- hinted/unhinted median on jank-jit: fib 2.514, tak 1.710, binary-trees 0.942
- hinted/unhinted median on jank-aot: fib 1.203, tak 1.892, binary-trees 0.938
- hinted/unhinted median on jank: fib 7.478, tak 12.355, spectral-norm 1.596, binary-trees 1.336
- hinted/unhinted median on cljs: fib 1.057, nbody 0.949, spectral-norm 1.029, mandelbrot 0.973, binary-trees 0.936, fannkuch 1.066, nsieve 0.912
- hinted/unhinted median on cljs-cohost: fib 11.578, tak 14.566, nbody 1.054, spectral-norm 1.596, mandelbrot 0.954, binary-trees 2.101, fannkuch 1.065, nsieve 1.050
- hinted/unhinted median on browser: fib 0.829, nbody 0.982, spectral-norm 0.874, mandelbrot 0.968, binary-trees 1.068, fannkuch 0.987, nsieve 1.035

ABSENT and DIFFER, as printed:

- jvm-cohost fib-hinted: `class cohost.user$fib cannot be cast to class clojure.lang.IFn$LL (cohost.user$fib is in unnamed module of loader clojure.lang.DynamicClassLoader @1aee6983; clojure.lang.IFn$LL is in unnamed module of ...`
- jvm-cohost tak-hinted: `class cohost.user$tak cannot be cast to class clojure.lang.IFn$LLLL (cohost.user$tak is in unnamed module of loader clojure.lang.DynamicClassLoader @1aee6983; clojure.lang.IFn$LLLL is in unnamed modul ...`
- jvm-cohost nbody-hinted: `class cohost.user$advance cannot be cast to class clojure.lang.IFn$ODO (cohost.user$advance is in unnamed module of loader clojure.lang.DynamicClassLoader @1a390b8c; clojure.lang.IFn$ODO is in unnamed ...`
- jvm-cohost spectral-norm-hinted: `class cohost.user$eval_a cannot be cast to class clojure.lang.IFn$LLO (cohost.user$eval_a is in unnamed module of loader clojure.lang.DynamicClassLoader @1aee6983; clojure.lang.IFn$LLO is in unnamed m ...`
- jvm-cohost mandelbrot-hinted: `class cohost.user$inside_QMARK_ cannot be cast to class clojure.lang.IFn$DDO (cohost.user$inside_QMARK_ is in unnamed module of loader clojure.lang.DynamicClassLoader @3adeca1f; clojure.lang.IFn$DDO i ...`
- jvm-cohost binary-trees-hinted: `class cohost.user$build cannot be cast to class clojure.lang.IFn$LO (cohost.user$build is in unnamed module of loader clojure.lang.DynamicClassLoader @1aee6983; clojure.lang.IFn$LO is in unnamed modul ...`
- jank-jit nbody: `error: The symbol 'Math/sqrt' could not be resolved.`
- jank-jit nbody-hinted: `error: The symbol 'Math/sqrt' could not be resolved.`
- jank-jit spectral-norm: `error: The symbol 'Math/round' could not be resolved.`
- jank-jit spectral-norm-hinted: `error: The symbol 'Math/round' could not be resolved.`
- jank-jit mandelbrot: `target/bench-programs/23404/mandelbrot.jank:16:1: error: use of undeclared identifier 's35'`
- jank-jit mandelbrot-hinted: `target/bench-programs/23404/mandelbrot-hinted.jank:16:1: error: use of undeclared identifier 's37'`
- jank-jit fannkuch: `target/bench-programs/23404/fannkuch.jank:9:1: error: use of undeclared identifier 's28'`
- jank-jit fannkuch-hinted: `target/bench-programs/23404/fannkuch-hinted.jank:7:1: error: use of undeclared identifier 's30'`
- jank-jit nsieve: `target/bench-programs/23404/nsieve.jank:11:1: error: use of undeclared identifier 's56'; did you mean 's16'?`
- jank-jit nsieve-hinted: `target/bench-programs/23404/nsieve-hinted.jank:11:1: error: use of undeclared identifier 's58'; did you mean 's18'?`
- jank-jit pipeline: DIFFER: 1336595249, JVM 374998750001
- jank-jit dispatch: `error: The symbol 'defprotocol' could not be resolved.`
- jank-aot nbody: `error: The symbol 'Math/sqrt' could not be resolved.`
- jank-aot nbody-hinted: `error: The symbol 'Math/sqrt' could not be resolved.`
- jank-aot spectral-norm: `error: The symbol 'Math/round' could not be resolved.`
- jank-aot spectral-norm-hinted: `error: The symbol 'Math/round' could not be resolved.`
- jank-aot mandelbrot: `/home/user/cohost/target/bench-programs/aot-mandelbrot/src/bench/prog.jank:17:1: error: use of undeclared identifier 's35'`
- jank-aot mandelbrot-hinted: `/home/user/cohost/target/bench-programs/aot-mandelbrot-hinted/src/bench/prog.jank:17:1: error: use of undeclared identifier 's37'`
- jank-aot fannkuch: `/home/user/cohost/target/bench-programs/aot-fannkuch/src/bench/prog.jank:10:1: error: use of undeclared identifier 's28'`
- jank-aot fannkuch-hinted: `/home/user/cohost/target/bench-programs/aot-fannkuch-hinted/src/bench/prog.jank:8:1: error: use of undeclared identifier 's30'`
- jank-aot nsieve: `/home/user/cohost/target/bench-programs/aot-nsieve/src/bench/prog.jank:12:1: error: use of undeclared identifier 's56'; did you mean 's16'?`
- jank-aot nsieve-hinted: `/home/user/cohost/target/bench-programs/aot-nsieve-hinted/src/bench/prog.jank:12:1: error: use of undeclared identifier 's58'; did you mean 's18'?`
- jank-aot pipeline: DIFFER: 1336595249, JVM 374998750001
- jank-aot dispatch: `error: The symbol 'defprotocol' could not be resolved.`
- jank nbody: `Unable to compile C++ source. -- nil:7:1: error: use of undeclared identifier 's34'`
- jank nbody-hinted: `Unable to compile C++ source. -- nil:7:1: error: use of undeclared identifier 's34'`
- jank mandelbrot: `Unable to compile C++ source. -- nil:7:1: error: use of undeclared identifier 's36'`
- jank mandelbrot-hinted: `Unable to compile C++ source. -- nil:7:1: error: use of undeclared identifier 's38'`
- jank fannkuch: `Unable to compile C++ source. -- nil:6:1: error: use of undeclared identifier 's28'`
- jank fannkuch-hinted: `Unable to compile C++ source. -- nil:6:1: error: use of undeclared identifier 's30'`
- jank nsieve: `Unable to compile C++ source. -- nil:10:1: error: use of undeclared identifier 's61'`
- jank nsieve-hinted: `Unable to compile C++ source. -- nil:10:1: error: use of undeclared identifier 's63'`
- jank pipeline: DIFFER: 1336595249, JVM 374998750001
- jank dispatch: DIFFER: 1691001973, JVM 12500045833333
- cljs tak: `TypeError: Cannot read properties of undefined (reading 'call')`
- cljs tak-hinted: `TypeError: Cannot read properties of undefined (reading 'call')`
- cljs json: DIFFER: 0, JVM 870000
- browser tak: `pageerror: Cannot read properties of undefined (reading 'call')`
- browser tak-hinted: `pageerror: Cannot read properties of undefined (reading 'call')`
- browser json: DIFFER: 0, JVM 870000

### What the ABSENT and DIFFER rows are

- **jank 0.1 codegen, an inner `loop` binding a name of an enclosing `loop`** (jank-jit, jank-aot, and cohost on
  jank: nbody, mandelbrot, fannkuch, nsieve, both forms): `error: use of undeclared identifier 'sNN'`. Repro in raw
  jank (`jank run`):
  `(defn h [n] (loop [a 0] (if (> a 0) (loop [a a] (if (> a 5) a (recur (inc a)))) (recur (inc a)))))` fails;
  renaming the inner `a` compiles and returns 6. The init expression does not matter (`(loop [a (inc a)] ...)` and
  `(loop [b 0 a a] ...)` fail too); `let` shadowing and a `loop` rebinding a fn parameter compile. The programs'
  inner loops: nbody `(loop [j (inc i) bs bs b b] ...)` and `(loop [j (inc i) e e] ...)`, mandelbrot
  `(loop [x 0 acc acc] ...)`, fannkuch `(loop [p p i 0 j k] ...)`, nsieve `(loop [j (+ i i) flags flags] ...)`.
  The cohost-on-jank `ABSENT` cells of these tables are from a tree before C-2's `:loop-rebind-shadow?` (jank's
  adapter: true; merged at 210a146, on this tree): with it, doc/perf/jank.md "Programs on cohost-on-jank" runs all
  four on cohost on jank with the JVM's answers at small sizes; the full-size rows are not re-measured on this tree.
  In the patched column U6-4 fixes the codegen itself, so jank's own core runs mandelbrot, fannkuch and nsieve
  there (jank-aot mandelbrot 769.4, fannkuch 506.9, nsieve 131.4 ms on the 19-patch series). No upstream draft
  named it at c499930.
- **jank 0.1 integer arithmetic wraps at 32 bits when both operands fit in int32** (jank-jit, jank-aot, cohost on
  jank: pipeline and dispatch `DIFFER`, each the JVM value mod 2^32). Raw jank: `(+ 2147483647 1)` -> `-2147483648`,
  `(* 65536 65536)` -> `0`, `(* 100000 100000)` -> `1410065408`, `(reduce + (range 100000))` -> `704982704`; with
  a wider operand the result is 64-bit: `(+ 4000000000 1000000000)` -> `5000000000`. The cause and the source
  lines are lane R's drafts doc/upstream/jank-12-i32-add.md and jank-21-int32-loop-local.md (an integer literal
  that fits in i32 is a C++ `int`).
- jank's own core: `Math/sqrt` (nbody), `Math/round` (spectral-norm) and `defprotocol` (dispatch) do not resolve.
- cljs's own core: `read-string` is not in `cljs.core` (tak: `TypeError: Cannot read properties of undefined
  (reading 'call')`); `(int ch)` of a one-character string is 0, so json's parser reads nothing (`DIFFER`, 0).
  These are the programs' JVM-isms on a host whose own core differs; cohost runs both on cljs.
- jvm-cohost, every program with a primitive-hinted fn: `class cohost.user$fib cannot be cast to class
  clojure.lang.IFn$LL` (tak `IFn$LLLL`, advance `IFn$ODO`, eval_a `IFn$LLO`, inside? `IFn$DDO`, build `IFn$LO`).
- Hinted slower than unhinted, where both run: cohost on jank (-O3) fib 7.48, tak 12.36, spectral-norm 1.60,
  binary-trees 1.34; cljs-cohost fib 11.58, tak 14.57, binary-trees 2.10; jank's own core fib 2.51 and tak 1.71
  (JIT -O3), fib 1.20 and tak 1.89 (AOT). On the
  JVM, hinted/unhinted is at most 1.026 (nsieve, min-max 155-453 vs 165-345 ms), 1.005 (mandelbrot) and 0.991
  (nbody): inside the box's noise; fib 0.62, tak 0.43, spectral-norm 0.50.

### cohost on jank from the snapshot (C-3 addendum)

With lane D's D-5 entry in the tree (`dev/perf/jank_snapshot_cli.clj`), `bb bench:programs jank` boots from the
snapshot when it is fresh (else from the AOT objects, as that entry decides) and records `:boot` per row. Measured
2026-11-02 1:34 PM ET on `c/bench-programs` merged with `d/jank-cli` (`bb boot:jank-snapshot`: built in 559016 ms,
`SNAPSHOT-BOOT-MS 633.302331`), `bb bench:programs jank "fib,tak,first-output"`, `:boot :snapshot` on every row:
first-output median 4293.496 ms (min-max 4051.667-4444.732; the AOT -O3 boot above: 20101.329), fib 484.177 ms,
tak 1402.188 ms (the program's own time: the same as from the AOT boot, 459.506 and 1488.983). The table above is
the AOT boot; the coordinator's rerun on a tree with D-5 merged gives the snapshot's.

### jank without -O against -O3

The same driver, the same box, jank-jit and cohost on jank before the addendum
(no `-O`: 8:54-9:54 AM) and after (`-O3`: 10:54-11:24 AM). Median ms; pipeline and dispatch are DIFFER rows (the
i32 wrap above), timed anyway.

| program | jank-jit no -O | jank-jit -O3 | cohost on jank no -O | cohost on jank -O3 |
|---|---:|---:|---:|---:|
| fib | 2068.851 | 109.448 | 2593.866 | 459.506 |
| fib-hinted | 1991.294 | 275.183 | 9652.053 | 3436.371 |
| tak | 5865.958 | 758.011 | 20005.129 | 1488.983 |
| tak-hinted | 8223.789 | 1296.105 | 62008.624 | 18396.719 |
| nbody | ABSENT | ABSENT | ABSENT | ABSENT |
| nbody-hinted | ABSENT | ABSENT | ABSENT | ABSENT |
| spectral-norm | ABSENT | ABSENT | 8649.375 | 3290.174 |
| spectral-norm-hinted | ABSENT | ABSENT | 13401.452 | 5252.280 |
| mandelbrot | ABSENT | ABSENT | ABSENT | ABSENT |
| mandelbrot-hinted | ABSENT | ABSENT | ABSENT | ABSENT |
| binary-trees | 6765.922 | 4016.127 | 7230.156 | 3439.206 |
| binary-trees-hinted | 6601.447 | 3781.694 | 9871.674 | 4594.999 |
| fannkuch | ABSENT | ABSENT | ABSENT | ABSENT |
| fannkuch-hinted | ABSENT | ABSENT | ABSENT | ABSENT |
| nsieve | ABSENT | ABSENT | ABSENT | ABSENT |
| nsieve-hinted | ABSENT | ABSENT | ABSENT | ABSENT |
| k-nucleotide | 1419.795 | 717.094 | 3032.476 | 1652.495 |
| pipeline | 1599.987 | 1235.519 | 2181.297 | 1568.218 |
| dispatch | ABSENT | ABSENT | 11665.907 | 8413.152 |
| json | 3107.481 | 2189.466 | 8699.881 | 6433.241 |
| first-output | 672.119 | 678.953 | 13872.158 | 20101.329 |


### Sentinels: binary-trees at depth 14, sum-overflow, sort (jank-MAX X1-1)

Three rows that make the upstream lanes' changes visible on the suite (jank-MAX §2 X1). They are fixed by what they
catch, not by the 100-1000 ms size rule above: a warm JVM call of each takes 0.6-37 ms.

- `binary-trees-14` (and `-hinted`): `binary_trees.clj` at depth 14, a second size beside 18, so the collector's
  share is visible at a small heap (U5's vectors and collector defaults).
- `sum-overflow` (and `-hinted`, a `^long` loop): `(reduce + (range 1000000))`, 499999500000 on the JVM, past
  2^31, so a host that wraps at 32 bits prints `DIFFER` (U1's i64 arms; jank-12, jank-21).
- `sort`: 100000 numbers from an LCG kept under 2^23 (no host wraps), sorted, then `nth` of the middle element of
  the sorted seq (U2's sorted walk, jank-11). No hinted twin: there is no primitive arm to hint.

Measured 2026-11-02, 11:01 AM to 11:17 AM ET, one 4-CPU box shared with two other lanes (every timing run serialized
through one lock), apt jank 0.1-noble, cohost `max/x1` (c/bench-programs 6ba26ac with max/main 1475973). `bb
bench:programs jvm|jank-aot|jank binary-trees-14,binary-trees-14-hinted,sum-overflow,sum-overflow-hinted,sort`, then
`bb bench:programs table jvm jank-aot jank` (rows for these five; the 21 rows above are C-1's run).

Median ms of 10 timed calls (xJVM: median / JVM median).

| program | size | jvm | jank-aot | jank |
|---|---:|---:|---:|---:|
| binary-trees-14 | 14 | 9.268 | 152.684 (x16.47) | 153.955 (x16.61) |
| binary-trees-14-hinted | 14 | 14.790 | 153.942 (x10.41) | 211.597 (x14.31) |
| sum-overflow | 1000000 | 8.891 | 99.390 DIFFER | 80.905 DIFFER |
| sum-overflow-hinted | 1000000 | 0.579 | 11.398 DIFFER | 10.501 DIFFER |
| sort | 100000 | 36.551 | 15782.846 (x431.80) | 105.723 (x2.89) |

min-max of the 10 timed calls / the cold call (the first call in a fresh process), ms.

| program | jvm | jank-aot | jank |
|---|---:|---:|---:|
| binary-trees-14 | 8.410-128.627 / 39.751 | 126.567-189.042 / 177.659 | 137.662-178.511 / 3384.105 |
| binary-trees-14-hinted | 8.863-126.885 / 50.075 | 121.648-170.560 / 187.343 | 192.200-241.764 / 3085.089 |
| sum-overflow | 8.220-223.141 / 38.494 | 93.486-135.052 / 124.876 | 72.047-97.288 / 366.215 |
| sum-overflow-hinted | 0.569-0.591 / 11.295 | 10.168-11.924 / 16.451 | 10.013-11.204 / 287.616 |
| sort | 31.767-126.055 / 126.310 | 10068.304-18151.171 / 10558.572 | 86.180-116.144 / 1613.717 |

- `sum-overflow`, both forms, jank-aot and cohost on jank: `DIFFER: 1783293664, JVM 499999500000`
  (499999500000 mod 2^32). This row turns `SAME` on U1's build (not yet built: U0-1).
- `sort` on jank's own core takes 15.8 s per call, x432 the JVM; its warm-up stopped at 1 call (the 10 s limit).
  Cohost on jank runs the same program in 106 ms (x2.89): cohost's `sort` and `nth` are the shim's, not jank's.
  The gap is jank's own `sort`/`nth` over the sorted seq (lane R's jank-11 names the sorted walk).
- binary-trees: jank-aot is x16.47 at depth 14 here and x14.41 at depth 18 in C-1's table above; cohost on jank
  x16.61 and x25.85. On the JVM one call of the 10 at depth 14 took 128.6 ms (median 9.3 ms), a collector pause
  inside the timed calls; the min-max columns show it.

### The sentinel table (jank-MAX X1-2): seven rows, one command

Two more sentinels, same shape as the three above:

- `lazy-pipeline`: `(count (filter odd? (map inc (range 1000000))))`, 500000; every element crosses three lazy
  seqs (U3's in-place walks and lazy lock, U4's internal reduce and chunks; jank-15, 16, 17).
- `keyword-map`: 1000000 `get` + `assoc` pairs on a 16-key map whose keys are 30-character keywords
  (`:keyword-map-sentinel-key-alpha` ...), with a checksum kept under 2^20 so no integer width changes it
  (U2's keyword hash; jank-14). 218003 on the JVM.

The command (the coordinator runs it after every merged patch):

```
bb bench:programs sentinels              # measure jank-aot and jank on the seven rows, jvm only where it has no record, print the table
bb bench:programs sentinels table        # print only, from the records
JANK_BIN=<path> bb bench:programs sentinels   # the same on another jank binary
```

Under `JANK_BIN` the jank targets' records go to `target/bench-programs/results/<target>-<tag>.edn`, the tag being the
first 12 hex digits of the sha256 of the binary's real path (the same tag as its AOT roots), so a source build's rows
never overwrite the apt binary's and either table can be printed again later. The JVM records are shared.

Measured 2026-11-02, 12:44 PM to 1:06 PM ET, cohost `max/x1` @ c3c76cb (max/main 0271898 merged). The box has 4 CPUs
and was shared with two other lanes, unserialized: the apt run and the u2-binary run overlapped from 12:56 PM to
1:04 PM ET, with other lanes' builds and benches running too. So compare the two tables row by row for large factors
only. The JVM medians for lazy-pipeline and keyword-map were measured in this run; the other five JVM records were
already in this checkout from the X1-1 done-bar re-run, and the command does not re-measure them.

`bb bench:programs sentinels` on apt jank 0.1-noble (log `/home/user/logs/x1-sentinels-apt.log` on the lane box):

| program | size | jvm | jank-aot | jank |
|---|---:|---:|---:|---:|
| binary-trees-14 | 14 | 13.474 | 119.482 (x8.87) | 259.437 (x19.25) |
| binary-trees-14-hinted | 14 | 11.973 | 111.824 (x9.34) | 257.988 (x21.55) |
| sum-overflow | 1000000 | 14.178 | 69.040 DIFFER | 119.049 DIFFER |
| sum-overflow-hinted | 1000000 | 0.608 | 11.801 DIFFER | 16.075 DIFFER |
| sort | 100000 | 38.737 | 17215.731 (x444.43) | 122.020 (x3.15) |
| lazy-pipeline | 1000000 | 35.656 | 1042.084 (x29.23) | 4297.217 (x120.52) |
| keyword-map | 1000000 | 90.400 | 614.343 (x6.80) | 1144.508 (x12.66) |

`JANK_BIN=/usr/local/lib/jank-u2/bin/jank bb bench:programs sentinels`: u2's build, src f3d82d65 with patches
11, 13, 14 (sorted walk, `=` inline, keyword hash; records tag `-bfe39dde20e3`):

| program | size | jvm | jank-aot | jank |
|---|---:|---:|---:|---:|
| binary-trees-14 | 14 | 13.474 | 159.199 (x11.82) | 292.704 (x21.72) |
| binary-trees-14-hinted | 14 | 11.973 | 162.473 (x13.57) | 310.971 (x25.97) |
| sum-overflow | 1000000 | 14.178 | 91.746 DIFFER | 171.123 DIFFER |
| sum-overflow-hinted | 1000000 | 0.608 | 8.854 DIFFER | 3.313 DIFFER |
| sort | 100000 | 38.737 | 50.393 (x1.30) | 157.493 (x4.07) |
| lazy-pipeline | 1000000 | 35.656 | 1619.950 (x45.43) | 2484.581 (x69.68) |
| keyword-map | 1000000 | 90.400 | 439.950 (x4.87) | 664.527 (x7.35) |

- `sort` shows U2-1 (jank-11) on the suite. On jank's own core it fell from 17215.731 ms (min-max
  12437.249-21181.731) to 50.393 ms (45.232-54.502), x444 to x1.30 the JVM. Cohost on jank was already off jank's
  `sort` (122.020 and 157.493 ms, inside the noise of this window).
- `keyword-map`, jank-aot: 614.343 ms (537.892-648.671) on apt, 439.950 ms (368.565-508.468) on u2's build. The
  ranges do not overlap, but the window was shared, so U2-3's own measure should confirm the change.
- `sum-overflow` prints `DIFFER: 1783293664, JVM 499999500000` on both binaries; neither carries U1's patch.
- `lazy-pipeline` is x29 (jank-aot) and x121 (cohost on jank) on apt. u2's build carries no walk patch, and its
  jank-aot min-max (1281.872-4429.067) shows the noise of this shared window.

### Two jank binaries: unpatched is the one that ships

Two jank binaries appear in the jank tables from here on. **Unpatched** is the apt package
`jank 0.1-noble` (`dev/install/jank.sh`), the default of every `bb boot:jank`, `bb bench:jank`
and `bb bench:programs jank` and of `cohost :jank`: it is what a user installs and the column the
public numbers are quoted from. **Patched** is jank-lang/jank at the pinned `f3d82d65` with
cohost's `upstream/jank/patches/series` applied, built by `dev/install/jank-src.sh` and selected
only by `JANK_BIN=<prefix>/bin/jank`; it is measured beside the unpatched column, never instead
of it. The patches are not sent upstream (no pull request, issue or branch against jank-lang/jank;
the drafts under doc/upstream/ stay drafts); whether cohost patches jank in place for its own
purposes is a later choice. The adapter-side work under `src/cohost/**` (natives, the keys, the
codemod) is what moves the unpatched column, and it is measured on unpatched jank. In the tables
of 2026-11-02 the two columns are headed `apt` and `merged`.

### cohost-max: the merged series (2026-11-02, X1-3)

The plan's scoreboard (jank-MAX §0) on the merged upstream series. Two jank binaries: `apt` is jank 0.1-noble;
`merged` is jank-lang/jank f3d82d65 with cohost's `upstream/jank/patches/series`, built with
`JANK_SRC=/usr/local/src/jank-main JANK_DEV_PREFIX=/usr/local/lib/jank-main bash dev/install/jank-src.sh`, then
`--patches` as patches merged. Every jank run had its own `XDG_CACHE_HOME`: the source builds all print the same
`print-binary-version` and would otherwise share one PCH. The binary-tagged AOT roots and the cache were cleared after
every rebuild. The box has 4 CPUs and was shared with the other lanes, unserialized, at load average 7-9. The JVM rows of
this run are slower than C-1's (fib 328.767 against 139.164 ms), and every xJVM below uses this run's JVM.

**Full suite, nine-patch series** (max/main faa50c4: 11, 13, 14, 12-21, 18, 19, 15a-16, 15b-17, 22). `bb bench:programs
jvm`, then `bb bench:programs jank-aot` and `bb bench:programs jank` on apt and with
`JANK_BIN=/usr/local/lib/jank-main/bin/jank`. Median ms of 10 timed calls (xJVM):

| program | size | jvm | jank-aot apt | jank-aot merged | jank apt | jank merged |
|---|---:|---:|---:|---:|---:|---:|
| fib | 36 | 328.767 | 148.339 (x0.45) | 140.364 (x0.43) | 553.721 (x1.68) | 544.508 (x1.66) |
| fib-hinted | 36 | 147.111 | 168.561 (x1.15) | 243.378 (x1.65) | 3048.341 (x20.72) | 2539.452 (x17.26) |
| tak | 30 20 10 | 415.510 | 750.618 (x1.81) | 866.382 (x2.09) | 1698.438 (x4.09) | 1565.381 (x3.77) |
| tak-hinted | 30 20 10 | 296.893 | 1492.643 (x5.03) | 1428.758 (x4.81) | 20615.452 (x69.44) | 17891.301 (x60.26) |
| nbody | 50000 | 245.980 | ABSENT | ABSENT | ABSENT | ABSENT |
| nbody-hinted | 50000 | 226.022 | ABSENT | ABSENT | ABSENT | ABSENT |
| spectral-norm | 600 | 304.986 | ABSENT | ABSENT | 5724.004 (x18.77) | 5287.932 (x17.34) |
| spectral-norm-hinted | 600 | 167.914 | ABSENT | ABSENT | 6703.139 (x39.92) | 7290.028 (x43.42) |
| mandelbrot | 1500 | 216.625 | ABSENT | ABSENT | ABSENT | ABSENT |
| mandelbrot-hinted | 1500 | 242.505 | ABSENT | ABSENT | ABSENT | ABSENT |
| binary-trees | 18 | 441.679 | 3827.530 (x8.67) | 1979.189 (x4.48) | 4353.341 (x9.86) | 1618.805 (x3.67) |
| binary-trees-hinted | 18 | 362.610 | 3611.230 (x9.96) | 1922.790 (x5.30) | 6120.744 (x16.88) | 2593.301 (x7.15) |
| binary-trees-14 | 14 | 18.072 | 156.323 (x8.65) | 71.412 (x3.95) | 239.796 (x13.27) | 87.586 (x4.85) |
| binary-trees-14-hinted | 14 | 14.605 | 159.882 (x10.95) | 97.638 (x6.69) | 378.712 (x25.93) | 129.429 (x8.86) |
| sum-overflow | 1000000 | 25.006 | 78.731 DIFFER | 18.754 (x0.75) | 168.079 DIFFER | 45.425 (x1.82) |
| sum-overflow-hinted | 1000000 | 0.599 | 11.365 DIFFER | 4.367 (x7.29) | 10.872 DIFFER | 6.216 (x10.38) |
| sort | 100000 | 43.809 | 27652.770 (x631.21) | 24.100 (x0.55) | 192.171 (x4.39) | 86.981 (x1.99) |
| lazy-pipeline | 1000000 | 36.320 | 1398.660 (x38.51) | 205.817 (x5.67) | 2880.591 (x79.31) | 688.740 (x18.96) |
| keyword-map | 1000000 | 127.189 | 557.583 (x4.38) | 281.313 (x2.21) | 1275.398 (x10.03) | 640.434 (x5.04) |
| fannkuch | 8 | 213.102 | ABSENT | ABSENT | ABSENT | ABSENT |
| fannkuch-hinted | 8 | 212.243 | ABSENT | ABSENT | ABSENT | ABSENT |
| nsieve | 20 | 340.399 | ABSENT | ABSENT | ABSENT | ABSENT |
| nsieve-hinted | 20 | 197.237 | ABSENT | ABSENT | ABSENT | ABSENT |
| k-nucleotide | 2000000 | 174.209 | 844.388 (x4.85) | 888.224 (x5.10) | 2055.509 (x11.80) | 1778.718 (x10.21) |
| pipeline | 500000 | 174.239 | 1290.984 DIFFER | 983.129 (x5.64) | 2171.439 DIFFER | 1747.661 (x10.03) |
| dispatch | 5000000 | 213.103 | ABSENT | ABSENT | 12678.188 DIFFER | 7569.793 (x35.52) |
| json | 30000 | 389.482 | 2331.012 (x5.98) | 1148.436 (x2.95) | 10164.134 (x26.10) | 7702.754 (x19.78) |
| first-output | - | 1482.445 | 674.079 (x0.45) | 819.871 (x0.55) | 17545.517 (x11.84) | 16793.854 (x11.33) |

Geomean xJVM (`bb bench:programs table jvm jank-aot jank`, on each binary's records):

- jank's own AOT: apt 5.842 over 14 programs, merged 2.545 over 17. Over the 14 that both run SAME: apt 5.842,
  merged 2.433.
- cohost on jank: apt 14.818 over 16, merged 9.125 over 20. Over the same 16: apt 14.818, merged 9.143.
- The merged binary turns sum-overflow, sum-overflow-hinted and pipeline (both targets) and dispatch (cohost) from
  `DIFFER` (the i32 wrap) to `SAME` (12-21).
- `ABSENT` on both binaries, the same causes as C-1's table. jank's own core: nbody (`Math/sqrt`), spectral-norm
  (`Math/round`), dispatch (`defprotocol`). Both targets: mandelbrot, fannkuch and nsieve (`use of undeclared
  identifier 'sNN'`, the inner-loop rebinding). Cohost: nbody too. These cells are before C-2 (cohost on jank:
  `:loop-rebind-shadow?`, on this tree) and before U6-4 (the patched column's codegen fix); see "What the ABSENT
  and DIFFER rows are" above.
- `bb bench:jank` (stock core / jank's own, 30 cases, geomean of the printed x column): apt x1.77, merged x2.12.
  Booted the same on both: stock 542, from host 171, skipped 78, errors 0.

**A B interleave, eleven-patch series** (max/main 3c08fb4: the nine above plus 20-native-returns and 23-chunk-branch;
`/usr/local/lib/jank-main` rebuilt with `--patches`, its AOT roots, records and cache cleared). Three rounds. Each round
runs apt jank-aot, apt jank, merged jank-aot, merged jank on binary-trees, binary-trees-14, sort, lazy-pipeline,
keyword-map, pipeline, json, fib, fib-hinted, tak and tak-hinted. Each cell is the median of the three rounds' medians
(10 timed calls each), with the min-max of those three in brackets. The JVM column is the full suite's JVM above.
Measured 2026-11-02, 2:48 PM to 4:31 PM ET.

jank-aot:

| program | jvm | apt median (min-max) | merged median (min-max) | merged / apt | merged xJVM |
|---|---:|---:|---:|---:|---:|
| binary-trees | 441.7 | 4187.7 (3666.1-4368.6) | 1137.1 (1067.8-1140.7) | 0.27 | x2.57 |
| binary-trees-14 | 18.1 | 197.1 (175.8-243.3) | 44.5 (37.0-50.9) | 0.23 | x2.46 |
| sort | 43.8 | 24106.7 (20321.0-24840.2) | 24.0 (23.8-37.1) | 0.00 | x0.55 |
| lazy-pipeline | 36.3 | 1103.4 (993.9-1452.7) | 86.0 (82.7-104.8) | 0.08 | x2.37 |
| keyword-map | 127.2 | 511.4 (472.6-521.4) | 256.0 (239.2-279.5) | 0.50 | x2.01 |
| pipeline | 174.2 | 1213.9 (1101.7-1247.5) DIFFER | 1128.2 (950.6-1264.1) | 0.93 | x6.48 |
| json | 389.5 | 1961.7 (1943.2-2352.3) | 1077.2 (1045.9-1136.7) | 0.55 | x2.77 |
| fib | 328.8 | 101.1 (97.4-106.1) | 107.9 (102.3-181.1) | 1.07 | x0.33 |
| fib-hinted | 147.1 | 174.0 (165.4-264.5) | 80.6 (77.7-81.1) | 0.46 | x0.55 |
| tak | 415.5 | 674.1 (646.9-726.6) | 316.2 (310.6-335.8) | 0.47 | x0.76 |
| tak-hinted | 296.9 | 1347.4 (1285.3-1429.6) | 182.6 (182.4-186.1) | 0.14 | x0.62 |

geomean merged/apt over 11: 0.212

jank:

| program | jvm | apt median (min-max) | merged median (min-max) | merged / apt | merged xJVM |
|---|---:|---:|---:|---:|---:|
| binary-trees | 441.7 | 3454.1 (3351.5-3729.3) | 1532.5 (1511.5-1590.5) | 0.44 | x3.47 |
| binary-trees-14 | 18.1 | 221.5 (192.2-222.9) | 106.7 (83.5-107.5) | 0.48 | x5.90 |
| sort | 43.8 | 118.2 (108.3-138.1) | 96.0 (85.0-109.6) | 0.81 | x2.19 |
| lazy-pipeline | 36.3 | 2405.1 (2184.6-2515.6) | 797.9 (589.0-902.8) | 0.33 | x21.97 |
| keyword-map | 127.2 | 824.3 (792.8-961.3) | 749.7 (725.0-1252.0) | 0.91 | x5.89 |
| pipeline | 174.2 | 1846.9 (1797.1-2105.8) DIFFER | 2429.5 (2237.1-2668.5) | 1.32 | x13.94 |
| json | 389.5 | 7498.1 (7434.6-8019.9) | 9415.1 (7117.3-10328.0) | 1.26 | x24.17 |
| fib | 328.8 | 454.9 (452.0-500.2) | 682.6 (472.2-707.2) | 1.50 | x2.08 |
| fib-hinted | 147.1 | 2867.1 (2866.0-3282.5) | 2488.3 (2483.0-2601.2) | 0.87 | x16.91 |
| tak | 415.5 | 1598.3 (1476.4-2185.2) | 1476.9 (1472.1-1551.9) | 0.92 | x3.55 |
| tak-hinted | 296.9 | 17636.6 (17076.7-22737.2) | 15890.1 (14175.0-16291.6) | 0.90 | x53.52 |

geomean merged/apt over 11: 0.806

- jank's own AOT moves on every row except fib (1.07, inside fib's spread). The biggest moves: sort x0.001 (11, the
  sorted walk), lazy-pipeline 0.08 (15a/15b, 23), tak-hinted 0.14 and fib-hinted 0.46 (20, native returns),
  binary-trees 0.23-0.27 (18, 19). Merged jank-aot is below the JVM on fib, fib-hinted, tak, tak-hinted and sort.
- Cohost on jank moves less (geomean 0.806), and three rows are slower on the merged binary:
  - fib: 682.6 against 454.9 ms. The spreads overlap (472.2-707.2 against 452.0-500.2).
  - json: 9415.1 against 7498.1 ms. The spreads overlap (7117.3-10328.0 against 7434.6-8019.9).
  - pipeline: 2429.5 against 1846.9 ms. apt's pipeline is the wrong answer (DIFFER, the i32 wrap), so that pair does
    not compare like for like.

  The fib and json rows are not confirmed regressions in this window. They are named here for the next merge's run.
- The full-suite tables above are the nine-patch binary. This interleave is the eleven-patch one. The next run (X1-4 or
  the coordinator's next merge) re-runs the full suite on the series of that day.

### The two columns on the full suite (X1-4, X1-5, 2026-11-02 evening)

Geomean xJVM of `bb bench:programs table jvm jank-aot jank`, one JVM run per column, the AOT
roots and the jank cache cleared before each binary; a row counts when it is SAME.

| column | cohost on jank | jank's own AOT |
|---|---:|---:|
| UNPATCHED: apt 0.1-noble, cohost 760ef01 (X1-5, 7:52 PM ET) | 15.213 over 16 | 7.095 over 14 |
| PATCHED: eighteen patches (11 .. 29), own cache (X1-4, 5:24 PM ET) | 6.648 over 20 (unhinted 5.112 over 14, hinted 12.270 over 6) | 1.719 over 17 (unhinted 1.590 over 12, hinted 2.071 over 5) |

The UNPATCHED row is the public figure; it predates U6-5, U6-6 and A5 on this tree and is re-run
by X1-5 on the current branch. The PATCHED jank-aot rows, ms: fib 70.2, fib-hinted 72.1, tak 182.6,
tak-hinted 205.6, binary-trees 925.5, sum-overflow 8.0 SAME, sort 27.4, lazy-pipeline 34.4,
keyword-map 216.2, pipeline 817.3 SAME, json 870.4. On unpatched jank the DIFFER rows stay
(sum-overflow, sum-overflow-hinted, pipeline, dispatch: the 32-bit wrap) and are not in the geomean.

## Cohost AOT optimization level

`bb boot:jank` calls `jank-aot-dir` (`bb.edn` lines 14-15), which loads `dev/perf/jank_aot_ensure.clj`. The recompile is one command, `jank --module-path src compile-module cohost.run -O3 --target-dir target/jank-aot`, at `dev/perf/jank_aot_ensure.clj:68-69`. That `-O3` covers `cohost.run` and the namespaces it depends on, including `cohost.shim` and `cohost.hosts.jank-host`. The script requires `cohost/shim.o` and `cohost/hosts/jank_host.o` in that same cache. There is no environment variable for the level. jank's own flag is `-O` / `--optimization`, values 0 through 3. The boot does not read it from the environment. The level is the literal `-O3` on that line, so the default is already `-O3`. `bb bench:programs jank` loads those objects. A second target would repeat the same compile. Default and `-O3` are that run: fib median `126.469398` ms, and the geometric mean of cohost `-O3` / default is `1`.

`jolt` is jolt 0.8.23's own core (`jolt run`). `jolt-cohost` is `bb boot:jolt` (`jolt -Scp src -m cohost.run --eval`). Boot: stock 466, host 199, natives 63, skipped 63, errors 0, battery 145/145, libraries 61/61. `bb bench:programs jolt` and `bb bench:programs jolt-cohost`. Every row printed SAME. Times are milliseconds.

| program | jolt median | jolt cold | jolt/jvm | cohost median | cohost cold | cohost/jvm | result |
|---|---:|---:|---:|---:|---:|---:|---|
| fib | 9.332578 | 9.417227 | 1.763046 | 39.499601 | 39.743152 | 7.461989 | 832040 |
| fib-hinted | 8.90545 | 9.138106 | 2.012526 | 251.79843 | 251.064143 | 56.903459 | 832040 |
| tak | 0.246605 | 0.259483 | 0.180311 | 1.349508 | 1.343892 | 0.986722 | 7 |
| tak-hinted | 0.185614 | 0.1982 | 0.164362 | 15.333638 | 15.341936 | 13.578026 | 7 |
| nbody | 1.567588 | 1.609389 | 0.404371 | 3.243066 | 3.359114 | 0.836574 | -169027 |
| nbody-hinted | 1.510234 | 1.474866 | 0.490469 | 3.283267 | 3.314992 | 1.066285 | -169027 |
| spectral-norm | 2.952609 | 4.393105 | 4.452628 | 5.423403 | 5.590879 | 8.178664 | 784826 |
| spectral-norm-hinted | 2.14092 | 2.649665 | 1.278389 | 15.692423 | 16.056846 | 9.370278 | 784826 |
| mandelbrot | 0.59783 | 0.674659 | 1.344139 | 0.717242 | 0.759875 | 1.612621 | 608 |
| mandelbrot-hinted | 0.110312 | 0.114599 | 0.515877 | 0.866126 | 0.90322 | 4.050460 | 608 |
| binary-trees | 0.282292 | 0.275508 | 0.544325 | 0.54897 | 0.571791 | 1.058543 | 7083 |
| binary-trees-hinted | 0.279242 | 0.284978 | 0.218671 | 1.061998 | 1.098512 | 0.831637 | 7083 |
| fannkuch | 27.381871 | 20.717505 | 1.284342 | 26.733999 | 26.608502 | 1.253953 | -502 |
| fannkuch-hinted | 19.117359 | 19.667614 | 0.932606 | 25.241029 | 27.179449 | 1.231338 | -502 |
| nsieve | 1.525536 | 1.848063 | 1.825042 | 2.180737 | 2.278142 | 2.608877 | 564 |
| nsieve-hinted | 1.393835 | 1.905298 | 1.362363 | 2.077267 | 2.211934 | 2.030364 | 564 |
| k-nucleotide | 0.2435 | 0.283621 | 0.216375 | 0.572904 | 0.59383 | 0.509086 | 500 |
| pipeline | 2.299106 | 2.213147 | 0.915832 | 2.015231 | 2.044342 | 0.802753 | 5995001 |
| dispatch | 3.615562 | 3.442353 | 1.667386 | 5.354062 | 5.324077 | 2.469129 | 200183333 |
| json | 0.015425 | 0.039097 | 0.105802 | 0.14983 | 0.224902 | 1.027704 | 580 |
| first-output | 127.289412 | 155.165961 | 0.330518 | 2036.561343 | 1997.019663 | 5.288114 | ok |

Geometric mean of the twenty-one jolt / JVM median ratios: `0.683374`. Geometric mean of the twenty-one jolt-cohost / JVM median ratios: `2.404439`.

## Cached boots (D-6 step 1, 2026-11-02 ET)

What each host can cache, measured on one box (lane D's; times are not comparable with other sections'). The boot is `bb boot:<host>`'s whole process (boot, battery, libraries), one run each. The probe (`bash dev/perf/cached_boot_probe.sh <dir>`) is one namespace of 1,000 small fns (`(defn fN [x y] (let [...] (if ... (str ...) (map inc [...]))))`, written per dialect), three runs each, wall seconds: `eval` reads and evaluates it form by form (`load-file`, as cohost's boot evaluates its rewritten forms), `module` requires it with the host's cache empty, `cached` with it warm. A cached boot here means jank's snapshot carried over: the boot's rewritten forms recorded once (D-1's record mode, portable), emitted as the host's modules, loaded through the host's own cache, so a fresh boot skips cohost's reading and rewriting of core.clj and the host's compile of what it rewrote.

| host | boot here | eval / module / cached (s) | what a cached boot can be |
|---|---|---|---|
| Basilisp 0.5.1 | 2 m 38.654 s; 529/130/36/96/0, 144/145, 61/61 | 12.731 / 13.128 / 13.008; 12.538 / 12.165 / 12.826; 0.774 / 0.744 / 0.728 | a generated `.lpy` module per booted namespace: `require` compiles it once to `__pycache__/*.lpyc`, then loads the bytecode, 17x faster than evaluating the same forms. Both of its costs (the rewrite in Python, the Python compile of each form) go. **Ranked first.** |
| ClojureWasm v1.15.4 | 1 m 31.604 s; 555/145/91/0, 143/145, 61/61 | 0.056 / 0.089 / 0.057; 0.058 / 0.059 / 0.057; no cache | evaluating is cheap (1,000 fns in 0.06 s) and `require` caches nothing; `cljw build` makes a binary that still evaluates the script at start (a load-time `(reduce + (range 2e7))` took 1.409 s as a script and 1.449 s built). So its boot is cohost's own reading and rewriting on cljw, and a generated module saves that part, not the eval. |
| Phel v0.54.0 | 1 m 13.065 s; 591/73/127/0, 145/145, 61/61 | 3.906 / 0.146 / 0.154 (`phel run`); `phel build --no-cache` 10.340 / 6.482 / 6.852 | `phel run` already caches the PHP it compiles (3.9 s first, 0.15 s after); cohost's boot evaluates forms at run time, which that cache does not cover. A generated module run by `phel run` would get it. |
| let-go 1.13.0 | 19.407 s; 569/105/117/0, 143/145, 61/61 | 0.101 / 0.099 / 0.105; `lg -c` 0.110 / 0.111 / 0.122; `.lgb` 0.019 / 0.020 / 0.019 | `lg -c` compiles to `.lgb` bytecode, 5x faster to load than the source; but the source itself is cheap, so its boot too is mostly cohost's rewrite. |
| ClojureScript (Node 24.22.1) | 53.161 s (the cljs build included); 518/87/46/140/0, 143/145, 61/61 | not probed (its boot is seconds once built) | the compiled JS of a generated module (cljs.main caches the build). |
| jolt v0.8.23 | 11.391 s; 466/199/63/63/0, 145/145, 61/61 | not probed (the fastest boot) | -- |

### Basilisp's cached boot (D-6)

Built as ranked: `bb boot:lpy-snapshot` records a Basilisp boot with the twelve libraries (`dev/perf/lpy_snapshot_record.lpy`, D-1's record mode, unchanged), emits one `.lpy` module per booted namespace (`dev/perf/jank_snapshot_emit.clj --host lpy`) into `target/lpy-snapshot/src/`, and loads them once so Basilisp compiles their bytecode (`__pycache__/*.lpyc`). Its key (`dev/perf/lpy_snapshot_ensure.clj`) is one sha256 over the stock sources, `src/cohost`, the emitter and the `lpy_snapshot_*` scripts, and `basilisp version`; a stale one is rebuilt (`LPY-SNAPSHOT-STALE another key` after an emitter change, shown), never loaded. `bb boot:lpy` boots from it when it is fresh, else boots as before. A build: 184,127 ms.

The emitter's Basilisp dialect: `.lpy` files, `basilisp.core/ns` headers (the replay's first call clears the refers Basilisp's ns form always makes), `(quote ns//)` written `(basilisp.core/symbol "ns" "/")` (Basilisp's reader takes no qualified `/`), Basilisp's reader position meta dropped. Two record/emit fixes it needed, for every host: a top-level `set!` of `*warn-on-reflection*` / `*unchecked-math*` is not recorded (it runs inside the load's binding; replayed outside it, it throws), and a regex is written with `\r \n \t` escaped (a raw CR in a `.lpy` becomes LF when Python reads it).

`basilisp run -p src dev/perf/lpy_snapshot_run.lpy` (boot!) against `bb boot:lpy` (fresh cache), alternating, `BOOT-MS` the boot alone:

| | runs (BOOT-MS) | median | process (boot + batteries) | battery, libraries |
|---|---|---:|---|---|
| boot! | 55087.051 / 55374.238 / 55274.832 | 55,275 ms | 1 m 58.101 s / 1 m 56.750 s / 1 m 55.722 s | 144/145, 61/61 |
| the cached boot | 2994.445 / 3099.626 / 3418.538 | 3,100 ms | 21.642 / 22.889 / 23.480 s | 144/145, 61/61 |

17.8x on the boot. The battery's one failure is the same on both: numeric tower, `(= 1 1.0)` is `true` on Basilisp (doc/REPORT.md).

## Basilisp, ClojureWasm, Phel, let-go

Boots, 2026-11-02. basilisp `bb boot:lpy`: stock 529, host 130, natives 36, skipped 96, errors 0, battery 143/145, libraries 61/61. ClojureWasm `bb boot:cljw`: stock 555, host 145, skipped 91, errors 0, battery 143/145, libraries 61/61. Phel `bb boot:phel`: stock 591, host 73, skipped 127, errors 0, battery 145/145, libraries 61/61. let-go `bb boot:lg`: stock 569, host 105, skipped 117, errors 0, battery 143/145, libraries 61/61.

ClojureScript 1.12.42 is in the local Maven repo (`clojure -P -M:cljs` exited 0). Node and Chromium benches are not started. They stay on the list.

`lpy` is basilisp 0.5.1's own core. The finished `bb bench:programs lpy` could not resolve `System/nanoTime` (the suite line was `raise ctx.AnalyzerException(`; a direct rerun of that wrap reported `unable to resolve symbol 'System/nanoTime'`). `first-output` does not use that call.

| program | lpy median | lpy cold | lpy/jvm | note |
|---|---:|---:|---:|---|
| fib | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| fib-hinted | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| tak | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| tak-hinted | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| nbody | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| nbody-hinted | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| spectral-norm | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| spectral-norm-hinted | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| mandelbrot | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| mandelbrot-hinted | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| binary-trees | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| binary-trees-hinted | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| fannkuch | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| fannkuch-hinted | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| nsieve | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| nsieve-hinted | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| k-nucleotide | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| pipeline | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| dispatch | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| json | ABSENT System/nanoTime | | | unable to resolve symbol System/nanoTime |
| first-output | 349.786772 | 345.477217 | 0.908253 | SAME |

`lpy-cohost` is still running. Finished rows are from `bb bench:programs lpy-cohost`.

| program | lpy-cohost median | lpy-cohost cold | lpy-cohost/jvm | note |
|---|---:|---:|---:|---|
| fib | 68668.971933 | 69068.767795 | 12972.463835 | SAME |
| fib-hinted | running | | | |
| tak | running | | | |
| tak-hinted | running | | | |
| nbody | running | | | |
| nbody-hinted | running | | | |
| spectral-norm | running | | | |
| spectral-norm-hinted | running | | | |
| mandelbrot | running | | | |
| mandelbrot-hinted | running | | | |
| binary-trees | running | | | |
| binary-trees-hinted | running | | | |
| fannkuch | running | | | |
| fannkuch-hinted | running | | | |
| nsieve | running | | | |
| nsieve-hinted | running | | | |
| k-nucleotide | running | | | |
| pipeline | running | | | |
| dispatch | running | | | |
| json | running | | | |
| first-output | running | | | |

`cljw` own core, `bb bench:programs cljw`, finished. All rows SAME. Geometric mean of the 21 ratios: `5.521527`.

| program | cljw median | cljw cold | cljw/jvm |
|---|---:|---:|---:|
| fib | 258.791722 | 250.40352 | 48.889129 |
| fib-hinted | 213.504856 | 218.850296 | 48.249565 |
| tak | 11.303418 | 11.833819 | 8.264738 |
| tak-hinted | 9.797178 | 9.715001 | 8.675459 |
| nbody | 7.543047 | 12.497634 | 1.945786 |
| nbody-hinted | 7.214054 | 9.352476 | 2.342861 |
| spectral-norm | 31.679446 | 31.754872 | 47.773611 |
| spectral-norm-hinted | 32.942604 | 33.127416 | 19.670726 |
| mandelbrot | 11.940398 | 12.024766 | 26.846351 |
| mandelbrot-hinted | 11.909486 | 11.927925 | 55.695006 |
| binary-trees | 3.024328 | 2.915662 | 5.831615 |
| binary-trees-hinted | 2.883956 | 2.85393 | 2.258389 |
| fannkuch | 162.470282 | 228.416185 | 7.620640 |
| fannkuch-hinted | 167.610682 | 205.18267 | 8.176583 |
| nsieve | 4.156203 | 4.059614 | 4.972183 |
| nsieve-hinted | 4.140753 | 3.936173 | 4.047257 |
| k-nucleotide | 1.321186 | 1.334856 | 1.174013 |
| pipeline | 6.357257 | 7.393957 | 2.532367 |
| dispatch | 7.764501 | 7.777447 | 3.580750 |
| json | 0.10543 | 0.14497 | 0.723158 |
| first-output | 10.710144 | 11.3251 | 0.027810 |

`cljw-cohost` is still running.

| program | cljw-cohost median | cljw-cohost cold | cljw-cohost/jvm | note |
|---|---:|---:|---:|---|
| fib | 313.386024 | 315.267143 | 59.202705 | SAME |
| fib-hinted | 1628.666196 | 1596.058136 | 368.059242 | SAME |
| tak | 11.18252 | 11.024245 | 8.176341 | SAME |
| tak-hinted | running | | | |
| nbody | running | | | |
| nbody-hinted | running | | | |
| spectral-norm | running | | | |
| spectral-norm-hinted | running | | | |
| mandelbrot | running | | | |
| mandelbrot-hinted | running | | | |
| binary-trees | running | | | |
| binary-trees-hinted | running | | | |
| fannkuch | running | | | |
| fannkuch-hinted | running | | | |
| nsieve | running | | | |
| nsieve-hinted | running | | | |
| k-nucleotide | running | | | |
| pipeline | running | | | |
| dispatch | running | | | |
| json | running | | | |
| first-output | running | | | |

`lg` own core, `bb bench:programs lg`, finished. All rows SAME. Geometric mean of the 21 ratios: `5.378788`.

| program | lg median | lg cold | lg/jvm |
|---|---:|---:|---:|
| fib | 202.471613 | 214.399309 | 38.249527 |
| fib-hinted | 206.750547 | 214.214823 | 46.723171 |
| tak | 6.812086 | 9.509864 | 4.980804 |
| tak-hinted | 6.650945 | 19.368971 | 5.889451 |
| nbody | 11.951767 | 9.009027 | 3.083049 |
| nbody-hinted | 11.661802 | 13.97779 | 3.787327 |
| spectral-norm | 37.54895 | 43.079839 | 56.625010 |
| spectral-norm-hinted | 30.511873 | 38.449635 | 18.219285 |
| mandelbrot | 6.996983 | 10.110264 | 15.731759 |
| mandelbrot-hinted | 11.109 | 9.557957 | 51.951514 |
| binary-trees | 2.685263 | 6.936737 | 5.177818 |
| binary-trees-hinted | 2.717584 | 3.20049 | 2.128105 |
| fannkuch | 202.899667 | 200.943873 | 9.516973 |
| fannkuch-hinted | 195.101098 | 186.708334 | 9.517653 |
| nsieve | 1.311874 | 3.954379 | 1.569432 |
| nsieve-hinted | 1.211636 | 3.649268 | 1.184278 |
| k-nucleotide | 1.671194 | 1.40621 | 1.485032 |
| pipeline | 28.093177 | 38.515226 | 11.190713 |
| dispatch | 12.924592 | 14.916383 | 5.960425 |
| json | 0.185182 | 0.31923 | 1.270188 |
| first-output | 7.064883 | 6.858866 | 0.018345 |

`lg-cohost` finished. `bb bench:programs lg-cohost`. All rows SAME. Geometric mean of the 21 ratios: `46.889842`.

| program | lg-cohost median | lg-cohost cold | lg-cohost/jvm |
|---|---:|---:|---:|
| fib | 220.115409 | 217.611492 | 41.582670 |
| fib-hinted | 2305.34758 | 2333.752483 | 520.981209 |
| tak | 9.19794 | 13.32779 | 6.725273 |
| tak-hinted | 145.126391 | 159.688673 | 128.510270 |
| nbody | 38.613692 | 78.998465 | 9.960696 |
| nbody-hinted | 38.735488 | 43.510931 | 12.579872 |
| spectral-norm | 280.964109 | 293.895161 | 423.702805 |
| spectral-norm-hinted | 396.917051 | 407.634758 | 237.007570 |
| mandelbrot | 86.303457 | 104.321966 | 194.041516 |
| mandelbrot-hinted | 87.492487 | 112.464563 | 409.160784 |
| binary-trees | 7.656029 | 7.471469 | 14.762623 |
| binary-trees-hinted | 12.633712 | 12.717552 | 9.893298 |
| fannkuch | 645.216733 | 689.460238 | 30.263776 |
| fannkuch-hinted | 642.003764 | 683.612019 | 31.318988 |
| nsieve | 38.244721 | 26.683283 | 45.753239 |
| nsieve-hinted | 42.087736 | 27.375508 | 41.137420 |
| k-nucleotide | 53.792721 | 74.169041 | 47.800498 |
| pipeline | 35.998721 | 39.038946 | 14.339829 |
| dispatch | 174.926754 | 169.391323 | 80.670851 |
| json | 2.960087 | 3.116886 | 20.303633 |
| first-output | 8712.230748 | 8717.079317 | 22.622088 |
`phel` own core finished, `bb bench:programs phel`. `phel-cohost` fib median is in the next table; the rest of that run is still going.

| program | phel median | phel cold | phel/jvm | note |
|---|---:|---:|---:|---|
| fib | 220.706048 | 290.279168 | 41.694249 | SAME |
| fib-hinted | ABSENT | | | [PHEL402] Phel\Lang\AbstractFn@anonymous(): Argument #1 ($n) must be of type long, int given, called in /tmp/phel/tmp/__phel_82352_c384c51a33c8d4892ade73239071d2c8_7a770e2acd188541 |
| tak | 2.781952 | 7.024128 | 2.034084 | SAME |
| tak-hinted | ABSENT | | | [PHEL402] Phel\Lang\AbstractFn@anonymous(): Argument #1 ($x) must be of type long, int given, called in /tmp/phel/tmp/__phel_82362_01802cdebf9fc3eddf1f9513b42b9c67_9529b9e03bbd94df |
| nbody | ABSENT | | | Class "Math" not found |
| nbody-hinted | ABSENT | | | PHP Warning:  "double" will be interpreted as a class name. Did you mean "float"? Write "\double" to suppress this warning in /tmp/phel/tmp/__phel_82369_7eacd53de524675e10c51f6a5cc |
| spectral-norm | ABSENT | | | Class "Math" not found |
| spectral-norm-hinted | ABSENT | | | [PHEL402] Phel\Lang\AbstractFn@anonymous(): Argument #1 ($i) must be of type long, int given, called in phar:///home/ubuntu/.cache/cohost-hosts/phel-v0.54.0/phel.phar/src/phel/core |
| mandelbrot | 72.243968 | 68.316928 | 162.430678 | SAME |
| mandelbrot-hinted | ABSENT | | | PHP Warning:  "double" will be interpreted as a class name. Did you mean "float"? Write "\double" to suppress this warning in /tmp/phel/tmp/__phel_82398_ab517d5ed9ff3b80118751ad348 |
| binary-trees | 8.329728 | 8.861952 | 16.061673 | SAME |
| binary-trees-hinted | ABSENT | | | [PHEL402] Phel\Lang\AbstractFn@anonymous(): Argument #1 ($d) must be of type long, int given, called in /tmp/phel/tmp/__phel_82468_8f9d5b64f57c7a849a19dcbd46307d27_b2b8881c6479acd1 |
| fannkuch | 343.599104 | 307.492096 | 16.116455 | SAME |
| fannkuch-hinted | 438.744832 | 484.914944 | 21.403370 | SAME |
| nsieve | 8.925184 | 9.494272 | 10.677450 | SAME |
| nsieve-hinted | 16.93184 | 9.447936 | 16.549529 | SAME |
| k-nucleotide | 7.849984 | 4.425984 | 6.975538 | SAME |
| pipeline | 33.619968 | 38.828288 | 13.392270 | SAME |
| dispatch | ABSENT | | | [PHEL001] Cannot resolve symbol 'w'. Did you mean 'when', '%', or '*'? |
| json | 0.129024 | 0.446976 | 0.884993 | DIFFER expected 580 got 0 |
| first-output | ABSENT | | | Cannot resolve symbol println; bench/programs/first_output.clj does not start with an ns form |

Geometric mean of the 11 phel / JVM median ratios for rows that produced a time: `12.451088`.

`phel-cohost` is still running where a row says running.

| program | phel-cohost median | phel-cohost cold | phel-cohost/jvm | note |
|---|---:|---:|---:|---|
| fib | 316.849166 | 512.81058 | 59.856937 | SAME |
| fib-hinted | running | | | |
| tak | running | | | |
| tak-hinted | running | | | |
| nbody | running | | | |
| nbody-hinted | running | | | |
| spectral-norm | running | | | |
| spectral-norm-hinted | running | | | |
| mandelbrot | running | | | |
| mandelbrot-hinted | running | | | |
| binary-trees | running | | | |
| binary-trees-hinted | running | | | |
| fannkuch | running | | | |
| fannkuch-hinted | running | | | |
| nsieve | running | | | |
| nsieve-hinted | running | | | |
| k-nucleotide | running | | | |
| pipeline | running | | | |
| dispatch | running | | | |
| json | running | | | |
| first-output | running | | | |


## ClojureScript Node

`bb boot:cljs`: stock 518, host 87, natives 46, skipped 140, errors 0, battery 143/145, libraries 61/61. `bb bench:programs cljs` is the host's own core (`cljs.main -re node`). `js/Date.now` is millisecond resolution, so a median of 0 is a sub-millisecond run. `cljs-cohost` (`node target/cljs-node/main.js --eval`) is running.

| program | cljs median | cljs cold | cljs/jvm | note |
|---|---:|---:|---:|---|
| fib | 6.000128 | 19.000064 | 1.133502 | SAME |
| fib-hinted | 5.999872 | 6.999808 | 1.355900 | SAME |
| tak | ABSENT | | | ABSENT undeclared Var cljs.user/read-string; TypeError Cannot read properties of undefined (reading call) |
| tak-hinted | ABSENT | | | ABSENT undeclared Var cljs.user/read-string; TypeError Cannot read properties of undefined (reading call) |
| nbody | 13.999872 | 37.999872 | 3.611373 | SAME |
| nbody-hinted | 12.0 | 41.000192 | 3.897162 | SAME |
| spectral-norm | 3.999744 | 32.0 | 6.031741 | SAME |
| spectral-norm-hinted | 8.0 | 36.999936 | 4.776969 | SAME |
| mandelbrot | 0.999936 | 8.0 | 2.248219 | SAME |
| mandelbrot-hinted | 0.999936 | 8.0 | 4.676225 | SAME |
| binary-trees | 0.0 | 2.999808 | | SAME, median 0 excluded from the geometric mean |
| binary-trees-hinted | 0.0 | 4.999936 | | SAME, median 0 excluded from the geometric mean |
| fannkuch | 88.999936 | 272.999936 | 4.174526 | SAME |
| fannkuch-hinted | 85.999872 | 248.999936 | 4.195348 | SAME |
| nsieve | 0.999936 | 16.999936 | 1.196252 | SAME |
| nsieve-hinted | 5.999872 | 16.999936 | 5.864399 | SAME |
| k-nucleotide | 5.000192 | 8.999936 | 4.443197 | SAME |
| pipeline | 8.999936 | 40.0 | 3.585059 | SAME |
| dispatch | ABSENT | | | ABSENT Can't change/establish root binding of: *unchecked-if* with set |
| json | 0.0 | 0.999936 | | DIFFER expected 580 got 0; median 0 excluded from the geometric mean |
| first-output | 8614.866417 | 8293.703139 | 22.369272 | SAME |

Geometric mean of the 15 non-zero cljs / JVM median ratios: `3.663063`. Zero medians excluded: binary-trees, binary-trees-hinted, json.


`cljs-cohost` finished. `node target/cljs-node/main.js --eval`. All rows SAME. Geometric mean of the 21 ratios: `3.568417`.

| program | cljs-cohost median | cljs-cohost cold | cljs-cohost/jvm |
|---|---:|---:|---:|
| fib | 16.511291 | 34.985732 | 3.119198 |
| fib-hinted | 124.01835 | 166.855207 | 28.026676 |
| tak | 0.962271 | 2.08558 | 0.703585 |
| tak-hinted | 9.097164 | 47.388461 | 8.055592 |
| nbody | 5.246313 | 28.399463 | 1.353326 |
| nbody-hinted | 10.752265 | 23.718061 | 3.491943 |
| spectral-norm | 8.002536 | 21.976406 | 12.068079 |
| spectral-norm-hinted | 26.05276 | 34.078675 | 15.556654 |
| mandelbrot | 1.674245 | 5.282076 | 3.764311 |
| mandelbrot-hinted | 1.570977 | 7.227463 | 7.346713 |
| binary-trees | 0.197245 | 7.655943 | 0.380335 |
| binary-trees-hinted | 0.861213 | 11.450477 | 0.674405 |
| fannkuch | 113.238141 | 208.027084 | 5.311415 |
| fannkuch-hinted | 129.769042 | 289.164348 | 6.330547 |
| nsieve | 3.198177 | 24.14507 | 3.826069 |
| nsieve-hinted | 3.030083 | 24.821575 | 2.961666 |
| k-nucleotide | 1.412419 | 7.227048 | 1.255083 |
| pipeline | 4.058098 | 9.748818 | 1.616514 |
| dispatch | 10.255879 | 35.585418 | 4.729697 |
| json | 0.258467 | 0.570729 | 1.772860 |
| first-output | 7620.182209 | 7562.899564 | 19.786486 |

## Chromium

`bb boot:browser` exited 0. stock 518, host 87, natives 46, skipped 140, errors 0, battery 143/145, libraries 61/61, elapsed 11041 ms. The page in `src/cohost/run_browser.cljs` runs the battery or `?bench` only. It has no `--eval`, so `bb bench:programs browser` and `browser-cohost` stay ABSENT: no program hook.

## Still running at close

These three `bb bench:programs` processes were still running when G-13 closed. Rows below are the lines already printed.

`lpy-cohost` fib 68668.971933, fib-hinted 146340.083443, tak 2006.472601, tak-hinted 5346.053021, nbody 1152.967872, nbody-hinted 1148.295367, all SAME. The rest of that suite had not printed.

`cljw-cohost` had printed through binary-trees median 4.066756 SAME. The rest had not printed.

`phel-cohost` had printed through dispatch median 234.659488 SAME. spectral-norm and spectral-norm-hinted ABSENT `Seq::range` end must be int|float, BigInt given. fannkuch and fannkuch-hinted ABSENT the same `Seq::range` BigInt error. nsieve and nsieve-hinted ABSENT `Unsupported operand types: int << BigInt`. pipeline ABSENT the same `Seq::range` BigInt error.


## Cross-host: capability keys per host (jank-MAX X3-1)

Each capability-gated gain is a key in a host's adapter map (`src/cohost/hosts/*`), default off; X3 turns it
on per host where that host's own bench says it pays. First key: `:multimethod-cache?` (X2-1/X2-2), the shim's
`MultiFn` with a one-entry dispatch cache.

**Which hosts run the shim's MultiFn: all of them.** `rewrite.cljc`'s `rw-new` sends
`(new clojure.lang.MultiFn ...)` to the shim's `new-MultiFn` on every host (only `java.util` collections pass
through to the host); probed on jolt and the JVM: `(:cohost.shim/multifn (meta f))` is true. So the key
reaches the multimethod case on the JVM too (an earlier `(class f)` probe that printed `clojure.lang.MultiFn`
was the shim's class mapping, not the JVM's class).

Method: base `max/main` @ `aeac81b`; key nil is a clean tree, key true is the same tree with the key in each
host's map; `bb bench:<host> --quick` (the JVM: the full `bb bench:jvm`, since `--quick` has no warmup there:
4.93-86.32 ms on one case), the two sides alternated A B A B ..., median and [spread] of the stock side;
lpy's two sides ran at the same time in each round (about 60 minutes a round at this load); `bb boot:<host>` on both sides. Box: 4 CPUs,
15 GB, shared with lanes u1 and u5, no lock, load 6-15; 2026-11-02, 2:30-5 PM ET. A host is flipped when the
multimethod case improves and no case's true median lies above its nil maximum.

| host | rounds | multimethod nil -> true, ms | geomean x nil -> true | battery / libraries (both sides) | key |
|---|---:|---|---|---|---|
| JVM (Clojure 1.12.0, Java 21) | 5 full | 8.49 [4.61-8.76] -> 7.24 [7.13-7.35] | 1.28 -> 1.25 | 149/149, 61/61 (148/149 under `LC_ALL=C.UTF-8`, both sides: below) | **on** |
| jolt 0.8.23 | 9 | 20.19 [15.19-30.48] -> 18.29 [15.02-26.39] | 1.36 -> 1.25 | 149/149, 61/61 | **on** |
| ClojureWasm 1.15.4 | 8 | 155.22 [96.68-191.55] -> 108.44 [80.94-141.07] | 4.85 -> 4.77 | 147/149, 61/61 | **on** |
| Phel 0.54.0 | 9 | 813.96 [517.76-968.47] -> 391.87 [267.88-522.38] (true below nil 9 of 9) | 4.37 -> 4.36 | 149/149, 61/61 | **on** |
| let-go 1.13.0 | 9 | 287.95 [211.03-509.99] -> 280.29 [202.36-412.69] (true below nil 6 of 9) | 6.28 -> 5.96 | 147/149, 61/61 | off: inside the noise |
| ClojureScript, Node, before X2-3 | 3 | 26.26 [24.61-76.55] -> 55.58 [49.48-69.24] | 1.27 -> 1.41 | nil 147/149, 61/61; **true 144/149, 59/61** | incorrect |
| ClojureScript, Chromium, before X2-3 | 3 | 51.39 [49.60-54.89] -> 40.09 [38.29-47.30] | 1.27 -> 1.31 | nil 147/149, 61/61; **true 144/149, 59/61** | incorrect |
| ClojureScript, Node, on X2-3 (`2f07bfc`) | 9 | 68.57 [28.07-87.89] -> 52.96 [21.49-129.66] (true below nil 7 of 9) | 1.37 -> 1.25 | 147/149, 61/61 | **on** (X3-2, `47d43ca`) |
| ClojureScript, Chromium, on X2-3 (`2f07bfc`) | 9 | 51.59 [20.84-83.10] -> 38.00 [14.09-55.30] (true below nil 7 of 9) | 1.37 -> 1.30 | 147/149, 61/61 | **on** (X3-2, `47d43ca`) |
| basilisp 0.5.1 | 3 paired | 21590.20 -> 21098.93, 16245.01 -> 19659.29, 7001.37 -> 6607.08 (round 3, X3-2, load 4.6): paired ratios 0.98, 1.21, 0.94 | 1.88 -> 1.85, 2.04 -> 1.80, 1.98 -> 1.92 | 148/149 (numeric tower), 61/61 | off: no gain |
| clojurust 0.2.32 | - | not measurable on a 15 GB box | - | `bb boot:cljrs` OOM-killed at 4.7 GB RSS (exit 137) or exit 139; `bb bench:cljrs` fails at key nil 3/3 ("wrong type: expected intercepted, got find-ns sentinel") | off |
| jank | - | (on since X2-1, with its own `cpp/raw` hit fn, X2-2) | - | - | on |

Notes:

- cljs/Chromium with the key before X2-3: battery rows "a class multimethod reaches user types", "multimethod: a
  miss is not cached", "multimethod: defmethod after a call is seen", libraries clojure.test "a failing is reports
  FAIL", "thrown? are testing" failed (a `::miss` sentinel that ClojureScript does not intern). X2-3 fixed it; the
  rows above "on X2-3" are measured on its merge, nil = a clean `2f07bfc` tree.
- X2-2's hit fn: on a non-jank host with the key true the shim's `new-MultiFn` calls a no-op `h-mm-hit` per
  dispatch; on the JVM the true side's multimethod was 3.23-3.40 ms at `5fd3e3e` (before X2-2) and 7.13-7.35 ms
  at `aeac81b`. Choosing the arity fns at construction would give it back (shared layer, X2-3).
- The JVM battery row "reduce over an eduction; a :tag that is data" fails with `LC_ALL=C.UTF-8` exported
  (`clojure.core.Eduction cannot be cast to clojure.lang.IReduce`, 7 of 7 boots) and passes with LANG/LC_ALL
  unset (4 of 4), the same tree; not the key's.
- The reduce and sort arms of X4-1 (`doc/research/jank-workarounds.md`): swept in X3-2, below.
- Next keys as they merge: the fn name on defn (M-9).

The keys that only jank sets (no cross-host sweep: each is a jank codegen fact):

| key | jank's value | what it does | measured |
|---|---|---|---|
| `:native-hints` | `{long long, double double, boolean bool, int int}` | the primitive hints passed through as native tags (H0/H1, U6-5) | doc/perf/jank.md "Type hints" |
| `:native-local-casts` | `{long cpp/jank.runtime.to_int, double cpp/jank.runtime.to_real}` | a hinted let*/loop* local's init through the host's typed conversion | U6-5 |
| `:defn-fn-name?` | true | the def's name on its fn*: self-calls are direct | doc/perf/jank.md "Emission gaps" |
| `:static-aliases` | `{"Numbers-minus" clojure.core/-}` | the shim's `-` wrapper emitted as jank's `-` | doc/perf/jank.md "Emission gaps" (M-9) |
| `:core-aliases` | `#{not}` | a call of the stock `not` emitted as jank's `not`; the stock fn stays booted | U6-6: with `-` and `not` as jank's own ops, fib cohost/jank-aot 5.09x -> 3.18x, tak 5.76x -> 2.82x |

### `:sort-host-compare?` (X3-2): X4-1's sort arm on every host

X4-1 made jank's sort take the host's compare when the comparator is the stock core's `compare` (whose body is
the host's compare), instead of the shim's comparator wrapper around a stock fn call per comparison. X3-2 made it
the adapter key `:sort-host-compare?` (default nil; `shim.cljc` `stock-compare`, `Arrays-sort`, `-compare`;
`81259d2`) and measured it per host with X3-1's method (base `max/main` @ `4698b55`; key nil = that tree, key
true = the same with the key; `bb bench:<host> --quick`, the JVM's full `bb bench:jvm`; load 13-18, box shared
with u1 and u5, no lock). The first pass turned on both X4 arms together (this key and a counted `nth` loop for
the shim's reduce over a host vector); the reduce key paid nowhere (transduce, into-vector, group-by worse on lg,
cljw, phel, jolt) and is not committed. Hosts where the pair showed a sort gain were re-measured with the sort key
alone.

| host | rounds | sort nil -> true, ms | sort-by nil -> true | geomean x | battery / libraries (both sides) | key |
|---|---:|---|---|---|---|---|
| JVM | 3 full, both keys | 21.78 [21.08-23.89] -> 23.69 [21.91-24.69] | 12.47 -> 11.02 | 1.28 -> 1.24 | 149/149, 61/61 (LANG/LC_ALL unset) | off: no gain |
| jolt | 3, both keys | 61.36 [51.05-81.73] -> 60.37 [56.99-75.50] | 36.22 -> 44.10 | 1.43 -> 1.27 | 149/149, 61/61 | off: no gain |
| ClojureScript, Node | 3, both keys | 52.95 [52.93-55.54] -> 54.00 [41.84-65.90] | 70.33 -> 80.63 | 1.18 -> 1.26 | 147/149, 61/61 | off: no gain |
| ClojureScript, Chromium | 3, both keys | 23.20 [19.15-32.79] -> 46.19 [43.69-66.70] | 22.89 -> 79.30 | 1.31 -> 1.34 | 147/149, 61/61 | off: slower |
| ClojureWasm | 3 both keys, then 6 sort key alone | both: 4755.30 -> 3979.81; alone: 4257.44 [2310.55-5461.05] -> 4041.50 [1946.94-5434.06] (true below nil 4 of 6) | alone: 2236.85 -> 2219.78 | alone: 4.67 -> 5.11 | 147/149, 61/61 | off: inside the noise alone |
| let-go | 9 sort key alone, then 6 with the order reversed | 1988.31 [1022.21-2573.78] -> 1238.85 [642.76-2082.75] (8 of 9); reversed 1854.82 [962.74-2412.19] -> 912.30 [568.96-1283.22] (6 of 6) | 1661.50 -> 1688.61; 1630.72 -> 1587.01 | 5.90 -> 5.88; 5.99 -> 5.95 | 147/149, 61/61 | **on** (`04733e3`) |
| Phel | 6 sort key alone | 1200.53 [1010.93-1753.69] -> 807.75 [746.68-928.35] (6 of 6) | 1570.43 -> 1542.78 | 4.31 -> 4.17 | 149/149, 61/61 | **on** (`b1899fc`) |
| basilisp | - | not run (a quick bench is ~25-60 min a side here) | - | - | - | off |
| clojurust | - | not measurable (X3-1) | - | - | - | off |
| jank | - | always on (X4-1's `#?(:jank ...)` arm) | - | - | - | on |

- let-go's first 9 rounds (nil always first) flagged the multimethod case 415.92 -> 516.42 ms; the key has no path
  there, and with the order reversed it went 450.25 -> 393.35: order bias, not the key.
- Phel's first run with the key failed: Phel has no `ns-resolve`; off jank `stock-compare` now finds the stock
  compare through the adapter's own `:interned-var` / `:var-get`.
- On Chromium the host's compare through JS `Array.prototype.sort` was slower than the shim's wrapper.


## jank's collector environment (jank-MAX A1)

Apt jank 0.1-noble (UNPATCHED) starts bdwgc from its own defaults; patch 19 compiles a default initial heap into
`jank_init` when `GC_INITIAL_HEAP_SIZE` is unset. A1 sets the collector environment from cohost's side for every jank
process cohost launches: `dev/perf/jank_gc_env.clj` (`GC_INITIAL_HEAP_SIZE=512M`; a `GC_*` variable the user sets
wins key by key; `COHOST_JANK_GC=none` sets nothing), passed by `bb.edn`'s `jank!` (boot:jank on the snapshot or the
AOT modules, boot:jank-snapshot, boot:jank-jit, bench:jank) and the tasks that shell jank directly (boot:jank-profile,
boot:jank-aot), and by `bench:programs`' cohost-on-jank target (`jank-jit`/`jank-aot` keep jank as shipped). The
snapshot bakes no heap: bdwgc reads the variable at `GC_init`. Commit `d537775`. Measured 2026-11-02, 7:30-10:20 PM
ET, apt jank, `max/main` @ `8434556`, box 4 CPUs / 15 GB shared with lanes u1 and u5 (no lock).

**The matrix** (3 interleaved rounds per cell, run as three parallel streams with the cell order rotated, load 4-11;
median [min-max]; ms unless named; heap:divisor, 3 = bdwgc's default divisor; `boot` = `bb boot:jank` on the
snapshot, `bootaot` = `JANK_BIN=/usr/local/bin/jank bb boot:jank`). Every one of the 42 boots: 542/171/78/0,
147/149, 61/61; `run.sh 17` (counts only) identical in every cell.

| row | none | 128M:2 | 128M:3 | 256M:2 | 256M:3 | 512M:2 | 512M:3 |
|---|---|---|---|---|---|---|---|
| run18 [i i] ms | 1628.4 [1000-1775] | 452.0 [377-723] | 470.0 [340-1055] | 340.0 [309-398] | 949.8 [341-1141] | 516.2 [466-548] | 768.7 [386-941] |
| run18 bt ms | 372.2 [205-389] | 214.9 [131-807] | 122.4 [114-216] | 123.2 [108-199] | 303.9 [192-395] | 153.6 [122-264] | 194.4 [84-318] |
| prog binary-trees-14 | 217.2 [205-363] | 467.2 [430-508] | 279.0 [233-341] | 237.3 [142-338] | 433.0 [391-521] | 172.2 [143-303] | 246.6 [152-315] |
| prog lazy-pipeline | 3772.3 [3423-5192] | 6308.9 [4132-6508] | 2656.0 [1805-3913] | 2774.2 [2281-4949] | 5905.6 [5448-9522] | 3565.1 [3298-6454] | 3168.5 [2239-5740] |
| prog keyword-map | 1012.7 [847-1137] | 1804.1 [1551-2205] | 918.7 [567-1006] | 649.9 [517-766] | 1372.4 [924-1448] | 763.0 [689-1361] | 638.9 [509-674] |
| prog json | 8156.5 [6725-13866] | 14194.1 [12822-14478] | 10135.8 [8997-13604] | 7937.6 [6131-12611] | 13300.5 [12752-13373] | 8598.6 [6262-13036] | 12586.9 [6313-13162] |
| boot wall s | 38.0 [26-51] | 77.4 [66-85] | 46.5 [37-79] | 39.2 [35-94] | 55.5 [24-75] | 36.8 [35-66] | 35.2 [24-90] |
| boot maxrss KB | 801708.0 [795736-805040] | 888104.0 [887880-888344] | 887976.0 [887960-888128] | 1029900.0 [1029848-1030072] | 1030152.0 [1029960-1030208] | 1314140.0 [1314120-1314200] | 1314144.0 [1314032-1314208] |
| bootaot wall s | 204.6 [173-335] | 443.1 [247-464] | 331.7 [215-449] | 347.7 [198-506] | 232.9 [174-411] | 213.6 [203-236] | 184.5 [175-462] |
| bootaot maxrss KB | 1778432.0 [1778280-1795964] | 1817704.0 [1817696-1817920] | 1782320.0 [1782288-1782576] | 1888680.0 [1888628-1892588] | 1888776.0 [1888644-1888872] | 2172876.0 [2172660-2172884] | 2176196.0 [2172716-2176400] |

512M beats bdwgc's defaults on 6 of 8 timed rows at either divisor (256M:2: 5, 128M:3: 4); at divisor 3 it wins
keyword-map and both boot walls outright. Boot maxrss: 1.64x apt's on the snapshot path, 1.22x on the AOT path
(within the 2x bar). The three streams made the program rows noisy, so they were measured again:

**Re-measure, one stream** (4 rounds, order rotated, `bb bench:programs jank`, MEDIAN-MS, median of the 4 rounds):

| program | none | 256M:2 | 512M |
|---|---:|---:|---:|
| binary-trees-14 | 160.4 | 136.1 | **114.6** |
| json | 5251.6 | 4541.4 | **3922.5** |
| keyword-map | 609.8 | 469.0 | **465.0** |
| lazy-pipeline | **2052.2** | 2506.2 | 2876.3 |

**Before / after** (`COHOST_JANK_GC=none` / the default, 3 interleaved rounds):

| measure | before | after |
|---|---|---|
| `bb bench:jank`, snapshot leg: stock geomean over 29 cases | 1 | **0.506** (15 cases below every before run, 0 above; format 413.66 -> 276.57 ms, sort 103.92 -> 24.16, lazy-pipeline 231.52 -> 62.34) |
| `bb bench:jank`, AOT leg | 1 | **0.856** (11 below, 7 above; format 408.81 -> 316.16) |
| `bench:programs jank` binary-trees-14 | 159.85 / 166.89 / 383.68 | 177.05 / 185.11 / 390.79 |
| `bench:programs jank` lazy-pipeline | 2376.57 / 2859.33 / 3114.86 | 3957.01 / 4028.63 / 4121.70 |
| `bb boot:jank` (snapshot) maxrss KB | 795580 / 802448 / 805212 | 1313788 / 1314248 / 1314428 |

lazy-pipeline is slower with the larger heap in both program measurements; binary-trees-14 was faster in the
single-stream re-measure and level in the before/after. The default stays 512M: it wins bench:jank on both legs,
`run.sh 18`, and three of the four program rows.

**PATCHED** (`JANK_BIN` = a source build carrying 19-gc-defaults, `/usr/local/lib/jank-dev`: 11 13 14 12-21 18 19;
3 rounds): not a no-op. Patch 19 applies its own heap only when the variable is unset, so cohost's 512M replaces it:
boot (AOT) maxrss 1776172 -> 2166628 KB (1.22x), binary-trees-14 80.86 -> 56.19 ms; boots 542/171/78/0, 147/149,
61/61 both ways. `COHOST_JANK_GC=none` gives a patched binary its own default.

Lane D's D-4 (cohost's mainline, not yet in `max/main`) chose the same 512M on another box for the CLI launch
(`dev/perf/jank_snapshot_cli.clj`); when `max/main` takes the mainline, that literal should read `(jank-gc-env)`.

## cohost-max: unpatched and patched

The jank-MAX fleet's scoreboard in two columns: UNPATCHED is apt jank 0.1-noble with cohost's launcher (A1's
`GC_INITIAL_HEAP_SIZE=512M` in effect), PATCHED is the 21-patch series build
(`JANK_BIN=/home/user/int-a/jank-inst/bin/jank`; the launcher's 512M applies there too). x1 writes the programs
table under this heading (X1-5); the `bb bench:jank` table is below (X1-5b).

### `bb bench:jank`: the stock core against jank's own core (X1-5b, u0)

Measured 2026-11-02/03, 10:50 PM-12:20 AM ET, on max/x3 @ `ce94816` (= max/main with `651592c` + A1), int-a's box
(4 CPUs, 15 GB, six lanes, no lock). Legs interleaved per round: apt on the snapshot, apt on the AOT modules
(`JANK_BIN=/usr/local/bin/jank`), patched on the AOT modules (a `JANK_BIN` binary has no snapshot). Rounds 1-2 ran
at load 8-14; from round 2's second leg on, each leg started only under the load gate (1-minute load < 8, free
memory > 4 GB). Rounds 4-5 are two extra AOT pairs for the regex row. x = stock / jank's own (the printed ratio);
the geomean is over the 30 printed x values of a run, median [range] over the runs.

| | apt, snapshot (3 runs) | apt, AOT (5 runs) | patched, AOT (5 runs) |
|---|---:|---:|---:|
| stock core / jank's own core, geomean x | **1.47** [1.38-1.50] | **1.48** [1.38-1.79] | **2.29** [1.87-2.56] |

The patched column's ratio is higher because the series speeds jank's own core more than the stock core
(lazy-pipeline, AOT medians: jank's own 82.39 -> 2.10 ms, 39x; the stock core 157.01 -> 24.83 ms, 6.3x).

Per case, stock-side ms median [range], and the median x:

| case | apt-snapshot stock ms | x | apt-aot stock ms | x | patched-aot stock ms | x | patched median above apt AOT max |
|---|---|---|---|---|---|---|---|
| fn-call | 1.27 [1.27-1.33] | 2.06 | 1.30 [1.28-1.38] | 2.09 | 0.21 [0.21-0.22] | 1.00 |  |
| loop-arith | 0.00 [0.00-0.00] | 1.06 | 0.00 [0.00-0.00] | 1.05 | 0.05 [0.05-0.05] | 0.99 | yes |
| hinted-loop | 0.00 [0.00-0.00] | 0.00 | 0.00 [0.00-0.00] | 0.00 | 0.05 [0.05-0.05] | 0.99 | yes |
| reduce-range | 3.79 [3.76-11.71] | 0.34 | 3.95 [3.55-7.66] | 0.49 | 0.97 [0.97-0.99] | 0.78 |  |
| lazy-pipeline | 93.44 [86.22-166.07] | 1.64 | 157.01 [72.17-246.86] | 1.90 | 24.83 [24.81-29.20] | 11.90 |  |
| transduce | 13.81 [10.06-24.95] | 2.01 | 16.01 [9.65-19.54] | 2.27 | 4.71 [4.69-6.66] | 2.83 |  |
| into-vector | 4.83 [4.71-12.52] | 0.99 | 5.94 [4.36-8.66] | 1.01 | 1.69 [1.68-1.72] | 1.13 |  |
| conj-vector | 19.90 [14.32-29.95] | 0.71 | 18.75 [9.91-22.01] | 0.83 | 10.55 [10.24-14.90] | 1.03 |  |
| transient-vector | 34.81 [22.34-55.47] | 6.52 | 34.95 [23.77-44.97] | 5.61 | 35.18 [34.55-63.49] | 35.30 |  |
| assoc-map | 34.90 [22.81-57.27] | 1.01 | 22.82 [22.29-50.86] | 0.96 | 22.27 [21.90-39.20] | 1.04 |  |
| map-lookup | 12.09 [8.07-20.27] | 0.96 | 8.42 [8.26-16.42] | 0.62 | 5.49 [5.38-9.44] | 1.47 |  |
| keyword-lookup | 8.85 [8.76-12.87] | 0.52 | 4.81 [4.74-8.88] | 0.48 | 1.69 [1.65-1.70] | 1.02 |  |
| destructure | 21.33 [15.28-37.11] | 1.53 | 25.08 [13.24-25.21] | 1.44 | 6.96 [6.84-10.85] | 4.10 |  |
| frequencies | 60.65 [56.32-90.67] | 1.31 | 93.62 [37.22-97.74] | 1.70 | 16.45 [16.25-20.55] | 2.98 |  |
| group-by | 27.63 [22.36-46.44] | 1.08 | 34.59 [18.74-40.17] | 1.02 | 12.14 [12.03-16.42] | 1.03 |  |
| sort | 48.56 [46.08-60.37] | 1.75 | 37.79 [24.36-52.68] | 1.76 | 10.68 [10.56-14.79] | 1.97 |  |
| sort-by | 65.31 [44.11-105.46] | 5.27 | 45.19 [41.36-94.08] | 5.16 | 8.48 [7.98-9.22] | 1.01 |  |
| set-build | 14.00 [9.88-22.78] | 1.07 | 13.69 [9.53-18.14] | 1.04 | 4.91 [4.89-9.07] | 1.09 |  |
| distinct | 346.44 [324.73-461.66] | 0.93 | 437.98 [192.78-526.05] | 1.07 | 75.65 [74.22-107.36] | 1.12 |  |
| partition | 121.67 [92.43-202.26] | 1.65 | 160.40 [58.01-243.18] | 1.70 | 34.26 [33.37-56.50] | 1.47 |  |
| lazy-iterate | 8.67 [5.06-12.88] | 1.65 | 4.83 [4.56-8.99] | 1.29 | 1.55 [1.54-1.60] | 1.70 |  |
| atom-swap | 9.34 [5.36-13.22] | 1.10 | 5.45 [5.22-9.46] | 1.14 | 4.32 [4.22-4.65] | 1.00 |  |
| protocol | 19.64 [16.81-28.57] | 0.97 | 12.05 [11.72-24.32] | 0.90 | 3.90 [3.84-4.20] | 0.98 |  |
| multimethod | 5.29 [3.88-8.03] | 0.18 | 3.93 [3.90-7.96] | 0.15 | 2.19 [2.17-2.45] | 1.62 |  |
| equality | 0.72 [0.71-0.73] | 0.99 | 0.71 [0.68-0.72] | 1.00 | 0.70 [0.68-0.73] | 0.99 |  |
| str-build | 60.57 [27.90-74.13] | 3.88 | 39.20 [29.23-65.34] | 4.70 | 16.54 [16.31-31.81] | 6.02 |  |
| string-join | 56.47 [29.25-71.07] | 0.02 | 27.55 [25.85-113.36] | 0.00 | 27.22 [21.68-41.44] | 0.00 |  |
| regex | 228.62 [193.13-437.10] | 69.87 | 262.87 [244.39-376.49] | 96.18 | 322.33 [212.62-489.66] | 108.14 |  |
| format | 366.52 [323.03-732.09] | 107.45 | 310.01 [260.27-596.84] | 102.24 | 420.56 [281.06-559.33] | 229.85 |  |
| pr-str | 1.00 [1.00-1.00] | 1.00 | 0.97 [0.96-0.98] | 1.00 | 1.02 [1.01-1.05] | 1.04 | yes |

The rows marked: loop-arith and hinted-loop take 0.00 ms on apt because its i32 loop arithmetic wraps and the loop
folds; the series computes the i64 sum (12-21) in 0.05 ms. pr-str is a 1-ms case (0.97 -> 1.02). No other case's
patched median is above apt's AOT maximum; regex, flagged after 3 runs (402.92 above 376.49), is not after 5.

**format** (X1-4's open question): stock ms per round, apt AOT / patched AOT: 596.84 / 424.77 (load 13), 461.46 /
420.56 (8), 277.75 / 329.87 (6), 260.27 / 281.06 (4-5), 310.01 / 559.33 (4-7). The series was faster in two pairs
and slower in three; the medians are 310.01 and 420.56 with overlapping ranges. A format regression on the
series is not established at this spread; the low-load pairs (rounds 3-4) put it at 1.08-1.19x if it exists. On
apt the snapshot leg is not slower than the AOT leg (366.52 vs 310.01, ranges overlapping).

`bb boot:jank` in this tree: apt (snapshot and AOT) 525/171/17 natives/78/0, battery 170/172 (subs count nth first
last butlast; Character outside ASCII), libraries 62/62; patched 523/171/19 natives/78/0 (sort and sort-by become
natives where the sort walks linearly, `651592c`), the same 170/172 and 62/62.

**On max/main `034534a`** (A4's extension: chunked ranges, the linear sorted walk, string nth; max/x3 @
`66329bd`), one round under the load gate (load 4.5-6.8, free 5-7 GB), 2026-11-03 1:00-1:09 AM ET. Stock ms /
x, jank's own ms in brackets; the table above is on `ce94816`, before it:

| row | apt, snapshot | apt, AOT | patched, AOT |
|---|---|---|---|
| sort | **10.78** / x0.31 (own 33.99); was 48.56 | **19.38** / x0.59 (own 32.37); was 37.79 | **6.49** / x1.18 (own 5.46); was 10.68 |
| lazy-pipeline | **20.02** / x0.23 (own 86.43); was 93.44 | **15.25** / x0.06 (own 244.93); was 157.01 | **7.17** / x3.40 (own 2.10); was 24.83 |
| geomean x over the 30 cases (this one round) | 1.22 | 1.22 | 2.05 |

On `034534a` the stock core beats jank's own core on both rows on apt (x < 1), and on the series it is within 3.4x
(lazy-pipeline) and 1.18x (sort). `bb boot:jank` there: 518/171/24 natives/78/0, battery 176/178, libraries 62/62
on all three (the snapshot, apt AOT, patched AOT).
