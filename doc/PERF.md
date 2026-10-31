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
