# Unit 6 — Slab residence on the jank seat: DESIGN (prep)

Design only. Unit 6 is hard-gated on the Eve-on-jank flagship gate
(MEMORY-PLAN.md §5); nothing here is implementation, and nothing here
estimates a number the flagship has not measured. Base: MEMORY-PLAN.md on
`muse/eve-cohost-impl` @ 0fa021f — the "Unit 6" section, §5 (sequence), and
the rulings §7–§10. Eve facts are read from github.com/SeniorCareMarket/eve
main @ d241de3 and labeled with that sha; facts about the flagship branch
are labeled **[unverified: flagship branch not read]**.

The probe scaffolding this design calls for is on the same branch:
`dev/u6/residence_probe.cljc` + `dev/u6/residence.sh` (§4).

## 1. Unit 6 re-read under the rulings

The plan's unit-6 text predates §§8–10. Read through them:

- **§8 (the heap IS an Eve atom).** Residence is not a per-collection
  property that some values acquire. On the jank seat the runtime atom
  domain — the heap a cohosted program sees — is an Eve domain whose roots
  live in Eve's slab. Unit 6 is therefore "the jank seat's runtime atom
  domain becomes slab-resident", and every value in it is resident by
  construction. Zone H/E/X is not the organizing model (§8).
- **§9 (no counterparts).** cohost builds no slab, region, or atom of its
  own on jank. Every primitive below the seam is Eve's (its jank region
  layer, its slab, its `eve.atom`). The jank seat takes the fill only when
  Eve-on-jank is real; until then it is NOT-YET. The probe obeys the same
  rule: a seat without Eve prints GATED, never a stand-in result.
- **§10 (containers are inside the heap).** The namespace table, interning
  and var roots are residents of the runtime atom domain (startup domain).
  Consequence for unit 6: "root identity stable across a snapshot rebuild"
  (the plan's gate) covers the startup domain's root — the containers —
  not only user atoms. That makes the snapshot-rebuild check a check on
  the namespace/var root's digest (§5).
- **§7 Q5 (kind is metadata; default in-mem).** A disk atom on jank is an
  atom whose metadata names the disk kind, realized by Eve's persistent
  domain (`eve.atom/atom {:persistent base}` / `persistent-atom-domain`,
  Eve main d241de3). The default runtime domain stays in-mem (slab in a
  process-local region); "residence" for the default domain means its
  roots are slab roots with Merkle identity, not that it is on disk.
- **Model law.** No journals, no liveness scans, no header counters, no
  arenas. Eve main's `persistent-atom-domain` takes a `:journal?` option
  (`eve.journal-file`, d241de3). Unit 6 opens domains with it unset (the
  default) and the design never turns it on; if a step appears to need it,
  the step stops and reports (plan §0 standing facts).

## 2. What the substrate is (measured from Eve main d241de3)

- `impls/jank/src/eve/region.jank` (118 lines): Eve's region layer on jank
  as `cpp/raw` — `open_fd`, `grow_never_truncate`, `map_shared` (MAP_SHARED),
  `msync_all`, `heap_region` (calloc), 32/64-bit seq-cst atomics (`cas_i32`,
  `cas_i64`, ...), shared futex wait/wake, fcntl range locks. A region is its
  base address as a jank i64.
- `impls/jank/src/eve/types.jank` (236 lines): five lazy value types over a
  calloc'd stand-in slab, each a C++ struct deriving `jank::runtime::object`
  with `object_type::opaque_box` (DJ-2's measured fact: jank answers
  `opaque_box` for every user struct).
- `src/eve/atom.cljc`: "one implementation for every host"; the host enters
  through `IMemRegion` (eve.mem), `ISlabIO` (eve.deftype-proto.alloc),
  `IEveRoot`/`IEveSio` (eve.deftype-proto.data) and a handful of one-line
  primitives. A domain is `.slab0`–`.slab6` + `.root` (header, 256 worker
  slots, 256 atom slots) + `.rmap`; an atom's root pointer is a
  slab-qualified i32 `[class:3|block:29]`.
- `src/eve/digest.cljc`: the root digest is SHA-256 over the root's
  canonical codec bytes, latched in a write-once side block (layout
  B-prime); `root-digest` answers nil for content held by reference.
- The flagship (region + slab + typed-view on jank, the `slab_io.jank:153`
  require fix) is what makes `eve.atom` load on jank. **[unverified:
  flagship branch not read]** — its API names, its `eve_obj` layout and its
  module path are taken at step 0 (§6) from its gate report.

## 3. Below the seam: the three pieces

### 3a. `cohost_object` ↔ `eve_obj` convergence

Today (cohost `src/cohost/hosts/jank_host.jank`, `cohost_object`): one C++
struct, tag `object_type::max + 1` (derived so a jank that adds a type does
not collide), slots `descriptor`, `methods` (vector; 0 equiv, 1 hasheq,
2 toString, 3 toCodeString, 4 invoke, 5 deref, 6 get, 7 seq, 8 compare,
9 find), `fields`, `extmap`, `meta_`; behaviors call/get/find/compare/
seqable/indexable/deref. Eve's side: one tagged object
(`eve_host::eve_obj` {tag, fields}) **[unverified: flagship branch not
read]**; Eve main's types are `opaque_box` structs.

Design: **one struct, one jank type id, two descriptor families.**

1. The converged object is cohost's struct shape (it already carries a
   dispatch table and meta); the jank type id is the one cohost derives.
   An Eve value is an instance whose `descriptor` is an Eve type
   descriptor (vector/map/set/list/atom, keyed by Eve's own type-id) and
   whose `fields` hold the root handle: (domain, root pointer
   `[class:3|block:29]`, header offset). Told apart by descriptor, as DJ-2
   ruled — never by a second C++ type.
2. The `methods` slots for an Eve descriptor point at Eve's own
   implementations (equiv = unit 3's contract with Eve's digest verdict as
   the fast path; hasheq = unit 3's semantic hash, never the digest; seq/
   get/find/count = Eve's slab readers). cohost writes no collection
   behavior for them.
3. Whichever side's struct survives, the other side's constructor becomes
   a call to it; the descriptor table is the only extension point. If the
   flagship's `eve_obj` carries layout cohost's struct lacks (e.g. an
   inline root pointer for speed), the converged struct takes that field;
   that is settled against the flagship's measured layout at step 0, not
   guessed here.

What changes for `opaque_box`: nothing in jank. The converged object keeps
cohost's derived tag; `(type x)` on it is answered by the descriptor, as
it already is for cohost records.

### 3b. Region leaves in cohost's jank adapter contract

Shape copied from unit 1's thread leaves (adapter leaves with nil
defaults, a portable layer, a boot fill, a policy opt):

| leaf | answers | realized by (jank) |
|---|---|---|
| `:region-open` | open/create a file region, never truncating | Eve `eve.region` open-mmap-region (jank impl: `open_fd` + `grow_never_truncate` + `map_shared`) |
| `:region-heap` | a process-local zeroed region | Eve `heap_region` |
| `:region-sync` | force a region to disk | Eve `msync_all` (via `ISyncableRegion`) |
| `:region-close` | unmap + close | Eve `unmap` + `close_fd` |

The leaf names are working names; the plan leaves `:region-open` /
`:region-map` shapes "to the lane to settle against Eve's region adapter
contract". Settling rule: the leaves are exactly `IMemRegion` /
`ISyncableRegion`'s constructors as Eve's jank region adapter exports
them; cohost adds no operation Eve's contract lacks. As in unit 1 the
leaves default to nil and the boot fill sets them — here, to Eve's
functions only (no-counterpart ruling: a seat whose leaf stays nil is
NOT-YET, never given a cohost implementation).

### 3c. Re-pointing units 3–5

- **Unit 3 (`cohost.memory`).** Today a value whose class Eve answers
  reaches the program as a tagged-map stand-in (`:cohost/kind ::root`,
  `:memory/root`, `:memory/form`). On jank after unit 6 the stand-in
  stratum is the converged object itself: `eve-value?` becomes a
  descriptor-family test, `:memory/form` is no longer precomputed (the
  object reads its root lazily), and the equiv/hasheq contract is unchanged
  — same answers, new representation. The JVM seat keeps its own stratum
  until its Eve realization (Q-C: NOT-YET, §9).
- **Unit 4 (collections).** The shim's jank collection creators answer
  converged objects over the runtime domain's slab.
- **Unit 5 (atom).** The shim's `Atom` on jank is an Eve atom in the
  runtime domain; `swap!` is Eve's CAS on the root pointer; a disk-kind
  atom (metadata, Q5) is an atom in a persistent domain.

## 4. The disk-resident-root probe (scaffolded on this branch)

Gate item: "a deref of a disk-resident atom root answering after a
process restart (residence is real, not cached)".

`dev/u6/residence_probe.cljc` — one `.cljc` program, two roles, read on
both seats (`#?(:jank ... :clj ...)`):

- `write <base> <tag>`: `eve.atom/atom {:id ::root :persistent base}`,
  `reset!` to a fixed nested value (vector, map, set, list, big integer,
  the run's tag), `eve.atom/sync!`, print the ROOT line, exit.
- `read <base>`: a fresh process joins the domain, derefs, prints the ROOT
  line, exits.
- ROOT line: `ROOT role=.. pid=.. digest=<8 i32 words> value=<pr-str>`; the
  digest is `eve.digest/root-digest` of the resident root, taken under
  `eve.atom/read-pinned`.
- Every Eve var is resolved at run time, so the file reads and compiles on
  a seat where Eve does not load; such a seat prints `GATED <reason>` and
  exits 3.

`dev/u6/residence.sh` — runs the writer, waits for it to exit, then runs
the reader as a separate process; four legs (writer → reader):
`jvm` (the reference; Eve main on the JVM), `jank` (the unit-6 gate item),
`jvm-jank` and `jank-jvm` (one on-disk format, two seats). PASS requires:
both exit 0, same digest, digest not nil, different pids, the reader's line
carries this run's tag, and (same seat only) the same printed value. A
GATED leg does not fail the run.

Why this proves residence and not caching: the reader is a new process
(different pid) started after the writer exited, so no in-memory state
survives; the tag is fresh per run, so a stale domain cannot pass; the
digest is Eve's content identity read from the slab root, not a value the
reader computed from something the writer printed.

What it does not yet prove (named, for the gate run): residence across a
machine reboot / page-cache drop (the reader may be served by the page
cache — that is still the file, not process memory); and the
`jvm-jank`/`jank-jvm` legs assume the flagship's jank realization reads
the same domain files as Eve's JVM adapters — if the flagship's format
differs, those two legs FAIL and that is a finding, not a probe bug.

## 5. Gate checks and how each is run (post-gate)

| gate item (plan) | run |
|---|---|
| flagship numbers (region + slab + typed-view vs node C++ ext) | consumed from the flagship gate report, not re-measured |
| `bb boot:jank` with the fill forced: battery 219/221, libraries 62/62 | `bb boot:jank` with the memory fill forced by the boot opt the unit adds (unit 1's `:host-lacks` pattern) |
| root identity stable across a snapshot rebuild | build the jank snapshot twice (`bb boot:jank-snapshot`, cleaned between), run the same program, print the startup domain's root digest (namespace/var containers, §10) and a user atom's; equal digests both times |
| disk-resident deref after restart | `dev/u6/residence.sh jank` PASS (plus the cross-seat legs) |
| batteries never regress | JVM gate re-run on the merged tree, numbers as printed |

## 6. Steps after the gate (none started)

0. Read the flagship gate report: API names, `eve_obj` layout, module
   path, numbers. Set `EVE_JANK_PATH` for the probe; run
   `dev/u6/residence.sh` — the jank legs must leave GATED.
1. Region leaves in the jank adapter contract (§3b), defaults = Eve's.
2. Struct convergence (§3a) behind the existing `cohost_object` API
   (`is_cohost`, `descriptor_of`, `fields_of`), no caller changes.
3. Re-point units 3–5 on jank (§3c); runtime domain on Eve's slab.
4. Gate run (§5), numbers as printed.

## 7. Open design points (to settle at step 0, against measured facts)

- Which struct survives the convergence (§3a.3) — decided by the
  flagship's measured `eve_obj` layout and its dispatch cost.
- Whether the runtime (default, in-mem) domain on jank lives in a heap
  region or an anonymous MAP_SHARED region — the latter is what a later
  unit-7 two-process share would want; unit 6 needs only the former.
- Whether jank 0.1's `catch` can carry a failed `require`'s reason: on
  jank 0.1-noble a failed `(require 'eve.atom)` is caught with the error
  bound as `nil` (measured 2026-11-05), so the probe's GATED line on jank
  names the namespace only.
