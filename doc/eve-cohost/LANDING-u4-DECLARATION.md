# LANDING DECLARATION — unit 4 (Eve-backed collections) on the Eve-in-cohost mainline

2026-11-05 ET. Operator's order: "Land it" (~3:29 PM ET). Collation pattern
as units 7, 8, 9.

- **Base:** `muse/eve-cohost-impl` @ `37dbcf4` (verified via ls-remote/fetch
  before basing).
- **Merged in:** `muse/eve-cohost-impl-u4` @ `63feae8` (unit 4 complete;
  UNIT4-REPORT.md on that branch).
- **Merge commit:** `1ec21fc` (--no-ff). Worktree
  `~/workspace/wt/collate-u4`; `~/src/cohost` untouched. Gate logs in
  `~/workspace/wt/collate-u4-logs/`.

## Conflicts — three files, all resolved as unions

1. **deps.edn** — mainline's `:cljs` alias (Eve as a git dep for the
   cljs seat) vs unit 4's `:eve` alias (sibling checkout) + its own
   `:cljs`. Resolution: BOTH aliases; the `:cljs` kept is mainline's
   richer formulation (with the Eve git dep pin). bb.edn auto-merged
   (unit 4's one-line change: boot:jvm runs `clojure -M:eve`).
2. **src/cohost/boot.cljc (1 region)** — `boot!`: mainline's
   `:eve-atoms` opt (unit 5) vs unit 4's `:eve-collections` opt.
   Resolution: boot! takes both opts; the state swap is mainline's
   formulation (host-lacks union incl. atom-surface-names); unit 4's
   `(install-collection-fill! eve-collections)` call sits in its own
   relative position, right after the swap, before the prelude. All
   four installs verified wired in the merged boot!:
   install-collection-fill! (l.1053), install-thread-layer!,
   install-atom-layer!, install-http-layer! (l.1108–1113).
3. **src/cohost/shim.cljc (5 regions)** — mainline's atom-layer
   (unit 5) and http (unit 9) branches vs unit 4's memory branches:
   - class-of: atom-value branch kept before the map branch; inside
     the map cond, unit 4's eve-backed? → PersistentHashMap test
     first, then h-sorted?.
   - Future.get dispatch: http Optional branch AND unit 4's
     transient/eve-backed lookup branches (disjoint value classes).
   - -peek/-pop/-meta: unit 4's transient-aware forms (a superset:
     -pop keeps mainline's h-transient? arm); -meta gained both
     checks as a cond (atom-layer, then transient, else host).
   - -withMeta: :jank keeps mainline's atom-layer check; :default is
     a cond of atom-layer, transient, eve-backed, else stock.
   - Atom ops (-swapVals/-resetVals/-compareAndSet/-addWatch/
     -removeWatch/-getWatches/-setValidator/-getValidator):
     mainline's atom-layer forms kept entire; unit 4's contribution
     composed into the host path — -addWatch and -setValidator pass
     their fn argument through memory/resolve-resident (unit 4's
     flagged crossing: a watch fn/validator held as a resident
     reference in an Eve-backed opts map resolves to the registered
     fn before the host's atom machinery invokes it), with unit 4's
     explanatory comment retained.
   No defn, install step, or require dropped from either side
   (both files already required cohost.memory and cohost.atom).
   Paren checks OK: boot.cljc 145 forms, shim.cljc 973 forms,
   memory_eve.clj 56 forms.

## Gates on the merged tree (as printed)

- **bb boot:jvm** — boot 791 top-level forms, **errors 0**;
  **battery 220/221**; **libraries 62/62**. The one FAIL is
  `reduce over an eduction; a :tag that is data`. **This failure is
  the unit branch's own, not a merge defect:** re-running
  `bb boot:jvm` on the unmerged unit tip `63feae8` reproduces it
  identically (220/221, same row; log
  `collate-u4-logs/boot-jvm-u4tip.log`). It is unit 4's named
  remainder — the eduction launch-shape diagnostic (fails under the
  `-M:eve` launch shape unit 4's bb.edn introduces, fill off
  included, per UNIT4-STATUS) — and the recheck that lane owed "on
  the landed tree" is hereby performed: it reproduces on the landed
  tree exactly as on the unit branch. It is NOT fixed in this
  collation and is carried as a named remainder. (The mainline base
  scored 221/221 at the u8 landing under its own launch shape.)
- **bb cli:check cljs** — **7/7 SAME**.
- **bb boot:cljs** — **rc=0**; battery **219/221** with FAIL rows
  exactly the pristine pair (numeric tower; defmacro &form &env);
  libraries **62/62**.
- **Unit 4 spot-check** — `probe_collections.clj`, fill ON, on the
  merged tree: **"PROBE 76 checks, 0 FAIL"**.
- **Conflict-marker sweep on the committed merge tree (1ec21fc)** —
  line-anchored `<<<<<<< ` / `>>>>>>> ` / `=======`: **0 / 0 / 0**
  over the whole tree, no extension filter. (Six unanchored hits
  are prose quotations of the marker strings inside prior landing
  declarations, identical on the base 37dbcf4.)
- **Jank gate — carried evidence, not re-run:** unit 4's lane ran
  it on J against pre-release Eve bindings (tree f0b12cb):
  boot:jank-snapshot RC 0; boot:jank RC 0, battery 219/221 with
  only the standing pair; libraries pass. It re-runs against the
  released 0.9.3 bindings when those land, per the release plan.

## Final state

The mainline now carries units **1, 2 (all seats), 3, 4, 5, 7, 8,
9**. Unit 6 (slab residence on jank) is the only unit out — its
implementation proceeds on the Agent 3 line now that the flagship
GATE has banked. Unit 4's other named remainders ride with the
merge unchanged (queue NOT-YET; sorted collections out; the
analyzer-distance and analyzer-embed families; HAMT print order;
dead-root reclamation — heap-grade territory; the deftype/IFn
variadic-invoke limit).
