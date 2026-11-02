# Variadic calls allocate their rest args; multimethod calls reset their cache and take two mutexes per call

Status: REPRODUCED; patch `upstream/jank/patches/29-variadic-multi.patch` (partial: variadic 4.0x and multimethod 1.8-2.3x faster, neither inside its bar)

Host: jank built from source at jank-lang/jank `f3d82d65` with `dev/install/jank-src.sh`, Ubuntu noble, 4 CPUs shared with other lanes running unserialized. Two binaries: `jank-u2-s27` (the series through 28) and `jank-u2-29` (the same + 29), each with its own `XDG_CACHE_HOME`.

## Repro

Repro: `dev/research/upstream/29-variadic-multi.jank` in johnmn3/cohost.cljc (branch `max/u2`), run as `bash dev/research/upstream/run.sh 29-variadic-multi` (3-run median timer, `jank -O3 run`). 24's shapes at 1e7 calls: a 1-arity fn call, a 2-arity call, `[x & ys]` calls whose rest is unused, read with `first`, read with `count`; a one-method `defmulti` on a constant dispatch fn, a two-method one alternating, a keyword-dispatched one alternating; then rest params that escape (returned, stored in an atom, `next`) and a `defmethod` added mid-loop, a `prefer-method`, a `derive` mid-loop.

Medians of 3 interleaved rounds (series / +29, ns per 1e7; the three rounds in brackets):

```
fn-call                         34027232 [31748850 34027232 50353942]   36185173 [35654732 36185173 61261305]
call-2                          39824685 [37991383 39824685 41092828]   37229891 [36391843 37229891 38634523]
call-variadic                  573775063 [478573359 573775063 681217890] 144311323 [140708724 144311323 173958790]
call-variadic-first            550719973 [522964529 550719973 619443248] 159841174 [147322780 159841174 164090636]
call-variadic-count            591201773 [513006073 591201773 631826345] 187872977 [186832973 187872977 222275448]
multi-one-method               483534732 [473806848 483534732 542988337] 213574047 [167297305 213574047 220120650]
multi-two-methods-alternating  572010825 [547760104 572010825 591006191] 316157793 [292421557 316157793 328940810]
multi-keyword-alternating      668313504 [624110974 668313504 750235034] 359939632 [347763331 359939632 367738999]
```

Both binaries print `escaping (2 3) [(2 3)] (5 6) 3 nil` and `multi [:default :default :a :a] :rect [:default :default :animal :animal]`, Clojure's values.

## Expected

A `[x & ys]` call whose rest does not escape costs a 2-arity call plus a few stores (Clojure allocates an ArraySeq, which the JVM's escape analysis often removes); a multimethod call whose dispatch value repeats costs a fn call plus its dispatch fn.

## Actual

The variadic call falls from 14.4x a 2-arity call to 3.9x (144311323 / 37229891); the one-method multimethod from 14.2x a fn call to 5.9x (213574047 / 36185173), two methods alternating from 16.8x to 8.7x.

## Where it is in the source

- `jit_variadic_function::call` (`runtime/obj/jit_variadic_function.cpp`) allocates the rest args on every call.
- `multi_function::reset_cache` (`runtime/obj/multi_function.cpp`) stores the hierarchy ref (`#'global-hierarchy`) in `cached_hierarchy`, and `get_method` compares that with `hierarchy.deref()` (the map): the compare fails on every call, so every call takes the recursive mutex and resets `method_cache` (a value found through `isa?` or `:default` is resolved again on every call), and `find_and_cache_best_method` passes the var where `is_preferred` walks parents of a map.
- `deferred_cpp_function::realize` (`runtime/obj/deferred_cpp_function.cpp`) takes a recursive mutex on every call. Under `jank run`'s lazy compilation every fn starts as a deferred fn; one held outside its var (a `defmethod`'s method, a `defmulti`'s dispatch fn) stays one, so each multimethod call took two more mutexes.

## What would fix it

The patch: a rest param that provably does not escape (unused, or only the direct argument of the inlined `first`, `second`, `count`, `empty?`; decided in the IR pass, recorded in a free bit of the arity flags) rides a sequence on the caller's stack; `reset_cache` stores the hierarchy's value and `is_a`/`is_preferred` take the map, as Clojure's MultiFn does; a keyword dispatch fn is a `get` computed inline; `realize` returns on an atomic flag and `call_fast` calls a realized deferred fn's compiled fn directly. What remains: under 3x for variadic calls needs the call site to call the variadic arity directly (no `jit_variadic_function::call` switch and virtual call); under 2x for multimethods needs the call site to compute a keyword or constant dispatch inline and call the cached method's arity directly (one dynamic call instead of three), which needs the defmulti's dispatch expression at the call site's compile.
