# Eve-in-cohost, unit 5: Atom onto Eve atoms (mem tier); var roots via the registry — STATUS

Branch `muse/eve-cohost-impl-u5`, off `muse/eve-cohost-impl` @
89ad378 (target audit tip). Unit 3's seam-identity layer is
cherry-picked in as 790146f (content-identical to
`muse/eve-cohost-impl-u3` @ e93971d; collation dedupes by content) —
unit 5's CAS comparison is unit 3's seam equality, and its atoms
hold unit 3's stand-ins.

## Scope (MEMORY-PLAN.md unit 5 + tasking, 2026-11-05)

1. `clojure.lang.Atom` backed by Eve atoms, mem tier:
   deref/swap!/reset!/compare-and-set!, watches, validators,
   metadata — stock-observable semantics through the shim,
   composing with the unit 1/2 thread layer.
2. A CAS-contention probe (contended swap!, N threads, no lost
   updates, timings vs stock, same session interleaved).
3. The dosync census: where the battery + library corpus actually
   uses ref/dosync/commute — counts + named sites, report only.
   Q2 is RULED: no STM for now; Ref is plotted, not built.
4. Var roots + namespace registry as Eve-backed residents in the
   STARTUP contention domain (Q-A, plan §10); boot green end to end.

Out of scope: deref redesign (plain deref's Eve behavior stands),
tiered atoms (unit 7), Ref/STM implementation.

## Measured starting state (2026-11-05, this lane, JVM seat)

From `dev/u5/probe0.clj` (Eve sources on the classpath, plain JVM
Clojure), numbers as printed:

- Eve heap domain open: 1077 ms first, 590 ms second (slab init;
  the heap spike's P1 price). Anonymous atom creation in the
  default domain: 501 ms (includes that domain's open).
- Warm ops on a heap atom: deref 0.3 ms; swap! ~1.1 ms/op over
  100 swaps (cold-ish box; re-measured in the layer probe).
- **Eve's codec refuses host values**: storing a fn, a
  StringBuilder, an atom, or a map containing a fn into an Eve
  atom throws `value->eve-bytes: unsupported type`. An Eve atom
  holds Eve-representable data only.
- Eve's `:clj` MmapAtom implements IDeref + IAtom (swap/reset)
  only: **no compareAndSet** (the `:bb` branch has one, and it
  is a deref/compare/swap loop), **no watches, no validators,
  no metadata**. Those live in this unit's wrapper.
- An Eve domain holds at most **256 atom slots**
  (`eve.deftype-proto.data/MAX_ATOM_SLOTS`; slot 0 is the
  domain registry) — the layer shards program atoms across
  domains.
- Cross-domain roots are refused at swap time (atom.cljc:929):
  a value allocated in another slab domain cannot be stored.

## Design (as tasked; build notes follow as built)

- **`src/cohost/atom.cljc`** — the portable layer (the
  `cohost.thread` pattern). An atom value is a tagged map
  (`:cohost/kind ::atom`) whose state atom holds: the Eve atom
  handle (the wrapper's CAS word + slab root), the mode
  (:eve when the current root is slab-resident, :host when it
  is not), the host seat (the root itself, :host mode), the
  watches map and the validator. Watches/validators/meta are
  the wrapper's, per the measured Eve surface above.
  - Representable roots live in the Eve atom; swap! delegates
    to Eve's own CAS loop (validator checked inside the swap
    fn, before publish).
  - A root Eve cannot represent (a fn, a host object — the
    multimethod-state shape) is held **by reference in the
    wrapper's host seat**, mem tier only (plan §1's Zone-X
    shape, D3): the atom stays an atom; the transition happens
    under the wrapper's monitor with exact watch pairs. A
    host-seat root is precisely what unit 7's tier promotion
    will refuse, loudly, by name.
  - compare-and-set! compares roots by **seam equality**
    (unit 3's `cohost.memory/equiv` over canonical forms) —
    with a materializing deref, identity across derefs is not
    available; the plan names CAS comparison as seam equality.
  - Domains: the hosting mints Eve heap domains and shards
    program atoms across them at the 256-slot cap; the
    registry (below) lives in its own **startup domain**,
    minted once at fill install.
- **`src/cohost/atom_eve.clj`** — JVM-seat Eve hosting (the
  `cohost.memory-eve` pattern: a `.clj` file nothing in the
  shim/boot graph requires, so jank/CLJS builds never load
  it). Supplies the layer's factory: Eve MmapAtoms in sharded
  heap domains + the startup domain's registry atom.
  `install!` registers the factory with the layer. On seats
  without Eve hosting the factory is absent, the layer is
  unavailable, and `new-Atom` keeps the host's answer —
  the unit 1 availability shape.
- **Shim wiring** (`src/cohost/shim.cljc`): `new-Atom`
  dispatches to the layer when the fill is in force;
  `-swap` / `-reset` / `-swapVals` / `-resetVals` /
  `-compareAndSet` / `-addWatch` / `-removeWatch` /
  `-getWatches` / `-setValidator` / `-getValidator` /
  `-deref` gain layer branches; `h-atom?` recognizes layer
  atoms (so `clojure.lang.Atom` / `IAtom` / `IAtom2` answers
  hold); `Var-intern` / `-bindRoot` / `-alterRoot` publish
  into the registry when the fill is in force.
- **Boot fill** (`src/cohost/boot.cljc`): a `:eve-atoms`
  boot opt. When set, boot installs the fill after the
  thread layer: loads the seat hosting, mints the startup
  domain + registry atom, publishes the booted namespace
  table into it, and turns the layer on for the booted
  program. Default boots never pass it — the standing
  batteries are untouched by construction.
- **The registry**: an Eve atom in the startup domain whose
  root is the namespace table `{ns-sym {var-sym entry}}`.
  Interning records the entry; a data root is held inline;
  a host root (a fn) is registered as a **resident**
  (John, 2026-11-05 ~11:02 ET: host references held in
  atoms/vars are domain residents, registered on entry — no
  special accounting): the entry names the var, and the host
  var remains its seat. Root writes through the shim's Var
  paths republish the entry.
- **Ref**: stays as it stands (the shim's quiet fallback on
  ref-less hosts; the host's own Ref where the host has one).
  No STM is built (Q2). The census (scope 3) is the unit's
  Ref deliverable.

## File-needs flag (sibling lane, unit 4)

Unit 4 owns the collection creators in the shim. This lane
touches: `src/cohost/shim.cljc` (Atom/Var entries only — no
creator lines), `src/cohost/boot.cljc` (one opt + one install
call), new files `src/cohost/atom.cljc`,
`src/cohost/atom_eve.clj`, probes at repo root. No collection
files are touched; if that changes it will be flagged here
first.

## State

- [x] Plan + unit 1 report + unit 2 status read; branch +
  worktree up @ 89ad378; unit 3 layer cherry-picked (790146f).
- [x] Feasibility measured (probe0, above).
- [x] Layer probe 2: domain sharding mechanics, warm timings,
  Eve-value roots via atom-sio, root-identity read (ptr stable
  across a no-op swap, moves on change).
- [x] `cohost.atom` + `cohost.atom-eve` built. Design as built:
  the wrapper holds watches/validators/meta in its state;
  swap! delegates to Eve's CAS loop (validator inside f);
  watched swaps and CAS take an exclusive claim for the exact
  watch pair; roots Eve cannot hold seat in the wrapper (one
  way), including stand-ins and foreign-domain Eve values,
  which Eve would otherwise read as garbage when nested.
- [x] Shim + boot wiring. Two seat facts shaped it: on the JVM
  seat the atom-surface core names must boot stock (the host's
  swap! casts its receiver -- the natives table and the
  take-from-host path both bypass the shim), via the :eve-atoms
  boot opt's host-lacks union + natives gate; and a program's
  defs intern through the host compiler, so the registry
  tracks namespaces by load-boundary sweeps on top of the
  facade paths' live publishes (Var-intern, bindRoot,
  alterRoot).
- [x] Layer probe (probe_atom.clj): 66 checks, 0 failures --
  semantics, host seat, registry, identity, thread-layer
  composition, contention (no lost updates, watched exact).
- [x] Booted-context check over the new atoms
  (eve_cohost_check_u5.clj): 31 checks, 0 failures -- the
  units 1-2 thread checks unregressed, atom semantics through
  the booted core, registry entries verified from the host.
- [x] Dosync census counted (dev/u5/census.clj, output
  dev/u5/census-out.txt): 13 transactional call sites in the
  vendored libraries (core.clj 9, test.clj 4), 6 in the
  battery, all in its one refs row; the only dosyncs wrap
  single commutes; no ensure, no ref-set in libraries.
- [ ] Gates: JVM battery 221/221 + libraries 62/62, boot
  errors 0 (default config); jank AOT gate on J 219/221
  (standing pair only); thread checks unregressed.
- [ ] Report filed (UNIT5-REPORT.md) + declaration.
