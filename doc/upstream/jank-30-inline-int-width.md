# An integer past 2^31 is heap-allocated: the NaN-boxed payload is 32 bits of a free 48

Status: REPRODUCED (patch: `upstream/jank/patches/30-inline-int-width.patch`, after 27)

Host: jank built from source at jank-lang/jank `f3d82d65` with cohost's series, Ubuntu noble, 4 CPUs shared with other lanes; every binary with its own `XDG_CACHE_HOME`.

## Repro

`dev/research/upstream/30-inline-int-width.jank` (`bash dev/research/upstream/run.sh 30-inline-int-width`): the sums of `reduce +` over a 1e6 vector and range and the `nth` loop, the integer edges (2^31, 2^47, i64 overflow, hash and `=` across the boundary), and the timings of `reduce + v`, the `nth` loop, `reduce +` from 3e9, and `reduce +` over the first 60000 elements (a sum inside i32).

## The bisect (U2-5 step 1)

`run.sh 15-reduce-walk` under `GC_INITIAL_HEAP_SIZE=1G`, three interleaved rounds, on f3d82d65 + 11 13 14 18 19 15a 15b 22 (no 12-21) against the same + 12-21: `reduce +` over a 1e6 vector 6981181 / 7619036 / 12545235 -> 33084192 / 44519817 / 36309312 ns, the `nth` loop 18246905 / 23937112 / 18283144 -> 44781944 / 46203783 / 42786553. The sums: 1783293664 without 12-21 (wrapped at 32 bits), 499999500000 with it (correct). A sum that stays inside i32 is equal on both (989883 / 1064909 / 928305 against 977091 / 948564 / 1056875 ns), and a sum that starts above i32 (from 3e9) is equal on both too (36-40 against 32-38 ms): the cost is the heap integer per step above the inline range, which 12-21 reaches by computing correctly, not a checked branch. (perf is not installed on the box; the probes stand in for the profile.)

## Expected

A sum below 2^47 costs what a sum below 2^31 costs: the tagged pointer's 48 low bits are free under the integer tag `0xFFF9`, as the pointer tag uses 48.

## Where it is in the source

`runtime/oref.hpp`: `integer_value_mask` is `0xFFFF'FFFF` and `min/max_small_integer` are `i32`'s; `as_integer` returns an `i32`; `make_box(integral)` (`core/make_box.hpp`) tags only that range and calls `make_box<obj::integer>` past it. With 12-21, `detail::boxed_add` (`core/math.hpp`) takes its 32-bit fast path only when both operands fit in 32 bits and otherwise `boxed_add_slow`, which allocates.

## What the patch does

The integer payload is 48 bits (two's complement, sign-extended when read); `obj::small_integer` and `oref<obj::small_integer>` hold an `i64`; `make_box` tags anything in 48 bits. In 12-21's boxed arms only the fast path's width check moves: add and sub of two operands within 47 bits run in i64 (no overflow possible) and tag; mul of two 32-bit operands runs in i64; everything else is 12-21's checked path, unchanged (overflow still throws), which now tags a result inside 48 bits. Codegen and the analyzer's literal typing treat a boxed literal as a `small_integer` up to 48 bits.

Numbers: the U2-5 blocks of `coord/cohost-max/reports/u3.md`.
