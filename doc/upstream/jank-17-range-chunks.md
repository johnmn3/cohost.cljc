# `(range n)` and a vector's seq are not chunked, so `map`/`filter` take the per-element path

Status: @@STATUS:17-range-chunks@@

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/17-range-chunks.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 17-range-chunks` (prepends a 3-run median timer, then `jank -O3 run`).

```
@@OUTPUT:17-range-chunks@@
```

## Expected

`chunked-seq?` is true for `(seq (range 10))` and for a vector's seq, as on the JVM, and the lazy `map`/`filter` pipeline over 1e6 runs within a small factor of the loop (the JVM: 25.9 ms for `count (filter odd? (map inc (range 1e6)))`, cohost's `dev/research/p5_seqs.clj`).

## Actual

@@ACTUAL:17-range-chunks@@

## Where it is in the source

`integer_range` has `behavior::chunkable` commented out as a TODO (`obj/integer_range.hpp:293`), `persistent_vector_sequence` is not chunkable, and `integer_range::next()` allocates a new range per step (`obj/integer_range.cpp:108`); `core.jank`'s `map` and `filter` carry Clojure's `chunked-seq?` branch (lines 2885, 3864) that never fires for these. The non-integer `range` is chunked (32) but locks a mutex per step (`obj/range.hpp:158`, `range.cpp:352`), and `array_chunk::chunk_next` copies the chunk buffer per element consumed (`obj/array_chunk.cpp:41`).

## What would fix it

Implement `chunkable` on `integer_range` (fill an `array_chunk` of 32 boxed ints, no mutex) and expose immer leaves as chunks for vector seqs; make `chunk_next` share the buffer.
