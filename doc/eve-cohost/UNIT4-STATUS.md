# Eve-in-cohost, unit 4 — STATUS: Eve-backed collections behind the shim

Branch `muse/eve-cohost-impl-u4`, off `muse/eve-cohost-impl` @
89ad378 (target audit tip), with unit 3 merged in (its seam
identity contract is unit 4's dependency).

## Design (settled by the plan + rulings; not re-litigated)

- Creators dispatch to the value layer when the fill is in force:
  `PersistentVector-create`, `LazilyPersistentVector-create` /
  `-createOwning`, `PersistentHashMap-create` / `-createWithCheck`,
  `PersistentArrayMap-create` / `-createAsIfByAssoc`,
  `PersistentHashSet-create` / `-createWithCheck`,
  `PersistentList-creator`. ArrayMap is answered by Eve map
  (plan §3). Sorted collections are OUT (Eve-side NOT-YET).
  Queue is the plan's tail — NOT-YET, not in this unit's fill.
- JVM seat: the layer's values are bare Eve values (Eve's JVM
  deftypes implement the clojure.lang contracts — unit 3 measured
  bare Eve values satisfying host `=`/`hash` against their twins),
  so the host's own dispatch serves conj/assoc/nth/get/count/seq.
  The seam contract (unit 3) is what makes the strata one world.
- Hosting is registered, never hard-required: `cohost.memory`
  (CLJC) holds the fill state + constructor registry;
  `cohost.memory-eve` (JVM-only .clj) registers Eve constructors.
  A seat with no Eve hosting cannot take the fill (NOT-YET, named
  at boot) — no substitute realization (§9).
- Guarded transients (plan §3): a layer transient whose state
  lives in an atom (the scope's local atom); mutation swaps the
  Eve root in that atom; `persistent!` seals; use after seal
  throws; use outside the creating scope throws. Served through
  the shim's transient method vars (-asTransient, -conj, -assoc,
  -without, -disjoin, -pop, -persistent, -count, lookups).
- Fill forcing: boot opt (the `:host-lacks` pattern). Per Q1 the
  JVM seat takes the fill as its DEFAULT once this unit's gate
  passes on it; other seats wait for their Eve hosting.

## Milestones

- [x] M1 — branch + unit 3 merged; unit 3 probe re-run on the
  merged tree: **111/111 PASS** (2026-11-05).
- [x] M2 — layer: fill state, constructor registry, Eve
  constructors (vector/map/set/list + empties), guarded
  transients in `cohost.memory` / `cohost.memory-eve`.
  Banked @ `0cfaead`.
- [x] M3 — shim: creator dispatch under fill; EMPTY statics
  rebound at fill install; transient method-var branches;
  class-of answers for Eve values. Banked @ `bd6490b`.
- [x] M4 — boot: `:eve-collections` opt + fill install;
  JVM default-on. Banked @ `bd7b1aa`. Post-M4 seam fixes
  (RT-vector is the analyzer's vector-literal construction and
  is the fill's surface; the analyzer-boundary rule below;
  `memory/install-fill!` portable wrapper) banked with the
  probe-series update — see the dated section below.
- [x] M5 — layer probe (probe_collections.clj): **76 checks,
  0 FAIL** with the fill forced on; 4/4 in off mode
  (2026-11-05 ~2:55 PM ET). Creators, seam contract, ops,
  meta through the seam vars, guarded transients (seal +
  CCE shapes), residents round-trips. Fixes banked with it:
  the ITransient* cast predicates answer layer transients by
  kind; RT-nth / -nth route Eve-backed values through the
  layer's nth-of (resident elements resolve). Unit 3 probe
  re-run after the equiv/hasheq change: **111/111**.
- [~] M6 — JVM gate, fill forced: **battery 221/221,
  libraries 62/62, boot errors 0** (dev/u4_gate.clj on,
  2026-11-05 ~2:10 PM ET; launch shape: -Sdeps paths, no
  -M:eve — see the eduction diagnostic). Remains in M6: the
  corpus comparison + same-run H vs E timings.
- [ ] M7 — jank gate on J: `bb boot:jank` AOT-compiles the new
  shim/layer code; battery 219/221 (standing pair only).
- [ ] M8 — standing checks (cli:check 7/7 SAME, debug-seam JVM
  where touched) + UNIT4-REPORT.md.

## Status update — 2026-11-05 ~1:30 PM ET (probe series + first gate numbers)

**What the probe series (dev/u4_probe7–28) has been chasing.**
Three seam behaviors that only appear once the fill is ON inside
a real boot, none visible from the layer alone: (a) how nested
host values stratify when stored — Eve's writers encode a host
Cons element as an EveVector, which broke `defn` (macro
expansions are seqs) with 425 boot errors; (b) which parts of
code position are the fill's surface at all; (c) why six battery
checks fail only under the fill, and why one more (eduction)
fails under one launch shape.

**Established so far, numbers as printed.**
- Deep conversion is required and is now the seam's rule: the
  hosting's `->eve` deep-converts on entry (Eve-backed → root;
  host vector/map/set rebuilt in Eve form; any seq → Eve list).
  Boot errors went **425 → 49 → 0**. Fill-on boot (default boot
  via cohost.run): **791 top-level forms, booted as stock 465,
  from host 202, natives 66, skipped 58, ERRORS 0**; `(vector 1 2
  3)`, `(defn hm ([] {}))`, `(hm)` → `{}` all correct.
- The analyzer boundary (the 49 → 0 step): source literals are
  host-reader constants on every seat, and macroexpansion
  assemblies are FORMS — the analyzer's territory, like the
  reader's output. While a `clojure.lang.Compiler` macroexpand
  frame is on the stack, the four builders answer host values;
  at program run time they answer Eve-backed values. The one
  code-position entry that IS the fill's surface: `RT-vector`
  (the analyzer's vector-literal construction), now filled.
  Small-arity `vector` (≤6) and 0-arity `hash-map` are
  literal-bodied in core.clj's own text — analyzer territory,
  host-answered, behavior-identical at the seam.
- Meta on the JVM seat: instance calls are real interop there —
  core's `with-meta` lands on Eve's stub method and returns the
  bare root (meta nil); the shim's `-withMeta` var, called
  directly, answers the layer wrapper with meta intact
  ({:m 2} measured). Named gap, see remainders.
- Host-side layer probe (dev/u4_probe6) is **fully green**:
  wrapped values (print/meta/assoc-propagation/=/hash, rseq,
  compare, IFn invoke), guarded transients (seal + scope
  errors with the named types), codec-boundary answers.
- First battery numbers on this tree (dev/u4_gate.clj):
  fill OFF — **battery 220/221**, libraries complete; fill ON
  (cohost.run default) — **battery 214/221**. The fill-caused
  six: atoms, binding dynamic, A7-4 variadic calls,
  with-local-vars, bound-fn, alter-var-root — ALL ONE
  MECHANISM: a fn or a Var reaches a checked constructor and
  the layer throws `:cohost.memory/unrepresentable`. That is
  the pre-ruling contract; the Nov 5 ~11:02 ET ruling (host
  references inside collections are domain residents,
  registered on entry) supersedes it. FIX IN PROGRESS: the
  residents mechanism — out-of-codec-universe leaves register
  in the runtime domain on entry and encode as a tagged
  reference in Eve form; reads resolve them level by level
  (the guarded materialization of the touched part);
  canonical form resolves them deeply, so unit 3's =/hasheq
  contract is unchanged.
- The eduction failure (battery row "reduce over an eduction;
  a :tag that is data"): present on this tree in BOTH fill
  modes, ABSENT on the base branch, the unit-3 tip, the merge
  commit, and M2/M3 (each measured). Bisected to the launch
  shape: runs launched with `-M:eve` fail it **3/3**
  (`ClassCastException: clojure.core.Eduction cannot be cast
  to clojure.lang.IReduce`, inside protocols' IReduceInit
  impl), runs without pass **3/3** (eduction = 9). The only
  classpath delta of `-M:eve` is the literal relative entry
  `../eve/src` placed first (measured via java.class.path;
  the classes themselves load from the same jar in both
  modes). Mechanism NOT yet diagnosed — recorded as an open
  diagnostic, not a fill defect: it reproduces with the fill
  forced off and no Eve hosting loaded. NOTE: bb.edn's
  boot:jvm uses `-M:eve`, so gate runs must state their
  launch shape until this is explained.

**Hash-facade ruling (operator, Nov 5 ~1:22 PM ET) — application.**
The seam already re-homes on entry, it does not merely wrap:
`->eve` rebuilds host collections AS Eve collections and
writes leaves (keywords, symbols included) in Eve's codec
form; from then on the value lives and hashes in Eve's world
behind the facade. hasheq answers never come from a host's
per-seat hash: they are computed from the canonical
Clojure-level form (unit 3's contract), and Eve's Merkle
digest never crosses as a hash answer. No host hash is
adopted anywhere in the seam. The one place re-homing is not
yet total is out-of-codec-universe host objects (fns, Vars):
today they are rejected with the named error; the residents
mechanism above makes them domain residents on entry instead
— that is the ruling applied to references, and it is the
fix in progress. No divergence beyond that is banked.

**Sequencing note (operator, Nov 5 ~11:48 ET) — recorded.**
The Eve-on-jank verification (end leg + flagship) drives to a
new Eve release first; this unit's jank-seat verification gate
will be re-run against the RELEASED higher-performance jank
bindings once they land. Any jank numbers produced before
that are against PRE-RELEASE bindings and are marked as such.
JVM-seat gates stand as final.

**Residents fix — LANDED (same day, ~1:50 PM ET).**
The registry + tagged-reference conversion is in
`cohost.memory-eve` (residents atom; `convert` in :store /
:probe modes; canonicalize and the seam ops resolve
references) and `cohost.memory` (construction no longer
throws the named error; `equiv`/`hasheq` canonicalize bare
Eve-backed values through `canonical-form-of`, since Eve's
own equiv cannot see through a reference). Host-side probe
dev/u4_probe29: **12/12** — fn value stored in an Eve map
comes back identical and callable (42), Var key lookup +
contains, char nth + char key probed by a different Character
instance, never-stored fn probe answers not-found, equiv +
hasheq against the host twin true, record value returns
identical, transient assoc of a fn survives persistent!.
Next: battery with fill forced, then probe_collections.

**Residents at the crossings — battery green (2026-11-05
~2:10 PM ET).** Two follow-on fixes after the residents
landing: (1) references arrive in two shapes — Eve's lookups
decode a reference as an Eve map, Eve's ITERATION materializes
it as a host map — so the hosting's one-level resolution
recognizes both; (2) the thread-binding push is a facade
crossing into the host's Var machinery (it iterates the
bindings map and casts keys to Var), so the shim's
Var-pushThreadBindings / with-thread-bindings materialize an
Eve-backed bindings map at the crossing (references resolved
one level, values keep their Eve form). Five binding-family
checks went green on that. The last battery row, atoms, was
the same shape one level over: (atom 0 :validator f) builds
its opts map through the fill, and the host atom machinery
later INVOKES what :validator holds — the shim's
-setValidator / -addWatch now resolve a resident fn at that
crossing. **Battery 221/221, libraries 62/62, boot errors 0,
fill forced.** FLAG for the unit 5 lane (per this lane's
coordination rule): the -setValidator / -addWatch edits touch
atom-surface method vars in shim.cljc; the change is confined
to resolving a resident reference argument before the host
call — no atom semantics, no var surfaces touched.

**What is running / remains.**
- Running at this bank: nothing — the last legs (battery
  controls + the eduction reruns) have completed and are
  reported above. Next: the residents fix (landed, above),
  then probe_collections.clj full run (M5; ~78 booted-world
  checks, its earlier partial run: 49 PASS before the
  macroexpand fix, with the remaining failures since
  attributed to the probe itself using literal-bodied small
  arities + the residents gap).
- Remains before the corpus run: residents fix + battery back
  to 221/221 with fill forced (M6), probe_collections green.
- Then: the 4,401-case corpus, fill forced vs the host-backed
  run, no new failures; same-run H-vs-E timings interleaved
  (M6). Then the jank gate on J (M7 — pre-release bindings,
  to be re-run post-release per the sequencing note), then
  standing checks + UNIT4-REPORT.md (M8).

**Named remainders so far.** Queue NOT-YET (plan's tail);
sorted collections OUT (Eve-side NOT-YET); macros that
embed a run-time Eve map/set VALUE into an expansion as a
literal still hit the analyzer's embed error (author's
crossing); dead roots are never reclaimed in a bare ctx
(units 5/6 + heap-grade territory); deftype cannot express
IFn's variadic invoke, so direct 21+-arg calls to a wrapped
collection-as-fn throw (`apply` is covered via applyTo);
subvec answers the host-strata vector on the JVM seat (the
adapter's RT funnel, not a creator; value correct).
**The JVM-analyzer distance (one family, measured across
the probe series):** on this seat the host analyzer compiles
program code directly, so core fns that are thin interop
wrappers or carry `:inline`s meet Eve values through Eve's
own methods, not the shim's vars: with-meta/meta (Eve stubs
meta), rseq (Eve's Reversible method unimplemented —
Eve-side), compare (the `:inline` lands on a real
Util.compare checkcast; Eve's bare types implement no
Comparable), and the inlined RT funnels (count/nth/get)
applied to values holding resident references (Eve returns
the raw reference, or serializes a resident probe key and
throws). Wherever the seam's own vars/funnels are the
surface — everything the codemod emits, and the shim vars
themselves — all of these answer correctly (the wrappers
implement meta/Comparable; the funnels probe-convert and
resolve). Codemod seats (jank/cljs) route every one of these
through the shim and do not have the distance. Closing it
on this seat is analyzer-surface work, not collection work;
it is recorded here so the corpus numbers below are read
against it.

## Coordination

- Sibling lane (unit 5) runs in parallel on atom/var surfaces.
  This lane owns the COLLECTION creators in the shim; atom/var
  files are not touched. Any unavoidable touch gets flagged
  here first.
