# Eve-in-cohost — MEMORY-MODEL PLAN (skeleton, full text follows)

Status: SKELETON banked first per signal discipline; the full plan
(unit scopes, gates, sequence, mapping table, open questions) lands
in the next commit on this branch.

## The work in one paragraph

Units 1–2 put Eve's thread layer behind cohost's Java shim. This
plan plots the second half of the same effort: Eve's memory model
behind the shim's data-structure surface — Eve's atoms and
persistent collections in the seat the Java implementations occupy
(the closure seam, John's ruling 2026-11-05 ~09:20 ET), so cohosted
programs gain Eve's atoms (chosen mem/disk/net kinds, shared across
instances and hosts) and Eve-backed collections without library
code learning any new surface.

## Unit list (one line each; full scope/gate/deps in the full text)

- Unit 3 — Seam identity: equality + hasheq mapping for Eve-backed
  values (semantic, never numeric). JVM seat. No slab dependency.
- Unit 4 — Eve-backed persistent collections behind the shim
  (vector, hash map, hash set, list; transients). JVM seat.
- Unit 5 — Atom behind the seam onto Eve atoms (deref/swap/CAS,
  watches, validators); Ref and Var plotted with their distances.
- Unit 6 — Slab residence on the jank seat (rides the Eve-on-jank
  region + slab + typed-view flagship gate).
- Unit 7 — Tiered atoms: mem → disk → net kinds, developer-chosen;
  two cohost instances sharing one atom.
- Unit 8 — The shared-heap illusion on heap-less hosts (cljs/worker
  shape): the probe's cljs row flipping is the executable test.

Standing gate for every unit: JVM battery 221/221 + libraries 62/62
and jank battery 219/221 (standing pair only) never regress.
