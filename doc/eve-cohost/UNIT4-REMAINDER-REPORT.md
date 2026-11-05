# Unit 4 remainder — the eduction battery row: verdict

Branch `muse/eve-cohost-impl` @ 7f464d4 (the unit 4 landing
tip). This closes the named remainder carried by
LANDING-u4-DECLARATION.md and the open diagnostic in
UNIT4-STATUS.md ("the eduction launch-shape diagnostic"):
battery row `reduce over an eduction; a :tag that is data`
read 220/221 under `bb boot:jvm` (the `-M:eve` launch shape)
on the unit branch and on the merged tree, 221/221 under
launch shapes without `-M:eve`.

## Verdict

**Dispositioned: not a cohost defect, not an Eve defect,
not a merge defect.** The failing code is stock Clojure
1.12.0's own `clojure/core/protocols.clj`, which cohost
boots unmodified by design. No cohost or Eve code changes.
The row stands in the battery as the canary for the stock
fragility named below.

## The mechanism (diagnosed firsthand)

`(reduce + (eduction (filter odd?) [1 2 3 4 5]))`:

1. `clojure.core/reduce` (stock core.clj, vendored at
   resources/cohost/clojure-1.12.0) sends any coll that is
   not `clojure.lang.IReduce` to
   `clojure.core.protocols/coll-reduce` at 2-arity.
   `clojure.core.Eduction` implements `IReduceInit` only —
   it is not an `IReduce` — so every eduction reduce takes
   this path, in stock Clojure too.
2. CollReduce has three impls an Eduction matches: `Object`
   (seq-reduce), `Iterable` (iter-reduce), and
   `clojure.lang.IReduceInit`. The IReduceInit **2-arity**
   impl is defective in stock Clojure: its body is
   `(.reduce ^clojure.lang.IReduce coll f)` — it casts the
   reducible to `IReduce`, which an IReduceInit-only
   reducible is not (protocols.clj lines 85–88 of the
   vendored stock source). If dispatch selects that impl,
   the call throws exactly the battery's error:
   `class clojure.core.Eduction cannot be cast to class
   clojure.lang.IReduce (clojure.core.Eduction and
   clojure.lang.IReduce are in unnamed module of loader
   'app')`.
3. Proven by direct invocation in plain stock Clojure
   (no cohost, no Eve): calling the IReduceInit impl's
   coll-reduce fn on an eduction throws that byte-identical
   message; calling the Iterable impl's fn returns 9.
4. Which impl dispatch selects among the three unrelated
   matches is a tie-break with no specificity order to
   decide it; it rides on the protocol impl map's hash
   iteration order over Class keys — JVM allocation-history
   state. That is why the failure *presented* as
   launch-shape-deterministic on unit 4's box (3/3 fail
   under `-M:eve`, 3/3 pass without; the collation gate hit
   it on both the unit tip and the merged tree) and why the
   classpath delta of `-M:eve` — one directory entry, with
   the classes loading from the same jar in both shapes —
   could flip it without any code path differing. It fires
   with the fill forced off, in which state
   `cohost.memory-eve` is never required and no Eve
   namespace loads (boot.cljc install-collection-fill!), so
   the Eve-backed seam is not on the path at all.

## Reproduction state today (this box, this branch)

The failure does **not** reproduce on today's box state
under any launch shape tried. The sibling `../eve`
worktree that unit 4's and the collation's runs resolved
against no longer exists (lost with a box cycle); this
lane restored it as a symlink to the real Eve checkout
(~/src/eve) before running, so `../eve/src` resolves
exactly as the alias intends. The tree (7f464d4) and the
`-M:eve` classpath basis are byte-identical to the
collation's (same .cpcache basis in the collation
worktree). Raw logs: ~/workspace/u4r-logs/ (boot-jvm-eve.log,
probe-eve.log, verify.log).

- `bb boot:jvm` (= `clojure -M:eve -m cohost.run`, fill
  default on): **battery 221/221, libraries 62/62**.
- dev/u4r_probe27.clj (= unit 4's dev/u4_probe27.clj),
  four runs: `-M:eve` fill off ×2, `-M:eve` fill on ×1,
  no-`-M:eve` fill off ×1 — **eduction: 9 in all four**.
- dev/u4r_probe.clj under `-M:eve`, fill off: the row
  evaluates to 9; the Eduction instance's class is the
  jar's `clojure.core.Eduction` (AppClassLoader, code
  source the clojure 1.12.0 jar), identical to the class
  literal; `instance? IReduceInit` true, `IReduce` false.
- probe_collections.clj, fill on, the gates' -Sdeps
  shape: **PROBE 76 checks, 0 FAIL** (unchanged).

## If it recurs

The battery row is the canary: a 220/221 whose only FAIL
is this row, with the cast message above, is this stock
dispatch fragility surfacing in that JVM state, not a
regression in the seam. Do not patch the vendored stock
protocols.clj (the boot's premise is stock core, byte for
byte); a host-side mitigation, if one is ever wanted,
belongs at the battery/harness level and needs its own
ruling.

## Files banked with this report

- dev/u4r_probe.clj — the diagnostic probe (row + class
  identity checks in the booted context).
- dev/u4r_probe27.clj — unit 4's eduction probe, copied
  so the repro of record lives on the mainline.
