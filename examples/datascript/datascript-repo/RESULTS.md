# RESULTS — datascript.cljc

Measured results for the one codebase, per host, per plane. Every row
carries: runtime / box / pin / tool versions / load. A failure is a
row with its exact failing command and output — never an estimate,
never smoothed over. Pins: lib = this repo's seed commit (content
byte-identical to SeniorCareMarket/eve 0.9.3.1's contract-test set,
verified by git blob SHA-1 over all 74 seed files, 2026-11-10);
Eve = SeniorCareMarket/eve @ 4a921298 (0.9.3.1).

## Contract proof (create → transact → query → pull → fresh-process readback)

| Plane | Host | Verdict | Evidence |
|---|---|---|---|
| A (on Eve) | JVM | **PASS** | write: all 6 checks pass (datoms 9, names, age-34, entity, pull name+friends), journal artifact present; fresh-process read: all 6 checks pass, seal identical to the write process ({:ptr 536870948, :blocks 125}). Hatch box, Temurin 21.0.13, Clojure CLI 1.12.3.1577, load 2.3/10.7/14.9 at start |
| A (on Eve) | Babashka | **PASS** | write + fresh-process read both all-6-checks pass, journal present, seals equal across the two bb processes. bb 1.13.226 (fleet), entered via ds-harness.bb-main (Eve's bb completion first — the shipped seat pattern; entering datascript directly on bb dies in SCI deftype analysis, per the shipped bb-flagship-main record) |
| B (cohost body) | JVM body | **PASS (gate mechanics, JVM peer scaffold)** | The shipped example's JVM body (examples/datascript/body/jvm_body.clj) wrote the contract dataset into the shared domain (CONTRACT-WRITE-OK), then two cohost.bodies/call exchanges were answered by the peer from its own deref: CALL1-DATOMS = 9, CALL2-NAMES = ["Ada" "Edsger" "Grace"]. The peer's own readback agreed (names, 9 datoms), the fresh-process harness read passed all 6 checks, and **all four prints carry the identical seal** (digest 1743633616 — also identical to plane A's JVM-built domain). The peer in this run was a JVM scaffold mirroring the basilisk body's program (seat-local de-risking); it proves the classpath, hosting, mailbox, call and shared-domain mechanics. It is NOT the basilisk verdict |
| B (cohost body) | Basilisk body | _filed to the Mac seat via the job agent (2026-11-10); see the gate note below_ | |

A note on seals: the JVM-built and bb-built contract domains agree
on :ptr, :name-hash and :blocks but their :digests differ
(1743633616 vs 1296497587). The shipped record's seal-equality claim
is for ONE shared domain read by many hosts — that is plane B's
gate, below — not for two domains built separately by two hosts.
Recorded as observed; not smoothed over, not dressed as a failure of
either host (each domain verifies exactly against its own writer's
and reader's battery).

**Plane B gate note:** the two-body gate (JVM body writes + calls,
basilisk body answers from its own deref of the shared domain, both
fresh-process harness readbacks) is scripted as
examples/datascript/run-gate.sh + mac-gate.sh in johnmn3/cohost.cljc
(branch datascript-example) and filed through the job agent to the
Mac seat (basilisk 0.5.1) on 2026-11-10 — this box has no working
basilisk (the fleet binary is caught in the box's filesystem flap and
PyPI is unreachable through its egress, both measured). The filing
also carries the D1 repro leg (seed lib) and closure leg (fixed
lib). Rows land here when the Mac RESULT banks.
**Router state, verified by seat 4 (2026-11-10 ~13:52 ET) in
~/workspace/job-agent/jobs/:** the first filing
(held-20261110Tds-cohost-mac-gate, routed 12:51 ET) is HELD —
"run is not an exact command (prose/placeholder)"; the successor
filing (held-20261110Tds-cohost-mac-gate2, routed 13:02 ET, exact
command form) is HELD — "submission to mac failed (see router
log); record kept for the sweep agent". The gate has NOT reached
the Mac; delivery waits on the same git-transport restoration as
the router's whole held set. No direct mailbox filing was made
(one-door rule).

## Shootout (cold, one datum, disk-backed, cap 10 s, median of 5)

| Plane | Host | Median | Runs | Notes |
|---|---|---|---|---|
| A | JVM | **10s+** (protocol verdict at the 10 s cap) | 152,204 / 182,940 / 213,795 / 576,590 / 438,391 ms — all five completed with ONE-DONE | Raw median 213,795 ms. Hatch box, loadavg 28–38 during the runs (a dozen lanes active), source form via Clojure CLI (the JVM compiles Eve+DS per cold process — user CPU ≈31 s of each run; the rest is load wait). This is a loaded-box row: the shipped 0.9.3.1 campaign's calm-box JVM row for this same driver was 4,308 ms. Form: `clojure -M -m contract.one`, Temurin 21.0.13 |
| A | Babashka | **10s+** (protocol verdict at the 10 s cap) | 89,482 / 82,986 / 59,590 / 49,702 / 60,197 ms — all five completed with ONE-DONE | Raw median 60,197 ms. Hatch box, loadavg 7–17 during the runs (a dozen lanes active; the bb process received ~12% CPU). Form: `run-bb.sh one` (bb 1.13.226, mode `one` → contract.one via ds-harness.bb-main). Seat-2's earlier FAIL row was a harness invocation defect, not a lib failure: the seat script passed the JVM-style mode `contract.one`, which bb-main rejects with `{:fatal "unknown mode contract.one"}`; with the corrected mode all five runs complete. Loaded-box row, like the JVM row above |

## Perf — people-1k (keys exact first, then ms)

| Plane | Host | Keys | build | find-datom | pull | datoms-seq | transact |
|---|---|---|---|---|---|---|---|
| A | JVM | **EXACT** (entities 1000, datoms 4000, names 1000, age-sum 4005, friends 2000, age-34 11, max-age 89 — atom-backed build vs in-process ground truth) | 81,677 | 535 | 4,837 | 193 | 141,513 |
| A | Babashka | _running_ | | | | | | Leg re-run by seat 4 (2026-11-10 ~13:50 ET): the bb process is CPU-starved on the shared box (~12% of one core; 3m37s CPU in 35 min wall, loadavg 23 at start falling to ~7) and still running detached; its PERF-OK EDN lands in ~/workspace/ds-cohost/bb-rerun-seat4.log when it completes. No numbers are recorded until it does — never estimated. Seat 2's identical leg died with its seat at the same phase |

JVM perf conditions (they are part of the row): hatch box under
extreme shared load (loadavg 28–34 during the run — a dozen lanes
active), Temurin 21.0.13, journal engaged (artifact present). The
build and transact columns are journal-forced commit paths and are
load-dominated here; find-datom/pull/datoms-seq are warm read paths
(535 ms per 1000 seeks, 4,837 ms per 1000 pulls, 193 ms per 4000
datoms). Calm-box re-runs replace these numbers when a seat runs
them; they are never estimated in the meantime.

## Defect ledger

### D1 — basilisk: datascript.db fails macroexpansion at the :lpy `tx-id?` `.equals` arm

- **Claim (inherited, being re-derived):** on basilisk, requiring
  datascript.db fails at macroexpansion, db.cljc:2484 — the `:lpy`
  `tx-id?` arm's `(.equals ":db/current-tx" e)` forms.
- **Provenance:** perf-doc lane, Eve 0.9.3.1 pin, fresh cache, minimal
  repro; PERF-DOC-REPORT.md on atom.broker branch lane-m/perf-doc-0931.
  Handed to this repo's seat 2026-11-10.
- **Tree leg (verified at the seed):** db.cljc:2484 is exactly that
  arm. The file's own PORT DELTA comment (db.cljc:2471-2477) records
  F-CLJR-TXID-EQUALS: the identical `.equals` body already died on
  :cljr and was fixed there by switching to the portable `=` body;
  the :lpy arm never got the same treatment.
- **Reconcile target:** lane-m/lpy-standing (atom.broker) passed
  people-1k on basilisk; only a subset of its fixes reached 0.9.3.1.
  Diff its db.cljc against this pin before designing any fix.
- **Reconcile result (2026-11-10):** DONE — the lpy-standing tree's
  db.cljc (blob 8978a91c vs the pin's 8e565b87) already carries the
  fix in exactly this arm: the portable `=` body with `^Boolean`
  dropped, the F-CLJR pattern. The 0.9.3.1 collation subset never
  included it. The fix is applied in this repo as the commit after
  the pure seed commit (the seed stays byte-identical to 0.9.3.1).
- **Disposition:** FIXED-IN-TREE — closure pending a basilisk run of
  the fixed lib at this pin (the plane-B gate's basilisk
  harness-read is that run). The old pin's failure stands on the
  perf-doc lane's fresh-cache minimal repro, cited above.

## Carried rows (Eve 0.9.3.1 record, not re-run at this pin)

From the shipped tree's own record (docs/datascript-eve/GUIDE.md host
table and doc/performance.md on SeniorCareMarket/eve @ 4a921298):
jolt, nbb, glojure, jank, ClojureCLR and compiled ClojureScript all
ran this same library content on Eve in the 0.9.3.1 campaign
(people-1k complete-verified on ClojureCLR; jank one-datum contract
5/5 at 0.317 s median with the journal engaged; compiled-CLJS
people-1k writes proven with 9/9 cross-host readback checks). These
rows are carried as provenance, labelled as the 0.9.3.1 campaign's —
this repo re-runs hosts as seats and venues allow and replaces a
carried row with a measured one whenever it does.
