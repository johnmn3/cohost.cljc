# Walking the result of `sort` is O(n^2): `native_vector_sequence::next` copies the whole vector

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/11-sorted-walk.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 11-sorted-walk` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/11-sorted-walk.jank
sort-first n=1000 ns 184004 runs 209445 115207 184004
sort-nth-half n=1000 ns 1464706 runs 1464706 4295175 709750
sort-reduce n=1000 ns 2741774 runs 2908790 2741774 2647008
sort-first n=10000 ns 1385521 runs 1361413 1385521 1528631
sort-nth-half n=10000 ns 197882069 runs 199394866 196363299 197882069
sort-reduce n=10000 ns 457330025 runs 464450349 457330025 420697190
sort-first n=100000 ns 11377314 runs 11377314 11016345 13413470
sort-nth-half n=100000 ns 6142807762 runs 6142807762 8514034906 5981564447
sort-reduce n=100000 ns 26596893121 runs 26596893121 17211296989 27247925721
EXIT 0
```

## Expected

`sort-first` (the sort alone) scales as n log n: about 20x from 1e4 to 1e5. `sort-nth-half` and `sort-reduce` (a walk of half or all of the result) scale as n^2: about 100x per decade, seconds at 1e5. The JVM sorts and reduces 1e5 longs in about 8 ms (cohost's `dev/research/p5_seqs.clj`, `sort-1e5` 8103593 ns).

## Actual

The sort alone (`sort-first`) is 184004 ns at 1e3, 1385521 at 1e4, 11377314 at 1e5 (about 8x per decade). Walking half the result (`sort-nth-half`) is 1464706, 197882069, 6142807762 ns (135x then 31x per decade), and reducing all of it 2741774, 457330025, 26596893121 ns (167x then 58x): 6-27 seconds at 1e5 for an 11 ms sort.

## Where it is in the source

`sort` returns a `native_vector_sequence` whose `next()` is `make_box<native_vector_sequence>(data, n)` with `data` a `native_vector<object_ref>` member held by value (`src/cpp/jank/runtime/obj/native_vector_sequence.cpp:98`, header `obj/native_vector_sequence.hpp:58`): every step copies all n elements. Only `next_in_place` avoids the copy. The sort itself (`std::stable_sort` over the native vector, `runtime/core/seq.cpp:1091-1124`) is n log n.

## What would fix it

`next()` should share the buffer between steps (hold the vector through a `jtl::ref`/shared pointer, or return an immer vector's sequence), as `array_chunk::chunk_next` should too (`obj/array_chunk.cpp:41`, "TODO: This copying will be slow").
