# Docs lane — DECLARATION (2026-11-05 ET)

**Branch:** `muse/eve-cohost-docs`, off `muse/eve-cohost-impl` @
c2323ec (units 1, 2, 3, 5, 7, 9 landed there; unit 8's merge b744ad2
banked with its landing gates still running — hence GATE-GREEN below).
Worktree `~/workspace/wt/cohost-docs`; `~/src/cohost` untouched.

## Files written

- `doc/guide/12-threading-with-eve.md` — the filled concurrency surface
  (the 29 names of `cohost.policy/thread-names`, verified by grep),
  stock semantics and the named distances, the mesh model (core thread
  first, synchronous access), boot parity, per-seat reality, the
  probe program as the worked example.
- `doc/guide/13-memory-with-eve-atoms.md` — the Eve atom layer: what
  changes and what does not, the named semantic differences (seam-
  equality CAS, host-held roots, whole-root deref, print form), domains
  in plain terms, the ClojureScript shared heap, the integer-key
  defect, and the cost numbers printed in UNIT5-REPORT.
- `doc/guide/14-tiered-atoms.md` — kinds by metadata (examples verbatim
  from unit7/REPORT), disk atoms (OS-resolved join, kill/rejoin as
  gated), network atoms as exercised, cross-process conveyance, the
  rules table with error keys.
- `doc/guide/15-eve-coverage-and-status.md` — the matrix: facility ×
  seat (JVM / jank / ClojureScript-Node) × LANDED / GATE-GREEN /
  NOT-YET, every row's Sources column citing its report or gate line.
- `README.md` — guide index entries 12–15.
- `doc/eve-cohost/DOCS-STATUS.md` — sources per guide and the record
  ambiguities, named rather than guessed.

Commits, in order: STATUS opened (fe4bb52), ch. 12 (4149cd1), ch. 13
(b95b8c8), ch. 14 (78f3c1f), ch. 15 + README index (af7143b), this
close-out.

## The matrix's NOT-YET cells, listed

Threading: `seque` on all three seats (host-only, unfilled).
Memory: Eve-backed Atom on jank (unit 6's ground); the registry atom
on jank and on ClojureScript.
Tiered atoms: disk atoms through cohost's layer on jank, and on
ClojureScript (throws loudly); network atoms on jank and on
ClojureScript; cross-process binding conveyance on jank and on
ClojureScript.
Not-yet-anywhere rows: Eve-backed collections (unit 4) on every seat;
slab residence on the jank seat (unit 6); the browser seat (no Eve);
sorted collections behind the seam.
GATE-GREEN (not NOT-YET, not LANDED): worker realms / shared heap on
ClojureScript, and the Eve-backed Atom on ClojureScript — both unit 8.

## Designed-but-unexercised, marked as such in the guides

1. **A network atom's client-side disk LRU** — the tier model says a
   network atom contains its disk level; as exercised (unit7/REPORT),
   the disk level is the gateway member's disk domain and the client's
   block cache is in memory. Chapter 14 states both, in those terms.
2. **`COHOST_CLJS_REALMS` above the default of 1 worker** — the knob
   exists in the code; unit 8's gate exercised only the default. (Not
   printed in the guides; recorded here.)
3. **The conveyance key names** (`:eve/conveys` / `:eve/convey`) —
   landed mechanism, but the unit-7 report marks the expression "the
   operator's to confirm"; the guides document the keys as they stand
   in the code without claiming the confirmation.
4. **`test-stacktrace` on ClojureScript** — the probe row matches the
   JVM on 22 of 23 cases; the stack-trace case is a JVM measurement
   with no honest cljs answer, left to the operator's ruling in
   unit8/REPORT and stated as pending in chapters 12 and 15.

## Honesty boundaries kept

- Cross-process claims rest only on unit 7's disk-atom gate (a JVM
  process through cohost's layer + a jank process on Eve-on-jank
  directly, 9/9, kill/rejoin both ways); chapter 14 says which side of
  that gate was cohost's layer and which was Eve directly.
- Unit 6 (slab residence on jank) is documented as NOT-YET: prep,
  scaffolding and a JVM reference leg only.
- Unit 4 (Eve-backed collections) appears only as a NOT-YET line in
  the matrix; no collections guide was written.
- No performance claim appears except UNIT5-REPORT's printed atom
  numbers, cited with their date and context.
- Every function and metadata key named in the guides was verified
  against the tree or the unit reports before being written down.
