# The collector runs with bdwgc's defaults: no initial heap, no free-space divisor, incremental mode after init

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/19-gc-defaults.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 19-gc-defaults` (prepends a 3-run median timer, then `jank -O3 run`).

```
== env NONE=1
binary-trees: 16 x (check (build 14)) ns 119571252 runs 167401332 115099312 119571252
2e6 two-element vectors ns 372617222 runs 401581100 370205120 372617222
== env GC_INITIAL_HEAP_SIZE=1G
binary-trees: 16 x (check (build 14)) ns 152410813 runs 152410813 153779930 152185514
2e6 two-element vectors ns 328822458 runs 330392592 289432463 328822458
== env GC_FREE_SPACE_DIVISOR=1
binary-trees: 16 x (check (build 14)) ns 156396408 runs 141590821 161600429 156396408
2e6 two-element vectors ns 459997530 runs 473395259 459997530 386379775
== env GC_INITIAL_HEAP_SIZE=1G GC_FREE_SPACE_DIVISOR=1
binary-trees: 16 x (check (build 14)) ns 163009842 runs 158585580 163009842 169333550
2e6 two-element vectors ns 349778927 runs 387377494 318535388 349778927
== env GC_ENABLE_INCREMENTAL=1
binary-trees: 16 x (check (build 14)) ns 349101915 runs 310983123 349101915 372495815
2e6 two-element vectors ns 2240779054 runs 2350836257 2240779054 2161491309
== collections in the default run (GC_PRINT_STATS=1, lines with 'GC #'):
110
```

## Expected

An allocation-heavy program does not spend its time in full collections of a tiny heap.

## Actual

110 collections during the default run. The environment knobs move binary-trees both ways between runs: in this run `GC_INITIAL_HEAP_SIZE=1G` made it slower (152410813 vs 119571252 ns) while an earlier matrix on the same box (cohost's `dev/research/p3_gc_env.sh`) made it faster (122085049 vs 174828601); `GC_ENABLE_INCREMENTAL=1` is 3-6x slower in both (349101915 here; 2e6 vectors 2240779054 vs 372617222). So the defaults are not obviously wrong for this program; the reliable finding is that incremental mode must not be the default, and that collection count and heap sizing deserve a measured default rather than bdwgc's.

## Where it is in the source

`jank_init` calls `GC_set_all_interior_pointers(1)`, `GC_init()` and `GC_allow_register_threads()` and nothing else (`src/cpp/jank/c_api.cpp:1121`; a `grep GC_set_` over `src/` and `include/` finds only those two calls); `--gc-incremental` calls `GC_enable_incremental()` after `GC_init` (`main.cpp:222`), which bdwgc says to call "as early as possible" (`gc.h:1274`). bdwgc's own doc on the divisor: "Decreasing it will appreciably decrease total collection time at the expense of space" (`gc.h:385`).

## What would fix it

Set an initial heap and a lower free-space divisor in `jank_init` (or honor and document `GC_INITIAL_HEAP_SIZE`/`GC_FREE_SPACE_DIVISOR`), keep parallel marking, and do not default to incremental mode (measured 4-6x slower here).
