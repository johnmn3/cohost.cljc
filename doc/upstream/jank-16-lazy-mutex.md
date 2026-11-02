# `first`/`next` on a realized lazy seq lock a `recursive_mutex` on every call

Status: @@STATUS:16-lazy-mutex@@

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/16-lazy-mutex.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 16-lazy-mutex` (prepends a 3-run median timer, then `jank -O3 run`).

```
@@OUTPUT:16-lazy-mutex@@
```

## Expected

`first` on a realized lazy seq costs what `first` on a list costs.

## Actual

@@ACTUAL:16-lazy-mutex@@

## Where it is in the source

`lazy_sequence::first()`/`next()`/`seq()` call `realize()` and take `std::lock_guard<std::recursive_mutex>` on every call, realized or not (`obj/lazy_sequence.cpp:53`), and `lazy_sequence` has no `next_in_place` (`obj/lazy_sequence.hpp:47`, commented out). `range` (the non-integer one) locks a per-object mutex per `next()` too (`obj/range.cpp:352`).

## What would fix it

A lock-free fast path once realized (an atomic flag checked before the lock), and `next_in_place` for lazy seqs.
