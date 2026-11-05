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
| ~~`:region-close`~~ | struck at step 0 (§7.5 point 4): Eve's jank adapter exports no close | — (the C++ `unmap` / `close_fd` have no Clojure caller in Eve) |

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
- On jank (step 0, §7.1's load-order rule): `eve.host-completion` is
  required before `eve.atom` / `eve.digest` / `eve.deftype-proto.data`, as
  Eve's own launcher does; `deref` / `reset!` of the Eve atom resolve
  `clojure.core`'s var root at run time (the completion re-roots them only
  for namespaces compiled after it, and the probe's namespace is compiled
  before it loads); a process holding Eve ends through `eve.process/exit!`
  (Eve's documented end for a jank program); `STAGE` lines on stderr (C
  stderr, unbuffered) record where a jank process stops. The JVM seat loads
  and runs as before.

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

## 6. Steps after the gate (status 2026-11-05: §8)

0. Read the flagship gate report: API names, `eve_obj` layout, module
   path, numbers. Set `EVE_JANK_PATH` for the probe; run
   `dev/u6/residence.sh` — the jank legs must leave GATED.
   **Done** (§7): the jank legs left GATED and now end at exit 139 (§7.6).
1. Region leaves in the jank adapter contract (§3b), defaults = Eve's.
   **Built** (§8.1, check and gates §8.4).
2. Struct convergence (§3a) behind the existing `cohost_object` API
   (`is_cohost`, `descriptor_of`, `fields_of`), no caller changes.
   **Stopped** on a decision (§8.2).
3. Re-point units 3–5 on jank (§3c); runtime domain on Eve's slab.
   **Blocked** on step 2 and the jank fault (§8.3).
4. Gate run (§5), numbers as printed. **Not run** (needs step 3).


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

### 7.6 The probe on jank, measured (step 0's run)

`EVE_SRC=<eve d241de3> CLOJURE_JARS=<clojure 1.12.0, spec.alpha 0.5.238,
core.specs.alpha 0.4.74> EVE_JANK_PATH=<bb classpath jank @ 14add0b15>
LC_ALL=en_US.UTF-8 bash dev/u6/residence.sh jvm jank jvm-jank jank-jvm`,
2026-11-05 4:35–4:42 PM ET, wall 463 s, as printed:

    LEG jvm PASS  writer-pid=14758 reader-pid=14794 digest=-2096274480,-623163342,-463366720,1732804911,-1023654562,-1977017875,1516150709,-1554473893
    LEG jank FAIL  writer-pid= reader-pid= ( writer exit 139; reader exit 139; no writer ROOT line; no reader ROOT line; ...)
    LEG jvm-jank FAIL  writer-pid=15284 reader-pid= ( reader exit 139; no reader ROOT line; ...)
    LEG jank-jvm FAIL  writer-pid= reader-pid=25303 ( writer exit 139; no writer ROOT line; ...)

- Every jank role loads Eve (the completion, then `eve.atom`, `eve.digest`,
  `eve.deftype-proto.data`), opens or joins the persistent domain, and — as
  a writer — completes `reset!` and `sync!`. Each then exits 139 while
  making its ROOT line. A writer-only jank run with finer stages (4:43 PM ET,
  120 s, exit 139) places it: `root-digest` under `read-pinned` returns,
  `deref` returns, the process faults in `pr-str` of the deref'd value.
  jank segfaults on this seat are VS Agent 3's segfault-localization item;
  the probe is not changed to avoid printing (that would work around it).
- `jank-jvm`: the JVM reader — a separate process started after the jank
  writer had died — joined the jank-written domain and printed the value
  with that run's tag (`:tag "u6-jank-jvm-14738-1793914834"`, digest
  `-215551517,-1571435707,-1096249244,-194124995,-235594122,802937462,1082986659,-1739641476`).
  So a root written and synced on jank is resident on disk, in the one
  on-disk format both seats read. The leg is still FAIL by its definition:
  the jank writer printed no ROOT line, so there is no writer digest to
  compare.
- The JVM leg re-run on the final probe (4:45 PM ET, 9 s) prints
  `LEG jvm PASS  writer-pid=6549 reader-pid=6582 digest=-578695409,...`.

## 8. Steps 1–3 (2026-11-05, implementer on the coordinator's box)

### 8.1 Step 1 — region leaves: BUILT

- `src/cohost/adapter.cljc`: `:region-open`, `:region-heap`, `:region-sync`
  in `contract-order` (each documented as Eve's export it carries), `nil` in
  `defaults`, and the accessors `regions?`, `region-open`, `region-heap`,
  `region-sync!` — each `nil` on a seat that does not supply the leaf. No
  `:region-close` (§7.5 point 4). `install!` refuses a capability the
  contract does not name, so a seat cannot fill a leaf outside this list.
- `src/cohost/region_eve.jank`: the jank seat's fill, the `cohost.http-eve`
  shape — `install!` puts `eve.region/open-mmap-region`,
  `eve.region/make-heap-region` and `eve.mem/sync-region!` over the current
  adapter. It requires `eve.host-completion` first (§7.1); nothing in
  cohost's shim or boot requires it, so no seat's boot changes.
- The check, `dev/u6/region.sh` + `dev/u6/region_check.jank`: through
  `cohost.adapter` only, a heap region round-trips a number (and reads back
  zeroed first); a file region is written and synced by one process and read
  back by a second process started after the first exited. GATED where Eve
  is not on the module path. Results: §8.4.

### 8.2 Step 2 — struct convergence: STOPPED on a decision the design and rulings §7–§10 do not settle

Step 0 settled the shape (§7.5 point 1: cohost's struct, no field added).
What it leaves open is where Eve's side changes. For an Eve value on jank
to BE the converged object, Eve's completion itself has to change:
`make-object` (host_completion.jank:118) builds cohost's object; `object?`
(:123 — today `opaque_box` and a `dynamic_cast<eve_obj *>`), `object-tag`
(:128) and `object-fields` (:129) read it; Eve's `=` / `hash` / printer hooks
(:72–77, set by `set-type-equality!` :554 and `set-printer!` :601) are
reached through the converged object's method slots instead of `eve_obj`'s
virtuals. That changes Eve-on-jank's object — the surface the flagship's J3
gate measured (its F-3 finding is about that object's `opaque_box` type).
Two ways to make the change, neither settled:

- **(a) Eve-side.** `eve.host-completion` builds the converged object; the
  flagship lane makes the change in Eve and re-gates its sweep on it. cohost
  supplies its existing struct and nothing else.
- **(b) cohost-side.** cohost's jank boot re-roots the four completion vars
  (`alter-var-root`, before Eve's namespaces compile — re-roots hold only
  for namespaces compiled after them, §7.1) to cohost's constructor and
  accessors. That replaces an Eve primitive's implementation from cohost,
  which is the line ruling §9 draws, and changes Eve's gated surface without
  Eve's gate re-running.

Also unsettled, and needed by either: one jank process holding two type
layers — cohost's own (`cohost_object` + descriptor protocols, for cohosted
programs) and Eve's completion, which interns `deftype` / `defprotocol` into
`clojure.core` and re-roots `=`, `hash`, `type`, `class`, `instance?`,
`defn`, `fn`, `let`, `loop`, `case`, `meta`, `sort`, `deref`, `reset!`, ...
for every namespace compiled after it (§7.1). §3a converges the object; it
says nothing of the two protocol systems. Measured so far: in the step-1
check (§8.4) one jank process loads cohost's jank host, then the completion
and Eve's region namespaces, and `cohost.adapter`'s install and accessors
still answer — the adapter level only. And cohost's booted program with
the completion loaded into the same process before it
(`dev/u6/boot_with_completion.jank`, §8.4): battery 219/221 and libraries
62/62, as without it; the boot report moves two names from booted-as-stock
to from-host (544/141 → 542/143), which two not identified [unverified].
Neither option is built until the operator answers.

**The question (operator):** (a) or (b); and whose gate re-measures
Eve-on-jank on the converged object.

### 8.3 Step 3 — re-pointing units 3–5: BLOCKED, on step 2 and on the jank fault

- Units 3 and 4: §3c's jank stratum is the converged object itself
  (`eve-value?` a descriptor-family test, unit 4's creators answering
  converged objects). Without step 2 an Eve value reaching cohost's booted
  program on jank is an `eve_obj` (`opaque_box`, no behaviors) that cohost's
  shim does not dispatch.
- Unit 5: an Eve atom's `deref` outside a `$` region answers plain data
  (`d/eve->clj`, `src/eve/atom.cljc:813–826`), so the atom's values need no
  converged object. Its handle does: `cohost.atom` calls `deref` / `swap!` on
  the handle with jank's own core fns (cohost is AOT-compiled before the
  completion loads, and the completion's re-roots hold only for namespaces
  compiled after it — the completion's own measured statement,
  host_completion.jank:29; not measured on cohost here), and an `eve_obj`
  `MmapAtom` has no jank `deref` behavior (`object_behavior::none`, :80). The converged object —
  whose `deref` slot is a jank behavior — is what lets the layer hold Eve's
  atom unchanged; the alternative, adding handle-operation fns to the
  portable layer's factory, is a unit-5 design change this unit does not own.
- The fault: printing a value deref'd from an Eve atom on jank exits 139
  (§7.6), and the gate run (§5: `bb boot:jank` with the fill forced) prints
  values. No step-3 code is written: its representation waits on §8.2 and
  its one verification path ends at that fault.

### 8.4 Step 1's check and gates, as printed

- `EVE_JANK_PATH=<bb classpath jank @ 14add0b15> bash dev/u6/region.sh`
  (2026-11-05, ET): 4:47 PM `REGION-CHECK PASS  writer-pid=6762
  reader-pid=6910 n=1847408095` (61 s); after `region-open` became
  single-arity, 4:49 PM `REGION-CHECK PASS  writer-pid=7045 reader-pid=7063
  n=1849649271` (62 s). The first run (4:46 PM) printed both REGION lines
  with the right numbers and FAILed on the check's own exit (it resolved
  `eve.process/exit!`, which the region path does not load); fixed in the
  check. Without `EVE_JANK_PATH`: `REGION-CHECK GATED  (GATED (require
  'cohost.region-eve) failed on this seat)` (18 s).
- JVM gate (`clojure -M -m cohost.run`'s classpath, run as `java -cp
  src:resources:<clojure 1.12.0, spec.alpha 0.5.238, core.specs.alpha
  0.4.74> clojure.main -m cohost.run`, 4:50 PM, 8 s): `booted as stock: 465
  from host: 202   natives: 66   skipped: 58   errors: 0`, `battery: 221/221
  stock behaviors pass`, `libraries: 62/62 cases pass across 12 clojure.*
  libraries` — unit 5's numbers.
- jank gate (`bb boot:jank`, 2026-11-05): RED on the base before any unit-6
  change — base 507ca52's AOT compile (`jank --module-path src
  compile-module cohost.run -O3 --target-dir target/jank-aot`, 216 s) prints
  4 clang errors "no viable overloaded '='" at jank's `core.jank:4527` and
  ends with no `cohost/shim.o`, so `bb boot:jank` fails ("compile finished
  without cohost/shim.o"; the same on d4064a8, 223 s). Cause: unit 9's
  `-getDefaultPort` `case` (upstream defect 19). Fixed in its own commit
  (799c8eb, shim.cljc only). With step 1 + that fix (5:00–5:07 PM ET, rc=0,
  466 s): `booted as stock: 544   from host: 141   natives: 28   skipped:
  108   errors: 0`, `battery: 219/221` (`FAIL subs count nth first last
  butlast`, `FAIL Character outside ASCII` — the standing jank pair),
  `libraries: 62/62`.
- The same AOT objects with `eve.host-completion` loaded first
  (`LC_ALL=en_US.UTF-8 jank --module-path <aot-dir>:src:<bb classpath jank>
  run dev/u6/boot_with_completion.jank`, 5:07–5:10 PM ET, rc=0, 175 s):
  `EVE-COMPLETION-LOADED`, `booted as stock: 542   from host: 143
  natives: 28   skipped: 108   errors: 0`, `battery: 219/221` (the same
  two FAILs), `libraries: 62/62`.
