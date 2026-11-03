# A variadic call goes through an arity switch and a virtual call; a multimethod call is three dynamic calls

Status: REPRODUCED; patch `upstream/jank/patches/34-callsite-variadic-multi.patch` (variadic and one-method multimethod inside their bars; two methods alternating on an arbitrary dispatch fn 3.0x a fn call, bar 2x)

Host: jank built from source at jank-lang/jank `f3d82d65` with cohost's series through 32 (... 28, 30, 29, 32; not 31). There are two binaries, the series (`jank-ctl`) and the series + 34 (`jank-inst`). They ran on 4 CPUs shared with other lanes, unserialized.

## Repro

Repro: `dev/research/upstream/34-callsite.jank` in johnmn3/cohost.cljc (branch `max/u1`), run as `bash dev/research/upstream/run.sh 34-callsite` (3-run median timer, `jank -O3 run`). It has 29's shapes at 1e7 calls, then four correctness lines:
- escaping rest params;
- a mid-loop `defmethod`, `prefer-method` and `derive`;
- a variadic var redefined mid-loop, `with-redefs` to a variadic fn and to a fixed one, and calls around a variadic fn's arities;
- the dispatch shapes defmulti reads (a constant, `(:k x)`, `(get x :k)`, a `nil` constant, a symbol body kept on the call path, a two-argument dispatch).

Medians of 3 interleaved rounds (series / +34, ns per 1e7; the three rounds in brackets; load 3.8-4.6):

```
fn-call                         34656548 [35232304 32604571 34656548]    31977813 [32591879 31240614 31977813]
call-2                          32862114 [32448824 32862114 33473575]    31840500 [67144721 31336682 31840500]
call-variadic                   84071982 [85986075 84071982 83971433]    66802207 [66285372 66802207 67277624]
call-variadic-first            108586300 [108841405 102947823 108586300] 94235946 [95389258 92540498 94235946]
call-variadic-count            131027435 [131027435 125939897 132656933] 107947185 [101037346 107947185 131284595]
multi-one-method               108766633 [108766633 108510402 115702273] 53802477 [51265706 53802477 55730340]
multi-two-methods-alternating  215406823 [215406823 240214019 212928752] 96621896 [95333608 96621896 98130349]
multi-keyword-alternating      222806755 [222806755 224779603 222291938] 118583975 [118583975 118314378 123735760]
```

Both binaries print Clojure's values on every correctness line.

## Expected

A `[x & ys]` call whose rest does not escape costs a 2-arity call plus a few stores. A multimethod call whose dispatch value repeats costs one fn call plus computing the dispatch value.

## Actual

- The variadic call goes from 2.56x a 2-arity call to 2.10x (66802207 / 31840500; bar 3x).
- The one-method multimethod goes from 3.1x a fn call to 1.68x (53802477 / 31977813; bar 2x).
- Two methods alternating go from 6.2x to 3.02x (bar 2x, not met). Their dispatch fn `(fn [x] (if (even? x) :even :odd))` is an arbitrary fn and is still called on every call.

## Where it is in the source

- `jit_variadic_function::call` (`runtime/obj/jit_variadic_function.cpp`) is a virtual call that then switches on the arity flags before reaching the variadic arity.
- `multi_function::call` (`runtime/obj/multi_function.cpp`) is a virtual call. It calls the dispatch fn (29 already computes a keyword dispatch inline), then `get_fn` out of line, then the method.
- The one-entry method cache holds one of two alternating values, so the other one goes to the map lookup on every call.

## What would fix it

The patch:
- The analyzer records each var's variadic shape at its def. A call to such a var, with at least its required args and no fixed arity of its count, emits `call_variadic_direct<P>`. That checks the fn object's arity flags at the call and calls the variadic arity's entry directly, with the rest on the stack when 29's bit allows. A redefinition, `with-redefs` or binding to anything else takes `call_fast`.
- A call to a var holding a multimethod emits `call_multi_direct`. It computes a key or constant dispatch inline (`defmulti` reads `(fn [x] lit)`, `(fn [x] (:k x))` and `(fn [x] (get x :k))` from the dispatch form, as well as a keyword), reads the method cache under the hierarchy compare, and calls the method through `call_fast`.
- The cache has two entries, so two alternating values both hit.
- `-Ono-direct-call` turns both call-site paths off.

What remains for an arbitrary dispatch fn is its own call. Getting two methods under 2x a fn call would need the dispatch fn's body inlined at the call site.
