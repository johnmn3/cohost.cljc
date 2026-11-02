# `(range n)` and a vector's seq are not chunked, so `map`/`filter` take the per-element path

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/17-range-chunks.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 17-range-chunks` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/17-range-chunks.jank
chunked-seq? (seq (range 10)) => false
chunked-seq? (seq (vec (range 10))) => false
chunked-seq? (seq (range 0.0 10.0)) => true
type (seq (range 10)) => integer_range
count (filter odd? (map inc (range 1e6))) ns 1365379728 runs 1480972619 1365379728 1136198093
count (filter odd? (map inc (vec (range 1e6)))) ns 2148792336 runs 1350879178 3986279892 2148792336
transduce (comp (map inc) (filter odd?)) + (range 1e6) ns 101097205 runs 73693169 101144727 101097205
loop equivalent ns 7379610 runs 8461983 7379610 7129254
EXIT 0
```

## Expected

`chunked-seq?` is true for `(seq (range 10))` and for a vector's seq, as on the JVM, and the lazy `map`/`filter` pipeline over 1e6 runs within a small factor of the loop (the JVM: 25.9 ms for `count (filter odd? (map inc (range 1e6)))`, cohost's `dev/research/p5_seqs.clj`).

## Actual

`(chunked-seq? (seq (range 10)))` prints `false` (`integer_range`), `(chunked-seq? (seq (vec (range 10))))` `false`; only the floating-point range is chunked. `count (filter odd? (map inc (range 1e6)))` is 1365379728 ns, over the vector 2148792336, the transducer 101097205, the equivalent loop 7379610: the lazy pipeline is 185x the loop and 13x the transducer.

## Where it is in the source

`integer_range` has `behavior::chunkable` commented out as a TODO (`obj/integer_range.hpp:293`), `persistent_vector_sequence` is not chunkable, and `integer_range::next()` allocates a new range per step (`obj/integer_range.cpp:108`); `core.jank`'s `map` and `filter` carry Clojure's `chunked-seq?` branch (lines 2885, 3864) that never fires for these. The non-integer `range` is chunked (32) but locks a mutex per step (`obj/range.hpp:158`, `range.cpp:352`), and `array_chunk::chunk_next` copies the chunk buffer per element consumed (`obj/array_chunk.cpp:41`).

## What would fix it

Implement `chunkable` on `integer_range` (fill an `array_chunk` of 32 boxed ints, no mutex) and expose immer leaves as chunks for vector seqs; make `chunk_next` share the buffer.

## A segfault the chunking exposes (fixed in 15b-17-reduce-chunks.patch)

`chunk_cons` (`runtime/core/seq.cpp`) built its `chunked_cons` with `seq(rest)`, so a chunked lazy pipeline realized its whole chain recursively at the first `seq` call and overflowed the stack at about 1e6 elements: `(count (map inc (range 0.0 1000000.0)))` exits 139 on the unpatched source build (`/usr/local/lib/jank-dev`, f3d82d65), and with `integer_range` chunked `(count (map inc (range 1000000)))` did too. 15b keeps the rest unrealized, as Clojure's `ChunkedCons` keeps `more`; `chunked_cons::next` and its in-place step realize the tail when they step past the chunk, and `chunk_next` returns a seq (`obj/chunked_cons.cpp`, outside U4-1's owns list; accepted by the coordinator's 12:43 PM ET NOTICE).
