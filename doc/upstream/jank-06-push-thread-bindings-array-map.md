# `push-thread-bindings` refuses an array map

Status: REPRODUCED

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`).

## Repro

`/tmp/upstream/06b-bindings.jank` defines `(def ^:dynamic *p* 1)` and calls `push-thread-bindings` on `(array-map #'*p* 4)`. `(type (array-map #'*p* 4))` printed `persistent_array_map` and `(type (hash-map #'*p* 4))` printed `persistent_hash_map` before the call aborted.

```
jank run /tmp/upstream/06b-bindings.jank
```

exit 1. stderr:

```
error: invalid thread binding map (must be hash map): {#'user/*p* 4}
```

The stack's first frames are `jank::error::throw_result_failure`, `jtl::detail::panic`, `jank::runtime::push_thread_bindings`.

The same binding as a hash map, `/tmp/upstream/06c.jank`:

```clojure
(def ^:dynamic *p* 1)
(push-thread-bindings (hash-map #'*p* 9))
(println "hash-bound" *p*)
(pop-thread-bindings)
(println "after-pop" *p*)
```

```
jank run /tmp/upstream/06c.jank
```

stdout (exit 0):

```
hash-bound 9
after-pop 1
```

## Expected

An array map of Var/value pairs is a valid binding map. `(push-thread-bindings (array-map #'*p* 4))` binds `*p*` to 4, as the hash-map call binds it to 9.

## Actual

The array map panics: `invalid thread binding map (must be hash map)`. The hash map binds and pops.

## Cohost today

The jank adapter's `:push-thread-bindings!` copies every entry through `(apply hash-map (mapcat identity m))` before calling `push-thread-bindings`.
