# A fn whose untyped params arrive as reals runs boxed (28 speculates only i64), and a nested loop that rebinds its outer loop's name does not compile

Status: REPRODUCED; fixed by cohost's upstream/jank/patches/32-real-speculation.patch (lane U6, order U6-4)

Host: jank source build f3d82d65 + cohost's series through 28 (the 16 patches at max/main 0e7ae5b), and jank 0.1-noble (apt) for the loop defect; Ubuntu noble, 4 CPUs (shared). Source citations are to jank-lang/jank at `f3d82d65`.

## Repro

Repro: `dev/research/upstream/32-real-speculation.jank` in johnmn3/cohost.cljc (branch `max/u1`), run as `bash dev/research/upstream/run.sh 32-real-speculation`. Its Expected line is Clojure 1.12's output on the same forms (Math/sqrt for the cpp sqrt).

```
== series through 28
real speculation: 3.0 3.0 throws 55.0 55.0 57054003 true 0.125 50 50 2
down-d 2e6 (untyped, real arg) ns 3878937
down-d-hinted 2e6 (^double) ns 1053054
kstep 1e6 (untyped n bx cx) ns 29263572
kstep-hinted 1e6 ns 18369509
mandel 100x100 (untyped cr ci) ns 1260986
```

The nested-loop defect, on apt 0.1-noble and every build since: `(loop [y 0 n 0] (if (< y 3) (recur (inc y) (loop [x 0 n n] ...)) n))` fails C++ codegen with `use of undeclared identifier 's25'`.

## Expected

The values Clojure prints (already right), an untyped fn called with reals as fast as its `^double` twin, and the nested loop compiling.

## Actual

`down-d` (a loop from an untyped param called with a real) is 3.7x its hinted twin; an nbody step with untyped `n bx cx` 1.6x; the mandelbrot inner loop boxed. The nested loop does not compile, which is why mandelbrot, fannkuch and nsieve are ABSENT on every jank target of cohost's bench:programs (doc/perf/HISTORY.md).

## Where it is in the source

28-param-speculation speculates untyped params as i64 only; a real argument takes the generic body. For the loop: `ir/processor.cpp` gen(recur) generates its arguments before assigning the loop shadows from `builder::local_to_loop_shadow`; an inner loop in an argument that rebinds a name overwrote that name's shadow, and nothing restored it.

## What would fix it

Speculate f64 too (a param with integer evidence stays i64), with the boxing entry choosing the i64 entry, the f64 entry or the generic body by each argument's tag; scope the loop shadows to their loop. Both are in 32-real-speculation.patch; its header has the before/after.
