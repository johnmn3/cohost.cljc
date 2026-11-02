# A lazy seq's per-chunk frame allocates 384 bytes, and the collector re-marks the heap every 128 MiB

Status: REPRODUCED; patch `upstream/jank/patches/25-closure-size-gc.patch`

Host: jank built from jank-lang/jank at `f3d82d65` plus cohost's series 11, 13, 14, 12-21, 18, 19,
15a-16, 15b-17, 22 and 23 (u5's prefix), Ubuntu noble, 4 CPUs, shared with two other lanes (load
8-12 during the timings below). Source citations are to that tree.

## Repro

Repro: `dev/research/upstream/25-closure-gc.jank` in johnmn3/cohost.cljc, run as `bash
dev/research/upstream/run.sh 25-closure-gc` (prepends a 3-run median timer, then `jank -O3 run`).
Before (the series through 23):

```
sizeof jit_closure 176 lazy_sequence 144 chunked_cons 88 integer_range 104
fn capturing two locals bytes 22403456 per element 224.03456
lazy-seq capturing two locals bytes 38395488 per element 383.95488
count (filter odd? (map inc (range 1e6))) bytes 60995056 per element 60.995056
count (filter odd? (map inc (range 1e6))) ns 141110706
loop equivalent ns 10950105
binary-trees: 16 x (check (build 14)) ns 88009600
binary-trees: 1 x (check (build 18)) ns 113070987
closures => [1 2] [1 1] [2 1 2] [1 1 (2 3)] true {:m 1} [1 2]
lazy seq meta => {:k 1} {:c 1} {:r 1}
```

## Expected

A closure costs what it captures plus a small header; a lazy seq a few words; the lazy
`map`/`filter` pipeline allocates little more per element than the values it produces; the
collector does not dominate an allocation-heavy loop.

## Actual

- `jit_closure` is 176 bytes for any closure: 11 arity function pointers (88 bytes, for a closure
  that has one arity) and an inline `lazy_meta` (a `folly::Synchronized` state plus an ns pointer,
  about 56 bytes) that almost no closure uses. Its capture context is a second allocation
  (`jtl::make_ref<struct ctx>` in `codegen/cpp_processor.cpp`). A two-capture closure: 224 bytes.
- `lazy_sequence` is 144 bytes: the same inline `lazy_meta`, and a `std::recursive_mutex` (40
  bytes) taken only while it is first realized (15a's flag skips it after). A `lazy-seq` form
  capturing two locals: 384 bytes, once per chunk per stage of a chunked pipeline.
- `chunked_cons` (88) and `integer_range` (104) carry the same inline `lazy_meta`.
- bdwgc runs with interior pointers recognized (`c_api.cpp`, `GC_set_all_interior_pointers(1)`),
  which adds a byte to every block: a 64-byte object takes an 80-byte block.
- `count (filter odd? (map inc (range 1e6)))` allocates 61 bytes per element; with collections off
  (`GC_DONT_GC=1`) the same pipeline runs in 45-49 ms, and a run of five collects 3 times at
  26/58/7 ms of marking each.

## What fixes it (25-closure-size-gc.patch)

- `lazy_meta_ptr` (`lazy_meta.hpp`): meta out of line, null until set, published atomically; used
  by `jit_closure`, `lazy_sequence`, `chunked_cons`, `integer_range`.
- `jit_closure` points at an arity table that codegen emits once per closure site (static
  storage) instead of holding 11 pointers; its flags sit in `object`'s 4 bytes of tail padding;
  `jit_closure::create<Ctx>` builds the box and its context in one block (fixed-arity closures;
  variadic closures unchanged; c_api's `jank_closure_set_arity*` write a private copy of the
  table). 176 -> 40 bytes; a two-capture closure 224 -> 64.
- `lazy_sequence`'s lock is a u32 owner token (in the tail padding) and a u8 depth; a waiter
  yields. 144 -> 56 bytes; a `lazy-seq` frame 384 -> 128.
- `map`/`filter` over an `integer_range` compute the chunk's values in their loop instead of
  making the range's chunk first (`chunk_map_first`, `chunk_filter_first`).
- The default initial heap is 256 MiB (was 128; the environment still wins). Measured through the
  environment on the 25 build before its last two changes (the context alignment and the range
  loop), 3 rounds each, configs interleaved, load 8-10, medians (pipeline / bt-14 / bt-18 ms; collections in the probe): 128M
  53.2 / 61.2 / 141.5, 7; 256M 39.4 / 38.2 / 54.1, 3; 512M 65.9 / 68.6 / 74.6, 2; divisor 2
  45.7 / 52.6 / 106.6, 8; divisor 1 69.4 / 66.6 / 69.0, 7.

After:

```
sizeof jit_closure 40 lazy_sequence 56 chunked_cons 40 integer_range 56
fn capturing two locals bytes 6397952 per element 63.97952
lazy-seq capturing two locals bytes 12800000 per element 128.0
count (filter odd? (map inc (range 1e6))) bytes 30993488 per element 30.993488
count (filter odd? (map inc (range 1e6))) ns 32343660
loop equivalent ns 6102757
binary-trees: 16 x (check (build 14)) ns 40885378
binary-trees: 1 x (check (build 18)) ns 77200889
closures => [1 2] [1 1] [2 1 2] [1 1 (2 3)] true {:m 1} [1 2]
lazy seq meta => {:k 1} {:c 1} {:r 1}
```

A build that changes these layouts must delete `build/core-libs` (phase-1 reuses a `core.o` newer
than `core.jank` whatever the headers say; the stale one segfaults in `jit_closure::call`) as well
as every PCH cache entry that names the install prefix.
