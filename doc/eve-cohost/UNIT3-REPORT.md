# Eve-in-cohost, unit 3 — REPORT: seam identity

Branch `muse/eve-cohost-impl-u3`, off `muse/eve-cohost-impl` @
29201de (the memory plan). Implementation @ e93971d. Ran parallel to
unit 2 (seat leaves); no shared files touched.

## What was built

The `=` / `hasheq` contract for Eve-backed values behind the closure
seam (MEMORY-PLAN.md, unit 3), in the unit-1 shape: a portable layer,
shim dispatch branches, a seat hosting file.

- **`src/cohost/memory.cljc`** — the value layer. An Eve-backed value
  is a stand-in: a plain map tagged `:cohost/kind ::root`, carrying
  `:memory/kind` (:vector/:map/:set/:list/:value), the Eve
  `:memory/root`, and the value's canonical Clojure-level form under
  `:memory/form`, computed once at wrap time (Eve values are
  immutable, so the cached form is sound). `equiv` = canonical forms
  equal as host values; `hasheq` = the host hash of the canonical
  form — the Murmur-family collection hash over the Clojure-level
  form, exactly the Zone-H twin's answer. **Eve's SHA-256 Merkle
  digest is never read, let alone returned as a hash answer** — the
  layer holds no digest and computes nothing from one; the mapping
  is semantic only, per John's ruling. This retires unit 1's
  tagged-map distance for Eve-backed values (equality was map
  equality of the stand-in: a stand-in equalled nothing but a
  byte-identical stand-in, and hashed as a map).
- **`src/cohost/memory_eve.clj`** — JVM-seat hosting (a `.clj` file,
  required by nothing in the shim/boot graph, so the jank and CLJS
  builds never load it): real Eve roots in memory — Eve persistent
  vector/map/set/list over one in-memory heap slab context
  (`alloc/make-jvm-heap-slab-ctx`, mem tier, no files), an Eve heap
  atom, and `atom-root-snapshot` (the atom's current root re-hosted
  in Eve and wrapped — a snapshot; later swaps do not move it, per
  the per-atom snapshot model). `canonicalize` walks an Eve root to
  its host form recursively, nested Eve values included.
- **Shim dispatch** (`src/cohost/shim.cljc`): `Util-equiv` and
  `-equiv` dispatch to the layer when either side is an Eve-backed
  stand-in; `Util-hasheq` (and with it `-hasheq`) likewise;
  `-hashCode` inherits through `Util-hasheq` on seats without a
  host static, and on the JVM/jolt seats
  **`src/cohost/hosts/java.clj`'s `Object/hashCode`** answers from
  the canonical form for stand-ins (the stand-in map's own hashCode
  is the wrong value's answer). Every non-Eve value takes the
  previous path unchanged — the probe asserts this explicitly.

## Measured starting state (why the stand-in was the problem)

Bare Eve values on the JVM already satisfy host `=` / `hash`
against their twins (EveVector hasheq 736442005 = the host
vector's) — Eve's deftypes implement the contracts. What failed was
the seam's own representation: the stand-in compared and hashed as
a map. The contract therefore had to live in the seam's dispatch,
not in the values.

## Numbers, as printed

| Gate | Result |
|---|---|
| Seam probe (`probe_memory.clj`, through the shim's own dispatch vars) | **111/111 PASS** |
| JVM battery (`bb boot:jvm`) | **221/221**, libraries **62/62**, boot errors **0** |
| Thread-layer probe (`probe_thread.clj`) | 29/29 PASS (unregressed) |
| Booted-context check (`eve_cohost_check.clj`) | 21/21 PASS (unregressed) |
| Jank gate on J (`bb boot:jank-snapshot` + `bb boot:jank`, AOT compile of the new shim code) | snapshot built (placement 857/872); battery **219/221**, libraries **62/62**, boot errors **0** — the 2 FAIL rows are the standing named pair verbatim (`subs count nth first last butlast`, `Character outside ASCII`) |

Probe coverage: vector, map, set, list, and atom-deref values
(collection and scalar roots, before and after a swap) — H vs E and
E vs H through both equiv paths, E vs E between separately built
stand-ins, hasheq equal wherever `=` holds (both hash paths and
hashCode), sequential cross-kind cases (E vector = H list twin,
with equal ordered-seq hasheq), nested Eve-in-Eve values, empty
collections, and lookup in collections keyed either way.

## Named remainders / labeled distances

- **Lookup is proven at the dispatch layer.** The probe's lookup
  cases place keys by `Util-hasheq` + `Util-equiv` — the two answers
  stock's hash collections are built from, and the answers unit 4's
  Eve-backed collections will place keys by. On the JVM seat a host
  `PersistentHashMap`'s internals are the host's own and bypass the
  shim, so a host map keyed by a twin does not find a stand-in key
  there; that is the same family as plan D2, and it closes when
  unit 4 puts the map itself behind the seam.
- **Nested stand-ins inside Zone-H collections** compare by the
  host collection's own machinery, not this contract (same
  boundary as above); values themselves, at the dispatch points,
  are covered.
- `identical?` never crosses (plan D2, kept and probe-asserted).
- The `:lg` branches of `Util-equiv`/`Util-hasheq` keep their
  host-identity answers; no Eve-backed values exist on that seat
  yet — the branch belongs to whichever unit first hosts them
  there.
- Framing note: John's 2026-11-05 ~09:45 ET ruling (the heap
  Clojure sees is itself an Eve atom) supersedes the plan's
  two-zone picture; this unit's contract stands as the foundation
  that single world requires — the `=` / `hasheq` answers every
  representation behind the seam must meet — and nothing built
  here depends on the zone framing.

## Addendum — the ruling's re-read (banked in UNIT3-STATUS.md)

Under the ruling, this report's Zone H / Zone E language reads as
implementation strata of the same classes within one Eve-backed
heap, and the probe's H vs E / E vs H / E vs E cases read as
single-world invariants across those strata. The parent lane
assessed the ruling against every concrete choice in the unit
(stand-in shape, canonical-form caching, dispatch points, the
`Object/hashCode` branch, probe construction): **no concrete
choice changes** — the contract was defined on canonical forms,
representation-agnostic from the start, and each choice is a
statement about the seam's classes, not about two worlds. The
layer docstring and probe header carry the reworded framing; this
report stands as the record of the gate as run.
