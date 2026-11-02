# `+`, `-`, `*` on two small integers compute in 32 bits: `(+ 2147483647 1)` is negative

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/12-i32-add.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 12-i32-add` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/12-i32-add.jank
(+ 2147483647 1) => -2147483648
(inc 2147483647) => 2147483648
(+ 2147483647 2147483647) => -2
(- -2147483648 1) => 2147483647
(* 65536 65536) => 0
(* 2147483647 2) => -2
(reduce + (range 1000000)) => 1783293664
(+ 3000000000 1) => 3000000001
(let [a 2147483647 b 1] (+ a b)) => -2147483648
(add2 2147483647 1) => -2147483648
EXIT 0
```

## Expected

Every line prints the 64-bit result: `2147483648`, `2147483648`, `4294967294`, `-2147483649`, `4294967296`, `4294967294`, `499999500000`, `3000000001`, `2147483648`, `2147483648`.

## Actual

`(+ 2147483647 1)` prints `-2147483648`, `(+ 2147483647 2147483647)` `-2`, `(- -2147483648 1)` `2147483647`, `(* 65536 65536)` `0`, `(* 2147483647 2)` `-2`, `(reduce + (range 1000000))` `1783293664`, and the same through a `let` and through a defn; only `inc` (`2147483648`) and an operand already outside `i32` (`(+ 3000000000 1)` = `3000000001`) are right. The same at `-O0` (`target/research/upstream/12-i32-add-O0.log`).

## Where it is in the source

A tagged small integer's payload is `i32` (`runtime/oref.hpp:2250`, `obj/number.hpp:106`) and the small+small arm of `add` computes `typed_l->data + typed_r->data` in `int` before `make_box` range-checks it (`runtime/core/math.hpp:86`); `sub`/`mul` have the same arm. `inc` is overflow-checked into `i64` (`math.hpp:1192`), so `(inc 2147483647)` is right while `(+ 2147483647 1)` is not. Clojure's `long` is 64-bit.

## What would fix it

Widen the small+small arms to `i64` (and give `+`/`-`/`*` the overflow check `inc` has, or document that jank wraps where Clojure throws).
