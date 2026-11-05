# jank: on the JIT eval path, a local of a member-typedef type is materialized with its template arguments dropped

Found by: cohost.cljc lane M, 2026-11-03/04, while building the C++ passthrough.
Environment: jank 0.1 (the .deb install, reports `jank` on Ubuntu x86_64), Linux.

## Summary

Compile the same form two ways:

```clojure
(ns demo (:include "vector"))
(let [v (cpp/new (cpp/std.vector cpp/int64_t))
      n (cpp/.size v)]
  n)
```

* As a **file/module** (`jank run file.jank`, `jank compile-module`): works.
* Through the **JIT eval entry** (form-at-a-time evaluation in a live
  process, e.g. cohost's `eval-str`, which is how a REPL drives jank):
  fails with

```
nil:1:6: error: no type named 'vector' in namespace 'std'; did you mean 'hecto'?
    1 | std::vector::size_type v3{ };
```

## The codegen diff (JANK_PRINT_CODEGEN=1)

The working paths declare the local with a deduced type:

```cpp
auto &&v3(v2->size());
```

The eval path materializes the local through a separate declaration
fragment whose type is spelled out — with the template arguments dropped:

```cpp
std::vector::size_type v3{ };
```

`std::vector::size_type` (for `std::vector<long>::size_type`) cannot
resolve no matter what headers are visible: the name itself is wrong.
This looks like the type printer used on the eval/materialization path
prints a member typedef's enclosing template without its arguments.

## Notes

* Analysis is not the problem: the constructor form
  `(cpp/new (cpp/std.vector cpp/int64_t))` resolves and runs on the eval
  path after the include (it errors only on returning a raw pointer).
  Only locals whose type is a member typedef of a template specialization
  hit the fragment.
* Workaround in user code: cast the value so the local's type is a plain
  type — `(cpp/cast cpp/long (cpp/.size v))` evaluates fine on the eval
  path. A `^cpp/long` hint on the binding does not change the fragment.
* The fragment is parsed as its own interpreter input
  ("In file included from <<< inputs >>>:1" precedes the error in the
  full output), not as part of the form's generated TU.
