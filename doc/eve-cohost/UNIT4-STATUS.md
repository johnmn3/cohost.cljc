# Eve-in-cohost, unit 4 — STATUS: Eve-backed collections behind the shim

Branch `muse/eve-cohost-impl-u4`, off `muse/eve-cohost-impl` @
89ad378 (target audit tip), with unit 3 merged in (its seam
identity contract is unit 4's dependency).

## Design (settled by the plan + rulings; not re-litigated)

- Creators dispatch to the value layer when the fill is in force:
  `PersistentVector-create`, `LazilyPersistentVector-create` /
  `-createOwning`, `PersistentHashMap-create` / `-createWithCheck`,
  `PersistentArrayMap-create` / `-createAsIfByAssoc`,
  `PersistentHashSet-create` / `-createWithCheck`,
  `PersistentList-creator`. ArrayMap is answered by Eve map
  (plan §3). Sorted collections are OUT (Eve-side NOT-YET).
  Queue is the plan's tail — NOT-YET, not in this unit's fill.
- JVM seat: the layer's values are bare Eve values (Eve's JVM
  deftypes implement the clojure.lang contracts — unit 3 measured
  bare Eve values satisfying host `=`/`hash` against their twins),
  so the host's own dispatch serves conj/assoc/nth/get/count/seq.
  The seam contract (unit 3) is what makes the strata one world.
- Hosting is registered, never hard-required: `cohost.memory`
  (CLJC) holds the fill state + constructor registry;
  `cohost.memory-eve` (JVM-only .clj) registers Eve constructors.
  A seat with no Eve hosting cannot take the fill (NOT-YET, named
  at boot) — no substitute realization (§9).
- Guarded transients (plan §3): a layer transient whose state
  lives in an atom (the scope's local atom); mutation swaps the
  Eve root in that atom; `persistent!` seals; use after seal
  throws; use outside the creating scope throws. Served through
  the shim's transient method vars (-asTransient, -conj, -assoc,
  -without, -disjoin, -pop, -persistent, -count, lookups).
- Fill forcing: boot opt (the `:host-lacks` pattern). Per Q1 the
  JVM seat takes the fill as its DEFAULT once this unit's gate
  passes on it; other seats wait for their Eve hosting.

## Milestones

- [x] M1 — branch + unit 3 merged; unit 3 probe re-run on the
  merged tree: **111/111 PASS** (2026-11-05).
- [ ] M2 — layer: fill state, constructor registry, Eve
  constructors (vector/map/set/list + empties), guarded
  transients in `cohost.memory` / `cohost.memory-eve`.
- [ ] M3 — shim: creator dispatch under fill; EMPTY statics
  rebound at fill install; transient method-var branches;
  class-of answers for Eve values.
- [ ] M4 — boot: `:eve-collections` opt + fill install;
  JVM default-on if the gate passes.
- [ ] M5 — layer probe (probe_collections.clj): creators, ops,
  transients (post-seal + cross-scope throws), meta, seam
  contract through the booted core, fill forced.
- [ ] M6 — JVM gate, fill forced: battery 221/221, libraries
  62/62, boot errors 0; corpus vs host-backed run, no new
  failures; same-run H vs E timings, interleaved.
- [ ] M7 — jank gate on J: `bb boot:jank` AOT-compiles the new
  shim/layer code; battery 219/221 (standing pair only).
- [ ] M8 — standing checks (cli:check 7/7 SAME, debug-seam JVM
  where touched) + UNIT4-REPORT.md.

## Coordination

- Sibling lane (unit 5) runs in parallel on atom/var surfaces.
  This lane owns the COLLECTION creators in the shim; atom/var
  files are not touched. Any unavoidable touch gets flagged
  here first.
