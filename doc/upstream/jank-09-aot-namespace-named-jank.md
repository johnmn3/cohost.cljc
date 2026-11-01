# A namespace whose name ends in `jank` fails AOT: `jank::runtime` resolves inward

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`).

## Repro

`/tmp/upstream/repro/jank.jank`:

```clojure
(ns repro.jank)
(defn f [] 1)
```

```
jank --module-path /tmp/upstream compile-module repro.jank -O0 --target-dir /tmp/upstream/aot-repro
```

The process exited 0. Clang's stderr begins:

```
/tmp/jank-compile-304EQH:2:1: error: no member named 'runtime' in namespace 'repro::jank'; did you mean '::jank::runtime'?
    2 | jank::runtime::obj::nil_ref const_11{ _jank_null{ } };
      | ^~~~~~~~~~~~~
      | ::jank::runtime
```

and ends:

```
fatal error: too many errors emitted, stopping now [-ferror-limit=]
20 errors generated.
```

No object file for `repro.jank` was produced. The same diagnostic, `no member named 'runtime' in namespace 'cohost::hosts::jank'`, is what `jank --module-path src compile-module cohost.run -O3` prints for `src/cohost/hosts/jank.jank` (recorded on the AOT stage-1 run; the minimal file above is the repro for this draft).

## Expected

`(ns repro.jank)` compiles. Generated references to the runtime are `::jank::runtime`, so they are not captured by the namespace `repro::jank` that the module itself generates.

## Actual

The generated C++ is inside `namespace repro::jank`, and an unqualified `jank::runtime` looks up `jank` as that namespace. Clang offers `::jank::runtime`. `compile-module` still exits 0.

## Cohost today

This tree does not AOT its namespaces, so `cohost.hosts.jank` is only ever evaluated as source. The stage-1 probe loads that one namespace from source and the others from objects.
