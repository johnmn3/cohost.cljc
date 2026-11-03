# A call through a var pays a virtual var deref and a virtual call; variadic and multimethod calls cost far more

Status: REPRODUCED; patch `upstream/jank/patches/24-direct-calls.patch` (partial: var calls meet their bar, variadic and multimethod calls do not)

Host: jank built from source at jank-lang/jank `f3d82d65` with `dev/install/jank-src.sh`, Ubuntu noble, 4 CPUs shared with other lanes running unserialized (load average about 7). Three binaries: `jank-dev` (no patches), `jank-u2-s` (the series 11, 13, 14, 12-21, 18, 19, 22), `jank-u2-24` (the series + 24), each patched binary with its own `XDG_CACHE_HOME`.

## Repro

Repro: `dev/research/upstream/24-direct-calls.jank` in johnmn3/cohost.cljc (branch `max/u2`), run as `bash dev/research/upstream/run.sh 24-direct-calls` (prepends a 3-run median timer, then `jank -O3 run`). P2's shapes at 1e7 calls: a loop alone, a `cpp/raw` inline fn, a var call in the same and in another namespace, a call through a local, a 2-arity call, a `[x & ys]` call, a one-method multimethod; then redefinition (`defn` twice, `with-redefs` around a loop, `alter-var-root`), `binding` of a dynamic var, and a multi-arity fn.

Two interleaved rounds (base, series, series+24), ns per 1e7:

```
row                  base r1     series r1   +24 r1      base r2     series r2   +24 r2
loop-only            40788169    53698544    8701358     50449577    42226723    14783802
call-cpp-objref      59355126    65205604    60895937    112266378   76192964    64441081
call-var-same-ns     123104744   128004520   77471113    207893015   159003236   83870056
call-var-other-ns    189895759   174463453   83384739    146384409   158042344   58407558
call-local           98626232    94372333    55195690    127156355   127884214   97717672
call-2               205380253   144199655   103898251   137776872   147286611   80789809
call-variadic        1417361026  940510806   746169302   1870083086  873298497   885648212
call-multi           715470542   589594008   688517942   840294535   787360180   628741626
```

Every binary prints `redefinition 1 2 3 2 [:a :b]`, `binding :bound :root` and `arities [:one 1] [:two 1 2] [:many 1 2 (3)] 1 1`, the values Clojure prints.

## Expected

A call through a non-dynamic var costs about what an inline C++ fn costs (the JVM's direct linking, or a call to a final method); a `[x & ys]` call costs a 2-arity call plus one small allocation; a one-method multimethod call about a fn call plus its dispatch fn.

## Actual

Before the patch (series) a var call is 2.0-2.1x `call-cpp-objref` (128004520 / 65205604), after it 1.27-1.30x (77471113 / 60895937, 83870056 / 64441081) and 0.91-1.37x across namespaces. Most of that is the var deref, not the call: the loop alone, whose only var is `n`, falls from 42-54 ms to 9-15 ms. With the patch at `-Ono-direct-call` the var rows stay where they are (80868763, 71804748): the inline var deref carries the gain, the inline arity-pointer call adds little. The variadic call stays 7-11x a 2-arity call (746169302 / 103898251, 885648212 / 80789809): one allocation instead of two for the rest args moved it 940510806 -> 746169302 in round 1 and not at all in round 2. The multimethod call is 7.5-8.9x a fn call (628741626 / 83870056).

## Where it is in the source

Compiled code derefs a var with `var->deref()` (`codegen/cpp_processor.cpp`, `gen(var_deref)`): a virtual call into `var::deref` (`runtime/var.cpp`), which calls `get_thread_binding` before it reads the root. A call is `f.call(args)` (`gen(dynamic_call)`): two tag tests on `f`, a virtual call to `jit_function::call`, a null test of the arity pointer, and the indirect call. A variadic call goes through `jit_variadic_function::call` (`runtime/obj/jit_variadic_function.cpp`), which allocates the rest args (`make_box<native_array_sequence>`: the sequence and its array, two GC allocations) on every call. A multimethod call is three dynamic calls (the multimethod, its dispatch fn, the method) plus the method lookup.

## What would fix it

The patch: compiled var derefs call an inline `var::deref_inline()` (the root load when the var was never thread-bound); calls compile to `jank::runtime::call_fast(f, args...)`, which calls a `jit_function`'s fixed arity through its arity pointer inline and falls back to `object_ref::call` for everything else, reading the fn object at every call so redefinition, `with-redefs` and `binding` need no flag (the default at `-O3`, `-Ono-direct-call` turns it off); the multimethod calls its dispatch fn and method through `call_fast`; rest args are one allocation. What remains: a variadic call needs no allocation when the rest args do not escape (escape analysis, or a fixed-arity entry the analyzer emits when the body only reads `(first ys)` / `(seq ys)`); a multimethod call needs its dispatch fn inlined when it is a keyword or a constant fn, so the call is one dynamic call, not three.
