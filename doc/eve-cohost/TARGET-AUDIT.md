# Eve target audit — the in-place rule, every target

> DECLARATION — audit banked 2026-11-05 on branch
> `muse/eve-cohost-impl`, base `d5ba16c`. Audit/research
> only — no implementation code anywhere; Eve's trees were
> read, never modified. Every claim below cites code
> (file/line) or a printed number (with its source run).
> Status: `TARGET-AUDIT-STATUS.md` on this branch.

## 0. The rule under audit

John (2026-11-05 ~10:19 ET): the byte form was *"meant to
be true across all platforms"* — one canonical serialized
byte representation in the slab on every target, read **in
place** (typed-view / lazy navigation of the stored form,
materializing only at domain crossings), so an in-memory
atom on any target behaves like an in-memory atom — **not**
like a disk atom (full deserialize-on-access /
materializing deref as the ordinary read path). His order:
*"do an audit across all of our 11 or 12 targets and check
to make sure that all of them do the correct thing."*

Two distinctions govern every verdict, per the brief:

- **Element decode is by design.** A scalar/element stored
  as bytes must be decoded to be handed back as a host
  value; that is cheap and is not the violation shape.
- **Whole-value materialization is the violation shape.**
  Rebuilding an entire collection (or an atom's whole root)
  as host data on an ordinary read — instead of navigating
  the stored form and returning a lazy slab-backed value —
  is disk-atom behavior in the mem path.

## 1. Step 0 — the targets, enumerated from the sources

The sources do not agree on one list; reconciling them is
the first finding.

| Source | What it lists | Count |
|---|---|---|
| `eve/doc/platform-support.md` (standalone Eve, `~/src/eve` @ main `c7dc390`) | Node.js Linux/macOS/Windows, JVM Linux/macOS, Browser — 3 runtime families × OS rows. **Babashka is never mentioned**, though it exists in code (below) | 6 rows / 3 families |
| Standalone Eve CLJC reader conditionals (census over `src/**/*.cljc`: `#?(:cljs` ×220, `#?(:clj` ×55, `#?(:bb` ×74, `#?(:default` ×1) | code targets: **:cljs, :clj, :bb** | 3 |
| Standalone Eve `IMemRegion` backends (`src/eve/mem.cljc`) | `JsSabRegion` (:cljs, L173), `NodeMmapRegion` (:cljs, L307), `LustreMmapRegion` (:cljs, L401), `JvmByteBufferRegion` (:clj, L879), `LustreJvmMmapRegion` (:clj, L1077), `BbMmapRegion` (:bb, L491), `BbHeapRegion` (:bb, L605) | 7 backends |
| Vendored Eve (`~/src/atom.broker` @ `claude/eve-jank`, `eve/hosts.edn`) | host keys: `:jvm :cljs :browser :bb :bb-minimal :nbb :scittle :cherry :cljr :lpy :jolt :clje :vs :glj :jank :lg :cljd` | 17 keys (16 distinct hosts + the bb-minimal variant) |
| Vendored Eve hosts README (`eve/doc/hosts/README.md`, same branch) | battery table: JVM, CLJS-Node, browser, bb, nbb, ClojureCLR, basilisp, jolt, clojerl, glojure, ValueScript = **11**; perf table: the same minus ValueScript, plus bb-minimal = **11 columns**; union = **12** | **11–12 — this is John's number** |
| Cohost seats (probe + redo §5.1) | JVM, cljs, jank, cljw, jolt, lg, basilisp, cljrs, phel | 9 |

**Reconciliation.** John's "11 or 12" is the vendored
hosts README's set: 11 battery hosts, 12 in the union of
its battery and perf tables. The full reconciled audit
list across *all* sources is **19 targets**: the 16
distinct vendored host keys, plus 3 cohost seats that
appear in no Eve source (cljw, cljrs, phel). The
standalone Eve repo's 3 code targets (:cljs/:clj/:bb) are
the same targets as the vendored :cljs/:jvm/:bb hosts,
audited on both lines where both lines carry code.

**There are two Eve lines, and the audit covers both.**
(1) *Standalone Eve* — `~/src/eve` @ `c7dc390`
(SeniorCareMarket/eve), CLJC with :cljs/:clj/:bb branches
and the `mem.cljc` regions. (2) *Vendored Eve* — the
adapter-architecture Eve inside atom.broker
(`claude/eve-jank`), one `eve.atom` "for every host"
behind per-host adapter roots (`eve/hosts.edn`). Where
the two lines differ in behavior, the target's section
says so.

## 2. Cross-cutting findings (all targets)

### 2.1 Collection reads navigate in place — everywhere the collections exist

Both lines share the same collection algorithms, and the
read paths walk the stored form by offset:

- **Map `get`** — `hamt-get` (`~/src/eve/src/eve/map.cljc:439`):
  walks HAMT nodes via `-sio-read-i32` offsets, compares the
  *stored key bytes* against the serialized probe key bytes
  (`bytes-equal?` on the bytes read at the node), and only
  at the leaf calls `deserialize-value-bytes` (map.cljc:74)
  on the one value's bytes — element decode, by design. A
  nested collection value decodes to Eve's slab-backed
  wrapper for that value, not to a materialized tree.
- **Vec `nth`** — `nth-impl` (vec.cljc:120) descends the
  trie by `node-get` i32 reads, then `read-value-block`
  (vec.cljc:105) decodes the single element's
  `[len:u32][bytes]` block. In place; element decode only.
- **Set `contains?`/`get`** — `EveHashSet.valAt`
  (set.cljc:676) serializes the probe value and calls
  `hamt-find`, which compares stored bytes in place; on a
  hit it returns the probe value itself. **No element
  decode at all** on the membership path.
- **List** — `read-node-value` (list.cljc:87) decodes the
  single node's element bytes; traversal is offset-chasing.

So the *form* of collection reads is correct on every
target that runs this code: no target's `get`/`nth`
materializes its collection. What varies is the **price**
of the shared path per target (§2.3) and whether the
target runs the code at all (§3).

### 2.2 Plain atom deref materializes the whole root — on every target, both lines

This is the audit's headline, and it contradicts both
John's premise for the audit (that the JVM is the
violator and the other seats do the correct thing) and
Eve's own platform doc.

- Standalone, :cljs — `cljs-mmap-deref`
  (atom.cljc:364): constructs the lazy root wrapper, then
  *"Materialize while pinned — Eve wrappers must not
  escape"*: `(d/eve->clj v)`. Its docstring: *"plain deref
  must not return slab-backed Eve wrappers."*
- Standalone, :clj — `jvm-mmap-deref` (atom.cljc:850):
  *"Read the current atom value. Returns PLAIN Clojure
  data (d/eve->clj). Eve values must not escape into the
  heap: the epoch is unpinned on return, so any
  slab-backed wrapper would dangle once epoch GC reclaims
  the old root. Bounded Eve-backed access will come via
  the $ operator."* The inner `jvm-read-root-value`
  (atom.cljc:805) does return slab-backed types on :clj —
  and deref then materializes them anyway.
- Standalone, :bb — same `jvm-mmap-deref`; additionally
  `jvm-read-root-value`'s :bb arm (atom.cljc:810–833)
  materializes **set, vec and list roots at read time**
  ("no bb deftype yet") — only maps get a slab-backed
  `BbEveHashMap`, and deref materializes that too.
- Standalone, legacy cljs system — `eve.shared-atom`'s
  `AtomDomain` deref (shared_atom.cljs:2919): *"Materialize
  SAB types to CLJS on deref — SAB types never escape to
  user code outside a swap! transaction"* — deserialize +
  `eve->cljs`, with a cache keyed by root descriptor so a
  repeated deref of an unchanged root returns the cached
  materialization.
- Vendored line, all hosts — `plain-deref`
  (atom.broker `eve/src/eve/atom.cljc:813`): *"The atom's
  value as plain data (d/eve->clj)… Slab-backed wrappers
  must not escape: the epoch is unpinned on return."*
  Plain `deref` dispatches to it on every host
  (`mmap-deref`, L828); the lazy slab-backed value is
  available **only** inside a `$` region (`region-deref`,
  L799) or `read-pinned` (L964). The vendored perf suite
  states the same fact as a workload definition:
  `atom-deref-map-1k` is *"deref of the 1,000-key map
  (materializing it as host collections)"* (hosts
  README), against `atom-$-get-map-1k`, the zero-copy
  pinned get.

**The rationale is real and documented in both lines:**
a plain deref unpins the read epoch on return, so a
slab-backed wrapper handed to arbitrary code could
dangle after epoch GC reclaims the root. The design
answer both lines chose is: materialize at deref, offer
laziness only inside a bounded pinned region (`$` /
`read-pinned`). Under John's rule as stated — lazy
slab-backed root as the ordinary read, materialization
only at domain crossings — **plain deref is the
violation shape on every target that has Eve atoms at
all.** It is a uniform design decision, not a JVM
defect. Its *cost* varies by orders of magnitude per
target (§3), which is why it was only ever *felt* on
the JVM.

### 2.3 The JVM's collection reads are disk-priced; cljs's are not

The shared HAMT/trie code prices completely differently
per branch, measured:

| op | cljs/SAB (redo §5.2, Eve `c7dc390`, release build) | JVM :clj (spike P3, standing) |
|---|---|---|
| map get | **1,970–2,335 ns** | **~119–134 µs** |
| map assoc (1k build, per op) | 9,340–9,607 ns | ~0.5 ms |
| atom deref, scalar root | 708–737 ns | ~104 µs |
| atom swap! inc | 4,347–4,511 ns | ~178 µs |
| deref, 100-entry map root | 96.6–105.0 µs (materializes) | ~1.78 ms (materializes) |

Why, from the code: the algorithm is one, the slab-IO
layer under it is not. On :cljs, `CljsSlabIO`
(alloc.cljc:1475) reads are DataView ops over the SAB.
On :clj, every `JvmSlabCtx` read (alloc.cljc:196–290)
re-decodes the slab-qualified offset (class idx, block
idx, base), dispatches through the region array, and
`-read-bytes` **allocates a fresh `byte[]` per call**;
the leaf decode on :clj is `eve-bytes->value`
(map.cljc:77), the full codec, where :cljs calls
`ser/deserialize-element`. A HAMT get makes dozens of
sio calls (node reads, hash-array reads, and a
`skip-kv-at` walk over preceding entries); at JVM
per-call prices that is the measured ~50–60× gap. The
JVM seat additionally has the capacity wall the spike
measured (10k-map / 1k-vec builds die in the heap
domain; the cljs seat completes both — redo §5.2),
notwithstanding `jvm-open-heap-domain!`'s "64 KB
initial — grows on demand" comment (atom.cljc:1452):
as measured, the growth does not save those builds.

### 2.4 Doc-vs-code contradictions (reported, not smoothed)

1. **platform-support.md "Deref Behavior"** claims SAB
   and persistent atom deref *"return lazy slab-backed
   Eve types"* / *"lazy EveHashMap"*, materialization
   *"on access (transparent)"*. The code (§2.2)
   materializes the whole root at deref on both seats.
   **The doc is wrong; the code is the fact.**
2. **platform-support.md + architecture.md +
   persistent-atoms.md name backings `JvmHeapRegion` /
   `JvmMmapRegion`.** No such types exist in
   `src/` (grep: only doc hits and one comment). The
   code's backings are `JvmByteBufferRegion` (one type
   over heap `byte[]` and `MappedByteBuffer`) and
   `LustreJvmMmapRegion` (mem.cljc:879/1077).
   mem.cljc's own header (L18–27) lists only 3 of its 7
   implementations — the two Lustre regions and both
   Bb regions are missing from it.
3. **platform-support.md omits Babashka entirely**,
   though the :bb branch is 74 reader-conditional sites,
   two region backends, and a shipped example
   (`examples/bb_atom_swap.clj`) — and that example
   currently **fails to load** (§3.4).

## 3. Per-target audit

Verdict vocabulary (per the brief): **CORRECT** /
**VIOLATION** (mechanism named) / **NO EVE PORT** /
**UNMEASURED** (blocker named). Because §2.1 and §2.2
separate cleanly, each target gets two sub-verdicts —
*collection reads* and *atom deref* — and one headline.

### 3.1 ClojureScript / Node — standalone :cljs + vendored :cljs

- **Backing.** In-process: `JsSabRegion` over
  SharedArrayBuffer (mem.cljc:173); cross-process:
  `NodeMmapRegion` / `LustreMmapRegion` over the N-API
  addon's mmap. Vendored line: `adapters/region/js` +
  `adapters/slab/module` + `adapters/typed-view/js`.
- **Read path.** §2.1 shared code over `CljsSlabIO`
  DataView reads. In place; leaf element decode only.
- **Deref.** Materializes (§2.2, both cljs atom systems).
  Scalar roots are cheap because there is almost nothing
  to materialize; a 100-entry map root costs ~100 µs
  *because* it materializes.
- **Cost.** Redo §5.2 (standalone, this audit's standing
  numbers): deref 708–737 ns, swap 4.3–4.5 µs, get
  2.0–2.3 µs, assoc 9.3–9.6 µs; capacity builds pass.
  Vendored perf suite (2026-10-21 window): deref-small
  8.9 µs, deref-map-1k **1.7 ms** (materializing),
  $-get-map-1k 6.4 µs.
- **Verdict.** Collection reads **CORRECT** (in place,
  mem-priced — ~40× stock, 50–60× cheaper than the JVM
  branch of the same code). Atom deref **VIOLATION**
  (whole-root materialization as the ordinary read;
  mechanism: `eve->clj` in `cljs-mmap-deref` /
  `plain-deref`). Headline: **MIXED — the reference seat
  for reads; deref violates uniformly with all seats.**

### 3.2 Browser — standalone :cljs + vendored :browser

- **Backing.** `JsSabRegion` only (no mmap, no addon;
  platform-support.md "SAB atoms only"). Vendored:
  region/js + slab/module, thread/browser adapters.
- **Read path / deref.** Identical :cljs code to §3.1,
  including both materializing derefs.
- **Cost.** Standalone line: not measured in this audit
  (no headless run; the code path is §3.1's, the
  substrate is the same SAB). Vendored perf column:
  deref-small 4.5 µs, deref-map-1k 1.0 ms, $-get 3.4 µs;
  battery 48 tests green (hosts README).
- **Verdict.** By code: reads **CORRECT**, deref
  **VIOLATION** (same mechanism as §3.1). Costs on the
  standalone line **UNMEASURED** here — blocked by
  nothing but the audit not re-running a headless
  browser bench; the vendored numbers above are the
  only browser measurements in evidence.

### 3.3 JVM — standalone :clj + vendored :jvm

- **Backing.** `JvmByteBufferRegion` (mem.cljc:879) —
  one IMemRegion over a heap `byte[]` (in-process,
  `mem/make-heap-region`) or a Panama-FFM
  `MappedByteBuffer` (cross-process);
  `LustreJvmMmapRegion` for the fcntl-locked variant.
  (Docs' "JvmHeapRegion" does not exist — §2.4.)
  Vendored: `adapters/region/jvm-varhandle` +
  `adapters/slab/heap`.
- **Read path.** §2.1's shared algorithms over
  `JvmSlabCtx` — in place in form, but every sio read
  re-decodes the offset, dispatches per call, and
  allocates per `-read-bytes`; leaf decode is the full
  `eve-bytes->value` codec. Disk-priced (§2.3).
- **Deref.** Materializes (§2.2): scalar ~104 µs,
  100-entry map root ~1.78 ms — the materialization and
  the per-call sio prices compound.
- **Cost.** Spike P3 table (§2.3). Vendored perf
  column: deref-small 17.1 µs, deref-map-1k 377.7 µs,
  $-get-map-1k **2.1 µs** — the pinned lazy get on the
  JVM is the *fastest in the table*, which isolates the
  JVM problem precisely: it is the materializing deref
  and the decode-heavy convenience paths, not the slab
  or the region.
- **Verdict.** **VIOLATION** — the worst seat, in two
  distinct ways: (i) deref materializes (uniform
  mechanism, §2.2); (ii) collection reads, though
  in-place in form, are priced like a disk atom
  (~119–134 µs get, ~0.5 ms assoc, capacity wall) —
  mechanism: per-call offset decode + allocating
  `-read-bytes` in `JvmSlabCtx` and the full-codec leaf
  decode, where the :cljs branch pays DataView prices
  for the identical algorithm. This is the seat the
  heap-grade dispatch (Eve-side, step 1) targets.

### 3.4 Babashka — standalone :bb + vendored :bb / :bb-minimal

- **Backing.** `BbHeapRegion` (heap ByteBuffer over a
  `byte[]`, mem.cljc:605) and `BbMmapRegion`
  (`MappedByteBuffer`, mem.cljc:491). Vendored:
  `adapters/region/jvm-nio-lock` + `adapters/slab/heap`.
- **Read path.** Shared §2.1 code (bb loads the JVM-side
  slab code via the :clj fallback in alloc.cljc — its
  header, L126: "also loaded by bb via :clj fallback").
- **Deref.** `jvm-mmap-deref` — materializes (§2.2);
  additionally set/vec/list roots materialize inside
  `jvm-read-root-value` before that (atom.cljc:817–833,
  "no bb deftype yet").
- **Cost.** **Blocked — see the finding.** This audit
  wrote a bb bench (banked: `dev/target_audit_bb_bench.clj`)
  and ran it, and also ran Eve's own shipped example.
  **Both fail identically at load on standalone Eve
  main `c7dc390`:**
  `Could not resolve symbol: ser/register-header-tag-reader!`
  at `alloc.cljc:607`, required from `eve.atom`.
  Cause, from the code: `register-header-tag-reader!`
  is defined only inside serialize.cljc's `:clj` section
  (serialize.cljc:163, section ends L175 "end :clj"),
  while alloc.cljc's JVM/bb section calls it
  unconditionally at load (alloc.cljc:607); under bb the
  two files' branch selection diverges and the var is
  never defined. Eve's own `bb atom-swap` example
  (`examples/bb_atom_swap.clj`) fails with the same
  error and stack. The vendored line's bb battery is
  green (247 tests, hosts README) and its perf column
  exists (deref-small 68.9 µs, deref-map-1k 12.2 ms,
  $-get 88.3 µs) — but on the **standalone** line the
  :bb target does not currently load at all.
- **Verdict.** Standalone :bb: **BROKEN / UNMEASURED**
  — no working port on current main (named defect
  above; measurement blocked by it, error quoted), and
  by construction its deref is the materializing one.
  Vendored :bb: reads CORRECT-by-shared-code, deref
  VIOLATION (uniform mechanism), costs as printed.

### 3.5 nbb — vendored :nbb

- **Backing.** region/js + slab/module + typed-view/js
  (interpreted CLJS on Node). **Reads/deref:** the
  vendored shared implementation (§2.1/§2.2).
- **Cost.** Perf column: deref-small 27.9 µs,
  deref-map-1k 5.8 ms, $-get 33.7 µs. Battery 242 tests
  green.
- **Verdict.** Reads **CORRECT** in form (interpreted
  prices, ~3–15× the compiled cljs column). Deref
  **VIOLATION** (uniform materialization).

### 3.6 Scittle — vendored :scittle

- **Backing.** region/js + slab/module adapters are
  listed, but hosts.edn's own comment states: *"the
  slab collections (types, regions, slabs) have not
  been run on it"* — the seat has run an aegis chat
  program's 43 namespaces in a page worker, not Eve's
  slab layer.
- **Verdict.** **UNMEASURED** — no battery, no perf
  row, and by its own hosts.edn admission the slab
  layer is unrun. Nothing to verdict beyond that.

### 3.7 Cherry — vendored :cherry

- **Backing.** region/js + slab/module + typed-view/js;
  core over cherry's own cljs.core, thread/none.
- **Cost/state.** No hosts README page, no battery row,
  no perf column.
- **Verdict.** **UNMEASURED** — adapters exist and are
  composed like :cljs's, but no run of the slab/atom
  layer is in evidence on this branch.

### 3.8 ClojureCLR — vendored :cljr

- **Backing.** `adapters/region/clr` + `slab/heap`.
  **Reads/deref:** vendored shared implementation.
- **Cost.** Perf column: deref-small 89.7 µs,
  deref-map-1k 4.2 ms, $-get 25.2 µs. Battery 202 tests
  green.
- **Verdict.** Reads **CORRECT** in form; deref
  **VIOLATION** (uniform). (Not to be confused with
  cohost's *cljrs* seat — §3.18.)

### 3.9 Basilisp — vendored :lpy (cohost seat: basilisp)

- **Backing.** `adapters/region/ctypes` (Python) +
  `slab/heap`. **Reads/deref:** vendored shared impl.
- **Cost.** Perf column: deref-small **1.3 ms**,
  deref-map-1k **636.0 ms**, $-get 2.1 ms — the
  materializing deref is two-to-three orders worse
  here than anywhere else; the pinned get is also
  slowest-in-table, so this seat's slab-IO layer
  itself is interpreter-priced throughout. Battery
  180 tests, 7 errors (thread waits; hosts README).
- **Verdict.** Reads **CORRECT** in form, priced at
  interpreter rates. Deref **VIOLATION** — uniform
  mechanism, worst measured cost in the audit.

### 3.10 Jolt — vendored :jolt (cohost seat: jolt)

- **Backing.** `adapters/region/jolt-ffi` (Chez FFI) +
  `slab/heap`. **Reads/deref:** vendored shared impl;
  note the redo (§5.1) established jolt has no Eve
  branch in the *standalone* line — the port exists
  only on the vendored line.
- **Cost.** Perf column: deref-small 17.2 µs,
  deref-map-1k 4.7 ms, $-get 25.0 µs. Battery 199
  tests green.
- **Verdict.** Reads **CORRECT** in form; deref
  **VIOLATION** (uniform).

### 3.11 Clojerl / BEAM — vendored :clje

- **Backing.** `adapters/region/beam` (NIF) +
  `slab/heap`. **Reads/deref:** vendored shared impl.
- **Cost.** Perf column: deref-small 74.8 µs,
  deref-map-1k 7.2 ms, $-get 75.5 µs. Battery 198
  tests green.
- **Verdict.** Reads **CORRECT** in form; deref
  **VIOLATION** (uniform).

### 3.12 Glojure — vendored :glj (cohost seat: lg's sibling — see §3.15)

- **Backing.** `adapters/region/glojure` (Go) +
  `slab/heap`. **Reads/deref:** vendored shared impl.
- **Cost.** Perf column: deref-small 318.4 µs,
  deref-map-1k 72.6 ms, $-get 383.8 µs. Battery 182
  tests green.
- **Verdict.** Reads **CORRECT** in form, slowest
  compiled-host prices in the table. Deref
  **VIOLATION** (uniform).

### 3.13 ValueScript — vendored :vs

- **Backing.** `adapters/region/vs` + `slab/heap`,
  slab-io/vs; runs under `vs compile` + wasmtime.
- **State/cost.** Battery: 260 passed / 0 failed over
  the three namespaces it compiles (hosts README;
  "not part of 0.9"). **No perf-suite rows** — the
  perf table has no :vs column, so no deref/$-get
  costs are in evidence.
- **Verdict.** Reads/deref by shared vendored code:
  reads CORRECT in form, deref **VIOLATION** by
  construction (uniform). Costs **UNMEASURED**.

### 3.14 Jank — vendored :jank (cohost seat: jank)

- **Backing.** Memory is a `calloc`'d block or a
  `MAP_SHARED` mapping with `__atomic` builtins and
  the futex syscall, through `adapters/region/jank` +
  `slab/heap` (eve/doc/hosts/jank.md). The slab-io
  adapter is Eve-side work in flight (J3 seams;
  region+slab+typed-view flagship ungated — redo §5.1).
- **Read path.** Eve's shared collection code compiled
  by jank over the slab-io adapter; slab-io conformance
  green (4 tests / 51 assertions, 2026-11-05). The
  full suite (§5 step 4) and the in-memory example
  (step 5) were still in work at last signal.
- **Cost.** No collection/atom op costs: *"scripts/perf-all.clj
  has no :jank host entry and the jank runner has no
  perf mode"* (jank.md). Seam benches exist only for
  int32 (230 ns/call native) and crypto.
- **Verdict.** **UNMEASURED** — port in flight; read
  path is the shared in-place code by construction,
  deref is the vendored materializing one by
  construction, and neither has a printed op cost on
  this host yet.

### 3.15 Let-go — vendored :lg (cohost seat: lg)

- **Backing.** **None.** The `:lg` row in hosts.edn
  lists http/crypto/core/completion/encoding/process/
  thread adapters — **no slab-io, no slab, no region
  adapter at all.** Eve's atom/collection substrate is
  not wired on this host.
- **Verdict.** **NO EVE PORT** (for the slab/atom
  layer) — what's missing: a region backend + slab-io
  adapter for let-go, i.e. the same Eve platform port
  the redo named for the standalone line.

### 3.16 ClojureDart — vendored :cljd

- **Backing.** **None**, identically: the `:cljd` row
  has no slab-io/slab/region adapter (core, completion,
  http, crypto, encoding, process, thread/none only).
- **Verdict.** **NO EVE PORT** (slab/atom layer) —
  missing a Dart region backend + slab-io adapter.

### 3.17 ClojureWasm — cohost seat cljw

- No Eve branch, host row, or adapter in either Eve
  line (redo §5.1: no Wasm platform port; Eve's `:clj`
  branch needs Java that isn't under cljw). The
  `:wasm` hits in standalone Eve are an internal
  SAB-as-Wasm-memory view (shared_atom.cljs:2597),
  not a target.
- **Verdict.** **NO EVE PORT** — missing: a Wasm
  platform port (branch + linear-memory region backend
  + atomics story).

### 3.18 ClojureRust — cohost seat cljrs

- No Eve host row anywhere. Not ClojureCLR (§3.8):
  :cljr is .NET; cljrs is a Rust host, and no Rust
  branch/backend exists in either line. Cohost itself
  has no CLI row for the seat yet either (probe).
- **Verdict.** **NO EVE PORT** — missing: a Rust-host
  platform port (branch + region backend).

### 3.19 Phel / PHP — cohost seat phel

- No Eve host row, branch, or adapter in either line;
  PHP was also absent from this box at probe time
  (environment gap, since remedied locally — `php` is
  in `~/.local/bin` — but no Eve port exists regardless).
- **Verdict.** **NO EVE PORT** — missing: a PHP
  platform port (branch + region backend).

## 4. The target table

| # | Target | Line(s) | Backing (in-mem domain) | Collection reads | Atom deref | Headline verdict |
|---|---|---|---|---|---|---|
| 1 | ClojureScript / Node | standalone :cljs + vendored :cljs | SAB (`JsSabRegion`); vendored region/js | CORRECT — in place, get 2.0–2.3 µs | VIOLATION — materializes (96–105 µs @100-map) | **MIXED** |
| 2 | Browser | both | SAB only | CORRECT (by code) | VIOLATION (by code) | **MIXED; standalone costs UNMEASURED** |
| 3 | JVM | standalone :clj + vendored :jvm | heap `byte[]` via `JvmByteBufferRegion`; vendored jvm-varhandle | VIOLATION — in-place in form, disk-priced (~119–134 µs get; capacity wall) | VIOLATION — materializes (~104 µs scalar; 1.78 ms @100-map) | **VIOLATION** |
| 4 | Babashka | standalone :bb + vendored :bb | `BbHeapRegion` / `BbMmapRegion`; vendored jvm-nio-lock | standalone: does not load (defect below); vendored: CORRECT in form | VIOLATION (uniform; bb also materializes set/vec/list at root read) | **Standalone BROKEN/UNMEASURED; vendored MIXED** |
| 5 | nbb | vendored | region/js + slab/module | CORRECT in form | VIOLATION (5.8 ms @1k-map) | **MIXED** |
| 6 | Scittle | vendored | region/js listed, slab layer unrun | — | — | **UNMEASURED** |
| 7 | Cherry | vendored | region/js + slab/module | — | — | **UNMEASURED** |
| 8 | ClojureCLR (:cljr) | vendored | region/clr + slab/heap | CORRECT in form | VIOLATION (4.2 ms @1k) | **MIXED** |
| 9 | Basilisp (:lpy) | vendored | region/ctypes + slab/heap | CORRECT in form, interpreter-priced | VIOLATION — worst cost (636 ms @1k) | **MIXED, worst deref** |
| 10 | Jolt | vendored | region/jolt-ffi + slab/heap | CORRECT in form | VIOLATION (4.7 ms @1k) | **MIXED** |
| 11 | Clojerl (:clje) | vendored | region/beam + slab/heap | CORRECT in form | VIOLATION (7.2 ms @1k) | **MIXED** |
| 12 | Glojure (:glj) | vendored | region/glojure + slab/heap | CORRECT in form | VIOLATION (72.6 ms @1k) | **MIXED** |
| 13 | ValueScript (:vs) | vendored | region/vs + slab/heap | CORRECT in form (by code) | VIOLATION (by code) | **MIXED; costs UNMEASURED** |
| 14 | Jank | vendored, in flight | calloc / MAP_SHARED via region/jank | in-place by construction; unmeasured | materializing by construction; unmeasured | **UNMEASURED** |
| 15 | Let-go (:lg) | vendored row, no substrate | **none — no slab/region adapter** | — | — | **NO EVE PORT** (slab/atom layer) |
| 16 | ClojureDart (:cljd) | vendored row, no substrate | **none — no slab/region adapter** | — | — | **NO EVE PORT** (slab/atom layer) |
| 17 | ClojureWasm (cljw) | cohost seat only | none | — | — | **NO EVE PORT** (Wasm port missing) |
| 18 | ClojureRust (cljrs) | cohost seat only | none | — | — | **NO EVE PORT** (Rust port missing) |
| 19 | Phel (PHP) | cohost seat only | none | — | — | **NO EVE PORT** (PHP port missing) |

## 5. The violations, named

1. **Materializing plain deref — every target with Eve
   atoms (rows 1–14 where measured or by construction).**
   Mechanism: plain deref routes to a materializing
   reader (`cljs-mmap-deref` / `jvm-mmap-deref`,
   standalone atom.cljc:364/850; `plain-deref`, vendored
   atom.cljc:813) that runs `eve->clj` over the whole
   root before returning, by documented design ("slab-
   backed wrappers must not escape: the epoch is
   unpinned on return"). The lazy slab-backed value
   exists only inside bounded pinned regions (`$`,
   `read-pinned`). Per John's rule — lazy root as the
   ordinary read, materialization only at crossings —
   this is disk-atom behavior as the mem path's ordinary
   read, uniformly. It is *priced* per target from
   ~100 µs (cljs, 100-entry root) to 636 ms (basilisp,
   1k-entry root).
2. **JVM collection reads disk-priced (row 3).**
   Mechanism: `JvmSlabCtx` per-call offset decode +
   allocating `-read-bytes` + full-codec leaf decode
   (alloc.cljc:196–290; map.cljc:77), where the same
   algorithm on :cljs pays DataView prices — get
   ~119–134 µs vs 2.0–2.3 µs — plus the heap domain's
   measured capacity wall.
3. **Babashka standalone target broken at load
   (row 4).** Mechanism: `register-header-tag-reader!`
   defined only in serialize.cljc's `:clj` section
   (L163) but called unconditionally by alloc.cljc:607
   in the section bb loads; Eve's own `bb atom-swap`
   example fails on main `c7dc390` with the quoted
   error. (The vendored line's bb is unaffected —
   different tree, green battery.)
4. **Doc contradictions (§2.4):** platform-support.md's
   lazy-deref claim (false in code on both seats it
   names); `JvmHeapRegion`/`JvmMmapRegion` names that
   exist only in docs; Babashka absent from the
   platform doc while present (and currently broken)
   in code.

**What is NOT a violation, for the record:** element
decode at collection leaves (§2.1) — by design on every
target; set membership, which compares stored bytes
without decoding at all; the pinned `$`/`read-pinned`
reads, which are genuinely zero-copy and, on the JVM,
the fastest get in the vendored table (2.1 µs @1k-map).

## 6. Count reconciliation (the declaration's count)

John's "11 or 12" = the vendored hosts README's battery
hosts (11) / battery∪perf union (12). All sources
reconciled (§1): 17 vendored host keys (16 distinct +
bb-minimal variant), 3 standalone code targets (the
same :cljs/:jvm/:bb targets on the other line), 6
platform-doc rows (3 families), 9 cohost seats — of
which 3 (cljw, cljrs, phel) exist in no Eve source.
**Audit rows: 19 targets.** Of those: 1 clean
VIOLATION headline (JVM), 9 MIXED (reads correct, deref
violates), 1 broken-at-load (standalone bb), 3
UNMEASURED (scittle, cherry, jank — plus the standalone
browser's costs), 5 NO EVE PORT (lg, cljd, cljw, cljrs,
phel). **No target is fully CORRECT under the rule as
stated**, because the materializing plain deref is
uniform across both Eve lines; the targets differ in
what it costs and in whether their collection reads are
mem-priced, not in whether deref materializes.

## 7. Declaration

Audit complete, 2026-11-05. The target table is §4;
the violations are named in §5; the count
reconciliation is §6 (19 audited targets; John's 11–12
is the vendored battery/perf set). Headline: collection
reads navigate the byte form in place on every target
that runs Eve's collections — the byte-form rule holds
there, at wildly varying prices (JVM worst, and the
only seat where reads themselves are disk-priced) —
while plain atom deref materializes the whole root on
**every** target, both Eve lines, by documented design,
contradicting Eve's own platform doc; and the
standalone Babashka target currently fails to load at
all, on Eve's own example. Five targets have no Eve
port for the slab/atom layer. One bench was run for
this audit (bb, banked at `dev/target_audit_bb_bench.clj`);
it and Eve's own bb example produced the §3.4 finding
instead of numbers.
