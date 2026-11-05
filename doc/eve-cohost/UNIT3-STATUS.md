# Eve-in-cohost, unit 3: seam identity — STATUS

Branch `muse/eve-cohost-impl-u3`, off `muse/eve-cohost-impl` @ 29201de
(the memory plan). Parallel to unit 2 (`muse/eve-cohost-impl-u2`, seat
leaves) — this branch does not touch it.

## Scope (MEMORY-PLAN.md, unit 3)

The `=` / `hasheq` contract for Eve-backed values behind the closure
seam: hasheq computed from the canonical Clojure-level form, so
mixed-representation values (Zone H host-native vs Zone E Eve-backed)
are equal and hash-consistent across the seam, in both directions.
Eve's SHA-256 Merkle digest never crosses the seam as a hash answer.
Gate: the mixed-representation probe + standing batteries unregressed
(JVM 221/221, libraries 62/62; jank AOT compile of new shim code).

## Measured starting state (2026-11-05, this lane, JVM seat)

- Eve's CLJC collections + heap atom load and run in plain JVM Clojure
  over an in-memory heap slab (`alloc/make-jvm-heap-slab-ctx`); no
  files, mem tier only.
- Bare Eve values already satisfy host `=` / `hash` against their
  Zone-H twins (EveVector hasheq 736442005 = host vector's). The seam
  problem is the **stand-in**: the tagged map that holds the Eve root
  behind the shim class compares as a map — `(= standin twin)` is
  false and `(hash standin)` is the map's hash today.
- An Eve heap atom's `deref` materializes host values on this seat;
  the atom case in the gate is therefore the root *snapshot* stand-in.
- Shim dispatch points stock core actually calls: `Util-equiv`,
  `Util-hasheq`, `-equiv`, `-hasheq`, `-hashCode` (via Util-hasheq) in
  `src/cohost/shim.cljc` — all currently delegate to host `=` / `hash`
  with no Eve-backed branch.

## Design (as planned; build notes follow as built)

- `src/cohost/memory.cljc` — the value layer (the `cohost.thread`
  pattern): stand-ins are maps tagged `:cohost/kind ::root`, carrying
  `:memory/kind`, the Eve `:memory/root`, and the canonical
  Clojure-level `:memory/form` computed once at wrap time (Eve values
  are immutable, so the cached form is sound). `equiv` / `hasheq` are
  defined on canonical forms only; the digest is never consulted.
- `src/cohost/memory_eve.clj` — JVM-seat hosting (never in the jank /
  cljs require graph): builds real Eve roots in-memory (vec / map /
  set / list over a heap slab ctx; an Eve heap atom and its root
  snapshot) and wraps them via the layer.
- Shim: `Util-equiv` / `Util-hasheq` / `-equiv` gain an Eve-backed
  branch dispatching to the layer; `-hashCode` inherits it through
  `Util-hasheq`. Non-Eve values take the old path unchanged.
- Gate probe: `probe_memory.clj` (host-side, `probe_thread.clj`
  shape) exercising the shim's own dispatch vars over mixed
  representations — vector, map, set, list, atom-root snapshot;
  H vs E, E vs H, E vs E; symmetry, hasheq-where-=, and lookup in
  collections keyed either way at the dispatch layer.

## State

- [x] Plan read; branch + worktree up; feasibility measured (above).
- [x] Layer (`cohost.memory`) built — @ e93971d.
- [x] JVM Eve hosting (`cohost.memory-eve`) built — @ e93971d.
- [x] Shim dispatch branches wired (`Util-equiv` / `Util-hasheq` /
  `-equiv`; `Object/hashCode` answers from the canonical form in
  `cohost.hosts.java`) — @ e93971d.
- [x] Gate probe `probe_memory.clj`: **111/111 PASS** (vector, map,
  set, list, atom-root snapshots incl. scalar; H vs E, E vs H, E vs E;
  nested; empties; dispatch-layer lookup keyed either way).
- [x] JVM battery unregressed: **221/221, libraries 62/62, boot
  errors 0** (`bb boot:jvm`); thread probe 29/29; booted-context
  check 21/21.
- [x] Jank AOT gate on J (`bb boot:jank-snapshot` + `bb boot:jank`,
  ~/cohost-eve-u3 on J): snapshot built; battery **219/221**
  (standing pair only), libraries 62/62, boot errors 0.
- [x] Report filed (UNIT3-REPORT.md) + declaration.

## Final

Unit 3 complete. Probe 111/111; JVM battery 221/221 + libraries
62/62, boot errors 0; jank battery 219/221 + libraries 62/62, boot
errors 0. Full account: UNIT3-REPORT.md.

Note (2026-11-05 ~09:45 ET, John's ruling, relayed via the parent):
the heap Clojure sees is to BE an Eve atom — the two-zone model is
superseded; this unit's contract stands as the foundation of that
single world (the = / hasheq answers any representation must meet),
not a bridge between worlds. The deliverable is unchanged.
