# Lane-M validation — the cohost fleet record, chased down (2026-11-08)

Read-only audit by lane-m/cohost-perf-refresh. Every claim below names its
verification method. Nothing was merged, moved, or extended by this audit.

## The two standing watches — verified firsthand, UNCHANGED

Method: git ls-remote origin, 2026-11-08 ~1:17 PM ET, compared against the
watermark files.

| branch | remote tip (ls-remote) | watermark | state |
|---|---|---|---|
| claude/cohost-coord-2 (coordinator ledger) | 70b44bc2aea35c6246dbe4ab83ed6409a093ed53 | 70b44bc2 (~/workspace/cohost-install/coord2-watch-watermark.txt) | tip == watermark — no unactioned coordinator input |
| claude/busy-rubin-cmwxzp (orders) | bef320f44a5719ddac751f2a19b1867fa86b9fe0 | bef320f4 | tip == watermark — no unactioned orders |

## The M-line (M-1 .. M-12) — banked state

Method: commit-subject census over origin/main of johnmn3/cohost.cljc +
branch tips by ls-remote + file reads from fetched refs.

- M-1, M-2, M-3, M-4, M-5, M-6, M-9: banked on main (unit commits in main's
  log; the fleet also used unit-n labels for the same work — e.g. the
  REBASE-093 series records unit-4/unit-6/unit-8/unit-9 on
  lane-m/cohost-on-eve-093, an ancestor of main).
- M-11: DONE on main — corpus 4401 across every host (88047c0 "M-11 rebased
  onto main (corpus 4401)"; fb36ee4 the merge into the release line; REPORT
  close-out commits 47674fd et al.). doc/REPORT.md on main is its record.
- M-12: COMPLETE but banked on a side branch — muse/release-perf @ 9944dd9
  ("M-12: the jank half of the release perf, measured on the hatch build
  box @ 6eec4e2", 2026-11-02). Its deliverable, doc/perf/release-perf.md +
  doc/perf/release-perf/*.edn (the 29-program bench: jvm stock / jvm-cohost /
  jank-jit / jank-aot / jank snapshot, median ms + xJVM), exists ONLY on that
  branch; the diff vs main is exactly those 7 files.
  FLAG (the one piece of M-work not on the ground in main): release-perf.md
  was never collated into main's doc/PERF.md. The perf-refresh lane
  supersedes its numbers with fresh ones rather than landing the stale doc.

## The release composition around the M-line

- main @ a956f215 (2026-11-07) IS the release composition: "release/eve-in-
  cohost fast-forwarded to main on the operator's word."
- lane-m/cohost-on-eve-093 (ancestor of main): REBASE-093 COMPLETE — seat
  batteries on the assembled tree (JVM 221/221, cljs 219/221 + 62/62 libs,
  jank 219/221 + 62/62); it moved the Eve pin to the 0.9.3 release (below).
- lane-m/cohost-disentangle @ c84f392b42: the five-seat installer (jank, bb,
  clj, jolt, ClojureCLR; one snapshot key df88adac...). The release line's
  cohost ship packet is built on it; ship call outstanding (John's).
- lane-m/cohost-release-audit @ f969ecfb (2026-11-07): independent
  verification of release/eve-in-cohost — banked, no open items named in its
  tip commits.

## The Eve dependency (what the release hangs off)

Method: deps.edn read on main @ a956f215.
- CLJS seats: eve/eve git dep pinned to 76fd94c3dc541a1e915d6759652114c9f6b392a8
  = Eve v0.9.3 release (SeniorCareMarket/eve, tag v0.9.3). Previous pin
  d241de3 (0.9.1 line).
- Hosting surface (:eve alias): sibling checkout ../eve/src.
- The NEW Eve is the 0931 collated tree @ e727a05837 (unshipped): over
  0.9.3 it carries tier rounds 1-3, segmentation, STM + jank seating, the
  DS11 tally fix, the bb journal fix, the allocator growth-race fix, and
  write-floor J1. The pin bump is the release lane's act when Eve ships.

## The external-model migration — read-only audit

Method: branch tips by ls-remote (2026-11-08) + the fleet's banked record.
The vars-registry line (externally coordinated; pathways P0-P6, one escape
hatch EH1) stands on atom.broker branches incl. claude/vars-registry-atom
@ 56bf427e, claude/fix-vars-registry-atom @ cae9506d,
claude/unify-fix-vars-regrow @ 7123983c, claude/r8-gc2-vars-async
@ bde9c1cc. Banked state per the record: Act 1 DONE (verified firsthand
against its branch when it landed); the Act 6 arc is CLOSED — (A) frozen
and gate-green, its one re-encode closed at stale 0, G3 landed on main with
its pair reproduced byte-identically, the install reproduced byte for byte,
boot/program-runtime records at stale 0. This lane made no merges or moves;
nothing here is unactioned for the local fleet — the line is the external
coordinator's, and its last verified state is as recorded above.

## Unactioned input flagged by name

1. doc/perf/release-perf.md (M-12) not collated into main — disposition:
   superseded by this lane's fresh perf refresh (doc/PERF.md, same date
   series), not landed stale.
2. Nothing from either watch branch (both at watermark).
3. The migration line: externally directed; state as audited; no local
   action owed.
