# `reduce` walks with an allocating `next()` per element; `reduce-kv` already loops directly

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/15-reduce-walk.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 15-reduce-walk` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/15-reduce-walk.jank
reduce + over 1e6 vector ns 81204945 runs 79927558 85290374 81204945
reduce-kv over the same vector ns 33133254 runs 33133254 49781436 24208853
loop nth over the same vector ns 22227975 runs 22227975 23128826 21726172
reduce + over (range 1e6) ns 73891447 runs 51844217 74543866 73891447
loop over (range 1e6) with next-in-place ns 12166154 runs 11970106 12166154 12515293
EXIT 0
```

## Expected

`reduce` over a vector costs about what `reduce-kv` and the `nth` loop cost (the JVM: 5.3 ms for `reduce +` over 1e6, cohost's `dev/research/p5_seqs.clj`).

## Actual

`reduce +` over the 1e6 vector is 81204945 ns; `reduce-kv` over the same vector 33133254; a `loop` with `nth` 22227975; `reduce +` over `(range 1000000)` 73891447 against 12166154 for a `loop` using `fresh-seq`/`next-in-place`: the allocating walk is 2.5-6x the in-place or indexed loop.

## Where it is in the source

`runtime::reduce` walks `make_sequence_range` and makes a virtual `f.call` per element (`runtime/core/seq.cpp:901`); the range advances with the allocating `next()` (`runtime/sequence_range.hpp:39`) because the `next_in_place` branch is commented out (`sequence_range.hpp:169`; `runtime/object.hpp:567`: "Each call to next() allocates a new sequence"). `reduce-kv` loops over the immer data directly (`seq.cpp:926`); `doseq` already uses `fresh-seq` + `next-in-place` (`core.jank` line 2742). `core.jank`'s `reduce` says `; TODO: Chunking support.` (line 1141).

## What would fix it

Finish `fresh_sequence_range` and use it in `reduce`, `str`, `sort`, `vec`, `apply_to`, `sequence_length` and equality; then a `visit_object` dispatch at the top of `reduce` with direct loops per collection (vector, list, maps, `integer_range`, chunked seqs), as `reduce_kv` has.

## Patches and their regression probes

- `upstream/jank/patches/15a-16-in-place-walks.patch` (part one, with jank-16): the in-place walk in every runtime walker. Its regression probe is `dev/research/upstream/15a-walk-proto.jank` (`bash dev/research/upstream/run.sh 15a-walk-proto`): the patch's walk compiled through `cpp/raw` against the installed runtime, checked against `reduce` on 25 seq kinds (the result, a second walk, and the walked object printing the same after: the ownership rule of `object.hpp`), then timed. On apt 0.1-noble (box shared): `walk-proto cases 25 ok 25`; `reduce +` over a 1e6 vector 95884038 ns, the prototype walk 22547564 (`reduce-kv` 22871171).
- `upstream/jank/patches/15b-17-reduce-chunks.patch` (part two, with jank-17): the per-collection `reduce` and the chunked walk. Probe: `dev/research/upstream/15b-reduce-proto.jank` (`run.sh 15b-reduce-proto`): `walk-proto cases 25 ok 25`, `cursor-proto cases 14 ok 14` (including the rest at every offset mid-chunk); `reduce +` over a 1e6 vector 93846335 ns -> 8750834 (the `nth` loop 21832473).
