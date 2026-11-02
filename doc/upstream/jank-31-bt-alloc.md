# A two-element vector literal spends more instructions building the box than allocating it

Status: REPRODUCED; patch `upstream/jank/patches/31-bt-alloc.patch`

Host: jank built from jank-lang/jank at `f3d82d65` plus cohost's series 11, 13, 14, 12-21, 18, 19, 15a-16, 15b-17, 22,
20, 23, 24, 26, 25, 27 (max/main f8a32df; u5's prefix), Ubuntu noble, 4 CPUs, shared with two other lanes (load 5-15
during the timings below). The patch also applies after 28, 30 and 29 (max/main 09dcbf5). Source citations are to that tree.

## Repro

Repro: `dev/research/upstream/31-bt-alloc.jank` in johnmn3/cohost.cljc, run as `bash
dev/research/upstream/run.sh 31-bt-alloc` (prepends a 3-run median timer, then `jank -O3 run`).
Before (the fifteen-patch series):

```
[i i] 2e6 ns 99301262 runs 121985074 99301262 96139357
GC_MALLOC(16) 2e6 ns 34642756 runs 34642756 59230921 25585706
(pair 1) 2e6 ns 21269917 runs 17764895 21269917 21580007
(nth pair 1) 2e6 ns 12861803 runs 12861803 12767418 17656348
binary-trees: 16 x (check (build 14)) ns 31437761 runs 31539577 26968618 31437761
binary-trees: 1 x (check (build 18)) ns 34953161 runs 34953161 96306432 34539701
GC_size of a runtime [i i] 96 [i i i] 96
literals [1 2 32 33]
ops [1 2] 1 2 2 :nf :nf :nf [1 2 3] [:a 2] [1] true true (1 2) {:m 1} [1 2 9] :oob :oob :oob [1 2 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39]
meta {:m 2} [1 2] {:m 2} {:m 2} {:m 2} [:z 2 9] nil [1 2] true [[5 6] {:k 1} {:k 1} [5 6 8]]
meta-in-header [1 2 3] {:m 4} [:z 2 3 9] nil [1 2 3] {:m 4} {:m 4} [1 2 3 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34] {:m 4}
EXIT 0
```

## Expected

`[i i]` costs about one small GC allocation (the jank-18 bar: within 3x of `GC_MALLOC(16)`); `(v 0)` on a small
vector is a tag test, a bounds check and a load.

## Actual

callgrind, instructions per `[i i]` (2e6 iterations minus 1, the loop of `run.sh 18`), on the fifteen-patch build:

```
total delta 976,737,517 instructions = 488.4 per unit (n=2000000)
   201,716,062  20.7%   100.9/unit  ./string/../sysdeps/x86_64/multiarch/memset-vec-unaligned-erms.S:__memset_avx2_unaligned_erms
   154,000,458  15.8%    77.0/unit  ???:oref<obj::persistent_vector> obj::make_small<obj::(anonymous na
   106,000,341  10.9%    53.0/unit  ???:_jank_vec_impl(char const*, unsigned long long, __va_list_tag*)
   102,859,152  10.5%    51.4/unit  ???:GC_malloc_kind
    95,999,952   9.8%    48.0/unit  ???:immer::detail::rbts::rbtree<oref<object>, immer::memory_policy<immer::heap_policy<immer::gc_heap>, immer::no_r
    72,477,690   7.4%    36.2/unit  ???:GC_allochblk_nth
    60,000,062   6.1%    30.0/unit  ???:0x0000000026a49000 [???]
    46,000,161   4.7%    23.0/unit  ???:_jank_vec(unsigned long long, ...)
    37,000,707   3.8%    18.5/unit  ???:GC_allochblk
    32,000,113   3.3%    16.0/unit  ???:obj::persistent_vector::create_small(oref<object> const*, unsigned long long) [/home/user/lanes/u5/ja
    11,999,994   1.2%     6.0/unit  ???:object::object(object_type, object_behavior)
```

callgrind, binary-trees 16 x depth 14 minus 1 x depth 1, per vector built (262,128 vectors; libLLVM, libclang and
libc malloc left out: that is the JIT compiling, which differs between the two runs):

```
plain: runtime + JIT-code delta (no libLLVM/libclang/libc malloc) 912.3 instr per vector
  145.2/vector  ./string/../sysdeps/x86_64/multiarch/memset-vec-unaligned-erms.S:__memset_avx2_unaligned_erms
  137.0/vector  ???:0x0000000026a91000'2 [???]
   93.0/vector  ???:0x0000000026a66000'2 [???]
   77.0/vector  ???:oref<obj::persistent_vector> obj::(anonymous namespace)::make_small<obj::(anonymous namespace)::copy_fill(oref<objec
   64.0/vector  ???:obj::persistent_vector::nth(oref<object>) const
   53.0/vector  ???:_jank_vec_impl(char const*, unsigned long long, __va_list_tag*)
   51.4/vector  ???:GC_malloc_kind
   48.0/vector  ???:immer::detail::rbts::rbtree<oref<object>, MP::rbtree()
   36.2/vector  ???:GC_allochblk_nth
   30.0/vector  ???:is_nil(oref<object>)
   30.0/vector  ???:to_i64(oref<object>)
   23.0/vector  ???:_jank_vec(unsigned long long, ...)
   18.5/vector  ???:GC_allochblk
   16.0/vector  ???:obj::persistent_vector::create_small(oref<object> const*, unsigned long long)
```

- `[i i]` asks for 80 bytes (the 56-byte `persistent_vector` box and a 24-byte immer leaf, jank-18's one block); bdwgc
  with all interior pointers adds one byte to every request, so it is a 96-byte block (`GC_size`); `GC_MALLOC(16)` is
  a 32-byte block the same way.
- Of the ~490 instructions, about 230 are the collector (the memset share is `rep stosb` counted per byte by
  callgrind) and about 220 are building the box: `_jank_vec` is a C variadic function, so each literal goes through
  a `va_list` into a 32-slot staging buffer (`_jank_vec` 23 + `_jank_vec_impl` 53), then `create_small` (16) and
  `make_small` (77), and immer's `rbtree()` default constructor (48): `make_small` moves its `rbtree` into the
  box, and immer's move constructor default-constructs (two function-local statics, `empty_root()` and
  `empty_tail()`) and then swaps (`immer/detail/rbts/rbtree.hpp:111`).
- `(v i)` goes `oref::call` -> `persistent_vector::call` -> `nth` (virtual) -> `is_integral` and `to_i64`
  (out of line in `runtime/core/math.cpp`) before the immer lookup.

## Patch

- `codegen/api.hpp`: a `_jank_vec(u64, object_ref const &...)` template for 1-32 elements without meta, always
  inline. Codegen's call `_jank_vec(2, a.erase(), b.erase())` is an exact match for it, so it wins over the C
  variadic overload, which stays for meta, string meta and 0 or more than 32 elements. It hands a stack array to
  `persistent_vector::create_small`.
- `persistent_vector.cpp` `make_small`: the box's immer vector is copy-constructed from a shared empty vector
  (a function-local static: four stores) and then given its size and tail; root and shift are the empty vector's.
  No `rbtree()`, no swap.
- `persistent_vector::call` and `nth(index)`: a tagged small integer in range is read in place; anything else takes
  the old path (same errors, same messages).
- `make_small`'s block overlaps box and leaf by one word: the leaf starts at the box's last member, `meta`, so the
  leaf's 8-byte header (immer's `ownee`, a transient's owner token under `gc_transience_policy`) is that word. immer
  writes a node's ownee only after `can_mutate(e)`, i.e. when it already equals a live transient's token, a fresh
  allocation (`operations.hpp` merge_leaf/merge_inner, `node.hpp` ensure_mutable_relaxed_e); the word is null or the
  vector's own `lazy_meta`, never such a token, so transients copy the leaf as before and no immer write reaches
  `meta`. `ownee().owned()` only chooses a deallocation size, and the GC heap never deallocates. Static asserts pin
  `meta` as the last word and the leaf header as one word. `[i i]` asks 72 bytes: bdwgc's 80-byte class instead of
  96; every small vector is 8 bytes smaller.

## After

```
[i i] 2e6 ns 58254367 runs 76128341 58254367 47644999
GC_MALLOC(16) 2e6 ns 16844906 runs 17325216 16844906 16564008
(pair 1) 2e6 ns 7509653 runs 7882692 7509653 7454397
(nth pair 1) 2e6 ns 8564805 runs 8468375 11919392 8564805
binary-trees: 16 x (check (build 14)) ns 14694399 runs 14694399 28321570 14634505
binary-trees: 1 x (check (build 18)) ns 19615225 runs 19382359 19615225 19733336
GC_size of a runtime [i i] 80 [i i i] 96
literals [1 2 32 33]
ops [1 2] 1 2 2 :nf :nf :nf [1 2 3] [:a 2] [1] true true (1 2) {:m 1} [1 2 9] :oob :oob :oob [1 2 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39]
meta {:m 2} [1 2] {:m 2} {:m 2} {:m 2} [:z 2 9] nil [1 2] true [[5 6] {:k 1} {:k 1} [5 6 8]]
meta-in-header [1 2 3] {:m 4} [:z 2 3 9] nil [1 2 3] {:m 4} {:m 4} [1 2 3 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34] {:m 4}
EXIT 0
```

Interleaved plain (the fifteen-patch series) / after (+ 31), 6 rounds of `run.sh 18-small-vectors` and
`run.sh 31-bt-alloc` each (12 samples a side; load 5-10, the box shared with two lanes):

| row | plain median | after median | |
|---|---|---|---|
| `[i i] 2e6` / that run's `GC_MALLOC(16) 2e6` | 4.96x | 3.44x (3.19-3.88) | bar 3x: not met |
| `[i i] 2e6` | 83.8 ms | 58.7 ms | |
| binary-trees 16 x depth 14 | 20.23 ms | 15.09 ms | bar 26.1 ms (3x the JVM's 8.7): met |
| binary-trees 1 x depth 18 | 22.21 ms | 15.64 ms | |
| `(pair 1) 2e6` | 13.01 ms | 7.93 ms | |

callgrind, per `[i i]`: 488 -> 349 instructions with the first three changes (`_jank_vec`, `_jank_vec_impl`,
`rbtree()` gone; `make_small` 77 -> 62), 318 with the overlap (the 80-byte block: memset 101 -> 83, `GC_allochblk*`
55 -> 45); per binary-trees vector 912 -> 741 with the first three.

What remains of `[i i]`'s 3x: bytes. An 80-byte block against `GC_MALLOC(16)`'s 32-byte one is 2.5x the memory to
clear, sweep and collect (in steady state, one collection per 2e6 `[i i]` at the 256 MiB default heap, none per 2e6
`GC_MALLOC(16)`); the box carries immer's 32-byte rbtree header (size, shift, root, tail) for a vector whose root is
always the shared empty root. Below the 80-byte class needs a vector that is not an immer vector (a separate small
vector type with every vector behavior of its own), which this patch does not do. On this box the ratio also moves
with the heap: at `GC_INITIAL_HEAP_SIZE=32M` the plain build's ratio is 3.1-3.2x because `GC_MALLOC(16)` slows down
(78-95 ms) more than `[i i]` does.

What remains of binary-trees (not this patch's files): the JIT-emitted bodies of `build` and `check` (boxed `+` and
the calls, ~230 instructions per vector) and `is_nil` called out of line (`runtime/core`, 30 per vector).
