# A two-element vector literal is three GC allocations and a 32-slot leaf

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/18-small-vectors.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 18-small-vectors` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/18-small-vectors.jank
[i i] 2e6 ns 489090242 runs 573128075 462364409 489090242
(cons i nil) 2e6 ns 169420218 runs 199569621 169420218 162350347
{:a i} 2e6 ns 207313710 runs 194326263 207313710 227701010
GC_MALLOC(16) 2e6 ns 47075345 runs 47075345 46439868 49052849
GC_MALLOC(272) 2e6 ns 336898478 runs 318456159 336898478 348350746
binary-trees: 16 x (check (build 14)) ns 113507840 runs 133924469 113507840 112102539
EXIT 0
```

## Expected

A two-element vector costs about one small allocation. The JVM allocates and initializes 2e6 two-element vectors in 20 ms (cohost's `dev/research/p3_gc_alloc.clj`, `pair-alloc-2e6` 20087465 ns) and runs binary-trees at depth 14 in 8.7 ms.

## Actual

2e6 `[i i]` take 489090242 ns (245 ns each), 2e6 `(cons i nil)` 169420218 (85 ns), 2e6 `{:a i}` 207313710 (104 ns), 2e6 `GC_MALLOC(16)` 47075345 (24 ns), 2e6 `GC_MALLOC(272)` (one 32-slot leaf) 336898478 (168 ns); binary-trees at depth 14 (16 trees) 113507840 ns against the JVM's 8.7 ms for the same program.

## Where it is in the source

`[a b]` is emitted as `_jank_vec(2, a, b)` (`codegen/cpp_processor.cpp:637`), which builds a stack `transient_vector`, `conj_in_place`s each element and calls `to_persistent()` (`codegen/api.cpp:146`). immer's transient owner token is a 1-byte atomic GC allocation (`immer/memory_policy`, `gc_transience_policy.hpp:50`); the first push copies the static empty tail into a full 32-slot leaf, 256 bytes plus header, whatever the element count (`immer/detail/rbts/rbtree.hpp:262`, `node.hpp:337`); `to_persistent` allocates the `persistent_vector` box (`obj/transient_vector.cpp:117`). Every immer node is a direct `GC_malloc` with no free-list layer (`immer/heap/gc_heap.hpp:105`, `heap_policy.hpp:26`). A bdwgc allocation is itself 25-40 ns here.

## What would fix it

A small-vector representation (inline storage up to the 32-element tail, one allocation) for literals and `vector` up to 32 elements, growing into the immer tree past that.
