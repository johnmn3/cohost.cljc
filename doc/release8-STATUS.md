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

- Product version of this release: **0.9** (corrected
  2026-11-05 ~09:52 ET: 0.9.2/0.9.3 are Eve's release line;
  this release is cohost 0.9, per John's ruling;
  `coord/releaseN` branches are internal coordination rounds,
  not product versions). Tag: `v0.9` (the repo's first tag),
  created at publish on John's word; an erroneous `v0.9.3`
  tag was removed the same morning. No
  version string lives in the tree (grep for 0.9.x: none) —
  the version is carried by the release notes and this report.

## State

- 2026-11-05 ~08:2x ET: branch created, pin committed.
- 2026-11-05 ~08:3x ET: local JVM battery on the release tree:
  "battery: 221/221 stock behaviors pass", "libraries: 62/62
  cases pass across 12 clojure.* libraries" (log:
  ~/workspace/release8-logs/boot-jvm.log) — matches the gate.
  cli:check jvm + debug-seam:check jvm next.
- 2026-11-05 ~08:4x ET: local verification COMPLETE on the
  release tree, all matching the gate:
  - cli:check jvm: 7/7 SAME, exit 0
    (~/workspace/release8-logs/cli-check-jvm.log).
  - debug-seam:check jvm: 10 PASS, 0 FAIL, 4 SKIP (#2/#9/#11/
    #12 jank-only or probe-owned), as printed in
    ~/workspace/release8-logs/debug-seam-jvm.log (runner exit
    code 1 as printed; the PASS/FAIL rows are the verdict and
    match the banked gate's JVM read exactly).
  Release notes drafted: doc/release8-notes.md (0.9).
  J legs next, on the tip that carries the notes.
- 2026-11-05 ~09:0x ET: J verification COMPLETE on the
  release tree @ 52606d1 (bundle clone ~/cohost-release8 on
  J; logs ~/release8-gate/ on J), serial, ALL matching the
  gate: snapshot cf70ff19… (identical hash to the gate's)
  built in 528,169 ms, PLACEMENT 858 of 873, BOOT-MS 437.86;
  battery 219/221 in both reads (FAIL rows = the standing
  named pair), libraries 62/62; cli:check jank 7/7 SAME;
  debug-seam:check jank 12/14 PASS, 0 FAIL; docs:check 22
  PASS, 0 failing. Deviations: none.
- READY. Readiness report: doc/release8-READINESS.md.
  Stops here, awaiting John's word (merge to main / publish
  0.9 are his step; no tag exists or was made).
