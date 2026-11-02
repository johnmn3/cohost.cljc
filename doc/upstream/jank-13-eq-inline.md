# `=` is not inlined: every `(= a b)` is a var deref and a dynamic call

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/13-eq-inline.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 13-eq-inline` (prepends a 3-run median timer, then `jank -O3 run`).

```
== jank -O3 run target/research/upstream/13-eq-inline.jank
loop-with-= 1e7 ns 192413773 runs 192413773 179774983 194097081
loop-with-== 1e7 ns 59715070 runs 64049869 59648317 59715070
loop-with-< 1e7 ns 39027765 runs 38721639 39027765 39926360
loop-with-identical? 1e7 ns 53625462 runs 53169562 53625462 57488272
EXIT 0
```

## Expected

The four loops cost the same within noise (the JVM inlines all four).

## Actual

The `=` loop is 192413773 ns for 1e7 iterations against 59715070 with `==`, 39027765 with `<`, 53625462 with `identical?`: `=` costs about 13 ns more per test than `==`, the var deref and dynamic call.

## Where it is in the source

`core.jank` line 659 has `=`'s `:inline` commented out (`; TODO: Add template to support this.`), while `==` (line 676), `<`, `+`, `inc` and the others inline to `cpp/jank.runtime.*` through the analyzer's `:inline` path (`analyze/processor.cpp:3449-3478`). So the loop test every program writes, `(= i n)`, pays a var deref plus `object_ref::call` (two tag tests, a virtual call, an arity-pointer test) per iteration, and `hoist_var_derefs` gives up on fns with loops (`ir/opt/hoist_var_derefs.cpp:28`).

## What would fix it

Uncomment the `:inline` on `=` (the runtime fn is `jank::runtime::equal`). `assoc` has the inverse defect: its `:inline` fn takes three parameters but `:inline-arities #{2}` never matches its 3-arity (`core.jank` line 467), so `(assoc m k v)` never inlines either.
