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

jank-FAST Step 8. `bb bench:programs <target>` and `bb bench:programs all`. Programs live in `bench/programs/`. The driver is `dev/bench_programs.clj`. `MEDIAN-MS` is the middle of five timed calls after one untimed call in the same process. `COLD-MS` is one call in a fresh process. The clock is inside the process (`System/nanoTime` on the JVM, `cpp/jank.runtime.current_time` on jank). Each timed call reads its size argument at runtime and adds the result into a volatile. A program's printed result is compared with the JVM result. `first-output` is process wall time until stdout contains `ok` (one cold process, one discarded warmup process, then five).

Sizes: fib `30`, tak `18 12 6`, nbody `200`, spectral-norm `40`, mandelbrot `32`, binary-trees `8`, fannkuch `7`, nsieve `12`, k-nucleotide `2000`, pipeline `2000`, dispatch `20000`, json `20`.

Measured 2026-11-01. `bb bench:programs jvm`, then `bb bench:programs jank-jit`, then `bb bench:programs jank-aot` (`compile-module -O3`, then `run-main`), then `bb bench:programs jank` (cohost's stock core, AOT boot, program source passed to `--eval`), then `bb bench:programs cljrs` (clojurust's own core), then `bb bench:programs jvm-cohost` (`clojure -M -m cohost.run -- --eval`), then `bb bench:programs cljrs-cohost`. A timed row printed `SAME` and the JVM result. `ABSENT` is the host error, not a gap. `first-output` is timed for jvm, jank-jit, jank-aot, cohost on jank, clojurust, jvm-cohost, and cljrs-cohost.

| program | jvm median | jvm cold | jit median | jit cold | jit/jvm | aot median | aot cold | aot/jvm | result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| fib | 5.293441 | 18.17026 | 81.066692 | 313.770143 | 15.314555 | 4.228778 | 4.22679 | 0.798871 | 832040 |
| fib-hinted | 4.425011 | 17.725987 | 79.821299 | 247.66818 | 18.038667 | 7.362588 | 7.320614 | 1.663858 | 832040 |
| tak | 1.367668 | 6.945293 | 2.653217 | 253.907528 | 1.939957 | 0.324893 | 0.370587 | 0.237553 | 7 |
| tak-hinted | 1.129298 | 3.340478 | 4.194396 | 42.517684 | 3.714162 | 0.617815 | 0.705569 | 0.547079 | 7 |
| nbody | 3.876606 | 13.552441 | ABSENT Math/sqrt | | | ABSENT Math/sqrt | | | -169027 |
| nbody-hinted | 3.079164 | 14.419944 | ABSENT Math/sqrt | | | ABSENT Math/sqrt | | | -169027 |
| spectral-norm | 0.663116 | 15.028961 | ABSENT Math/round | | | ABSENT Math/round | | | 784826 |
| spectral-norm-hinted | 1.674702 | 13.756405 | ABSENT Math/round | | | ABSENT Math/round | | | 784826 |
| mandelbrot | 0.444768 | 1.998482 | ABSENT s35 | | | ABSENT s35 | | | 608 |
| mandelbrot-hinted | 0.213834 | 1.950147 | ABSENT s35 | | | ABSENT s35 | | | 608 |
| binary-trees | 0.518609 | 3.696582 | 1.736672 | 353.635392 | 3.348712 | 0.905763 | 0.556616 | 1.746524 | 7083 |
| binary-trees-hinted | 1.276997 | 3.619701 | 1.740416 | 290.493741 | 1.362897 | 0.951506 | 1.972727 | 0.745112 | 7083 |
| fannkuch | 21.31977 | 60.519953 | ABSENT s28 | | | ABSENT s28 | | | -502 |
| fannkuch-hinted | 20.498867 | 67.888894 | ABSENT s28 | | | ABSENT s28 | | | -502 |
| nsieve | 0.835891 | 3.45517 | ABSENT s56 | | | ABSENT s56 | | | 564 |
| nsieve-hinted | 1.023101 | 2.823484 | ABSENT s56 | | | ABSENT s56 | | | 564 |
| k-nucleotide | 1.125359 | 5.198497 | 1.078813 | 113.019317 | 0.958639 | 0.491155 | 0.596223 | 0.436443 | 500 |
| pipeline | 2.510401 | 9.299799 | 4.264566 | 368.019931 | 1.698759 | 3.360476 | 2.776184 | 1.338621 | 5995001 |
| dispatch | 2.168401 | 10.863573 | ABSENT defprotocol | | | ABSENT defprotocol | | | 200183333 |
| json | 0.145791 | 0.566916 | 0.073902 | 790.121021 | 0.506904 | 0.033901 | 0.106072 | 0.232532 | 580 |
| first-output | 385.120548 | 377.261128 | 362.395388 | 373.893489 | 0.940992 | 384.276232 | 662.439066 | 0.997808 | ok |

Times are milliseconds. jit is `jank-jit` (jank's own core, `jank run`). aot is `jank-aot` (jank's own core, `-O3`).

ABSENT on both jank-jit and jank-aot: nbody and nbody-hinted, `The symbol Math/sqrt could not be resolved`. spectral-norm and spectral-norm-hinted, `The symbol Math/round could not be resolved`. mandelbrot and mandelbrot-hinted, `use of undeclared identifier 's35'`. fannkuch and fannkuch-hinted, `use of undeclared identifier 's28'`. nsieve and nsieve-hinted, `use of undeclared identifier 's56'`. dispatch, `The symbol defprotocol could not be resolved`.

Geometric mean of the jank-jit / JVM median ratios: `2.425829` over the ten programs that ran (including first-output). Geometric mean of the jank-aot / JVM median ratios: `0.705328` over the ten programs that ran (including first-output). Ratio of every JVM median to itself is 1, geometric mean `1`.

Hinted median / unhinted median on the JVM: fib `0.835942`, tak `0.825711`, nbody `0.794294`, spectral-norm `2.525504`, mandelbrot `0.480776`, binary-trees `2.462350`, fannkuch `0.961496`, nsieve `1.223965`. Geometric mean of those eight ratios: `1.085595`.

On jank-jit, where both forms ran: fib `0.984637`, tak `1.580872`, binary-trees `1.002156`. On jank-aot: fib `1.741068`, tak `1.901595`, binary-trees `1.050502`.

`jank` is cohost on jank. Times are milliseconds.

| program | cohost median | cohost cold | cohost/jvm | result |
|---|---:|---:|---:|---|
| fib | 126.469398 | 401.822922 | 23.891718 | 832040 |
| fib-hinted | 442.530535 | 732.72114 | 100.006652 | 832040 |
| tak | 10.118948 | 284.221359 | 7.398687 | 7 |
| tak-hinted | 31.30494 | 314.73165 | 27.720708 | 7 |
| nbody | ABSENT s34 | | | -169027 |
| nbody-hinted | ABSENT s34 | | | -169027 |
| spectral-norm | 31.55363 | 1532.564494 | 47.583877 | 784826 |
| spectral-norm-hinted | 48.056577 | 1614.759454 | 28.695599 | 784826 |
| mandelbrot | ABSENT s36 | | | 608 |
| mandelbrot-hinted | ABSENT s36 | | | 608 |
| binary-trees | 2.152043 | 575.325684 | 4.149645 | 7083 |
| binary-trees-hinted | 2.644479 | 592.012082 | 2.070858 | 7083 |
| fannkuch | ABSENT s28 | | | -502 |
| fannkuch-hinted | ABSENT s28 | | | -502 |
| nsieve | ABSENT s61 | | | 564 |
| nsieve-hinted | ABSENT s61 | | | 564 |
| k-nucleotide | 2.518669 | 365.33767 | 2.238103 | 500 |
| pipeline | 5.749218 | 1079.960877 | 2.290159 | 5995001 |
| dispatch | 24.320671 | 921.303304 | 11.215947 | 200183333 |
| json | 0.262185 | 1639.843371 | 1.798362 | 580 |
| first-output | 10009.518983 | 10145.458176 | 25.990613 | ok |

Cohost ABSENT reasons: nbody and nbody-hinted, `use of undeclared identifier 's34'`. mandelbrot and mandelbrot-hinted, `use of undeclared identifier 's36'`. fannkuch and fannkuch-hinted, `use of undeclared identifier 's28'`. nsieve and nsieve-hinted, `use of undeclared identifier 's61'`.

Geometric mean of the thirteen cohost / JVM median ratios: `10.340329`.

Hinted median / unhinted median on cohost, where both ran: fib `3.499112`, tak `3.093695`, spectral-norm `1.523013`, binary-trees `1.228823`.

`cljrs` is clojurust's own core. Times are milliseconds.

| program | cljrs median | cljrs cold | cljrs/jvm | result |
|---|---:|---:|---:|---|
| fib | 1432.576098 | 1452.711787 | 270.632297 | 832040 |
| fib-hinted | 1350.766075 | 1319.948278 | 305.257111 | 832040 |
| tak | 30.360394 | 34.486952 | 22.198658 | 7 |
| tak-hinted | 30.24246 | 31.79012 | 26.779876 | 7 |
| nbody | ABSENT not callable: <nil> is not | | | -169027 |
| nbody-hinted | ABSENT not callable: <nil> is not | | | -169027 |
| spectral-norm | 364.45816 | 415.780843 | 549.614487 | 784826 |
| spectral-norm-hinted | 421.163685 | 473.198959 | 251.485748 | 784826 |
| mandelbrot | 5.973609 | 16.83927 | 13.430843 | 608 |
| mandelbrot-hinted | 6.937899 | 17.972488 | 32.445257 | 608 |
| binary-trees | 10.920277 | 13.61334 | 21.056860 | 7083 |
| binary-trees-hinted | 11.107553 | 12.140018 | 8.698183 | 7083 |
| fannkuch | ABSENT vector index out of bounds: 0 | | | -502 |
| fannkuch-hinted | ABSENT vector index out of bounds: 0 | | | -502 |
| nsieve | 705.783142 | 703.566645 | 844.348297 | 564 |
| nsieve-hinted | 776.055012 | 768.159621 | 758.532161 | 564 |
| k-nucleotide | 11.595415 | 11.994055 | 10.303748 | 500 |
| pipeline | 292.63828 | 340.102924 | 116.570333 | 5995001 |
| dispatch | 74.356264 | 77.886771 | 34.290827 | 200183333 |
| json | 18.164097 | 27.988001 | 124.589975 | 580 |
| first-output | 11.607159 | 17.149073 | 0.030139 | ok |

clojurust ABSENT reasons, as printed by `bb bench:programs cljrs`: nbody and nbody-hinted, `not callable: <nil> is not`. fannkuch and fannkuch-hinted, `vector index out of bounds: 0`.

Geometric mean of the seventeen cljrs / JVM median ratios: `48.975879`.

Hinted median / unhinted median on cljrs, where both ran: fib `0.942893`, tak `0.996116`, spectral-norm `1.155589`, mandelbrot `1.161425`, binary-trees `1.017149`, nsieve `1.099566`.

`jvm-cohost` is `bb boot:jvm`'s entry, `clojure -M -m cohost.run`, with the program source passed to `--eval`. `bb bench:programs jvm-cohost`. Times are milliseconds.

| program | jvm-cohost median | jvm-cohost cold | jvm-cohost/jvm | result |
|---|---:|---:|---:|---|
| fib | 6.925279 | 100.765007 | 1.308275 | 832040 |
| fib-hinted | ABSENT cohost.user$fib cannot be cast to clojure.lang.IFn$LO | | | 832040 |
| tak | 1.493625 | 2.279273 | 1.092096 | 7 |
| tak-hinted | ABSENT cohost.user$tak cannot be cast to clojure.lang.IFn$LLLO | | | 7 |
| nbody | 5.881155 | 11.028646 | 1.517089 | -169027 |
| nbody-hinted | ABSENT cohost.user$advance cannot be cast to clojure.lang.IFn$ODO | | | -169027 |
| spectral-norm | 2.972595 | 11.769845 | 4.482768 | 784826 |
| spectral-norm-hinted | ABSENT cohost.user$eval_a cannot be cast to clojure.lang.IFn$LLO | | | 784826 |
| mandelbrot | 0.739784 | 4.120885 | 1.663303 | 608 |
| mandelbrot-hinted | ABSENT cohost.user$inside_QMARK_ cannot be cast to clojure.lang.IFn$DDO | | | 608 |
| binary-trees | 0.407226 | 2.500697 | 0.785227 | 7083 |
| binary-trees-hinted | ABSENT cohost.user$build cannot be cast to clojure.lang.IFn$LO | | | 7083 |
| fannkuch | 31.943536 | 50.202803 | 1.498306 | -502 |
| fannkuch-hinted | 25.690042 | 41.558123 | 1.253242 | -502 |
| nsieve | 2.396237 | 7.064721 | 2.866686 | 564 |
| nsieve-hinted | 2.447969 | 9.586698 | 2.392695 | 564 |
| k-nucleotide | 1.311027 | 2.932697 | 1.164986 | 500 |
| pipeline | 1.270138 | 5.243058 | 0.505950 | 5995001 |
| dispatch | 10.115538 | 13.574763 | 4.664976 | 200183333 |
| json | 0.121365 | 0.742074 | 0.832459 | 580 |
| first-output | 2763.332345 | 2787.681567 | 7.175240 | ok |

Geometric mean of the fifteen jvm-cohost / JVM median ratios: `1.692743`.

Hinted median / unhinted median on jvm-cohost, where both ran: fannkuch `0.804232`, nsieve `1.021588`.

`cljrs-cohost` is `bb boot:cljrs`'s command (`CLJRS_IR_THRESHOLD=1`, `CLJRS_NO_JIT=1`, `cljrs run` of `src/cohost/run.cljc`) with the program source passed to `--eval`. `bb bench:programs cljrs-cohost`. Times are milliseconds. The harness's `volatile!` fails on this host, so every program that uses it is ABSENT with that cast. `dispatch` fails first, inside the program. `first-output` does not use a volatile.

| program | cljrs-cohost median | cljrs-cohost cold | cljrs-cohost/jvm | result |
|---|---:|---:|---:|---|
| fib | ABSENT Volatile cannot be cast to Future | | | 832040 |
| fib-hinted | ABSENT Volatile cannot be cast to Future | | | 832040 |
| tak | ABSENT Volatile cannot be cast to Future | | | 7 |
| tak-hinted | ABSENT Volatile cannot be cast to Future | | | 7 |
| nbody | ABSENT Volatile cannot be cast to Future | | | -169027 |
| nbody-hinted | ABSENT Volatile cannot be cast to Future | | | -169027 |
| spectral-norm | ABSENT Volatile cannot be cast to Future | | | 784826 |
| spectral-norm-hinted | ABSENT Volatile cannot be cast to Future | | | 784826 |
| mandelbrot | ABSENT Volatile cannot be cast to Future | | | 608 |
| mandelbrot-hinted | ABSENT Volatile cannot be cast to Future | | | 608 |
| binary-trees | ABSENT Volatile cannot be cast to Future | | | 7083 |
| binary-trees-hinted | ABSENT Volatile cannot be cast to Future | | | 7083 |
| fannkuch | ABSENT Volatile cannot be cast to Future | | | -502 |
| fannkuch-hinted | ABSENT Volatile cannot be cast to Future | | | -502 |
| nsieve | ABSENT Volatile cannot be cast to Future | | | 564 |
| nsieve-hinted | ABSENT Volatile cannot be cast to Future | | | 564 |
| k-nucleotide | ABSENT Volatile cannot be cast to Future | | | 500 |
| pipeline | ABSENT Volatile cannot be cast to Future | | | 5995001 |
| dispatch | ABSENT unbound symbol: protocol-fn | | | 200183333 |
| json | ABSENT Volatile cannot be cast to Future | | | 580 |
| first-output | 42555.86778 | 46280.039108 | 110.500123 | ok |

The driver's line for the volatile failures is `class clojure.lang.Volatile cannot be cast to class java.util.concurrent.Future`. Geometric mean of the one cljrs-cohost / JVM median ratio (`first-output`): `110.500123`.

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

Chromium: Playwright and headless Chromium are installed. The browser boot starts after `bb bench:programs cljs-cohost` releases the machine.

