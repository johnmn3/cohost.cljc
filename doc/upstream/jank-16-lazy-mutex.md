# `first`/`next` on a realized lazy seq lock a `recursive_mutex` on every call

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/16-lazy-mutex.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 16-lazy-mutex` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/16-lazy-mutex.jank
types lazy_sequence persistent_list cons
first on a realized lazy seq 1e7 ns 525211376 runs 671853803 525211376 512610544
first on a list 1e7 ns 103310725 runs 103310725 103303133 103331795
first on a cons 1e7 ns 102369144 runs 102369144 104681980 101412232
next on a realized lazy seq 1e7 ns 1743012597 runs 1675820320 1868517767 1743012597
next on a list 1e7 ns 797772518 runs 663915407 797772518 811227232
EXIT 0
```

## Expected

`first` on a realized lazy seq costs what `first` on a list costs.

## Actual

`first` on a realized 4-element lazy seq is 525211376 ns per 1e7 calls against 103310725 on a list and 102369144 on a cons (5x); `next` 1743012597 against 797772518 (2.2x).

## Where it is in the source

`lazy_sequence::first()`/`next()`/`seq()` call `realize()` and take `std::lock_guard<std::recursive_mutex>` on every call, realized or not (`obj/lazy_sequence.cpp:53`), and `lazy_sequence` has no `next_in_place` (`obj/lazy_sequence.hpp:47`, commented out). `range` (the non-integer one) locks a per-object mutex per `next()` too (`obj/range.cpp:352`).

## What would fix it

A lock-free fast path once realized (an atomic flag checked before the lock), and `next_in_place` for lazy seqs.
