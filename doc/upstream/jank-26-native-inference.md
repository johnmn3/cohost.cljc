# A native `loop` local keeps its native type through `recur` whatever `recur` passes it, so a real truncates into an integer local and an object throws; every var read is a virtual call

Status: REPRODUCED; fixed by cohost's upstream/jank/patches/26-native-inference.patch (lane U6, order U6-2)

Host: jank source build f3d82d65 + cohost's series through 20 (11, 13, 14, 12-21, 18, 19, 15a-16, 15b-17, 22, 20), and jank 0.1-noble (apt) for the before; Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65`.

## Repro

Repro: `dev/research/upstream/26-native-inference.jank` in johnmn3/cohost.cljc (branch `max/u1`), run as `bash dev/research/upstream/run.sh 26-native-inference` (prepends a 3-run median timer, then `jank -O3 run`). Each widening case runs under a catch, so a throw prints `throws`.

```
== jank -O3 run target/research/upstream/26-native-inference.jank   (series through 20)
error: call to 'from_object' is ambiguous      ((recur nil) into an integer local)
recur widening: 3 throws [3 2 1] 6 throws throws 2.5
native loops: 499999500000 1999.0 2000000 15 120.0
loop-only (P2: (< i n) against a var) ns 11870140
```

apt 0.1-noble stops at the second case: `An object of type integer was expected here, but a small_real was provided instead` (convert::from_object).

## Expected

`recur widening: 4.5 3.5 [3 2 1] 6 x3 nil 2.5` (Clojure auto-boxes a primitive loop local whose recur argument is not that primitive), and a loop bounded by a var (`(loop [i 0] (if (< i n) (recur (inc i)) i))`) costs about what the native loop costs.

## Actual

`(loop [s 0 n 0] (if (< n 3) (recur (+ s 1.5) (inc n)) s))` prints 3 (the real is cast into the i64 local); `(loop [acc 0 xs [1.5 2]] ... (recur (+ acc (first xs)) ...))` throws (the object is unboxed as an integer); `(recur (str ...))` throws and `(recur nil)` does not compile. P2's loop-only (5e6 iterations) takes 12185290 ns on the series build and 13345444 on apt: the loop counter is already native (an `int` on apt, an i64 since 12-21); the cost is reading `n`, a virtual `var::deref` per iteration.

## Where it is in the source

`analyze_recur` converts every recur argument to the loop binding's type (`analyze/processor.cpp`, `expected_type = mutable_type(...)` then `apply_implicit_conversion`), and a literal-initialized binding is native (`cpp_util.cpp` literal_type; i64 since 12-21), so a mismatched argument becomes a C++ cast or a `convert<long>::from_object`. Generated code reads every var with `var->deref()` (`codegen/cpp_processor.cpp`, var_deref), a virtual call that loads `thread_bound` and then the root (`runtime/var.cpp` var::deref).

## What would fix it

Keep a native loop local native only while every recur argument for it is a native number of the same kind, else box it (analyze the loop again with it boxed, as Clojure's auto-boxing does); read vars with the root inline when the var is not thread-bound. Both are in 26-native-inference.patch; its header has the before/after.
