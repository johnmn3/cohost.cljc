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
