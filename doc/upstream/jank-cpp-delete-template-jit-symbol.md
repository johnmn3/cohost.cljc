# jank: cpp/delete of a template type fails JIT symbol lookup (deallocate not found)

Found by: cohost.cljc lane M, 2026-11-03/04, while building the C++ passthrough.
Environment: jank 0.1 (.deb install), Linux x86_64.

## Repro (plain jank, one file)

```clojure
(ns pj2 (:include "vector"))
(let [v (cpp/new (cpp/std.vector cpp/int64_t))
      n (cpp/.size v)]
  (cpp/delete v)
  (println n))
```

`jank run pj2.jank` (and `jank --module-path <dir> compile-module pj2`,
which also starts a JIT session) fails:

```
JIT session error: Symbols not found: [ _ZNSt15__new_allocatorIlE10deallocateEPlm ]
error: Failed to find symbol: 'pj2$let_3_0'
```

`_ZNSt15__new_allocatorIlE10deallocateEPlm` is
`std::__new_allocator<long>::deallocate(long*, unsigned long)` — the
implicit instantiation the delete expression needs is not emitted into
the JIT session for the linker to find.

## Notes

* Removing the `(cpp/delete v)` makes the same file run and print 0 on
  both `jank run` and `compile-module`.
* The constructor and member calls on the same object work; only the
  delete's allocator instantiation is missing.
* Through cohost's JIT eval the same shape fails as
  "Failed to find symbol: 'bt_w1$let_3_0'".
