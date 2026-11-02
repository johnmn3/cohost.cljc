# A multimethod call does a hash-map get per call; `truthy` loads the object's type

Status: REPRODUCED; patch `upstream/jank/patches/22-multi-cache-truthy.patch` (partial: the one-method multimethod moves 5.0x -> 4.0x of a plain fn call, not under 2x)

Host: jank built from source at jank-lang/jank `f3d82d65` (2026-11-01) with `dev/install/jank-src.sh`, Ubuntu noble, 4 CPUs shared with other lanes running unserialized. Three binaries: `jank-dev` (f3d82d65, no patches), `jank-u2` (f3d82d65 + 11, 13, 14, 18, 19), `jank-u2-22` (the same + 22). Each patched binary ran with its own `XDG_CACHE_HOME` (both print the same binary version, so they would otherwise share one stale PCH and fail with "Parsing failed").

## Repro

Repro: `dev/research/upstream/22-multi-truthy.jank` in johnmn3/cohost.cljc (branch `max/u2`), run as `bash dev/research/upstream/run.sh 22-multi-truthy` (prepends a 3-run median timer, then `jank -O3 run`). Rows: a plain fn call; a one-method `defmulti` on a constant dispatch value; `(:shape m)` through a fn and through a two-method `defmulti` on `:shape`, same value and alternating; a two-method `defmulti` on parity against a fn doing the same work; `(if x ...)` over a vector of mixed values, and over nil/false only.

Round 2 of two interleaved rounds (base, no22, with22), ns per 1e7 calls:

```
row                              jank-dev     jank-u2 (no 22)  jank-u2-22
fn-call                          170322656    121818641        124892539
multi-one-method                 844438416    593089235        495994397
fn-keyword-get                   302587484    177123392        161838274
multi-keyword-same               952381358    664272005        543798668
multi-keyword-alternating       1087033775    746564974        722839461
fn-parity                        418554332    331248418        348836505
multi-parity-two-methods        1264852000    907036810        842293166
truthy-mixed                     310371743    214342761        216634563
truthy-nil-false-only            263125709    213394398        218790151
```

Round 1, no22 -> with22: multi-one-method 863471911 -> 640951545, multi-keyword-same 934401980 -> 689543069, multi-keyword-alternating 1093602994 -> 1097388349, truthy-mixed 265826646 -> 297714594. `truthy check [0 0 1 1 1 1 1 1 1 1]` on every binary.

## Expected

A multimethod call whose dispatch value repeats costs about a plain fn call plus the dispatch fn (the JVM's MultiFn caches the method per dispatch value; P2's `call-multi` was 55-61 ns against 9 ns for a plain call). `(if x ...)` is two pointer compares.

## Actual

Before the patch the one-method multimethod is 4.9x a plain fn call (593089235 vs 121818641 ns on jank-u2); after it 4.0x (495994397 vs 124892539). The keyword-dispatched multimethod with a repeating value falls 18% (664272005 -> 543798668); alternating values are unchanged (746564974 -> 722839461). The truthy rows do not move outside the noise of a shared box (214342761 -> 216634563).

## Where it is in the source

`multi_function::get_method` (`src/cpp/jank/runtime/obj/multi_function.cpp`) compares `cached_hierarchy` with `hierarchy.deref()` and then does `method_cache->get(dispatch_val)`, a `persistent_hash_map` lookup (hash of the dispatch value, a HAMT walk, an equality test), on every call. The rest of the cost is three dynamic calls per multimethod call: the multimethod itself (`object_ref::call` on the var's value), `dispatch.call(...)`, and the method's `call(...)`; a plain fn call is one. `truthy(object_ref)` (`include/cpp/jank/runtime/core/truthy.hpp`) tests for tagged small ints and reals, loads the type for `is_nil`, and `dyn_cast`s to `boolean`.

## What would fix it

The patch: a one-entry cache ahead of the map get (the last `(method_cache, dispatch value, method)`, one immutable GC-allocated entry behind an atomic pointer, valid only while `method_cache` is the same map; every reset, hierarchy change, add/remove/prefer installs a new map), and `truthy` as two word compares against `_jank_nil` and `jank_false` (the tree constructs no other `obj::nil` or `obj::boolean`). Getting under 2x of a plain call needs one dynamic call fewer per multimethod call: a direct call of the cached method's arity entry, or inlining the dispatch fn when it is a keyword or a constant (U7's direct calls).
