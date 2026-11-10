# RECOVERY STATUS — DS-on-cohost successor seat 3, 2026-11-10 ~13:40 ET

Predecessor (seat 2) died on a platform transport error at 13:33 ET
after ~85 min of real work, no declaration. This is the inventory of
what it left, verified firsthand by seat 3 before building anything.

## What exists

- **johnmn3/datascript.cljc: still does NOT exist** (API 404).
  Creation was attempted by seat 2 through both sanctioned routes —
  GitHub MCP create_repository and stored-PAT POST /user/repos —
  both 403 ("Resource not accessible"). The repo is staged
  repo-ready locally at ~/workspace/ds-cohost/datascript-repo/
  (lib-ds + lib + lib-ds-deps + test drivers + harness + docs +
  README + RESULTS, deps.edn pinning Eve as a git dep at SCM/eve
  sha 4a921298, no repo-relative Eve paths). Unblocking act is
  operator-side: create the empty repo or widen the token/App.
  Seat 3 re-tests creation once; everything else proceeds regardless.
- **Example consumer: BANKED.** johnmn3/cohost.cljc branch
  `datascript-example` is 3 commits ahead of main (a956f215):
  README, deps.edn, src/ds_example/demo.cljc, body/jvm_body.clj,
  body/lpy_body_ds.lpy, run-gate.sh, mac-gate.sh — thin consumer,
  no second lib copy (verified by compare file list).
- **Seed verified by content (seat 2):** all 74 files of the shipped
  SCM/eve 0.9.3.1 contract-test set byte-identical to the staged
  lib; the D1 fix rides as a post-seed change in the staged tree
  (see RESULTS.md defect ledger).

## What was proven (evidence: datascript-repo/RESULTS.md + seat logs)

- Plane A (directly on Eve) contract, JVM: **PASS** — write + fresh-
  process read, all 6 checks, journal present, seals equal.
- Plane A contract, Babashka: **PASS** — same battery, bb 1.13.226.
- Plane A people-1k perf, JVM: **keys EXACT**; timings banked with
  their loaded-box conditions named (loadavg 28–38; never smoothed).
- Plane B (cohost body) gate mechanics, JVM body + JVM peer
  scaffold: **PASS** — shared domain, two bodies/call exchanges,
  all four prints one identical seal. NOT the basilisk verdict.
- Plane A shootout, JVM: protocol verdict **10s+** at the cap (all
  five runs completed far over cap on the loaded box; raw times and
  the calm-box campaign row both recorded in RESULTS.md).

## What remains

- **Basilisk plane-B gate + D1 closure:** scripted (run-gate.sh +
  mac-gate.sh on the example branch) and filed by seat 2 through
  the job agent to the Mac seat. This box has no working basilisk
  (PyPI unreachable through egress — measured again by seat 3;
  the fleet binary is caught in the box filesystem flap). Seat 3
  verifies the filing's router state and banks the RESULT when it
  lands. D1 stays FIXED-IN-TREE until that run.
- **bb shootout row:** seat 2's five runs all FAILed on a harness
  defect — `{:fatal "unknown mode contract.one"}`: the shootout
  invoked the bb entry with a JVM-style mode it doesn't accept.
  Not a lib failure. Seat 3 fixes the invocation and re-runs.
- **bb people-1k perf:** leg was mid-run at the death ("BB PERF"
  header, no numbers). Seat 3 re-runs it.
- **Repo push:** the moment creation unblocks — seed commit first
  (byte-identical to 0.9.3.1), D1 fix as the commit after.

---

# SEAT 4 ADDENDUM — 2026-11-10 ~13:52 ET

Seat 3 died undeclared minutes after banking the recovery above
(platform daemon restart). Seat 4 re-verified the inventory firsthand:
johnmn3/datascript.cljc still 404; example branch tip 6964d55e (3
commits over main a956f215, examples/datascript complete, thin);
staged repo tree intact at ~/workspace/ds-cohost/datascript-repo/.

Mac filing router state, verified in ~/workspace/job-agent/jobs/:
- held-20261110Tds-cohost-mac-gate.json (routed 12:51 ET): HELD —
  "run is not an exact command (prose/placeholder)" (base64 script
  rejected by the router).
- held-20261110Tds-cohost-mac-gate2.json (routed 13:02 ET): HELD —
  "submission to mac failed (see router log); record kept for the
  sweep agent". The basilisk two-body gate has NOT reached the Mac;
  it is blocked on the same git-transport failure as the rest of the
  held set. One-door rule: no direct mailbox filing from this seat.
  D1 closure and the basilisk plane-B row wait on that delivery.

Seat 4 work queue: re-test repo creation once; re-run bb people-1k
perf (seat 2 died mid-run — bb-legs.log ends at the BB PERF header;
bb contract write/read PASS with journal, seals equal); re-run the
bb shootout with the corrected invocation (`run-bb.sh one` — the
mode is `one`, not the JVM-style `contract.one` that produced
{:fatal "unknown mode contract.one"}).

---

# SEAT 4 DECLARATION — 2026-11-10 ~14:35 ET (partial: one named blocker, one running leg)

Seat 4 (successor to seats 1–3, all lost to platform failures)
completes the recoverable work and declares:

DONE THIS SEAT
- Inventory re-verified firsthand (repo 404; example branch;
  staged tree; bb-legs.log; router held records). Recovery banked
  to the example branch (96f63ca5) before any other work.
- Repo creation RE-TESTED once: PAT POST /user/repos still 403
  "Resource not accessible by personal access token". BLOCKER
  stands, operator-side: create the empty johnmn3/datascript.cljc
  repo or widen the token/App. The staged tree at
  ~/workspace/ds-cohost/datascript-repo/ is push-ready: seed commit
  = the byte-identical 0.9.3.1 set, D1 fix as the commit after
  (fix re-verified in-tree this seat: db.cljc:2484 :lpy arm carries
  the portable `=` body, ^Boolean dropped — the lpy-standing arm).
- Mac gate filing router state VERIFIED (see seat-4 addendum
  above): both filings HELD at the router, the exact-command
  successor held on "submission to mac failed". Basilisk plane-B
  gate + D1 closure remain venue-blocked on git-transport
  restoration; nothing this seat can lawfully do moves them
  (one-door rule).
- bb shootout RE-RUN with the corrected invocation (`run-bb.sh
  one`): all 5 runs ONE-DONE — 89,482 / 82,986 / 59,590 / 49,702 /
  60,197 ms; median 60,197 ms; protocol verdict 10s+; loadavg
  7–17, bb at ~12% CPU (loaded-box row). Banked in RESULTS.md.
  The seat-2 FAIL row is closed as a harness invocation defect
  (mode `contract.one` passed to bb-main), not a lib failure.

STILL RUNNING (named remainder, not a verdict)
- bb people-1k perf: re-run started 13:50 ET; the bb process is
  CPU-starved on the shared box (3m37s CPU in 35 min wall) and
  continues DETACHED (parent script shell killed by this seat so
  no duplicate shootout leg starts after it; bb survives under
  init). Its PERF-OK EDN lands in
  ~/workspace/ds-cohost/bb-rerun-seat4.log; the RESULTS.md row
  stays _running_ until a seat reads it back. Never estimated.

STATE AT DECLARATION: everything provable from this box is proven
and banked — plane A contract JVM+bb PASS, plane A perf JVM
EXACT, plane A shootout JVM+bb rows banked, plane B JVM-body gate
mechanics PASS, example consumer banked on cohost.cljc
datascript-example. Open, in order: (1) repo creation (operator
act), then the push is mechanical; (2) Mac delivery of the
basilisk gate (router/git restoration), closing plane B + D1;
(3) the detached bb perf leg's readback.

---

# SEAT 5 ADDENDUM — 2026-11-10 ~14:25 ET

Seat 5 (successor; seat 4 ended with work open). Recovery verified
firsthand this seat:

- **Repo: still 404.** Creation re-tested once via PAT
  POST /user/repos → 403 "Resource not accessible by personal
  access token" (fourth recorded failure; MCP route also 403 in
  earlier seats). Blocker is operator-side and unchanged: create
  the empty johnmn3/datascript.cljc repo or widen the token/App.
  The staged tree stays push-ready.
- **Staged tree re-verified:** lib-ds/deps.edn pins Eve as a git
  dep on SeniorCareMarket/eve @ 4a921298 — no repo-relative Eve
  path. D1 fix present at lib-ds/datascript/db.cljc:2484 — the
  :lpy arm carries the portable `=` body, `^Boolean` dropped.
- **Detached bb people-1k perf leg (pid 9779): still running** at
  recovery (~33 min wall, CPU-starved, started under load ~23);
  log holds only the BB PERF header. This seat monitors and
  harvests its PERF-OK EDN into RESULTS.md when it lands; no
  duplicate started.
- **Load at recovery: load1 ~22.** Under the operator's load
  discipline no heavy local run starts above load1 8, and only
  one heavy leg runs at a time (the detached perf leg is it).
  The clean bb shootout re-run — which must replace the
  contention-tainted 60,197 ms banked row — waits for a calm
  window AND the perf leg's completion. If no calm window comes
  this seat, the tainted row stands labelled as such; it is never
  replaced by an estimate.
- **Router Mac filings: both still HELD** (read in
  ~/workspace/job-agent/jobs/ this seat). Plane-B basilisk gate
  and D1 closure remain venue-blocked on git-transport
  restoration; the one-door rule stands.
