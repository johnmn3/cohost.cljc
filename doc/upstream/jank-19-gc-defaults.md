# The collector runs with bdwgc's defaults: no initial heap, no free-space divisor, incremental mode after init

Status: @@STATUS:19-gc-defaults@@

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/19-gc-defaults.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 19-gc-defaults` (prepends a 3-run median timer, then `jank -O3 run`).

```
@@OUTPUT:19-gc-defaults@@
```

## Expected

An allocation-heavy program does not spend its time in full collections of a tiny heap.

## Actual

@@ACTUAL:19-gc-defaults@@

## Where it is in the source

`jank_init` calls `GC_set_all_interior_pointers(1)`, `GC_init()` and `GC_allow_register_threads()` and nothing else (`src/cpp/jank/c_api.cpp:1121`; a `grep GC_set_` over `src/` and `include/` finds only those two calls); `--gc-incremental` calls `GC_enable_incremental()` after `GC_init` (`main.cpp:222`), which bdwgc says to call "as early as possible" (`gc.h:1274`). bdwgc's own doc on the divisor: "Decreasing it will appreciably decrease total collection time at the expense of space" (`gc.h:385`).

## What would fix it

Set an initial heap and a lower free-space divisor in `jank_init` (or honor and document `GC_INITIAL_HEAP_SIZE`/`GC_FREE_SPACE_DIVISOR`), keep parallel marking, and do not default to incremental mode (measured 4-6x slower here).
