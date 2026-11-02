# A keyword's hash is recomputed from its name on every hash-map lookup

Status: @@STATUS:14-keyword-hash@@

Host: jank 0.1-noble (`jank compiler jank-0.1-alpha`, binary `x86_64-unknown-linux-gnu-cf627abb…`), Ubuntu noble, 4 CPUs. Source citations are to jank-lang/jank at `f3d82d65` (2026-11-01); the installed runtime headers and `core.jank` are byte-identical to that commit (`diff -q`), the compiler's `.cpp` files are not shipped.

## Repro

Repro: `dev/research/upstream/14-keyword-hash.jank` in johnmn3/cohost.cljc (branch `r/research-jank`), run as `bash dev/research/upstream/run.sh 14-keyword-hash` (prepends a 3-run median timer, then `jank -O3 run`).

```
@@OUTPUT:14-keyword-hash@@
```

## Expected

`get` with a short keyword and with a long keyword cost the same; `hash` of either is a field read.

## Actual

@@ACTUAL:14-keyword-hash@@

## Where it is in the source

`keyword::to_hash` is `sym->to_hash() + 0x9e3779b9` (`obj/keyword.cpp:45`) and `symbol::to_hash` is `hash::combine(hash::string(name), hash::string(ns))` (`obj/symbol.cpp:152`), a per-character loop (`hash.cpp:129`), on every call; `jtl::immutable_string` already caches its hash (`jtl/immutable_string.hpp:648`) but `hash::string` does not use it. Persistent hash maps are `immer::map` keyed by `std::hash<object_ref>` -> `to_hash()` (`runtime/detail/type.hpp:53`), so every `get`/`assoc` on a map past the array-map size (8 pairs) hashes the keyword's text. Array maps compare keywords by identity and do not pay it (`native_array_map.hpp:55`).

## What would fix it

Cache the hash in `keyword`/`symbol` at intern time (keywords are interned, `context.cpp:588`), or route `hash::string` through the string's cached hash.
