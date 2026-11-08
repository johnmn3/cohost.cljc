# STATUS — cohost perf refresh + lane-M validation (lane-m/cohost-perf-refresh)

## Bank 1 — 2026-11-08 ~1:22 PM ET — orientation + Eve pin + watches verified; benches submitted

**Eve version cohost depends on (firsthand, deps.edn on main @ a956f215):**
- CLJS seats: eve/eve git dep pinned to 76fd94c3dc541a1e915d6759652114c9f6b392a8 = Eve v0.9.3 release (SeniorCareMarket/eve tag v0.9.3; pin landed by lane-m/cohost-on-eve-093, ancestor of main; previous pin d241de3 = 0.9.1 line).
- Hosting surface (:eve alias): sibling checkout ../eve/src (cohost.memory-eve loads Eve namespaces from it).
- Delta vs the NEW Eve (0931 @ e727a05837, unshipped): the whole 0931 fix set over 0.9.3 — tier rounds 1-3, segmentation, STM + jank seating, DS11 tally fix, bb journal fix, allocator growth-race fix, write-floor J1. The pin bump itself is the release lane's act at ship; this lane runs the on-Eve surfaces against a sibling tree at e727a05837 (ws: /home/john/j/cohost-perf-ws/{cohost,eve->ab/eve}).

**Workstream 2 — watches verified FIRSTHAND by ls-remote (2026-11-08 ~1:17 PM ET):**
- claude/cohost-coord-2 tip 70b44bc2aea35c6246dbe4ab83ed6409a093ed53 == watermark (~/workspace/cohost-install/coord2-watch-watermark.txt). UNCHANGED — no unactioned coordinator input.
- claude/busy-rubin-cmwxzp tip bef320f44a5719ddac751f2a19b1867fa86b9fe0 == orders watermark bef320f4. UNCHANGED.

**Workstream 1 — bench refresh submitted to the J gatekeeper** (job ids 20261108T182834-cohostperf-bench-{jvm,jolt,jank,cljs,lpy}, CLASS=heavy, per-host tree copies under cohost-perf-ws/): the PERF.md lineage (bb bench:<host> -> target/bench-*.edn -> bench:report). Lineup = PERF.md's hosts (JVM, jolt, basilisp/lpy, CLJS node) + jank. Chromium: availability check owed; labeled unrun if absent.

NEXT: collect bench results as the gatekeeper runs them -> bench:report tables -> PERF.md fresh section (every row labeled Eve pairing / host / date / load). Then doc/LANE-M-VALIDATION.md (M-1..M-12 audit + migration audit + watch verdicts).

## Bank 2 — 2026-11-08 ~1:47 PM ET — JVM bench packaged for Grok: READY-TO-DISPATCH

**Package:** tools/grok-bench-jvm.sh (this branch, syntax-checked). Self-contained
on the runner box: installs JDK 21 / Clojure CLI / Babashka 1.13.226 only if
missing (a failed install exits 3 with an INSTALL_FAILED marker in the log),
clones cohost @ lane-m/cohost-perf-refresh + atom.broker @
lane-m/eve-release-0931 as siblings (eve -> ab/eve, tip e727a05837 = the NEW
Eve), preps deps, runs bb bench:jvm, banks the deliverable.
**Deliverable (named):** doc/perf/refresh-2026-11-08/bench-clj-grok.edn +
bench-clj-grok.log, committed on lane-m/cohost-perf-refresh. If the runner
box cannot push to the cohost repo, the script prints PUSH_FAILED + the
clone path and still exits with the bench rc — coordinator retrieves.

**DOUBLE-RUN GUARD (binding):** this package and J gatekeeper job
20261108T182834-cohostperf-bench-jvm are TWINS for one cell. Exactly one
runs. Protocol for whoever fires the Grok dispatch:
1. FIRST confirm the J twin still sits in ~/j-queue/inbox (queued, not
   started) AND withdraw that inbox file in the same action.
2. If the J twin has left the inbox (running or done), DO NOT dispatch.
3. Only then fire the mailbox dispatch naming tools/grok-bench-jvm.sh.
The J submission stays in place until that moment — coverage has no gap.
Directive order for the remaining packages (same pattern): jolt next, then
lpy; they are packaged on the coordinator's word, not preemptively.

## Bank 3 — 2026-11-08 ~1:51 PM ET — redistribution executed (John's standing rule)

Final J states of the five bench cells (gatekeeper done/ + results/, firsthand):
jvm EXIT=0 (bench-clj.edn), jolt EXIT=0 (bench-jolt.edn), cljs EXIT=0
(bench-cljs.edn), lpy EXIT=1 — FAILED on J: "Cannot run program basilisp"
(no basilisp on J; nothing else wrong), jank RUNNING (elapsed ~13 min at
last check — a running twin is never withdrawn or duplicated; left alone).
By execution time NO twin was still queued (the gatekeeper had drained the
inbox), so the withdrawal step was vacuous for every cell — recorded, not
assumed. The bank-2 Grok twin for JVM never fired; the guard held trivially.

Packages: tools/grok-bench.sh (jolt|jank|cljs|lpy) banked beside
grok-bench-jvm.sh, same staging/guard/deliverable pattern. Dispatch fired
for the ONE cell lacking a result: **DISPATCH #8** in the Grok mailbox
(tip 3a245186) — bench:lpy rerun, J twin's failure + the no-other-cells
instruction stated in the dispatch. Cells with J results keep them; their
Grok packages stand banked unused (rerun capacity, not duplicate work).

LABEL CORRECTION (matters for PERF.md): the CLJS seat's Eve is the PINNED
v0.9.3 git dep — dev/eve-cljs/eve_paths.clj resolves the pinned checkout
in ~/.gitlibs, NOT the sibling tree. The sibling @ e727a05837 serves the
:eve hosting alias (cohost.memory-eve). Bank-1/board wording that paired
the cljs bench with the sibling is superseded by this bank.

NEXT: bench:report over the three banked J edns (rows for jvm/jolt/cljs);
jank row when its J run lands; lpy row on Grok RESULT #8; then the PERF.md
fresh section with per-seat Eve labels (cljs = v0.9.3 pinned; others as run).

## Bank 4 — 2026-11-08 ~1:56 PM ET — refresh tables banked

All five J cells final: jvm/jolt/cljs/jank EXIT=0 (bench-jank.edn landed
after bank 3 — the running twin completed on J); lpy EXIT=1 (no basilisp
on J), rerun live as Grok DISPATCH #8. doc/PERF.md now carries the
"Refresh — 2026-11-08" section: both bench:report tables for the four
measured seats + boot table, every row under the section's labels (J,
2026-11-08, hot window load ~5-6, Eve pairing stated: pin v0.9.3 / CLJS
seat Eve v0.9.3-paired / sibling 0931 for hosting surfaces only).
Geomeans vs JVM: JVM x1.1, jank x3.1 (new seat), CLJS x7.2, jolt x9.9.
Flagged in the section, not laundered: the CLJS swap! cell (11,563 ms,
x6193.8) is anomalous — calm re-run owed before citing; jank own-core
32-bit-wrapped answers on four arithmetic cases (excluded cells, named);
Chromium UNRUN (no playwright/chromium on J, checked). Raw edns banked
in doc/perf/refresh-2026-11-08/ (bench-{clj,jolt,jank,cljs}-j.edn).
NOTE (routing change, John Nov 8): queue submissions now go through the
single job agent (~/workspace/job-agent/inbox/); DISPATCH #8 predates the
change and finishes where it is.
REMAINING: lpy row fill-in when Grok banks bench-lpy-grok.edn; the CLJS
swap! calm re-run; post-pin-bump CLJS re-run (release lane owns the bump).

## Bank 5 — 2026-11-08 ~1:57 PM ET — job-agent routing adopted

Per John's one-door rule (~/workspace/job-agent/README.md), this lane no
longer submits to any venue directly. State of this lane's queue work
under the new protocol:
- DISPATCH #8 (bench:lpy on Grok) predates the protocol; per its adoption
  clause it finishes where it is — NOT resubmitted (the job agent owns
  the double-run guard now, and a resubmission would be the double-run).
- The two unrun follow-up legs are submitted as job-agent tasks:
  inbox/cohost-perf-refresh-cljs-calm-rerun.json (the anomalous CLJS
  swap! cell; start gate load1 < 3 written into needs) and
  inbox/cohost-perf-refresh-cljs-postbump.json (CLJS seat on the shipped
  Eve; its run self-verifies the pin bump and exits PIN_NOT_BUMPED if the
  release lane has not bumped yet).
- Workstream 2's verification legs were executed and banked (banks 1-2)
  before the protocol existed; completed work is not resubmitted.
