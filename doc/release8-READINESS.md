# cohost 0.9 — READY (release readiness report)

2026-11-05, prep lane. This release is assembled and verified
and stops here: **no merge to main, no tag, no publish has been
done.** The remaining step is John's (below).

## The release

- Product version: **0.9** (corrected 2026-11-05 ~09:52 ET:
  the 0.9.2/0.9.3 numbers are Eve's release line, not cohost's;
  this release is cohost **0.9**, per John's ruling.
  `coord/releaseN` is the internal coordination round.)
- Branch: `coord/release8`, content = the collated debug-seam
  line `muse/debug-seam` @ 2563eee (100 commits ahead of
  origin/main @ 2ddebc8, 0 behind; release7 @ 82c9b77 in its
  ancestry), plus the README template pin @ f025976 ("pin the
  project template at 2563eee", the release6/7 shape), the
  release notes and this report.
- Release notes: `doc/release8-notes.md` on this branch.
- Status trail: `doc/release8-STATUS.md` on this branch.

## Verification — every number as printed

Expected numbers are the final gate's (banked on
`muse/cohost-reports` @ 0251f3ecbe0b for the identical content
tree @ 2563eee). Release-tree re-verification:

Local, this box (release tree; logs ~/workspace/release8-logs/):

| Leg | Result |
|---|---|
| JVM battery (`bb boot:jvm`) | "battery: 221/221 stock behaviors pass"; "libraries: 62/62 cases pass across 12 clojure.* libraries" |
| cli:check jvm | 7/7 SAME, exit 0 |
| debug-seam:check jvm | 10 PASS, 0 FAIL, 4 SKIP (#2/#9/#11/#12 jank-only or probe-owned); runner exit code 1 as printed — the rows match the banked gate's JVM read exactly |

J, j.slaa.us (release tree @ 52606d1, fresh clone from bundle;
serial; logs ~/release8-gate/ on J):

| Leg | Result |
|---|---|
| Snapshot (`bb boot:jank-snapshot`) | "SNAPSHOT-BUILT cf70ff19fc745ed51072e8b3144c63322e7c25c046750fddf0f3853b5c46da7a in 528169 ms"; "PLACEMENT 858 of 873 targeted forms at their original lines, 15 unplaceable"; SNAPSHOT-BOOT-MS 437.85962; exit 0 |
| Battery (snapshot leg + `bb boot:jank`) | both reads: "battery: 219/221 stock behaviors pass", "libraries: 62/62"; boot:jank SNAPSHOT-BOOT-MS 456.115686; exit 0 |
| cli:check jank | 7/7 SAME, exit 0 |
| debug-seam:check jank | "12/14 PASS, 0 FAIL", exit 0 |
| docs:check jank | 22 PASS blocks; "docs:check: 0 failing command(s) over 1 target(s)"; exit 0 |

Notes on the reads:

- The snapshot hash cf70ff19… is **identical to the final
  gate's snapshot** — the release tree builds the same
  snapshot, byte for byte. The build time (528,169 ms vs the
  gate's 362,325 ms) and boot times are wall-clock on a shared
  box, not content differences.
- The 2 jank battery FAIL rows are the standing named pair,
  verbatim as at the gate: `subs count nth first last butlast`
  ("ERR Unterminated vector.") and `Character outside ASCII`
  ("ERR Unexpected end of file."). Not seam regressions.
- Checks #9/#12 SKIP in the jank runner by design (probe-
  owned). They are PASS by their probes at the final gate on
  this identical content tree; the probes were not re-run in
  release prep. With them: 14/14 accounted, 0 FAIL.
- Perf spot-check (PERF.md style — startup/boot only; this
  release changes no perf-facing surface, so no new PERF.md
  table): J snapshot boot 437.9 ms, J boot:jank 456.1 ms,
  against the gate's 576.8 ms snapshot boot on the same box.

**Deviations from the gate: none.** Every verdict matches.

## NOT in this release (held, untouched)

- The #13 headline table fixes (CompilerException arm in
  java.clj exception-class; IExceptionInfo in shim
  token-ancestors).
- The U4c position-meta shrink to :file+:line.

Both were never cleared by John; the debug-seam close-out left
them untouched and this release does not fold them in.

## The remaining step — John's

On John's word: merge `coord/release8` to main (the lineage
is a clean fast-forward shape over main: content branch is
100 ahead / 0 behind, release commits on top), and publish
0.9 by his usual route. Nothing else is owed from prep.

## Published (2026-11-05, on John's word)

- main fast-forwarded to the release tip; tag **v0.9** on the
  corrected tip (this commit's child state). An earlier tag
  `v0.9.3` was created in error (Eve's number) and removed the
  same morning.
