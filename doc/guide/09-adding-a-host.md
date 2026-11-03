# Adding a host

*2026-11-02.*

Write one namespace that builds the capability map and calls
`cohost.adapter/install!`. `cohost.adapter/contract` lists every capability
with its arglist and doc; `defaults` the generic implementations; `required`
the ones with no default. `install!` refuses a map that lacks a required
capability or names one the contract does not (a misspelled key would
otherwise leave the generic default silently in place). Among the
capabilities a new host usually wants beyond the required ones:
`:user-type?` and `:invoke-member` (so `(.f x)` reaches a deftype field or a
reify method), `:interop-passthrough?` (true when the host has a java.*
surface of its own) or else `:native-method` (a method the shim lacks
reaches the object's own at run time), `:type-method-style` (`:python` for
basilisp-like hosts, `:protocols` where a JVM interface method is one of the
host's protocol methods, as on ClojureScript), `:new-exception` (the shim's
exceptions as the host's own, so they print as the JVM's), the `:iterator`
family (the host's own java.util.Iterator) and `:java-statics` (the host's
exact `Character` predicates). A host whose `format` is not the JVM's leaves
`:format` out: the shim's java.util.Formatter serves it.
`hosts/jolt.clj` (with `hosts/java.clj`) is the
shortest complete example; `hosts/cljs.cljs` the most involved (no macro
vars, no run-time namespace reflection, no run-time `extend`: the adapter
supplies all three from the analyzer state).

cohost's own source reads on every host. A new host's reader feature goes in
`dev/lint_reader_order.clj`'s list, and `bb lint:reader-order` checks every
`#?` in `src`. The host's catch-all, the symbol whose `catch` catches
everything, is written once, in `cohost.adapter/try-all`; cohost's own code
catches only through it. (The adapter's `:catch-all` is the same fact for the
code cohost evaluates.)

## Natives: the host's own fns, one at a time

The stock definitions are the reference. A host can still opt into its own
implementation of a stock fn, one name at a time, through `:natives`, a map
from qualified name to implementation:

```clojure
:natives {'clojure.core/frequencies frequencies   ; the host's own fn
          'clojure.core/group-by :host            ; the host core's var of that name
          'clojure.string/join clojure.string/join}
```

- **What happens:** `cohost.core`, or the stock library's namespace, gets the native
  instead of evaluating core.clj's definition. A name without an entry boots the stock
  one, so a host starts with `{}` and adds entries as they prove out.
- **What it costs:** a native has the host's semantics, not core.clj's. So every entry is
  gated by the battery, the jolt corpus where it applies, and the bench, which checks
  each stock answer against JVM Clojure's.
- **What you see:** the boot report counts natives, and their vars carry
  `:cohost/native`.
- **Chunked seqs:** `:chunks` is the same idea for these. A host that gives its chunk
  fns has the stock chunked branches (map, filter, concat, doseq, for) build and walk
  its own 32-element chunks.

`doc/perf/HANDOFF.md` says which natives each host has today and where the
remaining time goes.
