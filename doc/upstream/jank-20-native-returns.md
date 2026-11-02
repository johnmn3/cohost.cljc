# A `^long` parameter unboxes on entry but the return always boxes, so a hinted leaf fn is slower than the unhinted one; return hints are ignored

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/20-native-returns.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 20-native-returns` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/20-native-returns.jank
2e6 calls add-plain ns 29717365 runs 30184824 29388588 29717365
2e6 calls add-hinted (^long params) ns 47635489 runs 52763627 47635489 47047078
2e6 calls add-ret-hinted (^long params and return) ns 50576272 runs 50576272 50888253 50012400
fib 30 plain ns 12343281 runs 12206591 12343281 12464519
fib 30 ^long param ns 13951691 runs 13924724 14385390 13951691
fib 30 in C++ through cpp/raw ns 3414837 runs 3427923 3266108 3414837
EXIT 0
```

## Expected

A hinted leaf is no slower than the unhinted one, and `fib` with `^long` approaches the C++ recursion.

## Actual

2e6 calls of `(+ a b)`: plain 29717365 ns, `^long` parameters 47635489 (1.6x slower), `^long` parameters and return 50576272 (the return hint changes nothing); fib 30: plain 12343281, `^long` 13951691, the C++ recursion 3414837 (3.6x below plain).

## Where it is in the source

A `:tag` is read only on fn parameters (`analyze/processor.cpp:1642`); every arity's tail is forced to `object_ref` (`processor.cpp:1766`); `defn`'s `:rettag` has no consumer (`core.jank` line 779); a call's result type is always `object_ref` (`analyze/expression.cpp:59`); `if` forces both branches boxed (`processor.cpp:2503`); the `needs_box` analysis is read by the IR once and never by codegen (`expression.hpp:198`, `ir/processor.cpp:390`). So `(defn f [^long a ^long b] (+ a b))` is `convert<long>::from_object` twice, a native add, `into_object` on the way out: more work than the boxed `add`. cohost's H0 measured the same (doc/perf/jank.md, "Type hints (H0)": hinted leaf 1.24-1.52x slower; a loop that carries the hinted value 0.10-0.13x).

## What would fix it

Infer or honor a native return type and emit a second, native entry per typed arity (used by named recursion and typed call sites), keeping the boxing entry for dynamic callers; propagate native types through `let`/`loop` locals and `if`; compute in `i64` with overflow checks (see jank-12 and jank-21).
