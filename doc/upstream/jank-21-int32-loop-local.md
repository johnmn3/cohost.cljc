# A `loop` local started from an integer literal is a C++ `int` (32-bit)

Status: @@STATUS:21-int32-loop-local@@

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/21-int32-loop-local.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 21-int32-loop-local` (prepends a 3-run median timer, then `jank -O3 run`).

```
@@OUTPUT:21-int32-loop-local@@
```

## Expected

`2147483650`, `2449965000`, `2147483648`, `2147483648`.

## Actual

@@ACTUAL:21-int32-loop-local@@

## Where it is in the source

An integer literal that fits in `i32` is typed C++ `int` (`analyze/cpp_util.cpp:621`), a `loop` binding keeps that type through `mutable_type` (`cpp_util.cpp:1023`), and `recur` converts into it (`processor.cpp:2024`); the generated C++ for `(loop [i 0 ...] (recur (inc i) ...))` is `int v19 (static_cast<int>(v18)); s5 = v19;` (cohost's doc/research/jank-faster-than-jvm.md §8.1). `add(primitive, primitive)` is an unchecked `l + r` under `-fwrapv` (`runtime/core/math.hpp:172`). Clojure's `long` is 64-bit.

## What would fix it

Type integer literals and literal-initialized locals as `i64`, and check overflow on the native arms as `inc` does.
