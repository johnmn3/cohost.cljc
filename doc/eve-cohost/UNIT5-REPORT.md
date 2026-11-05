# Eve-in-cohost, unit 5 -- REPORT

Atom onto Eve atoms (mem tier) + var roots via the registry.
Branch `muse/eve-cohost-impl-u5`, off `muse/eve-cohost-impl`
@ 89ad378, with unit 3's implementation cherry-picked as
790146f (its seam contract is this unit's composition base).

## What shipped

**The atom layer** (`src/cohost/atom.cljc`, portable; JVM
hosting in `src/cohost/atom_eve.clj`, required by nothing in
the shim/boot graph, so jank/CLJS builds never load it):

- `clojure.lang.Atom` behind the shim, backed by Eve atoms,
  mem tier. An atom value is a tagged map whose state holds
  the Eve handle plus the wrapper's own watches, validator
  and metadata. Program atoms are sharded across Eve heap
  domains at Eve's 255-slot domain limit; the registry atom
  lives alone in the startup domain (Q-A + section 5's write
  profile mapping).
- swap! delegates to Eve's own CAS loop (its loop re-runs f
  per attempt, stock-shaped); the validator rides inside f.
  Watched swaps and compare-and-set! take an exclusive claim
  on the wrapper, because Eve's loop does not surface the
  exact (old, new) pair watches must fire with; the claim
  waits for in-flight swaps to drain, runs f once, fires
  watches after the publish commits. swap-vals/reset-vals
  share the claim path. compare-and-set! compares by seam
  equality (unit 3's canonical-form contract).
- Roots Eve cannot hold seat in the wrapper, one-way: the
  Eve codec refuses host values (functions, host objects),
  and an Eve value nested inside host data would be read
  back as garbage (verified: it derefs to an "Unknown EVE
  type tag" error), so the layer walks roots structurally;
  bare Eve roots are published through Eve's own validation,
  which refuses foreign domains loudly. A seated root's
  deref identity is exact: a unit-3 stand-in comes back
  identical, and the atom's Eve root pointer does not move
  across a no-op swap (measured 62 -> 62; a real conj moves
  it). The Eve atom holds the marker :cohost.atom/host-held.
- Metadata lives in the wrapper state (core's meta ops cast
  to IReference on this seat, so the shim's -meta/-withMeta/
  -alterMeta/-resetMeta branch to the layer).

**The registry** (scope item 4): one Eve atom, named
`cohost.registry/table`, in the startup domain; the table is
a map {namespace {var {:root v} | {:host schema} |
{:resident true}}}. Roots that are Eve-representable data
are held inline (deref of the registry materializes them:
verified {:root 42}, {:root {:a 1 :b 2}}); host roots
(functions) are residents. Publication paths: the shim's
Var-intern, -bindRoot and -alterRoot publish live. A
program's defs on the JVM seat intern through the host
compiler without crossing the facade, so namespaces join
the registry by sweeps at load boundaries instead: the boot
sweeps cohost.core, the user namespace and every loaded
library namespace at install; boot's load-lib! sweeps each
namespace it loads. Verified end to end: def, defn, and
alter-var-root in a booted program all read back from the
host through the registry atom.

**Shim + boot wiring.** The fill is opt-in per boot
(`:eve-atoms true`): boot installs the hosting, mints the
registry, sweeps, and flips the fill. Two seat facts shaped
the wiring:

1. On the JVM seat the atom-surface core names (swap!,
   reset!, compare-and-set!, swap-vals!, reset-vals!,
   add-watch, remove-watch, set-validator!, get-validator)
   must boot STOCK: the host's own swap! casts its receiver
   to IAtom, and the boot's natives table and the
   take-from-host path both bypass the shim. With
   :eve-atoms, boot unions the surface names into
   :host-lacks (stock sources, whose bodies cross the shim
   verbs and so reach the layer) and gates the natives
   table on the same set. Stock boot counts with the fill:
   501 stock / 172 host / 61 natives / 88 skipped / 0
   errors (496/172/66/88 without).
2. The layer is invisible when the fill is off: new-Atom
   and every wrapped verb check fill-active? and hosting
   availability first, so default boots are unchanged
   (regression gates below).

Composition: the unit 1/2 thread layer is untouched and
keeps working over the new atoms -- futures capture them,
agents act on them, pmap maps over them (checks below);
boots without :eve-atoms never load the hosting
(atom_eve.clj is JVM-only, like memory_eve.clj).

## Gates, as printed

Layer probe (probe_atom.clj, host-side, this branch):

    PROBE-ATOM: 66 checks, 0 failures
    hosting: {:program-domains 1, :atoms-minted 22,
              :startup-domain? true}

Booted-context check (eve_cohost_check_u5.clj; boots with
:eve-atoms true; the units 1-2 thread checks plus the atom
semantics through the booted core):

    CHECK-U5: 31 checks, 0 failures

Default-config JVM gate (bb boot:jvm, this branch):

    booted as stock: 465   from host: 202   natives: 66
      skipped: 58   errors: 0
    battery: 221/221 stock behaviors pass
    libraries: 62/62 cases pass across 12 clojure.* libraries

Regression checks with the wiring in place: unit 1's
eve_cohost_check.clj 21/21; unit 3's probe_memory.clj
111/111 PASS.

Jank AOT gate (J, bb boot:jank; exit 0 -- the new shim
graph, cohost.atom included, AOT-compiles):

    booted as stock: 514   from host: 171   natives: 28
      skipped: 78   errors: 0
    battery: 219/221 stock behaviors pass
       FAIL subs count nth first last butlast
       FAIL Character outside ASCII
    libraries: 62/62 cases pass across 12 clojure.* libraries

The two FAILs are the standing named jank-only pair,
verbatim from every prior gate on this line.

## The CAS-contention probe (scope item 2)

probe_atom.clj, one session, arms interleaved: 4 threads x
2,500 swap! inc on one atom, 3 rounds, correctness exact on
both arms (10,000 -- no lost updates; a watched arm also
exact at 1,000). Numbers as printed, two runs:

    run 1   stock rounds ns/op: [416 1178 613]
            layer rounds ns/op: [441106 228571 244858]
    run 2   stock rounds ns/op: [900 8 197]   (quiet box;
            the 8 is a timer artifact of an unloaded round)
            layer rounds ns/op: [908079 542527 651474]

Uncontended, same session (probe0/probe1): stock swap!
~0.7 us/op; the Eve-backed swap! ~0.8 ms/op; Eve deref
~145 us/op against stock ~0.2 us. The layer's cost is
Eve's JVM atom price, not the wrapper's (the wrapper adds
a state read and a claim check per op). This is the
section 9 Q-C position exactly: the seat's mechanism is
real Eve as-is, and heap-grade speed is the named Eve-side
work (typed-view reads, region growth), not a counterpart.

## The dosync census (scope item 3)

dev/u5/census.clj, output dev/u5/census-out.txt. Method:
sources parsed and walked by form (call sites = forms
headed by a transactional name); the battery's programs
are string literals, each read and walked as code.

- Vendored library corpus (17 .clj files + battery.cljc
  read as forms): 13 transactional call sites --
  core.clj 9, test.clj 4. By name: ref 5, commute 4,
  dosync 3, alter 1. ref-set 0, ensure 0, sync 0.
- The only dosyncs: core.clj:5967 (contains one commute),
  core.clj:5979 (creates a ref, one commute) -- the
  *loaded-libs* bookkeeping in the load machinery -- and
  test.clj:320 (one commute, the report counters).
- ref sites: core.clj:2313, 5895, 5979; test.clj:762, 811.
- Battery: 512 program strings walked; 6 transactional
  call sites, all in the single "refs: a transaction, and
  none" row: ref x1, alter x2, ref-set x1, dosync x2 --
  the row that pins alter/ref-set outside a transaction
  throwing IllegalStateException, and a dosync committing.

Read: everything the gates run uses transactions in
exactly one shape -- a single ref, mutated by alter or
commute inside one dosync, no nesting, no ensure, no
multi-ref coordination beyond core's own load-time
bookkeeping. That is the whole surface a later STM
(Q2: a later release) would have to serve; the battery
row passes today on the shim's standing Ref fill, and
this unit built none of it.

## Named remainders

- Layer atoms print as their stand-in map, not
  #atom[...]; the thread layer's values carry the same
  distance (unit 1's report). Meta IS served (the wrapper
  state's), so the print is the only surface gap.
- deref materializes the whole root (plan D2 stands;
  identical? never crosses strata). A stand-in or a host
  value nested in a root seats as a resident rather than
  slab-residing; Eve-side nesting of foreign values is
  where that closes.
- Once a root seats in the wrapper (:host), the atom
  stays :host; no promotion back to the Eve seat.
- A swap function may run twice when its computed result
  transitions seats (once speculatively through Eve's
  loop, once under the claim against the seated root);
  stock permits multiple runs, so this stays inside the
  contract, but f should stay pure, as stock requires.
- Registry completeness on the JVM seat is by
  load-boundary sweeps plus facade publishes: a program's
  own defs are swept when its namespace crosses a boundary
  the boot controls (the check sweeps 'user explicitly;
  library namespaces sweep themselves via load-lib!).
- Timed deref on a layer atom returns the value through
  the shim's -get path, matching cohost's standing
  host-atom behavior; stock throws for atoms. Inherited,
  not introduced.
- compare-and-set! on a layer atom compares by seam
  equality over canonical forms (unit 3's contract): two
  distinct-but-equal roots CAS as equal. Stock compares
  by identity. This is the seam's stated semantics, named
  here so it is a decision on record, not a surprise.
- Eve JVM atom operations run ~300-1000x stock (above).
  Per section 9 the seat waits on the named Eve-side
  hot-path work for heap-grade; no counterpart was built.
- The jank seat: the layer is compiled by the AOT gate
  (cohost.atom enters the shim's require graph) but the
  fill is JVM-hosted in this unit -- atom_eve.clj is
  :clj-only, and the jank seat's Eve hosting is unit 6's
  ground (flagship-gated). Boots on jank run with the
  fill off, exactly as before this unit.
