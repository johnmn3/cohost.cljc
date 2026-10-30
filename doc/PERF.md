# Performance

How fast the stock core.clj runs on each host through cohost: against JVM
Clojure, and against each host's own hand-written core. It also records what
the two perf passes changed. Measured on 2026-10-30, US Eastern time, on one
4-CPU box, one host at a time. The before-numbers come from commit `4ce56eb`
in a worktree, run right before the after-numbers.

## Summary

Geometric means over the 29 cases. Before is commit `4ce56eb`; after is `19dbd1a`
(pass 1, pass 2, the basilisp alias fix and the bench's settle pass).

**cohost's stock core against JVM Clojure's own core, at the same size:**

| | JVM | jolt | basilisp (1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|
| before | ×5.0 | ×46.4 | ×7149 | ×44.3 | ×33.1 |
| after | **×1.3** | **×4.7** | **×3639** | **×6.5** | **×5.1** |

**cohost's stock core against each host's own core, on that host:**

| | JVM | jolt | basilisp (1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|
| before | ×5.0 | ×17.4 | ×5.3 | ×16.2 | ×14.2 |
| after | **×1.3** | **×1.7** | **×2.6** | **×2.1** | **×1.9** |

**Speed-up of the stock core, before over after:** JVM ×3.9, jolt ×10.2, basilisp ×2.0,
Node ×7.0, Chromium ×6.6.

basilisp's own core is about 1,360× slower than JVM Clojure's on these cases, so its first
row measures Python more than cohost. Its second row is the one that measures cohost.

**Largest single moves, cohost on that host against JVM Clojure:**

| case | host | before | after |
|---|---|---:|---:|
| `clojure.string/join` | JVM | ×534 | ×0.99 |
| `clojure.string/join` | jolt | ×1772 | ×4.8 |
| re-seq | JVM | ×150 | ×0.99 |
| keyword lookup on small maps | jolt | ×168 | ×2.3 |
| sort-by | jolt | ×571 | ×5.4 |
| loop/recur arithmetic | Node | ×513 | ×1.6 |

What did it:
- **The JVM's** two were reflective calls in its adapter, now type-hinted.
- **On jolt**, `clojure.string/join` is now jolt's own (a native).
- **Keyword lookup, sort-by and loop arithmetic** gained from rewrite-time inlining and
  host aliases.

**Boot:**
- basilisp: 179 s → 128 s;
- the others: within 10%, already 1.6–6.4 s.

The codemod still walks a stock macro's arguments twice: once with the call, and again when
its expansion is rewritten. Walking each form once is the largest boot cost left. It needs
each local's type hint inside a macro expansion, and jolt's `&env` does not carry hints.
`doc/perf/HANDOFF.md` has the details.

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

On each host, one process boots core.clj through cohost and compiles every case
twice. One copy runs in a namespace that sees only the booted stock core, the
other in a namespace that sees the host's own core. Each thunk is called once
cold, then repeatedly for 500 ms to warm up. It is then timed until at least 3
calls and 1 s are spent, and the number reported is the median call.

Both sides must return the same value. A stock answer counts against JVM
Clojure only when it equals JVM Clojure's own answer.

On the JVM the host's own core is JVM Clojure, so one run gives both the
baseline and cohost-on-the-JVM.

## What changed

- **Pass 1** removed needless costs from the codemod's output, the shim, the
  adapters and the boot loop. `doc/perf/HANDOFF.md` lists each change.
- **Pass 2** added `:natives`: a host opts into its own implementation of a
  stock fn, one name at a time, and the stock definition stays the fallback.

## Tables

### The stock core, before and after

Median ms per call before → after on each host, and the speed-up (before over after).

| case | JVM Clojure 1.12.0 | jolt v0.8.23 | basilisp 0.5.1 (at 1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|
| a defn called in a loop | 0.43 → 0.51 ×0.85 | 11.8 → 1.34 ×8.8 | 277 → 273 ×1.0 | 17.1 → 1.03 ×16.6 | 9.70 → 0.70 ×13.9 |
| loop/recur arithmetic | 0.09 → 0.09 ×1.0 | 27.9 → 0.51 ×55.1 | 625 → 621 ×1.0 | 45.0 → 0.14 ×325.1 | 28.1 → 0.10 ×281.0 |
| reduce + over a range | 1.68 → 1.76 ×0.96 | 9.32 → 4.49 ×2.1 | 531 → 566 ×0.94 | 9.59 → 3.93 ×2.4 | 7.10 → 3.10 ×2.3 |
| count, filter, map over a range | 120 → 10.5 ×11.4 | 1150 → 77.9 ×14.8 | 7746 → 1662 ×4.7 | 1136 → 129 ×8.8 | 979 → 108 ×9.0 |
| transduce (comp (map inc) (filter odd?)) | 29.1 → 4.75 ×6.1 | 308 → 29.3 ×10.5 | 2263 → 781 ×2.9 | 286 → 31.6 ×9.0 | 257 → 29.3 ×8.8 |
| into [] with a map transducer | 46.8 → 3.59 ×13.0 | 648 → 18.1 ×35.9 | 3941 → 657 ×6.0 | 521 → 47.0 ×11.1 | 471 → 39.4 ×12.0 |
| reduce conj [] (persistent vector) | 3.32 → 3.27 ×1.0 | 10.2 → 7.97 ×1.3 | 290 → 300 ×0.97 | 13.4 → 13.9 ×0.97 | 9.90 → 10.3 ×0.96 |
| conj! into a transient vector | 43.5 → 3.15 ×13.8 | 604 → 15.5 ×39.0 | 4026 → 617 ×6.5 | 534 → 42.7 ×12.5 | 432 → 33.5 ×12.9 |
| assoc into a map | 11.1 → 10.1 ×1.1 | 24.4 → 21.7 ×1.1 | 194 → 179 ×1.1 | 33.1 → 27.8 ×1.2 | 24.1 → 21.6 ×1.1 |
| get from a 1,000-key map | 3.55 → 3.72 ×0.96 | 32.4 → 20.6 ×1.6 | 1249 → 1164 ×1.1 | 45.4 → 17.9 ×2.5 | 28.2 → 15.1 ×1.9 |
| keyword lookup on small maps | 2.30 → 1.97 ×1.2 | 368 → 4.48 ×82.3 | 3112 → 803 ×3.9 | 448 → 6.85 ×65.5 | 316 → 6.40 ×49.3 |
| map destructuring in fn args | 25.3 → 6.02 ×4.2 | 620 → 25.0 ×24.8 | 4257 → 923 ×4.6 | 624 → 36.9 ×16.9 | 514 → 33.6 ×15.3 |
| frequencies | 112 → 8.20 ×13.7 | 1218 → 60.0 ×20.3 | 7735 → 1856 ×4.2 | 1142 → 69.8 ×16.4 | 973 → 63.0 ×15.4 |
| group-by into 31 groups | 62.3 → 6.80 ×9.2 | 821 → 32.1 ×25.6 | 5359 → 1416 ×3.8 | 667 → 29.4 ×22.7 | 511 → 24.9 ×20.5 |
| sort numbers | 52.3 → 12.2 ×4.3 | 282 → 45.9 ×6.2 | 2130 → 889 ×2.4 | 271 → 51.0 ×5.3 | 226 → 40.6 ×5.6 |
| sort-by a key of maps | 46.2 → 4.32 ×10.7 | 3761 → 24.4 ×154.4 | 489 → 133 ×3.7 | 233 → 33.4 ×7.0 | 162 → 28.7 ×5.6 |
| build a set | 51.0 → 7.35 ×6.9 | 656 → 39.5 ×16.6 | 5048 → 1490 ×3.4 | 569 → 67.4 ×8.4 | 455 → 52.3 ×8.7 |
| distinct over mapcat | 105 → 30.6 ×3.4 | 733 → 119 ×6.2 | 4582 → 1408 ×3.3 | 979 → 343 ×2.9 | 790 → 171 ×4.6 |
| partition 3 | 27.8 → 8.85 ×3.1 | 214 → 8.36 ×25.6 | 1684 → 1275 ×1.3 | 332 → 36.0 ×9.2 | 238 → 28.1 ×8.5 |
| nth of a lazy iterate | 0.91 → 0.89 ×1.0 | 6.91 → 4.05 ×1.7 | 246 → 218 ×1.1 | 18.1 → 4.75 ×3.8 | 8.20 → 3.50 ×2.3 |
| swap! an atom | 7.93 → 2.87 ×2.8 | 182 → 13.1 ×13.9 | 889 → 767 ×1.2 | 99.5 → 46.7 ×2.1 | 62.0 → 32.8 ×1.9 |
| protocol call on a record | 0.78 → 0.74 ×1.1 | 9.90 → 6.31 ×1.6 | 228 → 202 ×1.1 | 9.68 → 2.29 ×4.2 | 5.40 → 1.70 ×3.2 |
| multimethod call | 14.1 → 6.55 ×2.1 | 83.7 → 16.6 ×5.1 | 1301 → 593 ×2.2 | 151 → 39.1 ×3.9 | 117 → 26.9 ×4.4 |
| = of two equal vectors | 2.14 → 2.23 ×0.96 | 0.65 → 0.66 ×0.98 | 0.60 → 0.63 ×0.96 | 0.95 → 0.94 ×1.0 | 0.90 → 0.90 ×1.0 |
| apply str over numbers | 9.70 → 1.42 ×6.8 | 82.8 → 8.28 ×10.0 | 599 → 148 ×4.0 | 116 → 12.2 ×9.5 | 86.4 → 10.2 ×8.5 |
| clojure.string/join | 365 → 0.66 ×552.1 | 1213 → 3.20 ×379.5 | 191 → 178 ×1.1 | 43.3 → 1.91 ×22.7 | 37.8 → 1.60 ×23.6 |
| re-seq over a string of numbers | 56.5 → 0.40 ×141.7 | 58.4 → 1.42 ×41.1 | 262 → 220 ×1.2 | 39.4 → 0.75 ×52.8 | 29.8 → 0.60 ×49.7 |
| format | 5.13 → 2.64 ×1.9 | 26.2 → 6.50 ×4.0 | 1029 → 914 ×1.1 | 104 → 68.0 ×1.5 | 95.2 → 49.3 ×1.9 |
| pr-str of small maps | 3.56 → 3.40 ×1.0 | 5.89 → 5.38 ×1.1 | 2.06 → 2.03 ×1.0 | 7.93 → 7.70 ×1.0 | 6.50 → 6.20 ×1.0 |
| **geometric mean speed-up** | ×3.9 | ×10.2 | ×2.0 | ×7.0 | ×6.6 |
| boot | 1.7 s → 1.6 s | 3.4 s → 3.3 s | 178.8 s → 128.4 s | 6.7 s → 6.4 s | 5.7 s → 5.2 s |

### Against JVM Clojure: the stock core on each host

Median ms per call; × is the stock core's time over JVM Clojure's own at the same size.

| case | JVM Clojure | cohost on JVM Clojure 1.12.0 | cohost on jolt v0.8.23 | cohost on basilisp 0.5.1 (at 1/20 size) | cohost on ClojureScript, Node | cohost on ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|---:|
| a defn called in a loop | 0.51 | 0.51 ×1.0 | 1.34 ×2.6 | 273 ×13279.7 | 1.03 ×2.0 | 0.70 ×1.4 |
| loop/recur arithmetic | 0.09 | 0.09 ×1.0 | 0.51 ×5.8 | 621 ×136917.5 | 0.14 ×1.6 | 0.10 ×1.1 |
| reduce + over a range | 1.69 | 1.76 ×1.0 | 4.49 ×2.7 | 566 ×7024.1 | 3.93 ×2.3 | 3.10 ×1.8 |
| count, filter, map over a range | 3.42 | 10.5 ×3.1 | 77.9 ×22.8 | 1662 ×10801.6 | 129 ×37.6 | 108 ×31.6 |
| transduce (comp (map inc) (filter odd?)) | 1.32 | 4.75 ×3.6 | 29.3 ×22.1 | 781 ×12187.7 | 31.6 ×23.8 | 29.3 ×22.1 |
| into [] with a map transducer | 1.89 | 3.59 ×1.9 | 18.1 ×9.5 | 657 ×6844.6 | 47.0 ×24.8 | 39.4 ×20.8 |
| reduce conj [] (persistent vector) | 3.28 | 3.27 ×1.0 | 7.97 ×2.4 | 300 ×1963.6 | 13.9 ×4.2 | 10.3 ×3.1 |
| conj! into a transient vector | 1.68 | 3.15 ×1.9 | 15.5 ×9.2 | 617 ×7655.5 | 42.7 ×25.3 | 33.5 ×19.9 |
| assoc into a map | 10.3 | 10.1 ×0.98 | 21.7 ×2.1 | 179 ×614.8 | 27.8 ×2.7 | 21.6 ×2.1 |
| get from a 1,000-key map | 3.63 | 3.72 ×1.0 | 20.6 ×5.7 | 1164 ×6753.4 | 17.9 ×4.9 | 15.1 ×4.2 |
| keyword lookup on small maps | 1.93 | 1.97 ×1.0 | 4.48 ×2.3 | 803 ×8523.3 | 6.85 ×3.5 | 6.40 ×3.3 |
| map destructuring in fn args | 2.12 | 6.02 ×2.8 | 25.0 ×11.8 | 923 ×9968.7 | 36.9 ×17.5 | 33.6 ×15.9 |
| frequencies | 6.10 | 8.20 ×1.3 | 60.0 ×9.8 | 1856 ×6354.7 | 69.8 ×11.4 | 63.0 ×10.3 |
| group-by into 31 groups | 6.28 | 6.80 ×1.1 | 32.1 ×5.1 | 1416 ×4535.4 | 29.4 ×4.7 | 24.9 ×4.0 |
| sort numbers | 11.9 | 12.2 ×1.0 | 45.9 ×3.8 | 889 ×1573.2 | 51.0 ×4.3 | 40.6 ×3.4 |
| sort-by a key of maps | 4.53 | 4.32 ×0.95 | 24.4 ×5.4 | 133 ×722.8 | 33.4 ×7.4 | 28.7 ×6.3 |
| build a set | 4.59 | 7.35 ×1.6 | 39.5 ×8.6 | 1490 ×5398.9 | 67.4 ×14.7 | 52.3 ×11.4 |
| distinct over mapcat | 29.9 | 30.6 ×1.0 | 119 ×4.0 | 1408 ×1324.6 | 343 ×11.5 | 171 ×5.7 |
| partition 3 | 8.98 | 8.85 ×0.99 | 8.36 ×0.93 | 1275 ×2960.5 | 36.0 ×4.0 | 28.1 ×3.1 |
| nth of a lazy iterate | 0.89 | 0.89 ×1.0 | 4.05 ×4.6 | 218 ×5163.2 | 4.75 ×5.3 | 3.50 ×3.9 |
| swap! an atom | 1.98 | 2.87 ×1.4 | 13.1 ×6.6 | 767 ×8302.5 | 46.7 ×23.5 | 32.8 ×16.5 |
| protocol call on a record | 0.74 | 0.74 ×1.0 | 6.31 ×8.6 | 202 ×5947.7 | 2.29 ×3.1 | 1.70 ×2.3 |
| multimethod call | 1.29 | 6.55 ×5.1 | 16.6 ×12.8 | 593 ×9506.7 | 39.1 ×30.2 | 26.9 ×20.8 |
| = of two equal vectors | 2.22 | 2.23 ×1.0 | 0.66 ×0.30 | 0.63 ×6.9 | 0.94 ×0.42 | 0.90 ×0.41 |
| apply str over numbers | 0.86 | 1.42 ×1.7 | 8.28 ×9.7 | 148 ×3705.0 | 12.2 ×14.2 | 10.2 ×11.9 |
| clojure.string/join | 0.67 | 0.66 ×0.99 | 3.20 ×4.8 | 178 ×5660.5 | 1.91 ×2.9 | 1.60 ×2.4 |
| re-seq over a string of numbers | 0.40 | 0.40 ×0.99 | 1.42 ×3.5 | 220 ×13095.7 | 0.75 ×1.9 | 0.60 ×1.5 |
| format | 2.33 | 2.64 ×1.1 | 6.50 ×2.8 | 914 ×7645.2 | 68.0 ×29.1 | 49.3 ×21.1 |
| pr-str of small maps | 3.53 | 3.40 ×0.96 | 5.38 ×1.5 | 2.03 ×12.5 | 7.70 ×2.2 | 6.20 ×1.8 |
| **geometric mean** | | ×1.3 | ×4.7 | ×3638.9 | ×6.5 | ×5.1 |

### Each host's own core against the stock core booted on it

Median ms per call: the host's own core / the stock core, and the stock core's time over the own core's.

| case | JVM Clojure 1.12.0 | jolt v0.8.23 | basilisp 0.5.1 (at 1/20 size) | ClojureScript, Node | ClojureScript, Chromium |
|---|---:|---:|---:|---:|---:|
| a defn called in a loop | 0.51 / 0.51 ×1.0 | 1.66 / 1.34 ×0.81 | 125 / 273 ×2.2 | 1.32 / 1.03 ×0.78 | 0.70 / 0.70 ×1.0 |
| loop/recur arithmetic | 0.09 / 0.09 ×1.0 | 0.51 / 0.51 ×1.0 | 280 / 621 ×2.2 | 0.14 / 0.14 ×1.0 | 0.10 / 0.10 ×1.0 |
| reduce + over a range | 1.69 / 1.76 ×1.0 | 3.84 / 4.49 ×1.2 | 485 / 566 ×1.2 | 3.72 / 3.93 ×1.1 | 2.90 / 3.10 ×1.1 |
| count, filter, map over a range | 3.42 / 10.5 ×3.1 | 5.28 / 77.9 ×14.8 | 731 / 1662 ×2.3 | 9.28 / 129 ×13.9 | 9.20 / 108 ×11.8 |
| transduce (comp (map inc) (filter odd?)) | 1.32 / 4.75 ×3.6 | 7.06 / 29.3 ×4.1 | 392 / 781 ×2.0 | 4.09 / 31.6 ×7.7 | 4.40 / 29.3 ×6.7 |
| into [] with a map transducer | 1.89 / 3.59 ×1.9 | 10.0 / 18.1 ×1.8 | 531 / 657 ×1.2 | 4.14 / 47.0 ×11.4 | 4.60 / 39.4 ×8.6 |
| reduce conj [] (persistent vector) | 3.28 / 3.27 ×1.0 | 8.40 / 7.97 ×0.95 | 283 / 300 ×1.1 | 9.80 / 13.9 ×1.4 | 7.90 / 10.3 ×1.3 |
| conj! into a transient vector | 1.68 / 3.15 ×1.9 | 4.35 / 15.5 ×3.6 | 522 / 617 ×1.2 | 2.53 / 42.7 ×16.8 | 2.60 / 33.5 ×12.9 |
| assoc into a map | 10.3 / 10.1 ×0.98 | 19.2 / 21.7 ×1.1 | 135 / 179 ×1.3 | 27.5 / 27.8 ×1.0 | 19.5 / 21.6 ×1.1 |
| get from a 1,000-key map | 3.63 / 3.72 ×1.0 | 10.2 / 20.6 ×2.0 | - / - - | 11.4 / 17.9 ×1.6 | 10.7 / 15.1 ×1.4 |
| keyword lookup on small maps | 1.93 / 1.97 ×1.0 | 4.56 / 4.48 ×0.98 | 386 / 803 ×2.1 | 6.75 / 6.85 ×1.0 | 6.90 / 6.40 ×0.93 |
| map destructuring in fn args | 2.12 / 6.02 ×2.8 | 6.59 / 25.0 ×3.8 | 399 / 923 ×2.3 | 13.4 / 36.9 ×2.8 | 12.1 / 33.6 ×2.8 |
| frequencies | 6.10 / 8.20 ×1.3 | 25.3 / 60.0 ×2.4 | 715 / 1856 ×2.6 | 25.4 / 69.8 ×2.7 | 25.9 / 63.0 ×2.4 |
| group-by into 31 groups | 6.28 / 6.80 ×1.1 | 20.9 / 32.1 ×1.5 | 713 / 1416 ×2.0 | 22.9 / 29.4 ×1.3 | 22.0 / 24.9 ×1.1 |
| sort numbers | 11.9 / 12.2 ×1.0 | 28.3 / 45.9 ×1.6 | 178 / 889 ×5.0 | 24.6 / 51.0 ×2.1 | 21.3 / 40.6 ×1.9 |
| sort-by a key of maps | 4.53 / 4.32 ×0.95 | 25.1 / 24.4 ×0.97 | 11.3 / 133 ×11.7 | 33.7 / 33.4 ×0.99 | 31.4 / 28.7 ×0.91 |
| build a set | 4.59 / 7.35 ×1.6 | 11.6 / 39.5 ×3.4 | 556 / 1490 ×2.7 | 10.5 / 67.4 ×6.4 | 11.1 / 52.3 ×4.7 |
| distinct over mapcat | 29.9 / 30.6 ×1.0 | 57.4 / 119 ×2.1 | 488 / 1408 ×2.9 | 178 / 343 ×1.9 | 135 / 171 ×1.3 |
| partition 3 | 8.98 / 8.85 ×0.99 | 8.38 / 8.36 ×1.0 | 943 / 1275 ×1.4 | 35.7 / 36.0 ×1.0 | 29.0 / 28.1 ×0.97 |
| nth of a lazy iterate | 0.89 / 0.89 ×1.0 | 1.72 / 4.05 ×2.4 | 25.3 / 218 ×8.6 | 3.54 / 4.75 ×1.3 | 3.05 / 3.50 ×1.1 |
| swap! an atom | 1.98 / 2.87 ×1.4 | 4.80 / 13.1 ×2.7 | 415 / 767 ×1.8 | 6.90 / 46.7 ×6.8 | 6.10 / 32.8 ×5.4 |
| protocol call on a record | 0.74 / 0.74 ×1.0 | 6.22 / 6.31 ×1.0 | 143 / 202 ×1.4 | 2.72 / 2.29 ×0.84 | 1.70 / 1.70 ×1.0 |
| multimethod call | 1.29 / 6.55 ×5.1 | 9.58 / 16.6 ×1.7 | 129 / 593 ×4.6 | 9.37 / 39.1 ×4.2 | 7.00 / 26.9 ×3.8 |
| = of two equal vectors | 2.22 / 2.23 ×1.0 | 0.66 / 0.66 ×0.99 | 0.60 / 0.63 ×1.0 | 0.95 / 0.94 ×0.99 | 0.90 / 0.90 ×1.0 |
| apply str over numbers | 0.86 / 1.42 ×1.7 | 2.64 / 8.28 ×3.1 | 80.2 / 148 ×1.8 | 3.36 / 12.2 ×3.6 | 2.80 / 10.2 ×3.6 |
| clojure.string/join | 0.67 / 0.66 ×0.99 | 3.34 / 3.20 ×0.96 | 59.4 / 178 ×3.0 | 1.85 / 1.91 ×1.0 | 1.60 / 1.60 ×1.0 |
| re-seq over a string of numbers | 0.40 / 0.40 ×0.99 | 1.44 / 1.42 ×0.99 | 5.76 / 220 ×38.2 | 0.74 / 0.75 ×1.0 | 0.60 / 0.60 ×1.0 |
| format | 2.33 / 2.64 ×1.1 | 4.76 / 6.50 ×1.4 | 37.3 / 914 ×24.5 | - / - - | - / - - |
| pr-str of small maps | 3.53 / 3.40 ×0.96 | 5.84 / 5.38 ×0.92 | 2.04 / 2.03 ×1.0 | 7.75 / 7.70 ×0.99 | 6.40 / 6.20 ×0.97 |
| **geometric mean** | ×1.3 | ×1.7 | ×2.6 | ×2.1 | ×1.9 |
| own core vs JVM Clojure (geo. mean) | ×1.0 | ×2.8 | ×1365.5 | ×2.9 | ×2.5 |

### Boot

| host | core.clj boot (791 forms) | the twelve libraries |
|---|---:|---:|
| JVM Clojure 1.12.0 | 1.6 s | 1.3 s |
| jolt v0.8.23 | 3.3 s | 2.8 s |
| basilisp 0.5.1 | 128.4 s | 107.0 s |
| ClojureScript, Node | 6.4 s | 6.4 s |
| ClojureScript, Chromium | 5.2 s | 5.1 s |

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

Then the tables (doc/perf/before and doc/perf/after hold this file's runs):

```bash
bb bench:report target
```

```bash
bb bench:report --compare doc/perf/before doc/perf/after
```
