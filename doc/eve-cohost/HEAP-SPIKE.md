# Eve-in-cohost — HEAP SPIKE: the heap Clojure sees, on an Eve atom

> DECLARATION — spike COMPLETE, banked 2026-11-05 on branch
> `muse/eve-cohost-impl` (skeleton @ 2a4410c; full text @
> fcd3c1d; the contention-domain refinement below folded in
> on top — verify with `git log
> origin/muse/eve-cohost-impl`). Exploration only — no
> implementation code. Headline: **the ruling survives as a
> semantic commitment on every seat, and as a substrate claim
> only where Eve's substrate is fast. Taken literally with
> Eve's current JVM implementations as the heap's mechanism,
> it is a real blocker on the JVM seat — measured, not
> argued (§P3). The shape the exploration supports is in §2;
> three questions are genuinely John's (§3).**
>
> POSTSCRIPT (§4): Q-B and Q-C were resolved by John's
> ruling of 2026-11-05 ~10:05 ET — no counterparts
> anywhere; the counterpart-domain language in §2 and
> §P4 is superseded by it. Q-A remains open.
>
> REDO (§5, 2026-11-05): the contaminated parts were
> redone under the corrected premises — §P4 replaced by
> a pointer to §5.1 (probed seat table), §P3's cljs half
> measured and both P3 conclusions redrawn (§5.2),
> §P2's clause rewritten (§5.3), §2's shape redrawn
> (§5.4). Read §5 with §4; P1, P5, P6 stand as written.
>
> RULINGS UNDER TEST — (1) the heap ruling (John,
> 2026-11-05 ~09:45 ET; MEMORY-PLAN §8): the heap Clojure
> sees on cohost is implemented on top of an Eve atom.
> (2) **the contention-domain refinement** (John, 2026-11-05
> ~09:47 ET): "We can use different atoms as well for
> different contention domains if certain things during
> startup and during runtime need separate contention
> domains." The design is therefore **not one runtime atom
> for everything**: multiple Eve atoms, one per contention
> domain, startup-phase and runtime-phase state separated
> where their contention profiles differ. P1 and P5
> evaluate the split as the expected shape, with the
> resident-to-domain mapping in §P5 and §2.4.

## 0. The direction under test

John (2026-11-05 ~09:45 ET; MEMORY-PLAN §8):

> The host heap that Clojure sees on cohost should be
> implemented on top of an Eve atom so that this discrepancy
> between types isn't there.

…followed by: "You're going to have to explore that and see
if there's any problems with that idea."

And the refinement (John, 2026-11-05 ~09:47 ET):

> We can use different atoms as well for different
> contention domains if certain things during startup and
> during runtime need separate contention domains.

So the atom the heap sits on is not singular: one Eve atom
per contention domain, with startup-phase state and
runtime-phase state in separate atoms where their
contention profiles differ. The problem axes below test
the heap ruling *with* the split as the expected shape —
§P1 for what the split does to bootstrap, §P5 for which
runtime residents (locals, analyzer/compiler state, the
var registry) naturally fall into which domain.

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

**The split, at boot (the refinement applied to this
axis).** Terminology first, because two different things
are called "domain": a **contention domain** is one atom —
one CAS root, one writer-side story (contention-domains
§1) — while Eve's `atom-domain` is a slab container that
already holds *many* atoms by slot (`eve/src/eve/atom.cljc`
line 78: slot 0 = the registry atom, slot 1 = the default
atom — separate CAS roots sharing one slab). A split can
therefore be realized two ways: several atoms in one slab
domain (shared allocation, independent arbitration), or
separate slab domains (independent allocation *and*
independent mint/adopt/replay units). Contention only ever
cares about the CAS root; bootstrap cares about the second
granularity, because the slab domain is the unit that gets
minted, adopted, or replayed.

Under the split, boot mints the domains **in phase order,
not all at once**:

1. The **startup domain** is minted in `boot-setup!`'s
   slot, before core.clj's first form — it must be: the
   analyzer/compiler-side state and the var registry are
   written by the very act of loading core (the resident
   mapping is §P5's table). This is the only domain whose
   existence boot's first steps depend on.
2. The **runtime domain** (steady-state residents: the
   thread layer's registries, runtime services) is minted
   at the phase transition — and cohost's boot already has
   the marker in code: `install-thread-layer!`
   (boot.cljc:909), whose docstring is "runs after the
   companions and before the libraries." That fill *is* a
   phase boundary: everything it installs is runtime-phase
   machinery. Alternatively the runtime domain is CAS-minted
   on first steady-state touch, `pm_runtime_atom`-style,
   and never exists at all in a boot that only compiles.

The split **shrinks** P1's two priced residues rather than
adding to them:

- The construction price is paid per domain, but only the
  startup domain's is on boot's critical path, and it is
  the smaller of the two traffic classes; the runtime
  domain's mint is deferred off the boot path entirely.
- The snapshot contract stops being one obligation and
  becomes a natural half plus a non-obligation. A cohost
  snapshot *is* a recording of the startup phase — so its
  replay product is exactly the startup domain
  (mint-by-replay, deterministically, as today), and the
  runtime domain is simply **not in the snapshot**: it is
  per-process state by definition (contention-domains §6:
  the runtime domain is a local affair, never
  network-backed), minted fresh on first touch after load
  or adopted where a seat wants warm state. The sharpest
  sub-problem from the unsplit reading — "how does a
  replay reproduce *the* heap-atom?" — dissolves into
  "replay reproduces the startup domain," which is what
  the snapshot already does for host-side boot state.

**Verdict: non-problem as a paradox; priced cost as
engineering** (domain construction price + the snapshot
replay obligation — the obligation now scoped to the
startup domain only, per the split above).

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
`cohost_object` ↔ `eve_obj` convergence. On every seat,
interop is Eve's own, because the values are Eve's: there
are no counterpart seats (ruling, §4; redo §5.3) — a seat
either runs Eve, and its interop is whatever Eve's types
do behind the shim's class surface, or it waits on the
Eve-side work §5.1 names for it.

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

> **REDO ADDENDUM (2026-11-05, §5.2).** Two sentences
> above are superseded: the bullet's "on the JVM seat,
> the counterpart's cost" and the section's closing
> conclusion ("The JVM seat's realization is therefore
> counterpart, not Eve-as-is"). Under the no-counterparts
> ruling the JVM conclusion reads: **Eve-side work on the
> JVM seat is the path** — the work items are named from
> these measurements in §5.2. The cljs/SAB half of this
> axis, unmeasured here (it rested on Eve's platform
> docs), is measured in §5.2 and its conclusion drawn
> there. The JVM measurements in this section stand
> untouched.

### P4. Per-host reality — REPLACED by the redo (§5.1)

> The original §P4 — its seat table and verdict — is
> replaced in full by **§5.1** (2026-11-05 redo, under
> John's corrected premises). The original table is the
> misleading artifact this redo exists to remove: its
> realization column assigned counterpart domains to four
> seats and read the rest off Eve's docs. §5.1 re-derives
> the table from Eve's platform code, probed seat by
> seat, and its realization column reads only EVE AS-IS /
> EVE-SIDE WORK NAMED / SEAT WAITS. §4's audit records
> the original's disposition; nothing else in this
> document cites the original table.

### P5. Failure modes — the runtime mutating its own heap-atom

- **Domain death.** An in-mem runtime domain is process
  memory: death is process death, everything lost — exactly
  today's host heap. And the precedent forbids the scary
  version outright: "The runtime atom is never
  network-backed. Peers cannot share a heap."
  (contention-domains §6). Distribution lives in user
  atoms, never in the runtime domain. **Non-problem.**
- **Contention — evaluated under the split, which is the
  expected shape (refinement ruling, §0).** The governing
  principle is the model's own: contention is a property
  of *writes*. Reads never contend — a deref pins an epoch
  and takes no CAS (contention-domains §1) — so each
  resident is placed by its **write profile**, and its
  reads follow for free. Read naively (every local a
  transaction on one runtime atom), the heap-atom would be
  the hottest CAS point in the system; under the split,
  no resident's write profile contends with another's:

  | resident | write profile | domain |
  |---|---|---|
  | locals, temporaries, form env & bindings | one thread, scope lifetime | **per-scope local atoms** — not a shared domain at all (Eve's atom law; in cohost the slots are host frames anyway, §P6 — the *values* are domain values) |
  | analyzer/compiler-side state — in cohost's terms: the codemod ctx, the macro table `scan-macros!` builds, the shim's eval/load hook state | bursts: continuous through boot, per-`eval` at runtime; effectively one writer at a time | **startup domain** |
  | var registry — namespace table, interning, var roots | writes concentrated in load episodes (boot, library load, `require`, `def`/`redef`); reads constant, cross-thread, contention-free | **startup domain** (placed by its writes; its hot reads pay nothing) |
  | thread-layer state — unit 1's executor/future/agent/latch registries | steady-state, multi-threaded, written whenever programs spawn work | **runtime domain** |
  | runtime services & program-facing steady-state registries | steady-state | **runtime domain** |

  Two consequences worth naming. First, the unsplit
  reading's one residue — a single registry atom
  serializing every `def` — dissolves: registry writes are
  load episodes, and load episodes already live in the
  startup domain, away from steady-state traffic. (Unit
  1's registry atom works at thread-layer scale today —
  29/29 probe, no contention pathology — and under the
  split it is a *runtime-domain* resident, which is where
  its traffic belongs.) Second, the split buys failure
  isolation for free: a bad swap in one domain cannot tear
  the other's tree (per-atom snapshots; §7's "no fourth
  form" of sharing), and steady state can die or restart
  without touching the startup domain's product.
  The one honest residue: **compile/load episodes at
  runtime** (`eval`, `require`, `redef`) write the startup
  domain while programs run — a startup-shaped burst
  crossing into runtime phase. It introduces no new
  hazard: a program reading a var mid-`redef` pins an
  epoch and sees the old root or the new one (per-atom
  snapshot law), and the serialization itself is parity
  with stock Clojure, whose loads are lock-serialized on
  the host today. Priced: parity, not regression.
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

**Verdict: non-problem under the split, with one priced
item at parity** (runtime compile/load episodes write the
startup domain — the serialization stock Clojure already
has). The failure taxonomy stays the walled world's:
misses, loudly, at the deref that asked
(contention-domains §9).

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

**The precedent on the split itself.** Here the
refinement goes one named step beyond VS's text, and VS
explains why it can. Contention-domains §6 names **one**
runtime domain — compiler/analyzer state and form working
data together — and does not split startup from runtime
inside it. VS doesn't need the phase split because its
boot *is* warm-start adoption: the previous run's runtime
atom is read back whole, so there is no long, distinct
construction phase with a traffic profile of its own.
Cohost's boot is the opposite shape — a minutes-to-seconds
construction episode (codemod, core, companions,
libraries) followed by steady state — and that episode is
exactly what the startup domain quarantines. What VS
*does* supply for the split: §1's definition (a contention
domain is one atom; splitting means more atoms, never
wider ones), §7's pointer-value form (a registry atom "can
name a hundred data atoms without holding a single
foreign subtree" — domains reference each other without
merging), and Eve's own micro-precedent inside one slab:
the registry atom and the default atom are already
separate CAS roots (atom.cljc:78). The split is the
precedent's tools applied at a phase boundary VS happens
not to have.

**Verdict: the precedent carries the ruling** — it
supplies the two load-bearing shapes (mint/adopt at a
well-known root; lax cost-only internals) and honestly
marks the two places cohost's shape differs. On the
domain split it supplies the tools and the definitions;
the startup/runtime cut itself is cohost's, earned by
cohost's boot shape.

## 2. The shape the exploration supports

1. **The ruling as a semantic commitment, per seat.** One
   value world: every value a program can hold is a domain
   value; crossings only at deref/swap; atom kinds as
   metadata (Q5); runtime state *as values* — var roots,
   registries, analyzer/compiler state — in the runtime
   atom domains, split per (4). This layer of the ruling
   has no measured problem anywhere in this spike.
2. **Realization per seat, labeled** (the plan's EVE AS-IS
   / COUNTERPART discipline, applied to the heap itself):
   substrate-backed where Eve's substrate is fast (cljs
   via SAB/shared-atom; jank after the flagship gate);
   a **counterpart domain layer** — cohost-side, the
   `cohost.thread` pattern — on the JVM seat and the
   no-Eve seats. Host-heap-lax internals inside the domain
   on every seat (precedent §6: cost-only).
3. **Minted or adopted, never constructed.** Each domain
   sits at a well-known root and is created by CAS on
   first touch (VS's `pm_runtime_atom`). In phase order:
   the startup domain in `boot-setup!`'s slot; the runtime
   domain at the phase transition (`install-thread-layer!`'s
   boundary) or on first steady-state touch. The snapshot
   path owes a re-mint-or-adopt contract for the startup
   domain only; the runtime domain is never snapshotted
   (P1).
4. **Split by contention domain from the start** (the
   refinement ruling): residents placed by write profile,
   reads following for free (P5's table). **Per-scope
   local atoms** for locals/temporaries. A **startup
   domain** — analyzer/compiler-side state, the var
   registry — written in boot/load episodes and
   write-silent at steady state; it is the snapshot's
   replay product. A **runtime domain** — thread-layer
   registries, runtime services — carrying steady-state
   concurrent traffic. Compile/load episodes at runtime
   write the startup domain, at parity with stock load
   serialization. VS's §6 names one runtime domain; the
   phase cut is cohost's, earned by its boot shape (P6).
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

> **REDO ADDENDUM (2026-11-05, §5.4).** Item (2)'s
> counterpart element is withdrawn (§4) and the shape is
> redrawn without it in §5.4: realization per seat is Eve,
> gated only by the named Eve-side work in §5.1's table.
> Items (1), (3), (4), (5) stand as written.

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

## 4. Postscript — Q-B and Q-C resolved (John, 2026-11-05 ~10:05 ET)

John: "We drop the counterpart idea for everything."

- **Q-B is answered: NO.** Counterpart domains on the
  no-Eve seats are not an acceptable fulfillment of the
  heap ruling. No substitute domain is built for those
  seats; they wait for Eve.
- **Q-C is answered: NO counterpart on the JVM seat.**
  The path is Eve-side work to bring Eve's JVM
  collections to heap-grade performance. §2's realization
  of the JVM heap as a counterpart domain is superseded;
  the JVM seat is NOT-YET until the Eve-side work lands.
- **Superseded by this ruling:** the "counterpart domain"
  language in §2 (shape item 2) and §P4 (the per-seat
  table and its verdict discussion), and the "realized
  per-seat" amendment this spike proposed for MEMORY-PLAN
  units 4/5. Realization is Eve or blocked, never
  substitute. The spike's measurements and findings stand
  unchanged — this postscript records the decision they
  fed; it does not revise the exploration.
- **Q-A is untouched** and remains genuinely open.
- Banked in MEMORY-PLAN §9, which also strikes the
  COUNTERPART category from the plan's mapping table.

### Audit — which verdicts depended on the counterpart category

Added 2026-11-05, at John's audit question. The audit
distinguishes two dependencies that traveled together:
the counterpart category itself, and the probe-era
presumption that some seats lack a shared heap. Factual
record; findings below are the spike's own, re-read.

| Item | Dependence | Disposition under the ruling |
|---|---|---|
| P1 Bootstrap | None. Bootstrap order, phase-order minting, and the snapshot scoped to the startup domain rest on cohost's boot shape and the contention-domain split. | **Stands.** |
| P2 Identity & GC | One clause: "On counterpart seats (P4), interop is whatever the counterpart implements behind the shim's class surface — the same contract cohost already lives by." | **Stands, except that clause — withdrawn.** There are no counterpart seats; interop on a seat is Eve's own or unit 6's convergence. |
| P3 Hot paths | The measurements: none — deref ~104 µs, lookup ~119–134 µs, assoc ~0.5 ms, and the 64 KB coalescing-region wall were measured on the JVM seat against Eve's JVM implementations, independent of any cljs presumption. The conclusion: yes — "The JVM seat's realization is therefore counterpart, not Eve-as-is" follows only if a substitute realization is permitted. | **Measurements stand; the conclusion is withdrawn.** Under the ruling it reads: Eve-side work on the JVM seat is the path — bring Eve's JVM collections to heap-grade performance; the seat is NOT-YET until then. |
| P4 Per-host reality | The seat table's cljs row was correct as written (Eve native, SAB atoms) but unmeasured by this spike, and sequenced last via unit 8; the no-shared-heap presumption lived in the framing around the row, not in the row. The JVM row ("counterpart domain"), the jank row ("counterpart until then"), and the cljw/jolt/lg row ("counterpart domain, or the seat waits") each depended on the category. | **cljs row stands, flagged unmeasured; the counterpart rows are withdrawn** — those seats are Eve or wait. |
| P5 Failure modes | None. Contention is priced by write profile; the resident-to-domain mapping and the domain split make no reference to the category. | **Stands.** |
| P6 VS precedent | None. The comparison (warm-start adoption vs construction boot; the §1/§6/§7 shapes) is independent of the category. | **Stands.** |
| §2 supported shape | Item 2's "counterpart domain layer" element — a cohost-side layer on the JVM seat and the no-Eve seats — is the category applied to the heap itself. | **That element is withdrawn** (recorded in §4 above). The remaining items stand. |

**Source of the presumption.** It entered this work from
the load probe's report (`muse/eve-cohost-probe`,
REPORT.md, conclusions item 3): "On cljs — the one
measured host with no shared heap and no host threads —
7 of 7 cross-thread cases fail identically, before any
closure crosses." The sentence is accurate about the
probe's measurement — cohost's cljs seat has no executor
behind the seam today — and false as a statement about
the seat: Eve's `shared_atom.cljs` over SharedArrayBuffer
is a shared heap on that seat, machinery this spike
itself cited in §P4 as the cljs seat's existing shape,
while the probe's framing continued to organize §P4's
table and P3's conclusion. The probe's measured facts
(7 of 7 failures at the executor seam; closure capture
never reached, never the failure) are unaffected. The
inference from "no executor today" to "no shared heap on
this seat" is what the ruling removes.

## 5. Redo — the contaminated parts, under the corrected premises (2026-11-05)

> Framing is John's rulings of 2026-11-05 ~10:05 ET:
> (1) no counterparts anywhere — every seat's runtime
> atom domain is real Eve or the seat waits on named
> Eve-side work; (2) cljs/node seats have the shared heap
> (workers + SharedArrayBuffer + Eve's shared atoms);
> (3) `eve.atom` the durable kind is not the runtime
> heap's mechanism (P3's measurement, standing). What
> stands unredone: P1, P5, P6, P3's JVM measurements.
> What is redone below: §5.1 P4 (full — the original §P4
> is replaced by a pointer here), §5.2 P3's cljs/SAB half
> (measured) + both halves' conclusions redrawn, §5.3
> P2's clause, §5.4 §2's shape redrawn. Status:
> `HEAP-REDO-STATUS.md` on this branch.

### 5.1 P4 redone — per-host reality, probed from Eve's platform code

**Method.** Eve's own sources, read directly
(`~/src/eve` @ main `c7dc390` — whose HEAD commit is
literally "JVM reunification: shared-memory domain
parity with CLJS (reference implementation)"), not
Eve's docs and not cohost's CLI rows.

**What Eve substrate is, mechanically.** Eve's CLJC
speaks exactly three platform features — `:cljs` (220
reader-conditional sites), `:clj` (55), `:bb` (74), plus
one `:default`. A seat has Eve substrate iff it can run
one of those branches *and* supply the branch's
`IMemRegion` backend (`eve/src/eve/mem.cljc`): the
protocol is one — fixed-size region, i32/i64 load/store,
CAS — and the backends are per-feature:

| feature | region backends in mem.cljc | atomics / wait |
|---|---|---|
| `:cljs` | `JsSabRegion` (SharedArrayBuffer); `NodeMmapRegion`, `LustreMmapRegion` (mmap via the N-API native addon) | `js/Atomics` on SAB; addon futex on mmap |
| `:clj` | `JvmByteBufferRegion`; `LustreJvmMmapRegion` (Panama FFM mmap) | `sun.misc.Unsafe`; `LockSupport` |
| `:bb` | `BbHeapRegion`, `BbMmapRegion` | ByteBuffer/Unsafe via bb's JVM |

So "Eve on a seat," in Eve's own terms, requires three
things: **(i)** a platform branch in Eve's CLJC (or the
seat faithfully presenting an existing feature, Java
classes and all — cohost's seats present Clojure, not
Java), **(ii)** an `IMemRegion` over memory that is
addressable and atomic-capable, **(iii)** i32 CAS +
wait/notify on that memory. Every seat below is read
against those three requirements — that is what
"actually missing, in Eve's terms" means in the table.

**A correction to the original's framing sentence.**
The original §P4 argued a cohosted Eve below the heap
is "exactly circular." That described a mechanism no
working seat uses: Eve is never *cohosted* underneath
cohost. On the JVM and cljs seats Eve arrives as a
library of the host itself, loaded by the host runtime
before and beneath cohost's booted world (unit 3's JVM
hosting already loads Eve's CLJC exactly this way). On
a seat with no Eve branch, the work that ends the wait
is the same kind of arrival — an Eve platform port at
host level — not cohosting, and not a substitute domain.

| cohost seat | Eve substrate today (probed) | realization |
|---|---|---|
| **JVM** | `:clj` branch complete: in-process `JvmByteBufferRegion` + Panama mmap cross-process; domains at CLJS parity per Eve's HEAD commit | **EVE AS-IS** as substrate; **SEAT WAITS** for the heap role on §5.2's named Eve-side perf work |
| **cljs** (self-hosted, Node) | `:cljs` is Eve's home branch and reference implementation: SAB atom domains (`cljs-open-sab-domain!`, atom.cljc:334), `JsSabRegion`, shared atoms across workers, mmap cross-process via the addon | **EVE AS-IS** — measured in §5.2; nothing Eve-side is missing for the heap role; the open work on this seat is cohost-side (unit 2's leaves, unit 8) |
| **jank** | No `:jank` branch in Eve's CLJC on main. Eve-on-jank is Eve-side work in flight on its own line: the J3 seams, with region+slab+typed-view as the ungated flagship | **EVE-SIDE WORK NAMED** — the flagship gate (region + slab + typed-view); the seat waits on it, then on unit 6's `cohost_object` ↔ `eve_obj` convergence |
| **cljw** (ClojureWasm, Zig/Wasm) | No Wasm branch; no `IMemRegion` over Wasm linear memory. cljw's feature set is `{:cljw :clj :default}`, but Eve's `:clj` branch calls Java (Unsafe, Panama, ByteBuffer) and there is no JVM under cljw | **SEAT WAITS** — Eve-side work named: a Wasm platform port (branch + linear-memory region backend + an atomics story) |
| **jolt** (Chez Scheme) | No Scheme-host branch. jolt presents a `:clj`-like Clojure surface over Chez; none of Eve's `:clj` machinery (Unsafe/Panama/ByteBuffer) exists beneath it, and no shared-memory region is exposed to the Clojure layer | **SEAT WAITS** — Eve-side work named: a Chez-host platform port (branch + region backend) |
| **lg** (let-go, Go) | No Go-host branch; the same structural gap as jolt — a Clojure surface over a host with no Eve backend and no exposed shared-memory region | **SEAT WAITS** — Eve-side work named: a Go-host platform port (branch + region backend) |
| **basilisp** (Python) | No Python branch. Additionally this seat's binding constraint today is cohost-side, not Eve's: cohost's own basilisp row does not boot (the load probe's CLI macroexpansion failure) | **SEAT WAITS** — Eve-side work named: a Python platform port; cohost's boot fix lands first regardless |
| **cljrs** (clojurust, Rust) | No Rust-host branch; cohost has no CLI row on this seat yet either | **SEAT WAITS** — Eve-side work named: a Rust-host platform port (branch + region backend) |
| **phel** (PHP) | No PHP branch; PHP is absent on this box (the probe's environment gap) | **SEAT WAITS** — Eve-side work named: a PHP platform port (branch + region backend) |

The pattern under the six waiting seats is uniform,
and it is worth stating once: each host is an
interpreter presenting Clojure, and none exposes to
its Clojure layer the two primitives every Eve backend
is built from — addressable shared memory and atomic
CAS on it. What is missing is never a cohost feature;
it is an Eve platform port, branch + region backend,
in host-native terms. That is a real queue of Eve-side
work — and under the ruling it is the only queue there
is. No seat gets anything else in the meantime.

**Verdict (redo):** the ruling is uniform; seats differ
only in which named Eve-side work gates them. Two seats
have substrate today (cljs outright; JVM as substrate
with its heap role gated on §5.2's perf work), one has
it in flight (jank), six wait on a named Eve platform
port each. The original's verdict — "a contract with
per-seat realizations of different depths" — is
withdrawn: realizations do not differ in depth, only in
whether the Eve-side work has landed.

### 5.2 P3 redone — the cljs/SAB half, measured; both conclusions redrawn

The original P3 never measured the cljs seat; its
"fast seat" claim rested on `platform-support.md`.
Measured now: Eve @ main `c7dc390`, shadow-cljs
**release** build, Node v24.22.1, this box (load ~5,
other lanes active; stock controls in the same
process, same loop shapes; two passes, agreeing).
Bench source banked at `dev/heap_cljs_bench.cljs`;
a dev-mode (`:none`) build of the same bench ran
~1.8× slower across the board — the release numbers
govern.

| op | Eve on cljs/SAB | stock cljs, same runs | Eve on JVM (P3, standing) |
|---|---|---|---|
| atom deref, scalar root | **708–737 ns** | 9.0–9.3 ns | ~104 µs |
| atom swap! inc | **4,347–4,511 ns** | 29–31 ns | ~178 µs |
| map lookup (get) | **1,970–2,335 ns** | 48–52 ns | ~119–134 µs |
| map assoc (per op, 1k build) | **9,340–9,607 ns** | 433–486 ns | ~0.5 ms |
| deref, 100-entry map root (foreign root — materializes) | **96.6–105.0 µs** | — | ~1.78 ms |
| capacity: 10k-entry map build / 1k-element vec build | **both OK** | — | **both die** (`coalesc-alloc!: out of memory`) |

As printed, additionally: `eve-atom after swaps:
100010` (the swap loop is CAS-correct, value exact);
the maproot deref returns a plain
`cljs.core/PersistentHashMap` — the root crossed in
from a foreign (non-domain) context, so deref
materialized it, exactly the crossing semantics P3
describes; a root born inside the domain is returned
as Eve's own lazy slab-backed types
(`platform-support.md`, "Deref Behavior" — that row is
docs, flagged as such; the materializing arm is the
one this bench priced).

**The cljs half, concluded from the numbers.** The
substrate is real and capacity-sound: the two builds
the JVM heap domain dies on complete here, and every
Eve op is 40–146× faster than Eve's own JVM port —
cljs is Eve's fast seat, now by measurement. Against
stock cljs in the same runs, Eve's ops run 20–145×
slower (deref ~79×, swap! ~145×, get ~41–45×, assoc
~20×): the shared-atom path (epoch pin, root protocol,
CAS loop) and slab-IO HAMT access are priced for
cross-worker durability, not for heap-hot loops. So
the cljs seat is **EVE AS-IS with named Eve-side
hot-path work**, not heap-grade as-is: (a) the atom
deref/swap path's per-op protocol cost; (b) HAMT
get/assoc cost through slab IO. Neither needs a new
mechanism — both are cost work on machinery that
exists — and neither is a substitute domain.

**The JVM half, conclusion redrawn.** The standing
measurements (§P3) name the Eve-side work directly:
(1) **deserialize-on-access collection reads** — values
in the JVM slab are serialized bytes on `JvmHeapRegion`
and a lookup decodes; heap-grade reads need the
typed-view shape the cljs seat already has;
(2) **a region growth story** — fixed 1 MB slab classes
and a 64 KB coalescing region, no paging or LRU, where
a program heap builds 10⁵–10⁶ values (VS's tiered L1→L2
→L3 slab is the precedent shape); (3) the materializing
deref of `eve.atom` is the durable kind's correct
semantics and stays — premise (3) of this redo: it is
not the heap's mechanism and is not on the work list.
The JVM seat waits on (1) and (2) for the heap role.

**P3's verdict, redrawn whole:** the blocker was never
"the heap on an Eve atom" — it was one reading (the
durable kind as mechanism) plus two seats' worth of
unfinished Eve-side performance work, now named per
seat from measurements on both. On cljs the work is
hot-path cost; on the JVM it is representation and
growth. Both are Eve's queue.

### 5.3 P2's clause, redone

The counterpart-seats clause in §P2 is rewritten in
place (see §P2, "Interop"): interop on every seat is
Eve's own, because the values are Eve's — a seat runs
Eve and inherits Eve's interop behind the shim's class
surface, or it waits on §5.1's named work. P2's verdict
(priced cost: crossing garbage, by-construction
retention, domain-local identity) is unchanged; none of
its three prices referenced the withdrawn category.

### 5.4 §2's shape, redrawn

1. **The ruling as a semantic commitment, per seat** —
   as §2 (1), unchanged.
2. **Realization per seat: Eve, or the seat waits.**
   Labels are exactly §5.1's: EVE AS-IS (cljs today;
   JVM as substrate), EVE-SIDE WORK NAMED (jank's
   flagship), SEAT WAITS (the JVM's heap role on §5.2's
   two work items; six seats on an Eve platform port
   each). There is no fourth label and no cohost-side
   domain layer on any seat. Host-heap-lax internals
   *inside* Eve's runtime domain remain what they were
   in §2 — the precedent's cost-only laxity (§P6), a
   property of Eve's own domain implementation, not a
   substitute for it.
3. **Minted or adopted, never constructed** — as §2
   (3), unchanged.
4. **Split by contention domain from the start** — as
   §2 (4), unchanged (P5's mapping).
5. **`eve.atom` is not the runtime heap's mechanism** —
   as §2 (5), unchanged.

Amendments to MEMORY-PLAN units 3–8, redrawn: units 4
and 5 realize per seat as that seat's Eve substrate
lands — cljs first (substrate present, §5.2's hot-path
work named), JVM when §5.2's items (1)–(2) land, jank
after the flagship gate; unit 6 keeps its job plus the
snapshot-adoption obligation in (3). No unit delivers,
and no seat receives, a substitute domain at any point.

### 5.5 Declaration

Redo complete, 2026-11-05. P4 is redone in full
(§5.1, probed from Eve's platform code; the original
§P4 is a pointer). P3's cljs/SAB half is measured
(§5.2, release build, two passes) and both P3
conclusions are redrawn. P2's clause is rewritten in
place (§5.3). §2's shape is redrawn (§5.4). P1, P5,
P6 and P3's JVM measurements stand unredone, as
scoped. The counterpart category appears in this
document only as history (§4 and this section's
record of its removal).
