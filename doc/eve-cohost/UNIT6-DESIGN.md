# Unit 6 — Slab residence on the jank seat: DESIGN (prep)

Design only. Unit 6 is hard-gated on the Eve-on-jank flagship gate
(MEMORY-PLAN.md §5); nothing here is implementation, and nothing here
estimates a number the flagship has not measured. Base: MEMORY-PLAN.md on
`muse/eve-cohost-impl` @ 0fa021f — the "Unit 6" section, §5 (sequence), and
the rulings §7–§10. Eve facts are read from github.com/SeniorCareMarket/eve
main @ d241de3 and labeled with that sha; facts about the flagship branch
were labeled **[unverified: flagship branch not read]** until step 0 read it
(2026-11-05 ~4:30 PM ET): §7 now carries them, cited file:line under `eve/`
in atom.broker `claude/eve-jank` @ 14add0b15 (the flagship's gated tip).

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
  require fix) is what makes `eve.atom` load on jank. Read at step 0 (§7):
  its API names, its `eve_obj` layout and its module path are §7.1–§7.3;
  `eve.atom`, `eve.digest` and `eve.deftype-proto.data` load on jank once
  `eve.host-completion` has loaded first (measured, §7.6).

## 3. Below the seam: the three pieces

### 3a. `cohost_object` ↔ `eve_obj` convergence

Today (cohost `src/cohost/hosts/jank_host.jank`, `cohost_object`): one C++
struct, tag `object_type::max + 1` (derived so a jank that adds a type does
not collide), slots `descriptor`, `methods` (vector; 0 equiv, 1 hasheq,
2 toString, 3 toCodeString, 4 invoke, 5 deref, 6 get, 7 seq, 8 compare,
9 find), `fields`, `extmap`, `meta_`; behaviors call/get/find/compare/
seqable/indexable/deref. Eve's side: one tagged object
(`eve_host::eve_obj` {tag, fields}, `opaque_box`, no behaviors — read at
step 0, §7.1); Eve main's types are `opaque_box` structs.

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


## 7. Step 0 — the flagship read, and the open points settled against it

Read 2026-11-05 ~4:30 PM ET on the coordinator's box from atom.broker
`claude/eve-jank` @ 14add0b15 (the flagship's gated tip; its J3 GATE report
is `docs/port-gap-closure/coordination/reports/eve-jank-J3-flagship-REPORT.md`
in that tree). Paths below are under `eve/` there. Names, layout and line
numbers only — no flagship code is copied into this repository.

### 7.1 Eve's object on jank: `eve_host::eve_obj`

- `adapters/completion/jank/src/eve/host_completion.jank:78–101`:
  `struct eve_obj : object`. Its jank type is `object_type::opaque_box`
  (:79), its behaviors `object_behavior::none` (:80), `pointer_free` false
  (:81). Two slots: `object_ref tag` (:82) — the deftype's tag, a
  namespace-qualified symbol (`(symbol (str *ns*) (name type-name))`, :737),
  which is also the value of the type's var (:750) — and `object_ref fields`
  (:83), a vector of the field values in factory order (a mutable field holds
  a `volatile!` cell, :10–13). Overridden virtuals: `to_string` /
  `to_code_string`, `equal`, `to_hash` (:88–101), each through one
  process-global hook — `printer`, `equiv`, `hasher`, file-static
  `object_ref`s registered with `GC_add_roots` (:72–77); unset, `equal` is
  identity and `to_hash` the address.
- C++ API, namespace `eve_host` (:104–114): `make(tag, fields)`, `is(o)`
  (`opaque_box` and a `dynamic_cast<eve_obj *>`), `tag(o)`, `fields(o)`,
  `set_printer` / `set_equiv` / `set_hasher` (:108–110), `addr(o)`,
  `i8_array(n)`. Clojure API, `eve.host-completion`: `make-object` (:118),
  `object?` (:123), `object-tag` (:128), `object-fields` (:129),
  `object-addr` (:130), `set-type-equality!` (:554), `set-printer!` (:601);
  the macros `defprotocol` (:383), `deftype` (:733), `defrecord` (:768 — a
  map carrying `:eve/record` in its meta, not an `eve_obj`), `reify` (:801).
- Dispatch keys on the tag: a protocol is a table per method keyed by a
  value's type key — a deftype's tag, a record's, `::nil`, else jank's own
  type name (:158–164); `type` / `class` / `instance?` are re-rooted onto it
  (:166–167, :183).
- An Eve slab-backed value is an `eve_obj` whose fields carry its slab
  handle: Eve's deftypes store `[slab-off__ sio__]` — the header-block slab
  offset and the domain's slab I/O (`src/eve/deftype_proto.cljc:53`). There
  is no inline root-pointer field: an atom's root pointer `[class:3|block:29]`
  is read from the domain's root region slot (`src/eve/atom.cljc:975`, in
  `read-pinned`), not from an object.
- Load order (:25–29): what the completion interns or re-roots into
  `clojure.core` — `deftype`, `defprotocol`, ..., `=` (:582), `hash` (:590),
  `deref` (:502), `swap!` (:508), `reset!` (:515), `defn` / `fn` / `let` /
  `loop` (:312–321, which strip native type hints), `case` (:350), `meta`
  (:596), `sort` (:882) — holds only for namespaces compiled after it loads.

### 7.2 Eve's region adapter on jank: `eve.region`

`adapters/region/jank/src/eve/region.jank`:

- `JankRegion [base size fd]` (:121) implements `eve.mem/IMemRegion` (:122),
  `IHostAtomics`, and `ISyncableRegion` (:144–146: `-sync!` /
  `-sync-range!`, msync; nothing when `fd` is nil). `JankLockedRegion [base
  size fd]` (:157) is the Lustre (fcntl-lock) variant.
- Exported constructors: `open-mmap-region` `[path size]` / `[path size
  opts]` (:199–217; `opts {:lustre? ...}`; creates or opens, grows, never
  truncates, `MAP_SHARED`, keeps the descriptor open) and
  `make-heap-region` `[size]` (:219–225; `calloc`, never freed — "a region
  lives as long as its domain"). Also `lustre-region?` (:227) and
  `region-base` (:232).
- Sync is `eve.mem/sync-region!` (`src/eve/mem.cljc:135`) over
  `ISyncableRegion` (`src/eve/mem.cljc:124`).
- No close: the C++ `unmap` (:50) and `close_fd` (:56) have no Clojure
  caller anywhere under `eve/` (grep at 14add0b15); Eve exports no function
  that unmaps or closes a region.

### 7.3 The API unit 6 calls (all present at 14add0b15, names as the probe uses them)

- `eve.atom` (`src/eve/atom.cljc`): `atom` (:1398 — `{:persistent path}`
  opens `persistent-atom-domain`, else an in-process domain,
  `open-heap-domain!` :663, whose root and reader map are `make-heap-region`s
  and whose slabs are `alloc/heap-domain-sio!`), `persistent-atom-domain`
  (:1065; `journal?` is opt-in and unset by default), `read-pinned` (:964),
  `sync!` (:1144), `close!` (:1139), `close-atom-domain!` (:1130),
  `lookup-or-create-mmap-atom!` (:1239), `atom-domain` (:1446),
  `shared-atom?` (:1418), the `MmapAtom` type (:990).
- `eve.digest/root-digest` (`src/eve/digest.cljc:185`);
  `eve.deftype-proto.data`: `IEveRoot` / `-root-header-off` (:458),
  `IEveSio` / `-eve-sio` (:464).
- `eve.process/exit!` (`adapters/process/jank/src/eve/process.jank:137`):
  the exit hooks, `fflush`, `_exit` — the end a jank program holding Eve
  takes (`eve/doc/hosts/jank.md`, "No exit hook on a normal end"; upstream
  defect 10).
- Module path: `bb classpath jank` in `eve/` — 16 roots: `src` and the
  `:jank` row's adapters `slab-io/jank`, `core/clojure`, `completion/jank`,
  `coll/jank`, `slab/heap`, `region/jank`, `typed-view/jank`,
  `encoding/jank`, `int32/jank`, `codec/byte-array`, `process/jank`,
  `thread/none`, `types/jank`, `crypto/jank`, `journal-file/unsupported`.
  The journal adapter on jank is `unsupported`: the model law's "no
  journals" holds on this seat by Eve's own row.

### 7.4 The flagship's numbers (consumed, not re-measured)

From the J3 GATE report @ 14add0b15: seams 5 (slab) + 6 (typed-view) GATE
PASS, the `hosts.edn` flips stay; §5 step 1 8 tests / 212 assertions / 0 / 0;
slab-io conformance 4 / 51 / 0 / 0; typed-view's scalar C++ ~2,400–4,900× Eve's
element loops at n=8000. The report states the node-C++-extension rows (atom
swap, mmap shapes) are VS Agent 2's lane A — they are not in the gate report.
Findings it carries out, each relevant here: F-3 (a deftype instance inside an
atom: "Objects of type `opaque_box` are not associatively writable", both
arms), the 64 KB slab-class-6 wall of the default domain, and multi-namespace
runner processes faulting (139/135) at moving points on both arms.

### 7.5 The open points, settled where the facts settle them

1. **Which struct survives (§3a.3) — settled by layout; its mechanism is
   OPEN (§8).** `eve_obj` is {tag, fields} plus three process-global hooks
   and no behaviors; `cohost_object` is {descriptor, methods, fields, extmap,
   meta_} with its own derived type id and seven behaviors (call, get, find,
   compare, seqable, indexable, deref) that cohost's battery runs through.
   `eve_obj` carries no field `cohost_object` lacks (no inline root pointer,
   §7.1), so by §3a.3's rule the converged object is cohost's shape and adds
   no field: Eve's tag takes the descriptor slot (both are the value of the
   type's var), Eve's field vector the fields slot. Dispatch cost: not
   measured here [unverified].
2. **Runtime domain: heap region or anonymous MAP_SHARED — settled by Eve's
   exports.** Eve's in-process domain is `open-heap-domain!` over
   `make-heap-region` (calloc), and Eve on jank exports no anonymous-shared
   constructor (§7.2). Under §9 the default runtime domain on jank is Eve's
   heap domain; an anonymous shared mapping (unit 7's two-process share) is
   Eve's to add, not cohost's to build.
3. **jank's `catch` and a failed `require` — unchanged.** The flagship's
   jank.md records the same (upstream defect 3: `:default` binds nil); the
   probe's GATED line on jank names the namespace only.
4. **Region leaves (§3b) — settled by the settling rule.** `:region-open` =
   `eve.region/open-mmap-region`; `:region-heap` = `eve.region/make-heap-region`;
   `:region-sync` = `eve.mem/sync-region!`. **`:region-close` is struck**:
   Eve's jank adapter exports no close (§7.2), and the rule admits no
   operation Eve's contract lacks.
