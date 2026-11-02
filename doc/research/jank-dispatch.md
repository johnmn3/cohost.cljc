# Dispatch on jank: protocol inline caches, record fields by index, a multimethod cache (X2-1)

Plan item 7 of `doc/hosts/jank-MAX.md`; the research is `doc/research/jank-faster-than-jvm.md` S3-3 and
its probe P11 (`dev/research/p11_protocol.clj`). Numbers are in the X2-1 commits and
`coord/cohost-max/reports/u3.md`.

## What a protocol method was

`defprotocol` (jank's `cohost.hosts.jank-host/defprotocol-macro`) defined each method as
`(fn [& args] (protocol-dispatch P 'm args))`: a variadic entry (jank packs the args into a seq),
`protocol-impl` (the impls atom deref, `type-key`, two or three nested `get-in`s into the
descriptor, the impls by name, by class, by supers, by Object) and an `apply` of the impl, on
every call. P11 at -O3: 1.16 us per call against the JVM's 9 ns.

## The shape emitted now

```clojure
(def area
  (let [cache (cohost.hosts.jank-host/method-cache)]          ; a volatile, one per method
    (fn ([p0] (let [impl (cohost.hosts.jank-host/cached-impl cache p0)]
                (if impl
                  (impl p0)
                  ((cohost.hosts.jank-host/miss-impl cache Area 'area p0) p0))))
        ([p0 p1] ...))))                                        ; one arity per declared arglist
```

- `cached-impl` is one C++ call (`cohost_obj::cached_impl`): when `p0` is a cohost instance it reads
  the cache's `[descriptor impl generation]`, compares the descriptor with `p0`'s (identity) and the
  generation with the global protocol generation, and returns the impl, else nil.
- `miss-impl` is today's `protocol-impl`; when `p0` is a cohost instance it refills the cache with
  the generation read before the lookup. A non-cohost value (a protocol extended to Long, String,
  nil) always takes this path: it still saves the variadic entry and the `apply`.
- `register-impl!` (behind `extend`, `extend-type`, `extend-protocol`) bumps the generation, so
  every cache misses once after any extend. A redefined `defrecord`/`deftype` has a new descriptor,
  so it misses by identity. `satisfies?`, `extends?`, the Object/nil fallbacks and the supers order
  stay in `protocol-impl`, unchanged.
- The cache is reached through `cohost.hosts.jank-host` vars, not `cpp/` forms in the emitted code:
  the adapter's `cpp/raw` namespace is not visible from JIT-compiled user code (P12).
- A protocol with no arglists, or a variadic one, keeps the old variadic method.

## Record fields

A method body bound every field with `(inst-field this i)` on every call. Now only the fields whose
symbol occurs in that arity's body are bound (a symbol scan of the body: a superset of the uses),
and `cohost_obj::field` reads the fields vector's immer data by index instead of a virtual `nth`
with a boxed index.

## The stock multimethod

The shim's `MultiFn` (`cohost.shim/new-MultiFn`) looked its method up with `mf-method`: the state
atom's deref, the hierarchy's deref, three identity checks over map lookups and a find in the
per-dispatch-value table, and a `swap!` of the state on every new dispatch value. Behind the new
adapter capability `:multimethod-cache?` (default nil; true in the jank adapter only), it uses
`mf-fast-method`: one entry `[state-value hierarchy-value {dv method}]` in an atom of its own,
valid while the state value and the hierarchy value are the very ones it was filled under
(`defmethod`, `remove-method`, `prefer-method`, `remove-all-methods` make a new state value;
`derive`/`underive` a new hierarchy value). A hit is two identity checks and one `get`; a new
dispatch value adds to the map without touching the state atom. A miss (no method) is not cached,
as the JVM's `findAndCacheBestMethod`. With the capability off the fn is the old one, byte for
byte, so every other host is unchanged until lane X3 measures it there.

## Battery rows (`src/cohost/battery.cljc`)

- protocol: extend-type after a call reaches the type (the generation)
- protocol: a redefined record dispatches its new method (descriptor identity; two arities)
- multimethod: defmethod after a call is seen
- multimethod: derive after a call is seen

Each checked on JVM Clojure 1.12 (`clojure -M`, the battery strings through `eval`).

## Files

`src/cohost/hosts/jank_host.jank` (the C++ cache and field accessor, `method-cache`, `cached-impl`,
`miss-impl`, `method-dispatch-form`, `method-fns-form`, `register-impl!`, `:multimethod-cache? true`),
`src/cohost/shim.cljc` (`mf-fast-method`, `new-MultiFn`; shared layer), `src/cohost/adapter.cljc`
(the capability; shared layer), `src/cohost/battery.cljc`, `dev/research/p11_protocol.clj` (four
more cases: a two-argument method, a five-field record, a protocol on Long, the three-way defmulti).
