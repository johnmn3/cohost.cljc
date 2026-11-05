# DECLARATION — units 7 and 9 landed on the Eve-in-cohost mainline (2026-11-05, ET)

Operator-ruled ~14:32 ET: take heap-grade to step 3 (separate lane) and land
units 7 and 9, agents on them. This collation landed the two units IN SEQUENCE
from a fresh worktree; ~/src/cohost's working tree was not touched.

## Base
- muse/eve-cohost-impl @ 21e1d9f (units 1, 2 all seats, 3, 5 landed).

## Landing 1 — unit 7, tiered atoms (mem -> disk -> net)
- Branch: agent1/eve-unit7-tiered-atoms @ 2c991ea (its own gate: JVM + jank
  over one disk atom 9/9, kill/rejoin both ways, per doc/eve-cohost/unit7/).
- Merge commit: 460b7b2.
- Conflicts: ONE, src/cohost/boot.cljc — the :host-lacks expression. The two
  sides are the same union in two formulations (mainline: into/concat/when;
  unit 7: cond->/into): host-lacks + core-lacks + atom-surface-names when
  :eve-atoms. The mainline formulation was kept; behavior is identical.
  src/cohost/shim.cljc auto-merged and was verified to carry both sides
  (unit 5's [cohost.atom :as atom-layer] require and unit 7's convey
  additions). Resolved boot.cljc paren check: OK, 138 forms.
- Gates ON THE MERGED TREE (460b7b2), as printed:
  - bb boot:jvm — "battery: 221/221 stock behaviors pass";
    "libraries: 62/62 cases pass across 12 clojure.* libraries"; errors: 0; rc=0.
  - bb cli:check cljs — 7/7 SAME; rc=0.
  - bb boot:cljs — rc=0, errors: 0; the only FAIL rows are the pristine pair
    (numeric tower; defmacro &form &env).
  - Conflict-marker sweep on the committed tree: line-anchored
    <<<<<<< / >>>>>>> / ======= = 0/0/0. (Raw substring hits are the prose
    "(<<<<<<< / >>>>>>>)" inside the previous landing's docs — the identical
    set exists at base 21e1d9f; the merge added none.)

## Landing 2 — unit 9, Java HTTP APIs over Eve's HTTP layer + portable Ring
- Branch: agent2/eve-unit9-http @ 2a551e9 (its own gate: 36/36 JVM, 36/36
  cljs, Content-Type compared parsed per the operator's raw-text ruling).
- Merge commit: e53d581, onto the post-unit-7 mainline (460b7b2 + status
  commit cad0c87).
- Conflicts: ONE file, src/cohost/boot.cljc, three regions — all pure unions,
  nothing dropped from either side:
  1. ns requires: kept both [cohost.atom :as atom-layer] (unit 5) and
     [cohost.http :as http] (unit 9).
  2. install defns: unit 5's install-atom-layer! and unit 9's
     install-http-layer! both kept whole; each defn carries its own
     trailing report (the two branches had shared one in the fork base).
  3. boot! call sites: both installs run, atom layer first, then the HTTP
     layer; the snapshot-replay call to install-http-layer! (unit 9's,
     auto-merged) resolves against the kept defn.
  shim.cljc and thread.cljc auto-merged; paren checks OK (boot 143 forms,
  shim 971, thread 90).
- Gates ON THE MERGED TREE (e53d581), as printed:
  - bb boot:jvm — "battery: 221/221 stock behaviors pass";
    "libraries: 62/62 cases pass across 12 clojure.* libraries"; errors: 0; rc=0.
  - bb cli:check cljs — 7/7 SAME; rc=0.
  - bb boot:cljs — rc=0, errors: 0; the only FAIL rows are the pristine pair
    (numeric tower; defmacro &form &env).
  - Conflict-marker sweep on the committed tree: 0/0/0 (same note as above).

## Final state
- Mainline tip after this declaration commit: see the fetched remote ref for
  muse/eve-cohost-impl (every push in this collation was verified on the
  fetched ref, never on push output).
- The mainline now carries units 1, 2 (all seats), 3, 5, 7, 9. Remaining in
  the unit sequence: unit 4 (running), unit 6 (gated on the Eve-on-jank
  flagship gate), unit 8 (running, cohost Agent 1). Jank-seat gates re-run
  against the released Eve 0.9.3 bindings per the standing release-first
  dependency; unit 9's jank seat additionally waits on Eve seam 8 (F-7).
- Gate logs: ~/workspace/wt/collate-u7-u9-logs/gates-u7.log and gates-u9.log
  (this lane's worktree logs dir).
