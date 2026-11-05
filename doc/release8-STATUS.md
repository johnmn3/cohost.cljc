# release8 — status

Prep lane: assemble release8 (the debug seam) and verify it on the
release tree. PREP ONLY — no merge to main, no tag, no publish.
The release stops at READY, awaiting John's word.

## Assembly

- Branch `coord/release8`, worktree `~/workspace/cohost-release8`.
- Base: `muse/debug-seam` @ 2563eee3627f (the collated debug-seam
  close-out). Verified at step 0: origin/main @ 2ddebc8ecd48;
  seam is 100 commits ahead of main, 0 behind; release7
  (coord/release7 @ 82c9b77) is in the seam's ancestry.
- README template pin commit: f025976 — "README: pin the project
  template at 2563eee (release8: the debug seam -- debugging
  through the seam, docs chapter 11)", matching the release6/7
  shape (pin names the last content commit).

## NOT in this release (held, untouched)

- The #13 headline table fixes (CompilerException arm in
  java.clj exception-class; IExceptionInfo in shim
  token-ancestors) — never cleared by John.
- The U4c position-meta shrink to :file+:line — never cleared.

## Verification plan (run on the release tree @ the pin tip)

Expected numbers are the final gate's, banked on
`muse/cohost-reports` @ 0251f3ecbe0b for the identical content
tree @ 2563eee. Any deviation is a finding, named with evidence.

Local (this box, serial):
- JVM battery: 221/221, libraries 62/62.
- cli:check jvm rows: 7/7 SAME.
- debug-seam check jvm: 10/14 PASS, 0 FAIL (4 SKIP are jank-only).

On J (j.slaa.us, serial — the jank seat):
- Snapshot build + boot ms.
- Jank battery: 219/221, libraries 62/62 (the 2 FAIL rows are
  the standing named pair: `subs count nth first last butlast`,
  `Character outside ASCII`).
- cli:check jank: 7/7 SAME.
- debug-seam check jank: 12/14 PASS, 0 FAIL, + #9/#12 PASS by
  probe (14/14 accounted).
- docs:check: 22 PASS, 0 FAIL.

Release perf spot-check: boot/startup numbers in the style of
doc/PERF.md, from the same runs (snapshot boot ms on J; JVM boot
locally).

## Version

- Product version of this release: **0.9.3** (John, 2026-11-05
  ~08:27 ET: the last shipped cohost release was 0.9.2;
  `coord/releaseN` branches are internal coordination rounds,
  not product versions). No git tags exist in this repo, and no
  version string lives in the tree (grep for 0.9.x: none) —
  the version is carried by the release notes and this report.

## State

- 2026-11-05 ~08:2x ET: branch created, pin committed.
- 2026-11-05 ~08:3x ET: local JVM battery on the release tree:
  "battery: 221/221 stock behaviors pass", "libraries: 62/62
  cases pass across 12 clojure.* libraries" (log:
  ~/workspace/release8-logs/boot-jvm.log) — matches the gate.
  cli:check jvm + debug-seam:check jvm next.
