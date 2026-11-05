# Eve-in-cohost — HEAP SPIKE: the heap Clojure sees, on an Eve atom

> DECLARATION — spike COMPLETE, banked 2026-11-05 on branch
> `muse/eve-cohost-impl` (skeleton @ 2a4410c; this full text
> sits on top — verify with `git log
> origin/muse/eve-cohost-impl`). Exploration only — no
> implementation code. Headline: **the ruling survives as a
> semantic commitment on every seat, and as a substrate claim
> only where Eve's substrate is fast. Taken literally with
> Eve's current JVM implementations as the heap's mechanism,
> it is a real blocker on the JVM seat — measured, not
> argued (§P3). The shape the exploration supports is in §2;
> two questions are genuinely John's (§3).**

## 0. The direction under test

John (2026-11-05 ~09:45 ET; MEMORY-PLAN §8):

> The host heap that Clojure sees on cohost should be
> implemented on top of an Eve atom so that this discrepancy
> between types isn't there.

…followed by: "You're going to have to explore that and see
if there's any problems with that idea."

Under the ruling, the heap a cohosted program sees — its
collections, its runtime objects, its locals/analyzer/compiler
state (the Q5 ruling, MEMORY-PLAN §7: these live in the
**default runtime atom domain**, as in VS) — is not the host's
heap with Eve-backed exceptions behind the shim. The heap
itself is an Eve atom: one value world, Eve structures by
construction, no Zone H / Zone E discrepancy to bridge.

Method: the plan and its addenda, unit 1's report, the load
probe's report, Eve's sources (`~/src/eve`), VS's model docs
and kernel (`~/src/atom.broker/vs`), cohost's boot/shim
sources, and five small JVM-seat probes run for this spike
(probe scripts quoted in the appendix; all numbers from a
shared box at load average ~5 on 4 cores with other lanes
active — in-process stock controls are quoted beside every
Eve number, and the claims made are orders of magnitude, not
digits).

## 1. Candidate problems, evidence, verdicts

### P1. Bootstrap — what creates the runtime atom domain?

**The paradox dissolves on inspection of the boot order.**
Cohost's boot (`src/cohost/boot.cljc`) runs:

1. `boot-setup!` (line 866) — creates the codemod ctx, the
   report atom, the boot `state` atom; installs the shim's
   eval/load hooks. All of this is **host-side**: it lives in
   cohost's own namespaces, evaluated by the host's Clojure.
2. The prelude (`policy/runtime-vars`), then `boot-file!`
   over `core.clj`, then the companions.
3. `install-thread-layer!` (line 909) — unit 1's fill, after
   core, before the libraries.

"The heap Clojure sees" is the **booted world's** heap.
Cohost's own machinery is that world's *host*, exactly as the
JVM is cohost's host. So there is no chicken-and-egg for the
domain itself: it is created host-side, in `boot-setup!`'s
slot — one step before the existing thread-layer fill — and
core.clj's first form already evaluates with the domain in
existence. VS's precedent is even cheaper: its runtime atom
is **minted by CAS on first touch** at a fixed root offset
and *adopted* on warm start (`pm_runtime_atom`,
`vs/src/vs/kernel/wasm/program_runtime.vs` — the block at
RUNTIME_ATOM_OFF 160, persisted at the root region's offset
0; the next run maps the previous run's pages and reads the
same atom). Creation-by-CAS-or-adoption, not construction.

**The pre-domain stratum is real but bounded and static:**
cohost's implementation state (boot/shim/adapter/layer code
and their atoms) plus the host machinery that reads and
evaluates the codemod during boot. It never shrinks to zero —
something must mint the domain — but it is the implementor's
heap, not the program's, and the ruling governs the program's.

**Two priced residues:**

- **Domain construction, as Eve's API stands, is not
  CAS-cheap.** `eve.atom/atom-domain` measured **1.1–1.2 s**
  on this box (warm code, second call) and allocates ~6.3 MB
  of fixed regions by default (6 slab classes × 1 MB + 64 KB
  coalescing + root + reader map; `eve/src/eve/atom.cljc`,
  `jvm-open-heap-domain!`). Against a whole current JVM boot
  of ~2.2 s wall (the probe's JVM row, boot + program), that
  is the same order as boot itself. The VS mint/adopt shape
  is the mitigation, and on the jank seat the snapshot can
  adopt rather than rebuild (VS's warm start does exactly
  this). Priced, with the shape named.
- **The snapshot contract.** Cohost's AOT snapshot is a
  *recording* of boot (`snap-begin!`/`snap-end!`,
  boot.cljc:1517/1579: "what boot! does before/after
  core.clj"). Today it replays host-side steps. If boot's
  product is a value inside the domain, the snapshot must
  say how that value is re-minted on replay — replay the
  domain's construction deterministically, or adopt a
  persisted boot domain VS-style. This is the sharpest
  bootstrap sub-problem: a named design obligation for the
  snapshot path, not a paradox.
- Eve is not yet a cohost dependency at all (`deps.edn`:
  only `org.clojure/clojure`). It enters per seat — unit 3's
  JVM hosting (`cohost.memory-eve`, on
  `muse/eve-cohost-impl-u3`) already loads Eve's CLJC over
  an in-memory heap slab ctx with no files, which is the
  entry shape P1 needs.

**Verdict: non-problem as a paradox; priced cost as
engineering** (domain construction price + the snapshot
replay obligation).

### P2. Identity & GC — Eve residents vs host objects in Eve clothing

**Who collects what.** Eve values are never host-GC'd:
their blocks are reclaimed by the writer-side retire walk
at the swap and the epoch flush (`vs/docs/memory-model.md`
§§3–4 of the model; no collector, no scan — the model law
bans both). The host GC sees exactly three things: the
small host stand-ins/wrappers, materialized copies produced
at crossings, and foreign host objects the domain holds.

**GC pressure moves; it does not simply grow.** Program data
churn — the bulk of any Clojure program's allocation —
leaves the host heap entirely, which relieves the host
collector of the program. The new pressures are two:

- **Crossing garbage.** A deref that materializes produces a
  fresh host tree per call (P3: a 100-entry root materializes
  in ~1.78 ms, and the copy is host garbage immediately).
  Hot crossing loops manufacture host garbage at the crossing
  rate. This is the model's own rule, not an accident:
  contention-domains §5 — plain deref materializes
  recursively; `$` is the bounded-navigation counterpart.
- **By-construction retention of foreign payloads.** A
  closure or host object stored as a domain value is held
  *by reference* in the wrapper's host seat (MEMORY-PLAN §1).
  Eve's retire discipline frees blocks; it knows nothing of
  host reachability, and the model law bans the liveness
  scan that would teach it. So what the domain holds, lives
  — for exactly as long as the domain value lives. A program
  that treats a domain atom as an unbounded cache gets slab
  growth on one side and pinned host objects on the other.
  There is no machinery-shaped fix the law allows; the
  answer is that retention is a programmer-visible property
  of storing, as it is for VS atoms today.

**Identity.** `identical?` survives exactly where there is
one object: host objects, and domain-resident wrappers
(stable per root — the assumption unit 3's stand-in design
already makes). It fails across materialization (every
`eve.atom` deref returns a *fresh* plain-Clojure tree —
`d/eve->clj`, atom.cljc:850) and across a swap round-trip
of a foreign value (serialized in, materialized out: `=`,
never `identical?`). So identity becomes a **domain-local**
notion. Code that keys by identity across crossings —
identity-hash-maps of values read out of atoms — changes
behavior silently. This is unit 1's tagged-map distance
(equality ≠ identity) generalized from layer values to all
values, and it must be a named, documented distance, in the
same ledger style as unit 1's remainders.

**Interop.** On the JVM seat, Eve collections are real host
objects implementing the host interfaces (unit 3's starting
state: bare Eve values already satisfy host `=`/`hash`
against their host twins), so a Java method wanting a `Map`
gets one. On jank, interop waits on unit 6's
`cohost_object` ↔ `eve_obj` convergence. On counterpart
seats (P4), interop is whatever the counterpart implements
behind the shim's class surface — the same contract cohost
already lives by.

**Verdict: priced cost.** No blocker. The prices —
crossing garbage, by-construction retention, domain-local
identity — are structural, nameable, and none is fixable by
machinery the model law permits; they belong in the plan's
distance ledger.

### P3. Hot paths — where the per-op cost lands

This is where the spike found its one hard edge. Three
measurements, one structural fact:

**`eve.atom` is a cross-process durable atom, not a heap
primitive.** Its deref (atom.cljc:850-864) refreshes slab
regions, pins the thread's epoch, reads the root, and
**fully materializes the value to plain Clojure data**
(`d/eve->clj`) before unpinning — its own docstring: "Eve
values must not escape into the heap: the epoch is unpinned
on return, so any slab-backed wrapper would dangle."
Measured on this seat (warm passes, in-process controls):

| op | Eve (eve.atom / eve collections) | stock control, same process |
|---|---|---|
| atom deref, scalar root | **~104 µs/op** | 45 ns/op (loaded box); 2.72 ns on xbench's cleaner run |
| atom swap! inc | **~178 µs/op** | 149 ns/op |
| map lookup (.valAt / get) | **~119–134 µs/op** | ~21 ns/op (stock nth, same run) |
| deref, 100-entry plain-map root | **~1.78 ms/op** | — (materialization: scales with value size) |
| swap! on a foreign (plain Clojure) root | **~199 µs/op** | 460 ns/op (stock swap! assoc, 100-map) |
| map assoc, build phase | **~0.5–5 ms per assoc** | 10k-entry build: ~ms total |

**Eve's JVM collections deserialize on access.** Values in
the slab are serialized bytes (platform-support.md: JVM
in-process backing is `JvmHeapRegion`, byte[] + Unsafe);
a lookup decodes. That is the persistence-first JVM port's
shape — Eve's fast seats are cljs/SAB and (in flight) jank,
where the same ops are typed-view reads.

**Capacity is a wall, not a slope.** The in-memory heap
domain's coalescing region is 64 KB: building a 10k-entry
map *and* a 1k-element vec in it both die
`coalesc-alloc!: out of memory`. The convenience slab ctx
(`default-sio`) lacks the coalescing class entirely — assoc
dies `ArrayIndexOutOfBoundsException` at alloc.cljc:400
(class 6 of 6). Slab classes are fixed 1 MB regions set at
domain creation; the JVM-seat substrate has **no growth,
paging, or LRU story** (contrast VS's tiered-LRU slab:
L1 heap → L2 SAB → L3 disk, atom.broker AGENTS.md). A
program heap — core.clj's own boot builds ~10⁵–10⁶
collection values — does not fit in, and could not be
priced into, fixed 64 KB–6 MB regions.

**Consequence, stated plainly:** if "the heap is an Eve
atom" means *route the existing `eve.atom` / Eve JVM
collection implementations under every program operation*,
then on the JVM seat: boot itself becomes minutes-scale
(measured ~525 ms per 1,000 assocs ⇒ core boot's collection
traffic is 2–3 orders past feasible), deref is ~10⁵ ns
where the heap needs ~10¹, and ordinary programs overflow
the domain. **That reading is a real blocker, and it is
dead on measurement, not on taste.**

**The shape that makes the common case free already exists
— it is the precedent's.** Contention-domains §5–§6: a
transaction's own data lives in the runtime domain;
materialization happens at *crossings* (a deref into another
domain, a store into another atom), never on intra-domain
reads. Values born in the runtime domain are read in place,
as Eve values; locals are the domain's frame data, not an
atom deref per access. Under that shape, the per-op price
of the heap ruling is:

- intra-domain collection ops: the seat's Eve-value op cost
  (typed-view reads where the substrate is native; on the
  JVM seat, the counterpart's cost — see below);
- program atom deref/swap!: the shim-crossing price the
  xbench already measured for the wrapper shape —
  **+3.10 ns deref, +3.52 ns swap!, reset! at parity** —
  i.e. one interface dispatch + one layer call, NOT
  eve.atom's materializing path;
- genuine domain crossings: materialization, priced per
  value size (the 1.78 ms / 100-entry figure is the honest
  shape of that price — it is why `$` exists).

**The JVM seat's realization is therefore counterpart, not
Eve-as-is** — a domain layer in cohost (the `cohost.thread`
pattern: one portable layer, per-host leaves) whose values
meet Eve's contracts, exactly as unit 3's identity layer
already treats them. Eve's JVM collections remain what they
are: the persistence-shaped port, consumed by unit 5/7's
disk/net atom kinds where their price is the point.

**Verdict: real blocker for the Eve-as-is reading on the
JVM seat; priced cost for the domain reading** (crossing
prices above; intra-domain cost is per-seat realization
cost). This is the spike's headline finding.

### P4. Per-host reality — which seats can host the heap today?

Eve's own platform table (`eve/doc/platform-support.md`):
full support on Node/cljs and JVM 21+; browser SAB-only;
macOS degraded waits. Eve exists as a library on JVM (:clj),
cljs/Node (its home; `shared_atom.cljs` is the heap-less
seat's existing shape), bb (not a cohost seat), and jank
(Eve-on-jank in flight — 2 of 8 J3 seams gated; the
region+slab+typed-view flagship has not gated).

Cohost's seats, against that:

| cohost seat | Eve substrate today | heap ruling realization |
|---|---|---|
| JVM | Eve :clj present, persistence-priced (P3) | **counterpart domain** (measured necessity) |
| cljs | Eve native (SAB atoms) | Eve-backed, via unit 8's path |
| jank | arriving — flagship gate pending | Eve-backed after unit 6; counterpart until then |
| cljw, jolt, lg | none | counterpart domain, or the seat waits |
| basilisp, cljrs, phel | none — and cohost itself has no working CLI row there today (probe report) | not their binding constraint |

On the no-Eve seats, Eve cannot be *cohosted* into the
substrate role: a cohosted Eve would run on the very heap it
is supposed to be — the circularity is exact. The domain
there can only be a **counterpart**: cohost-side layer code
implementing the domain contract (one world, per-atom
snapshots, deref/swap crossings, kind metadata) over host
structures, losing slab residence and cross-instance
sharing on that seat, keeping the semantics. The plan's
label discipline already has the vocabulary: those seats'
heaps are COUNTERPART, not EVE AS-IS, and Q1's default-on
ruling reads per-seat with the realization labeled.

**Verdict: priced cost / sequencing fact.** The ruling is
a contract with per-seat realizations of different depths.
Not a blocker to the design; a blocker to ever *claiming*
a uniform Eve-backed heap.

### P5. Failure modes — the runtime mutating its own heap-atom

- **Domain death.** An in-mem runtime domain is process
  memory: death is process death, everything lost — exactly
  today's host heap. And the precedent forbids the scary
  version outright: "The runtime atom is never
  network-backed. Peers cannot share a heap."
  (contention-domains §6). Distribution lives in user
  atoms, never in the runtime domain. **Non-problem.**
- **Contention.** Read naively — every local a domain
  transaction — the runtime domain would be the hottest
  CAS point in the system. The shape in P3 removes the
  naive reading: intra-domain data is read in place; only
  genuine state changes swap. Those are the runtime's own
  writes: var-root changes and registry updates. Unit 1's
  registry atom already works this way at thread-layer
  scale (29/29 probe; no contention pathology measured).
  The one named residue: a single registry atom serializes
  `def`/`redef` through one CAS — boot is single-threaded
  anyway, and analyzer/compiler state is boot-phase state,
  quiescent while programs run. If a profile ever shows it,
  the model's own tool is the answer: **split contention
  domains** (frame/temporary domains per scope vs the
  shared registry domain — the Q5 ruling's locals already
  live in per-scope local atoms in Eve's law). Priced:
  acceptable as plotted, with the split available.
- **Snapshot semantics.** Per-atom snapshots are the law; a
  var root changing under a running fn is stock Clojure
  behavior, not a new hazard. The genuinely new surface is
  narrower: the domain's *substrate bookkeeping* must never
  become program-visible (the model-census rule the plan
  cites — the descriptor holds substrate bookkeeping only;
  program meta rides the wrapper), and the AOT snapshot
  obligation from P1 (re-mint or adopt the boot domain on
  replay). Both are discipline items with named owners,
  not failure modes discovered in the design.
- Eve's swap machinery itself is contention-aware where it
  matters (jittered CAS backoff, OBJ-4 in atom.cljc;
  time-throttled retire flush, OBJ-1) — for the disk/net
  atom kinds that use it, not for the runtime domain.

**Verdict: non-problem, with one priced item** (registry
contention; mitigated by domain splitting). The failure
taxonomy stays the walled world's: misses, loudly, at the
deref that asked (contention-domains §9).

### P6. The VS precedent — what VS already does

VS is the working example, and it supports the ruling —
with two differences that matter for cohost.

**What VS does.** The runtime domain's atom holds the
runtime itself: compiler and analyzer state, each form's
environment, bindings, and temporaries
(contention-domains §6). In the kernel it is one atom at a
fixed root offset, minted by CAS on first touch, adopted
across warm starts (`pm_runtime_atom`, quoted in P1).
Its internals **may be lax** — heap-like mechanics for form
working data — under one absolute constraint: **laxity is
cost-only**; no observable behavior may depend on it. And
the §6 asymmetry, which is the sentence this whole spike
turns on:

> Eve-the-library runs on a host (the JVM) that provides a
> managed heap and a collector; materialization there
> targets the heap and the host cleans up. VS is its own
> host — there is no heap underneath — so the runtime
> domain is where heap-like economics must live if they
> live anywhere.

Cohost stands in **Eve-the-library's position on every
seat**: there is always a host runtime underneath it. So
cohost's runtime domain may lawfully realize its lax
internals on the host heap — which is precisely what a
counterpart domain (P4) is, and why P3's verdict is
"counterpart on the JVM seat" rather than "impossible on
the JVM seat."

**Where cohost differs from VS, materially:**

1. **Snapshot: replay vs adoption.** VS warm-starts by
   *adopting* the previous run's slab. Cohost's snapshot is
   a *replay* of boot events (P1). The domain's boot state
   needs a replay contract — or the jank snapshot grows an
   adopt path for the domain, following VS. Open design
   obligation, named in P1.
2. **Locals are host frames in cohost.** VS's locals are
   compiler-managed frame data inside the domain. Cohost
   compiles booted code to *host* code through the codemod;
   a booted program's local slots are host frames (JVM
   locals, jank C++ locals) on every seat, and no ruling
   changes that without changing what cohost is. The Q5
   sentence — "locals … live in the runtime atom domain" —
   is literally true in VS and semantically true in
   cohost: the *values* locals hold are domain values; the
   slots are host machinery. Eve-the-library on the JVM
   lives with exactly this split today.

**Verdict: the precedent carries the ruling** — it
supplies the two load-bearing shapes (mint/adopt at a
well-known root; lax cost-only internals) and honestly
marks the two places cohost's shape differs.

## 2. The shape the exploration supports

1. **The ruling as a semantic commitment, per seat.** One
   value world: every value a program can hold is a domain
   value; crossings only at deref/swap; atom kinds as
   metadata (Q5); runtime state *as values* — var roots,
   registries, analyzer/compiler state — in the default
   runtime atom domain. This layer of the ruling has no
   measured problem anywhere in this spike.
2. **Realization per seat, labeled** (the plan's EVE AS-IS
   / COUNTERPART discipline, applied to the heap itself):
   substrate-backed where Eve's substrate is fast (cljs
   via SAB/shared-atom; jank after the flagship gate);
   a **counterpart domain layer** — cohost-side, the
   `cohost.thread` pattern — on the JVM seat and the
   no-Eve seats. Host-heap-lax internals inside the domain
   on every seat (precedent §6: cost-only).
3. **Minted or adopted, never constructed.** The runtime
   domain sits at a well-known root and is created by CAS
   on first touch (VS's `pm_runtime_atom`), in
   `boot-setup!`'s slot; the snapshot path owes it a
   re-mint-or-adopt contract (P1).
4. **Split by contention class from the start:** per-scope
   frame/temporary domains vs the shared runtime registry
   domain — the model's own discipline, cheap to adopt
   before the traffic exists (P5). Consistent with John's
   standing direction that different atoms serve different
   contention domains, startup-phase and runtime-phase
   state in separate domains where their profiles differ.
5. **`eve.atom` is not the runtime heap's mechanism.** It
   is the durable/cross-process atom — unit 5/7's disk/net
   *kind* machinery — and its materializing deref is
   correct for that job. The runtime domain's atom ops are
   the wrapper-priced ones the xbench measured
   (+3.1/+3.5 ns). Confusing these two atoms is the one
   reading of the ruling that measurement kills (P3);
   keeping them distinct is the whole ballgame.

Under this shape, MEMORY-PLAN units 3–8 survive with two
amendments: unit 4's collections and unit 5's atoms are
realized per-seat under (2), and unit 6's job grows by the
snapshot-adoption obligation in (3).

## 3. Questions that are genuinely John's

**Q-A. The scope of "runtime objects."** In cohost,
namespaces and vars-as-containers are *host structures* on
every seat (`host/ensure-bare-ns!`, `host/intern!` are
adapter leaves over host namespace machinery) — they are
how one stock core.clj runs on 8 hosts at all. The bounded
reading of the ruling — the domain holds *values* (var
roots, collections, runtime data), containers stay host
structures whose contents are domain values — is
buildable under §2 and matches unit 5's plot (Var roots in
the layer's registry atom). The literal reading — the
containers themselves are domain residents — re-platforms
cohost's central mechanism. Which is meant?

**Q-B. Counterpart domains on the no-Eve seats.** On
cljw/jolt/lg (and, when they boot, basilisp/cljrs/phel)
there is no Eve substrate and cannot be one below the heap
(P4). Is a counterpart domain — same semantics, host
realization, labeled COUNTERPART — an acceptable
fulfillment of the ruling on those seats, or do those
seats wait for Eve?

**Q-C. The JVM seat's fork.** Eve exists on the JVM but
its collections/atom are persistence-priced (P3: ~10⁵ ns
ops, fixed small capacities). §2 realizes the JVM heap as
a counterpart domain. The alternative is Eve-side work:
bring Eve's JVM collections to heap-grade performance.
That is a real resource fork — it spends Eve-lane effort
to make one seat AS-IS — and it is not this effort's to
take silently.

---

## Appendix — probe record

Probes run 2026-11-05 on this box (load avg ~5.2, 4 cores,
other lanes active), JVM seat, OpenJDK 21, from `~/src/eve`
with Eve's `:jvm-test` JVM opts (`--add-opens`,
`--enable-native-access`). Each bench ran two full passes
and reported the second; stock controls ran in the same
process with the same loop shape. Scripts:
`/tmp/heap_probe{,2,3,4,5}.clj` (ephemeral; the numbers
they printed are quoted in §P3 and here).

- Domain creation: `eve.atom/atom-domain` — 1,200.8 ms
  (first call, includes Eve stack load), 1,111.2 ms (warm).
- `eve.atom` heap atom: deref 128,620 → 103,702 ns/op
  (passes 1/2); swap! inc 220,212 → 177,608 ns/op.
  Controls: clojure atom deref 45.26 ns/op, swap! 148.95
  ns/op (pass 2, same box); xbench clean-box stock deref
  2.72 ns/op, swap! 16.53 ns/op.
- Eve map in a heap domain (domain's own sio): assoc build
  100 → 478.7 ms, 500 → 708.4 ms, 1000 → 525.4 ms,
  2000 → 1,439.7 ms; get .valAt 134,025 ns/op, core get
  119,366–124,962 ns/op. (First builds include first-use
  costs; the ~0.5 ms/assoc figure is the warm 1k build.)
  Stock controls same process: vec nth 21.54 ns/op.
- Capacity failures, verbatim: 10k-entry map build and
  1k-element vec build in the heap domain —
  `coalesc-alloc!: out of memory` (coalesc.cljc:294);
  10k-entry map via convenience ctx —
  `ArrayIndexOutOfBoundsException: Index 6 out of bounds
  for length 6` at `JvmSlabCtx/_sio_alloc_BANG_`
  (alloc.cljc:400).
- Foreign root: eve-atom deref of a plain 100-entry map
  1,778,731 ns/op; swap! assoc on it 198,616 ns/op;
  stock swap! assoc control 460.12 ns/op.

*Sources: MEMORY-PLAN.md + §§7–8 addenda (this branch);
UNIT1-REPORT.md (this branch); the load probe REPORT.md
(`muse/eve-cohost-probe` @ 1829658);
XBENCH-REPORT.md (`muse/eve-cohost-xbench` @ a48293e);
UNIT3-STATUS.md (`muse/eve-cohost-impl-u3` @ e93971d);
`src/cohost/boot.cljc`, `src/cohost/shim.cljc`, `deps.edn`
(this repo); `eve/src/eve/atom.cljc`, `eve/src/eve/map.cljc`,
`eve/src/eve/deftype_proto/alloc.cljc`,
`eve/doc/platform-support.md` (~/src/eve);
`vs/docs/contention-domains.md`, `vs/docs/memory-model.md`,
`vs/src/vs/kernel/wasm/program_runtime.vs`,
`AGENTS.md` (~/src/atom.broker).*
