# `reduce` walks with an allocating `next()` per element; `reduce-kv` already loops directly

Status: @@STATUS:15-reduce-walk@@

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/15-reduce-walk.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 15-reduce-walk` (prepends a 3-run median timer, then `jank -O3 run`).

```
@@OUTPUT:15-reduce-walk@@
```

## Expected

`reduce` over a vector costs about what `reduce-kv` and the `nth` loop cost (the JVM: 5.3 ms for `reduce +` over 1e6, cohost's `dev/research/p5_seqs.clj`).

## Actual

@@ACTUAL:15-reduce-walk@@

## Where it is in the source

`runtime::reduce` walks `make_sequence_range` and makes a virtual `f.call` per element (`runtime/core/seq.cpp:901`); the range advances with the allocating `next()` (`runtime/sequence_range.hpp:39`) because the `next_in_place` branch is commented out (`sequence_range.hpp:169`; `runtime/object.hpp:567`: "Each call to next() allocates a new sequence"). `reduce-kv` loops over the immer data directly (`seq.cpp:926`); `doseq` already uses `fresh-seq` + `next-in-place` (`core.jank` line 2742). `core.jank`'s `reduce` says `; TODO: Chunking support.` (line 1141).

## What would fix it

Finish `fresh_sequence_range` and use it in `reduce`, `str`, `sort`, `vec`, `apply_to`, `sequence_length` and equality; then a `visit_object` dispatch at the top of `reduce` with direct loops per collection (vector, list, maps, `integer_range`, chunked seqs), as `reduce_kv` has.
