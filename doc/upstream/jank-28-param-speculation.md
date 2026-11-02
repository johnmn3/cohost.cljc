# A fn with untyped params runs boxed even when it only ever does arithmetic on them: fib and a countdown loop from a param pay a box per operation

Status: REPRODUCED; fixed by cohost's upstream/jank/patches/28-param-speculation.patch (lane U6, order U6-3)

Host: jank source build f3d82d65 + cohost's series through 26 (11, 13, 14, 12-21, 18, 19, 15a-16, 15b-17, 22, 20, 23, 24, 26), Ubuntu noble, 4 CPUs (shared with other lanes). Source citations are to jank-lang/jank at `f3d82d65`.

## Repro

Repro: `dev/research/upstream/28-param-speculation.jank` in johnmn3/cohost.cljc (branch `max/u1`), run as `bash dev/research/upstream/run.sh 28-param-speculation` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/28-param-speculation.jank   (series through 26)
speculation: 6765 2.0 42 3 2.5 5050 55.0 throws throws 6765
fib-var 30 (untyped param) ns 5046072
fib-hinted 30 (^long) ns 3708798
down-plain 2e6 (loop from an untyped param) ns 59608765
```

## Expected

The values Clojure prints (the line above is already right), and `(defn fib-var [n] ...)` within reach of the `^long` twin; a loop started from an untyped param (`(loop [i n acc 0] ...)`) as fast as one started from a hinted param.

## Actual

`fib-var` 30 is 1.36x `fib-hinted` (5046072 vs 3708798 ns): every call boxes its result and every `<`, `dec`, `-`, `+` dispatches on boxed operands. `down-plain` (2e6 steps) takes 59608765 ns where the same loop from a `^long` param takes about 0.5 ms (H0's down-long-plain against down-long-hinted: 46852122 vs 519750): `i` starts as the boxed `n`, so the loop local is an object and every step allocates.

## Where it is in the source

A param's type is its `:tag` or `object_ref` (`analyze/processor.cpp`, analyze_fn_arity); 20-native-returns gives an arity a native entry only when a param is hinted or a return is tagged; nothing infers a type for an untyped param. A loop binding initialized from an object-typed local is an object (`analyze_let`, `mutable_type`).

## What would fix it

Speculate: analyze an arity whose untyped param is used as a number a second time with the param as i64, keep both the native entry and the original (generic) body, and have the boxing entry pick the native entry when the arguments are integers and the generic body otherwise, so any other argument (a real, a string) behaves exactly as before. Named recursion and typed call sites call the native entry only with native integer arguments. 28-param-speculation.patch does this; its header has the before/after.
