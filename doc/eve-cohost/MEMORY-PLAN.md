# Eve-in-cohost — MEMORY-MODEL PLAN

> DECLARATION — BANKED 2026-11-05 on branch
> `muse/eve-cohost-impl`, content tip `1ae3a96` (this header-fix
> commit sits directly on top of it; verify with
> `git log origin/muse/eve-cohost-impl`). Planning only — no
> implementation code. One-line unit summaries:
> - Unit 3 — Seam identity: equality + hasheq for Eve-backed values.
> - Unit 4 — Eve-backed persistent collections behind the shim.
> - Unit 5 — Atom behind the seam onto Eve atoms; Ref/Var distances.
> - Unit 6 — Slab residence on the jank seat (flagship-dependent).
> - Unit 7 — Tiered atoms (mem → disk → net), cross-instance sharing.
> - Unit 8 — Shared-heap illusion on heap-less hosts (cljs test).

## 0. What this plan is

Units 1–2 put Eve's **thread layer** behind cohost's Java shim:
stock Clojure calls into the Java implementations of its threading
surface, the shim occupies that seat, and Eve sits behind it —
library code unchanged. This plan plots the second half of the
same directed effort (John, Nov 4 2026): **Eve's memory model
behind the shim's data-structure surface**, under the same rule,
stated by John on 2026-11-05 ~09:20 ET:

> The closure seam is: Clojure calls into the Java implementations
> of Clojure's data structures. Fall along that seam.

So Eve's atoms and persistent collections enter **behind**
`clojure.lang.Atom`, `clojure.lang.PersistentVector`, and the rest
of the shim's class surface — in the seat the Java implementations
occupy — never as a new cohost-facing API that library code must
learn. A cohosted program keeps writing `(atom x)`, `(swap! a f)`,
`(conj v x)`, `(= a b)`, `(hash m)`; what changes is which
implementation answers from behind the seam.

The payoff, in John's terms: cohost gains a uniform memory model
on every host; an atom can be a **disk atom** or a **network atom**
(developer-chosen kinds, §5), so two cohost instances — on
different hosts, including heap-less ones — share memory through
Eve's atoms instead of a marshalling scheme built beside the
language.

This is a plan, not an implementation. Every unit names its gate
in terms of runners that already exist. Nothing in this plan is
claimed built.

### Standing facts the plan is built on (not re-litigated)

- **The seam shape works.** Unit 1 proved the pattern end-to-end:
  adapter-contract leaves with nil defaults (`cohost.adapter`),
  one portable layer (`cohost.thread`), shim entries dispatching
  to the layer, a boot fill interning the layer's impls under the
  names the host core left uninterned (`install-thread-layer!`,
  `policy/thread-names`, the `:host-lacks` boot opt as the
  exercise path). The memory units reuse this exact shape:
  memory leaves in the adapter contract, a value layer in
  `cohost.memory` (working name), shim class entries dispatching
  to it, a boot fill / policy opt exercising it per seat.
- **The probe measured where library code actually touches the
  seam** (`muse/eve-cohost-probe`, REPORT.md @ 1829658): 0 of the
  12 stock libraries call `future`/`agent`/`pmap` in their own
  code, and every threading failure routed through three shim
  entries. The memory analogue is what unit 3–4's probes must
  measure for collections: which shim classes the libraries'
  values actually flow through (the shim already answers this
  statically for most of them — its creators delegate to host
  constructors today, §2).
- **Eve's model law binds every design below** (John, standing):
  atoms all the way down — state lives in atoms, temporaries in
  the scope's local atom; **no journals, no liveness scans, no
  header counters, no arenas**. Value blocks are write-once;
  reference types are wrappers with their own CAS words pointing
  only at immutable values; snapshots are per-atom, never a
  cross-atom global instant. Any unit whose design seems to need
  a banned mechanism stops and reports instead of bolting it on.
- **Atom kinds are developer-chosen** (John, Oct 19 2026):
  tiers mem → disk → net, each an LRU for the level below, the
  bottom level owns the root pointer; a network atom already
  contains its disk LRU. No automatic availability-driven
  upgrade or fallback; downgrading net → disk is not supported.
  Joining a disk atom is OS-resolved; joining a network atom is
  resolved by the atom broker's signaling server.
- **Hashing: the mapping owed is semantic, never numeric**
  (John, Oct 24 2026 + Nov 4 2026). Eve's identity is a 256-bit
  SHA-256 Merkle digest (in the block's ExtCID); Clojure's
  `hasheq` is Murmur-based and Eve does not owe it numeric
  agreement. What the seam owes is the *contract*: two values
  that are `=` must have equal `hasheq`, on every host, whichever
  side of the seam they were built on. Unit 3 is that contract.
- **The two projects converged on one value shape** (transfer
  analysis, 2026-11-04): cohost's jank adapter is one tagged C++
  object (`cohost_object` {descriptor, methods, fields, extmap,
  meta}); Eve's jank completion is one tagged C++ object
  (`eve_host::eve_obj` {tag, fields}) — DJ-2's ruling after
  measuring that jank's `(type x)` answers `opaque_box` for every
  user struct. On seats without a C++ object (the JVM seat first),
  cohost's stand-in idiom is the tagged map (unit 1's layer values
  are tagged maps, equality = map equality — a named distance
  unit 3 must retire for Eve-backed values).

## 1. The central tension, and how the seam absorbs it

Cohost's standing fact: **cohosted values ARE host values — no
marshalling.** Eve's values are slab blocks: write-once,
Merkle-identified, resident in mapped regions. A plan that waved
either fact away would be plotting a different project. The seam
absorbs the tension by dividing the value world into three zones,
with the boundaries at exactly the places the shim already is:

**Zone H — host-native, forever.** Scalars (numbers, strings,
keywords, symbols, chars), host objects (a Java object, a C++
object, a closure, a JS value), and any collection a program
builds that never needs Eve's properties. These stay what they
are today: the host's own values, created by the host's own
constructors through the shim's existing delegation. Eve never
sees them, nothing marshals them, and no unit below touches
them. Most values in most programs live here and always will.

**Zone E — Eve-backed, behind the shim classes.** A value whose
shim class (`clojure.lang.Atom`, `…PersistentVector`, …) is
answered by the Eve-backed implementation: the shim stand-in
(the tagged map on CLJC seats; the shared tagged C++ object on
jank) holds a **wrapper** — Eve's reference-type shape, a CAS
word pointing at an immutable root — and every operation the
class contract names dispatches through the value layer to
Eve's implementation behind it. From above the seam the value
is indistinguishable in *surface*: it prints, seqs, counts,
compares and hashes by the class contract. From below, its root
is an Eve value: persistent, Merkle-identified once resident
(unit 6), shareable across instances once tiered (unit 7).

**Zone X — the crossing, explicit and narrow.** Values cross
between H and E only at named points: construction through a
shim creator while the Eve-backed fill is in force (unit 4),
`reset!`/`swap!` publishing a value into an Eve-backed atom
(unit 5), and `deref` returning the current root (which may
itself be a Zone-H value an atom was reset to — an Eve atom
holding a host value *by reference in the wrapper's host seat*
is legal in the mem tier; it is precisely what cannot be
promoted to disk/net residence, §5 unit 7). There is **no
implicit marshalling**: nothing walks an arbitrary host object
graph converting it. A Zone-H value that cannot be represented
in Eve (a closure, a live host object) stored into a Zone-E
*collection* is the hard case — it is open question Q3, because
each answer costs something different.

**What makes the two zones one world for library code is unit
3**: `=` and `hasheq` are seam-level contracts, not
representation properties. A Zone-H vector and a Zone-E vector
with the same elements are the same value to every library,
or the whole plot fails its transparency test — the same test
the probe set for threading ("the JVM's values, with library
code unchanged"), applied to data.

**Labeled distances the zones force** (named here, owned by
the units noted):

- D1. Zone-E collection operations pay the wrapper dispatch
  Zone-H operations don't (the seam's standing stand-in cost;
  measured per unit, never assumed — unit 4's gate includes the
  battery's own timings discipline, and any native-speed claim
  waits for unit 6's slab numbers).
- D2. Identity equality (`identical?`) never crosses zones: a
  Zone-E value is a stand-in, not the root block. Unit 1 already
  carries this distance for layer values; unit 3 keeps it and
  says so in the mapping table.
- D3. A Zone-H value inside a Zone-E atom's mem tier is shared
  by reference within the process only; disk/net tiers require
  Eve-representable roots (unit 7 states the failure shape:
  a named seam error at promotion/creation time, never a
  silent copy).
- D4. Sorted collections have no landed Eve counterpart
  (§3 table) — Zone E has a hole where `PersistentTreeMap`/
  `PersistentTreeSet` sit, until Q4 is answered.

## 2. Where the shim surface stands today (measured, not recalled)

From `src/cohost/shim.cljc` on this branch: the shim declares
the full class table stock core branches on
(`PersistentVector`, `PersistentArrayMap`, `PersistentHashMap`,
`PersistentHashSet`, `PersistentList`, `PersistentQueue`,
`Atom` with its `IAtom`/`IAtom2`/`IRef` ancestry, the transient
interfaces, the seq classes). Its **creators delegate to the
host**: `PersistentHashMap-create` is `(apply hash-map …)`,
`PersistentArrayMap-create` is `(into {} m)`,
`LazilyPersistentVector-create` is `(vec coll)` — i.e. today's
collections are Zone-H by construction, and the shim's class
answers are *predicates over host values* (`vector?`, `set?`,
`h-atom?` in the instance-predicate table). `new-Atom` is
`host/atom*`; `new-Ref`, on hosts without refs (basilisp, cljs),
already falls back to `host/atom*` — a standing, quiet
distance (§3, Ref row). Unit 1 added the first behind-the-seam
implementations on this surface (Thread/Executors/CountDownLatch/
Agent statics dispatching to `cohost.thread`); the memory units
extend the same move from the concurrency classes to the data
classes.

On the Eve side, what exists to sit behind the seam: Eve's own
persistent collections as CLJC (`eve/src/eve/map.cljc`,
`vec.cljc`, `set.cljc`, `list.cljc`, HAMT utilities) and
`eve/src/eve/atom.cljc`, written against Eve's substrate; the
slab substrate itself (`slab/heap` root, `slab-io/protocol`
adapter — conformance green on jank: 4 tests / 51 assertions in
the eve-jank lane's step-2/4 rows, 2026-11-05); `region/jank`
(`JankRegion`: calloc or file `MAP_SHARED`, `__atomic` builtins,
futex); and `shared_atom.cljs`, Eve's existing answer on the
heap-less seat. The Eve-on-jank J3 seams gated so far: int32
(3.5×) and crypto (GATE PASS 2026-11-05 @ c73ad6b). The flagship
— region + slab + typed-view as one unit — has **not** gated
yet; §5 states exactly which units wait for it.

## 3. Mapping table — shim data-structure surface → Eve

Labels: **EVE AS-IS** = Eve's own implementation sits behind the
seam unchanged · **COUNTERPART** = a cohost-side implementation
built to Eve's design/contracts (the `cohost.thread` pattern) ·
**ABSENT** = no Eve counterpart exists; the surface stays
host-backed (or unfilled), and the distance is named.

| Shim surface (clojure.lang) | Eve counterpart | Label | Unit |
|---|---|---|---|
| `Atom` (IAtom/IAtom2, deref/swap!/reset!/CAS, watches, validators) | Eve atom: wrapper with its own CAS word over an immutable root; per-atom snapshots; kinds mem/disk/net | **EVE AS-IS** (mem tier first; kinds in unit 7) | 5, 7 |
| `Ref` (IRef, dosync/STM) | None — Eve has no STM, and its model law (per-atom snapshots, no cross-atom instant) is STM's contrary | **ABSENT** — today the shim quietly falls back to an atom on ref-less hosts; disposition is Q2 | 5 (plot), Q2 |
| `Var` (roots, thread bindings) | No Var in Eve; conveyance half already exists (unit 1's `:thread-bindings` leaves + core's binding-conveyor, probe-proven = 3 on JVM and jank) | **COUNTERPART** — Var root as a slot in the layer's registry atom; bindings already ride unit 1 | 5 |
| `Agent` | Eve thread layer (unit 1) | **COUNTERPART** — done | 1 |
| `PersistentVector` | Eve vec (persistent vector over Eve's HAMT/block substrate) | **EVE AS-IS** candidate — gated by unit 4's battery-with-fill run | 4 |
| `PersistentHashMap` | Eve map (HAMT; slab-internal key hash is Eve's own — the seam's `hasheq` is unit 3's separate contract) | **EVE AS-IS** candidate | 4 |
| `PersistentArrayMap` | No separate small-map representation in Eve; Eve map covers the semantics | **COUNTERPART** — layer answers the class with Eve map; the ≤16-pair array-map perf shape is a labeled distance | 4 |
| `PersistentHashSet` | Eve set | **EVE AS-IS** candidate | 4 |
| `PersistentTreeMap` / `PersistentTreeSet` (sorted) | No landed Eve sorted collection (the tonsky persistent-sorted-set spike was evaluated Oct 2026, not landed) | **ABSENT** — stays host-backed; a permanent hole in Zone E unless Q4 answers otherwise | —, Q4 |
| `PersistentList` | Eve list | **EVE AS-IS** candidate | 4 |
| `PersistentQueue` | No distinct Eve queue type; Eve list/vec cover the semantics | **COUNTERPART** (low priority; host-backed until demanded) | 4 (tail) |
| Transients (`IEditableCollection`, `TransientVector/Map/Set`, `persistent!`) | Eve's rule: local mutation is **guarded transients** — mutation confined to the scope's local atom, sealed at the boundary | **COUNTERPART** — the discipline is Eve's model law; the implementation sits on unit 4's collections + a scope-local guard | 4 |
| `Delay` / `Promise` / `Future` (IDeref/IPending) | Eve thread layer (unit 1) | **COUNTERPART** — done | 1 |
| Meta (`IObj`/`IMeta`, `with-meta`) | Eve value blocks carry substrate bookkeeping only (model census: the descriptor holds no program meta); cohost's C++ object carries an explicit meta slot | **COUNTERPART** — meta rides the wrapper/stand-in, never the block | 4, 5 |
| Numbers, strings, keywords, symbols, chars | Eve has its own encodings in its own world; at *this* seam they are Zone H by design | **ABSENT** (by design — never Eve-backed; §1 Zone H) | — |
| Records / deftype instances | cohost's `cohost_object` {descriptor, methods, fields, extmap, meta} ↔ Eve's `eve_obj` {tag, fields}: the same shape, two type systems (transfer analysis §1) | **COUNTERPART** — convergence exploited on jank in unit 6; records' *field values* may be Zone-E values from unit 4 on | 6 |

Reading rule for the table: "EVE AS-IS candidate" means the
unit's gate is what promotes it to EVE AS-IS — Eve's collections
were written against Eve's substrate and core, and the claim
that they answer cohost's shim classes unchanged is exactly
what unit 4 measures, not a premise.

## 4. The units

Standing gate discipline for **every** unit below, in addition
to its own gate: the standing batteries never regress —
**JVM battery 221/221 + libraries 62/62** (`bb boot:jvm`) and
**jank battery 219/221** (`bb boot:jank` on J, the 2 FAIL rows
only the standing named pair: `subs count nth first last
butlast`, `Character outside ASCII`); new shim code passes the
jank AOT compile (unit 1's rule: JIT is not evidence); the
debug-seam check stays green where the seam surface is touched;
`cli:check` stays 7/7 SAME. Work follows the house pattern:
implementation first, verification after, per-unit layer probe
in the `probe_thread.clj` shape, and a `:host-lacks`-style boot
opt as the exercise path so the fill can be gated on a capable
host before it ever becomes a seat's default.

### Unit 3 — Seam identity: equality and hasheq for Eve-backed values

- **Scope.** The contract layer, before any collection moves:
  `=` and `hasheq` answers for Zone-E values, defined so that
  (a) a Zone-E value equals the Zone-H value with the same
  elements/entries, in both argument orders, through the shim's
  own dispatch (`equiv`/`hasheq` paths stock core actually
  calls); (b) `hasheq` is computed from the value's canonical
  Clojure-level form (the Murmur-family collection hash over
  the seq, as stock defines it) — **not** from Eve's Merkle
  digest; the digest never crosses the seam as a hash answer;
  (c) unit 1's tagged-map distance (equality = map equality of
  the stand-in) is retired for Eve-backed values: two stand-ins
  wrapping equal roots are `=`, and a stand-in equals its
  Zone-H twin.
- **Seam surface it sits behind.** The shim's equality/hash
  dispatch itself (`equiv`, `hasheq`, `hashCode` answers and
  the `IHashEq` ancestry in the class table) — no class moves
  yet; the unit ships the *rules* plus a minimal Eve-backed
  value (an Eve atom's root snapshot and one Eve collection
  hosted in-memory on the JVM seat) to prove them against.
- **Gate.** A seam probe in the probe program's shape: mixed-
  representation cases (H vs E, E vs H, E vs E) for vector,
  map, set, list, and atom-deref values — equality symmetric,
  `hasheq` equal wherever `=` holds, hash-map lookup by a
  Zone-H key finding a Zone-E entry and vice versa. Standing
  batteries green. The probe's numbers are the unit's
  declaration; no battery number may move.
- **Dependencies.** None on the slab flagship; none on unit 2.
  Needs only Eve's CLJC collections/atom loadable on the JVM
  seat in-memory (mem tier, no regions). **This unit can start
  now**, in parallel with unit 2.

### Unit 4 — Eve-backed persistent collections behind the shim

- **Scope.** The class move, one collection end-to-end first
  (vector *or* hash map — the lane picks by which the libraries
  exercise more, measured, not guessed), then the rest of the
  §3 rows: `PersistentVector`, `PersistentHashMap`,
  `PersistentHashSet`, `PersistentList`, `PersistentArrayMap`
  (counterpart via Eve map), transients with the guarded-
  transient discipline (mutation only inside the scope's local
  atom; `persistent!` seals and the transient is dead after —
  enforced, not conventional), meta on the wrapper. The shim's
  creators for these classes dispatch to the value layer when
  the seat's fill is in force; the fill is exercised through a
  boot opt (the `:host-lacks` pattern) before any seat takes
  it as default — whether it ever becomes a default is Q1,
  and the unit does not presume the answer: it ships the fill
  and the gate that *could* justify a default.
- **Seam surface.** The creators + instance predicates +
  method vars of the five classes and the transient
  interfaces — the exact delegation points §2 inventories.
- **Gate.** With the fill forced on the JVM seat: battery
  221/221, libraries 62/62, **and the corpus** (the 4,401-case
  corpus from the M-11 line) with no new failures against its
  host-backed run — the corpus is the collections gate the
  battery is too small to be. Jank seat: AOT compile of the
  new shim/layer code green (`bb boot:jank` on J), battery
  219/221. A layer probe for the transient guard (mutation
  after `persistent!` throws; cross-scope transient use
  throws). Timings recorded (battery wall, corpus wall) H vs
  E in the same run — D1 is priced, not asserted.
- **Dependencies.** Unit 3 (identity rules must exist before a
  second representation of the same values does). No slab
  dependency on the JVM seat (in-memory Eve structures);
  the jank *residence* of these values is unit 6, not here —
  on jank this unit's gate is compile + battery with the
  values hosted as they are on the JVM seat.

### Unit 5 — Atom behind the seam onto Eve atoms; Ref and Var plotted

- **Scope.** `clojure.lang.Atom` answered by an Eve atom
  (mem tier): the shim's `new-Atom` dispatches to the value
  layer; deref/swap!/reset!/compare-and-set!/swap-vals!/
  reset-vals! ride the Eve wrapper's CAS word over immutable
  roots; watches and validators map onto Eve's atom facilities
  (where Eve's answer differs from stock's — e.g. watch
  delivery on a failed swap — the difference is named in the
  unit report, not smoothed). `Var`: roots held in the layer's
  registry atom (unit 1's precedent — its registry is already
  an atom), bindings unchanged (unit 1 conveyance). `Ref`:
  **plotted, not built** — the unit delivers the written
  disposition analysis Q2 asks for (what dosync programs
  exist in the library set — the probe's method applied to
  `dosync`/`ref`/`alter`/`commute` in the vendored sources —
  and what each Q2 answer costs), because building an STM
  against Eve's model law is a decision, not a default.
- **Seam surface.** `new-Atom`, the `IAtom`/`IAtom2` method
  vars, the `IDeref`/`IRef` predicates (unit 1 already taught
  them to recognize layer values; this unit extends the same
  branches to memory-layer values).
- **Gate.** Layer probe: CAS contention (N threads × swaps,
  final value exact), watch/validator cases matching stock's
  printed behavior, `swap-vals!`/`reset-vals!` old/new pairs,
  an Eve-backed atom holding a Zone-E collection (unit 4)
  whose root's Merkle identity is stable across swaps that
  don't change it — the first place the seam touches Eve
  identity, as a *measurement*. Batteries green; probe
  program re-run: its future-capture-atom case (answers 1
  today) must still answer 1 through the Eve-backed atom.
- **Dependencies.** Unit 3 (CAS comparison is seam equality),
  unit 4 (for the collection-root measurement; the atom unit
  itself can land with Zone-H roots first). No slab
  dependency (mem tier).

### Unit 6 — Slab residence on the jank seat

- **Scope.** Zone E gets its substrate: on the jank seat,
  Eve-backed values' roots live in Eve's slab — regions
  (mem/file-backed `MAP_SHARED`), write-once blocks, Merkle
  identity in the ExtCID — and the shim's jank stand-in and
  Eve's completion object **converge**: one tagged C++ object
  whose descriptor dispatch serves both type systems (the
  transfer analysis's convergence, §0), so a cohost record
  and an Eve value are the same *kind* of object on jank,
  told apart by descriptor/tag as DJ-2 ruled. Includes the
  known require fix in the slab-io adapter
  (`slab_io.jank:153`) the flagship lane carries.
- **Seam surface.** Nothing new above the seam — that is the
  point: units 3–5's answers are re-pointed at resident
  roots. Below the seam: `cohost_object` ↔ `eve_obj`
  descriptor convergence, region leaves in cohost's jank
  adapter contract (the memory analogue of unit 1's thread
  leaves: `:region-open` / `:region-map` shapes are the
  lane's to settle against Eve's region adapter contract).
- **Gate.** The flagship's own numbers, inherited: region +
  slab + typed-view measured against node's C++ extension
  numbers on the same bench shape (Eve-on-jank lane's gate —
  this unit does not re-measure what that gate measures, it
  *consumes* it). Cohost-side gate: `bb boot:jank` with the
  fill forced — battery 219/221, libraries 62/62, root
  identity stable across a snapshot rebuild (same program,
  same root digest, printed), and a deref of a disk-resident
  atom root answering after a process restart (residence is
  real, not cached). Batteries never regress.
- **Dependencies.** **Hard dependency: the Eve-on-jank
  flagship gate** (region + slab + typed-view as one unit)
  must land first — this unit is its first consumer, and
  plotting it before the flagship's numbers exist would be
  designing against guesses (the same error the load probe
  was created to avoid). Also units 3–5 (the shapes being
  re-pointed). The two efforts are one coordinated effort:
  the flagship lane's gate report is this unit's step 0.

### Unit 7 — Tiered atoms: mem → disk → net, shared between instances

- **Scope.** The atom kinds, expressed at the seam: an
  Eve-backed atom is created with a **chosen kind** (how the
  choice is expressed in program code is Q5; the unit builds
  the mechanism behind whichever surface Q5 settles — until
  then, behind the boot-opt/policy surface the fill already
  uses). Disk kind: the atom's root lives in a file-backed
  region; a second cohost process opening the same atom joins
  it OS-resolved (no coordinator thread) and sees swaps.
  Net kind: joining is broker-resolved (the atom broker's
  signaling server + blob store + peers; the network atom
  contains its disk LRU); the bottom tier owns the root
  pointer. No automatic upgrade/fallback anywhere; a mem
  atom holding a Zone-H root (D3) **fails loudly at kind
  promotion/creation**, naming the unrepresentable value —
  never a silent marshal. Bindings conveyance through the
  atom (unit 1's mechanism, now across processes): a future
  on instance B sees the binding frame instance A published
  through the shared atom — the cljs.thread shape at process
  scale, transparent to the program.
- **Seam surface.** `new-Atom`'s option surface (behind it),
  nothing else: `(atom x)` programs are untouched; kind-
  chosen atoms are the same class.
- **Gate.** A two-process probe, in the probe program's
  discipline: process 1 (JVM seat) and process 2 (jank seat,
  on J) share one disk atom; interleaved swaps from both,
  final value exact and equal from both sides; root digest
  identical from both processes. Net kind: the same probe
  over a broker-joined atom with the simulator standing up
  broker + blob store locally (Eve's own test shape). A
  killed-and-restarted process rejoining sees the last
  published root (per-atom snapshot rule: its own atom's
  last root, no cross-atom instant is ever claimed).
  Batteries green on both seats.
- **Dependencies.** Unit 5 (atoms), unit 6 (disk residence
  on jank; the JVM-seat disk half can precede unit 6 if
  Eve's JVM host region support measures ready — the unit
  report must say which). Net kind additionally depends on
  Eve-side network-atom state as it stands in Eve's own
  line — consumed as-is, gaps named, not filled from cohost.

### Unit 8 — The shared-heap illusion on heap-less hosts

- **Scope.** The cljs seat of the memory work, and the
  design's stated destination: on a host with no shared
  heap (cljs / web workers), the shared atom between
  workers (Eve's `shared_atom.cljs` is the existing Eve-side
  shape) sits behind the shim's `Atom`; values and binding
  frames cross worker boundaries **through the atom**, so a
  `future` whose closure captures a value in another heap
  resolves the capture through the shared atom — the one
  case the probe found no current host exhibiting, because
  the machinery died earlier, at the executor seam unit 2
  is fixing. Library code is unchanged throughout: that is
  the unit's whole claim.
- **Seam surface.** `Atom` + the unit 1/2 thread surface
  (the executor entries the probe named) — the two halves
  meet here and nowhere else.
- **Gate.** **The probe's cljs row, flipped**: `dev/eve_probe.clj`
  re-run on cljs answers the JVM's values on all 23 cases —
  future ×4, promise, agent, pmap included — with the probe
  program itself byte-identical (the transparency test in
  executable form, per the probe report's conclusion). Plus
  a worker-crossing case the original probe couldn't pose:
  capture of a value created in another worker's heap,
  answered through the shared atom. Batteries: cljs
  cli:check rows stay SAME; JVM/jank batteries untouched.
- **Dependencies.** Unit 2 (cljs thread leaves — the
  executor seam must exist before anything can cross it),
  unit 5 (atoms behind the seam), unit 7 (conveyance
  through atoms, at process scale before worker scale).
  This is the last unit because everything it needs is
  built by the ones before it.

## 5. Sequence, and what waits on the flagship

```
now ── Unit 3 (identity) ── Unit 4 (collections) ── Unit 5 (atom; Ref plotted)
              │                      │                     │
unit 2 ───────┴── (cljs leaves)      │                     │
                                     ▼                     ▼
              Eve-on-jank FLAGSHIP GATE (region+slab+typed-view)
                                     │
                                     ▼
                              Unit 6 (slab residence, jank)
                                     │
                                     ▼
                              Unit 7 (tiered atoms, two-process)
                                     │
                                     ▼
                              Unit 8 (heap-less illusion, cljs row flips)
```

- **Startable today, no flagship dependency:** units 3, 4, 5
  (JVM seat, in-memory Eve structures, mem tier). They also
  de-risk the flagship's consumption: the shapes unit 6
  re-points are settled and gated before residence is asked
  of them.
- **Hard-gated on the flagship:** unit 6 only. Units 7–8
  inherit the gate transitively (7's jank disk half, 8 via
  7) — their JVM/cljs halves are plotted so work need not
  idle behind it, but no unit after 5 *declares* complete on
  a seat whose residence is unmeasured.
- **What the flagship owes this plan** (its own gate, stated
  here so the dependency is exact): region + slab +
  typed-view as one gated unit including the `slab_io.jank`
  require fix, measured against node's C++ extension numbers
  on the same bench shape, before/after in one run — plus
  the standing byte-work adoptions that ride it (hardware
  SHA on the Merkle path — already measured at the crypto
  seam: no win at that call shape, does not ship there;
  simdutf, aklomp-base64, Highway for typed-view bulk ops —
  each stays only if its before/after wins).
- **Parallelism rule:** one heavy leg at a time per box
  (standing). Units 3–5 are REPL-scale and battery-scale;
  they interleave with unit 2 and the 0.9.3 line without
  contending for J's snapshot builds, which unit 6 will
  need exclusively when its turn comes.

## 6. Open questions — genuinely John's

Settled by the record (not asked): falling along the seam
(§0); semantic-not-numeric hashing (§0); kinds are chosen,
never automatic (§0); no STM-shaped cross-atom instant
exists in Eve's law (bounds Q2's answers, doesn't pick one);
no banned mechanisms (§0). What remains are preference
forks — each stated with the facts that make it one.

**Q1. Do Eve-backed collections become a seat's DEFAULT, or
stay opt-in? — RULED: default-on (see §7).**
Facts: unit 4 ships the fill behind a boot opt either way,
so both futures are buildable from the same gate. Default-on
makes Zone E the value world every program gets (one world,
the transparency claim at full strength) and puts D1's cost
and any Eve-representation surprise on every existing user,
in a 0.9.x line. Opt-in keeps two value worlds alive
indefinitely, and every "library code unchanged" claim then
holds only inside programs that opted in. The atom-kind
ruling (chosen, never automatic) was made about *atoms*;
whether it extends to *collections* is not in the record.
Fork: default-on (per seat, as each seat's gate passes) vs
opt-in until a major version vs opt-in permanently.

**Q2. Ref/STM: build it, disposition it, or keep the quiet
fallback? — RULED: no STM for now (see §7).**
Facts: Eve has no STM and its snapshot law argues against
one; the shim *already* falls back `Ref` → atom on hosts
without refs, quietly — a `dosync` program there gets atom
semantics with no signal, which is the worst-shaped answer
regardless of the fork chosen. Unit 5 measures how much of
the vendored library set touches `dosync`/`ref` at all.
Fork: (a) build an STM counterpart over Eve atoms (against
the grain of the model law, priced by unit 5's analysis);
(b) formally disposition Ref = atom semantics **with a loud
failure** anywhere `dosync`'s cross-ref atomicity is
actually exercised; (c) keep the quiet fallback as the
documented answer.

**Q3. A Zone-H value that Eve cannot represent, stored into
a Zone-E collection: reject, or handle?**
Facts: closures and live host objects cannot be slab
residents — no serialization of them exists or is plotted.
Today (Zone H everywhere) any value can be stored in any
collection, so rejecting is a real behavior change for
programs that mix host objects into collections they also
want shared or durable. The model law bans side tables
that outlive their scope, which kills the persistent
side-table answer; a *scope-local* handle table is legal
under the law but the handles die with the process, so a
"shared" collection containing one is shared with a hole.
Fork: (a) reject at the seam with a named error (clean
model, real breakage); (b) opaque in-process handles
(works until the value crosses a process or a restart —
then a named failure at the crossing); (c) collections
stay Zone-H whenever any element is unrepresentable
(silent representation flip — the choice the rest of this
plan's "no silent anything" discipline argues against,
listed because it is what a naive implementation drifts
into).

**Q4. Sorted collections: wait, build, or leave the hole? —
CORRECTED by John: Eve has sorted collections (see §7).**
Facts: `PersistentTreeMap`/`PersistentTreeSet` have no
landed Eve counterpart (ABSENT, §3); the battery exercises
sorted behavior, so Zone E can never be a seat's whole
collection world while the hole stands — Q1's default-on
answer is capped by this one. Eve's side evaluated the
tonsky sorted-set spike in Oct 2026 and did not land it.
Fork: (a) leave sorted host-backed permanently and name
it a standing distance; (b) the memory effort builds the
counterpart behind the seam (Eve's design rules, cohost's
code — the COUNTERPART label); (c) wait for Eve's own
sorted collections to land in Eve's line first.

**Q5. How is an atom's kind expressed in program code? —
RULED: metadata (see §7).**
Facts: kinds must be developer-chosen (settled), and the
seam rule forbids a new cohost-facing API (settled) — but
`(atom x)` has no kind argument in stock Clojure, so the
choice needs *some* expression that is neither. The
candidates differ in who can see the choice: an option map
on the shim's Atom constructor path (visible to the
program, behind the seam — but every host's `atom` must
grow the same opt or programs stop being portable); a
per-process boot/policy default (portable programs, but
the choice leaves the code and enters configuration —
the shape John's tmpfs ruling already used: "configuration
only"); or metadata on the atom value itself. Fork:
constructor opt vs boot/policy default vs metadata —
noting the broker/disk join semantics (§0) treat kind as
a property of the *atom*, which pulls toward the first
and third.

## 7. Rulings (John, 2026-11-05 ~09:40 ET)

**Q1 RULED: default-on.** Eve-backed collections become a
seat's default as that seat's gate passes.

**Q2 RULED: no STM for now** — STM is deferred to a later
release. Ref's interim disposition is NOT further ruled
here; unit 5's dosync census still runs and reports.

**Q3 OPEN.** John asked what the silently host-backed
option would look like (whether it means weak-reference
handles). The answer being put to him: no — silently
host-backed means the collection itself quietly
remains/becomes an ordinary host collection (no handles at
all; works in-process; silently loses Zone E properties —
sharing, durability, representation stability). Opaque
handles are option (b), strong and scope-local in the
plan's form (a weak handle could lose its referent to GC
while the collection lives — a distinct failure mode).
Awaiting his pick.

**Q4 CORRECTED by John:** Eve DOES have sorted collections
— `eve.deftype.rb-tree` (Okasaki persistent red-black tree
over the slab/SAB, sorted-set API) exists in `~/src/eve`;
VS has sorted collections and VS vendors Eve; the
standing direction is adding them to Eve. The plan's
ABSENT label for sorted collections (§§3, 6) is superseded:
sorted collections are an Eve-side counterpart in
progress, and per John they depend on the stronger
equality/hashing work (unit 3's seam identity contract).
Unit 4's gate and Q1's cap are re-read accordingly —
default-on is no longer capped by a permanent hole; sorted
rides the Eve line + unit 3.

**Q5 RULED: metadata.** An atom's kind is expressed as
metadata on the atom value. Default kind is in-mem.
Default atoms run within the existing default runtime atom
domain (as in VS); locals, analyzer state, compiler state
and the like live in the runtime atom domain, visible
across threads in that domain.

---

*Plan banked 2026-11-05. Sources: UNIT1-REPORT.md +
STATUS.md (this branch), the load probe REPORT.md
(`muse/eve-cohost-probe` @ 1829658), the transfer analysis
(`~/workspace/cohost-install/eve-jank-cohost-transfer.md`),
`eve/doc/hosts/jank.md` (`claude/eve-jank`), Eve's
memory-model docs (`vs/docs/memory-model.md`), and John's
rulings as cited inline. Claims about Eve-side state are
labeled with their seat and date; the flagship gate's
numbers do not exist yet and are nowhere estimated here.*
