# Unit 4 report — Eve-backed collections behind the shim

Branch `muse/eve-cohost-impl-u4` (from `muse/eve-cohost-impl`
@ 89ad378, unit 3 merged in). Status of record:
doc/eve-cohost/UNIT4-STATUS.md.

## What shipped

Persistent vector, hash-map, hash-set and list are
Eve-backed behind the shim's existing creator/construction
surface, on by default on the JVM seat (the seat whose gate
passed; per Q1). Library code does not know: the creators
answer Eve values, and every seam operation (RT funnels,
method vars, predicates, equality/hash dispatch) routes
Eve-backed values through cohost.memory's hosting ops.

- The seam: PersistentVector-create,
  LazilyPersistentVector-create / -createOwning,
  PersistentHashMap-create / -createWithCheck,
  PersistentArrayMap-create / -createAsIfByAssoc (answered
  by the Eve map), PersistentHashSet-create /
  -createWithCheck, PersistentList-creator, and RT-vector
  (the analyzer's vector-literal construction) dispatch to
  the layer when the fill is in force. The swapped EMPTY
  statics (PersistentVector-EMPTY, PersistentArrayMap-EMPTY)
  are Eve empties while the fill is installed.
- The fill (cohost.memory): a fill state atom, a hosting
  registry (registered, never hard-required), guarded
  transients (mutation swaps a new Eve root into the
  transient's scope atom; use after persistent! or outside
  the scope is a named error), and the layer API the shim
  routes through (lookup/nth/ops with not-found shapes).
- The hosting (cohost.memory-eve, JVM): bare Eve roots as
  the values; deep conversion on entry (the hash-facade
  ruling's coercion point: host collections and leaves are
  re-homed into Eve's types at the seam, including host
  references the codec cannot write, which are registered
  as domain residents and resolve back identically on
  read); meta rides mint-on-demand wrappers implementing
  the stock surfaces (IFn, Comparable, Reversible,
  Seqable); Eve-native map/set transients pass through.
- Boot wiring: :eve-collections opt mirrors :host-lacks;
  explicit-on without hosting is a named ex-info; the boot
  report carries :eve-collections :on/:off. Fill-on boot:
  791 top-level forms, booted as stock 465, from host 202,
  natives 66, skipped 58, errors 0.
- Crossings fixed at the facade: thread-bindings maps
  materialize before the host Var machinery casts their
  keys; atom validator/watch arguments resolve their
  resident reference first (flagged for the unit 5 lane —
  the change is confined to resolving the argument); the
  shim's ITransient* predicates answer layer transients
  kind-aware; RT-nth / -nth route Eve-backed values to the
  layer's nth (2-arity throws IndexOutOfBoundsException).
- ifn? at the seam: an Eve-backed list is not IFn (stock
  lists are not callable), though Eve's list type is IFn
  on its own side; vec/map/set stay IFn.

Unit 3's contract holds throughout: =/hasheq answer from
the canonical Clojure-level form; Eve's Merkle digest
never crosses as a hash. Unit 3's probe re-verified on
this tree: 111/111.

## Gates

JVM seat (final, this tree): boot errors 0; battery
221/221; libraries 62/62; probe_collections 76/76 with
the fill on, 4/4 with it off; residents probe 12/12.

Jank seat, run on J against PRE-RELEASE Eve bindings
(per the Nov 5 sequencing note; the gate re-runs against
the released bindings when they land): bb
boot:jank-snapshot RC 0 — the new shim and layer code
AOT-compiles; boot on jank 791 forms, errors 0. bb
boot:jank RC 0 — battery 219/221 with ONLY the standing
named pair failing (subs count nth first last butlast;
Character outside ASCII); libraries pass. Gate attempt 1
failed on a defect of this unit (below) and is recorded
in STATUS; attempt 2 is the pass above.

cli:check: jvm 7/7 SAME, jolt 7/7 SAME; cljs 7/7 SAME
on the fixed tree (it caught the portability defect
below pre-fix). lpy: the basilisp cached-boot build
fails in this worktree (importer FileNotFoundError on
its own bytecode cache) — reproduced identically on
the base branch in a clean worktree on this box, so it
is a pre-existing worktree environment artifact, not a
unit change; the lpy rows are unverifiable from this
worktree and the seat's verdict belongs to the main
checkout.

## Corpus (the plan's collections gate)

jolt-conformance corpus, 4,833 :common rows, run through
the booted world on this tree (dev/u4_corpus.clj;
dev/u4_corpus_diff.clj compares). Fill off: {:pass 4215
:fail 422 :error 196}. Fill on: {:pass 4192 :fail 442
:error 199}. 24 rows changed vs the host-backed run,
every one in a named family:

- Eve HAMT iteration/print order (7 rows): seq/print
  order of Eve maps and sets is HAMT order — array-map
  keeps no insertion order, hash-map/hash-set print in
  Eve's order, reduce-kv's early stop lands differently.
  Equality is unaffected. Four of these are the plan's
  own section-3 decision (ArrayMap answered by Eve map)
  priced by the corpus.
- Class identity (8 rows): on this seat the booted class
  fn is host reflection, and bare Eve values carry Eve's
  classes (eve.list.EveList, eve.map.EveHashMap) where
  rows expect stock class names; find's type-error
  message names Eve's list class likewise.
- Meta on lists through core fns (5 rows) and the
  analyzer-embed pair (2 rows) and (eval (list)) (1
  row): the JVM-analyzer distance and boundary, named
  below.

No equality or hashing row changed: unit 3's =/hasheq
law holds across the corpus under the fill.

## Timings (same-run, arms interleaved, dev/u4_bench.clj)

One booted session, fill toggled per arm, 3 rounds,
medians in ms (10k-element workloads through the booted
core): vec build H 19.0 / E 1884.1; map build H 60.8 /
E 14139.2; map assoc-grow H 24.4 / E 8506.9; vec
conj-grow H 63.1 / E 1019.2; map lookup H 15.0 /
E 1596.7; vec lookup H 76.7 / E 490.9; vec reduce
H 13.5 / E 117.2. Eve's JVM seat is disk-priced (the
heap audit's finding); these are that price at the seam,
and closing it is the heap-grade lane's work, not this
unit's. Measured cross-round effect: in one long-lived
slab context, round-3 map WRITES cliff (build 282,231.7
ms, assoc-grow 180,902.8 ms) while vector writes and all
reads hold — the bare context never reclaims dead roots,
and map writes pay first. The box was shared with other
legs during the bench; read ratios as orders of
magnitude.

## Named remainders

- Queue is NOT in the fill (plan's tail; Eve-side
  NOT-YET).
- Sorted collections stay out (Eve-side NOT-YET, per
  the plan's Q4 correction).
- The JVM-analyzer distance (one family): on this seat
  the host analyzer compiles program code directly, so
  core fns that are thin interop wrappers or carry
  :inlines meet Eve values through Eve's own methods,
  not the shim's vars — with-meta/meta lose meta on bare
  roots (the shim's -withMeta answers correctly), rseq
  hits Eve's unimplemented Reversible on bare roots
  (the seam's -rseq answers), core's inlined compare
  checkcasts to Comparable (shim Util-compare answers),
  and inlined RT funnels applied to resident-bearing
  values hand Eve raw reference markers / resident probe
  keys. Codemod seats (jank/cljs) route all of these
  through the shim and do not have the distance. Closing
  it here is analyzer-surface work, not collection work.
- The analyzer boundary: forms are the analyzer's
  territory; the fill is for program values. Source
  literals are host-reader constants on every seat. On
  the JVM seat the hosting keeps macroexpansion output
  host-strata (a frame scan in memory_eve); a macro that
  embeds a run-time Eve map/set VALUE into an expansion
  as a literal still hits the host analyzer's embed
  error, and an Eve list in form position (eval) is the
  author's crossing. Two corpus rows (index-of with a
  char needle after require; re-matcher) fail in
  fn* compilation embedding an Eve slab object — same
  family; the exact constant was not isolated
  (time-boxed).
- Eve HAMT print/iteration order differs from stock, as
  measured by the corpus families above.
- Dead roots are never reclaimed in a bare slab context
  (quantified in Timings); reclamation belongs to units
  5/6 and the heap-grade lane.
- The class token for an Eve map answers
  PersistentHashMap at the seam (the plan's ArrayMap
  direction); class identity of the bare values
  themselves is Eve's, per the corpus family above.
- subvec answers the host-strata vector on the JVM seat
  (the adapter's RT funnel, not a creator; the value is
  correct, the backing is host).
- deftype cannot express IFn's variadic invoke, so
  direct 21+-argument calls to a meta-wrapped collection
  throw; apply (applyTo) covers the shape.
- The eduction launch-shape diagnostic stays open (in
  STATUS): one battery row fails only under -M:eve
  launches from this worktree, fill off included, absent
  on the base branch; it is not a fill defect, and the
  row gets rechecked on the landed tree where ../eve/src
  is the real Eve tree.
- Portability defect found and fixed in this unit
  (f0b12cb): the layer constructed (Object.) for its
  nth sentinel — cljs compiled it to a nonexistent
  constructor and jank's analyzer rejected it (J gate
  attempt 1). The layer now stays seat-clean CLJC.
