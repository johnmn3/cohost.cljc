# RELEASE-CLEAR STATUS — the two owed PENDING rows (Lane M, Nov 7)

Operator order (Nov 7 ~3:45 AM ET): clear the two owed rows on
`release/eve-in-cohost` — the jank gate re-run (on the build box,
j.slaa.us) and the documentation chapters landing. NOT the move to
main: that stays the operator's word, not given.

## Plan
- ITEM 1: `bb boot:jank` on j.slaa.us against this branch's tip
  (cf9d874), Eve sibling at the released 0.9.3 pin 76fd94c3dc
  (tag v0.9.3). Then unit 9's jank harness leg
  (dev/eve_http_seats.clj) for its seam-8 verdict. Numbers banked
  into RELEASE-MANIFEST.md PENDING-REBIND + Gates as printed.
- ITEM 2: land Agent 2's chapters (agent2/eve-cohost-docs-landing:
  guide ch. 12–15 + coverage matrix) onto this branch, spot-checked
  against landed state (unit 6 LANDED via collation; pin adoption
  satisfied), incl. the named chore: unit 8's matrix cell
  GATE-GREEN -> landed.

## State
- 03:47 ET: lane opened. Build box checked firsthand: load 0.22,
  4 cores, 15 GB — idle. J has bb 1.13.226 (~/bin), jank
  (/usr/local/bin), and the 0.9.3 pin commit present in its ~/eve.
  Run layout: ~/cohost-release-clear/{cohost,eve} on J.
NEXT: set up J run dirs, launch boot:jank detached with log.

## Progress
- 03:52 ET: J run layout verified — cohost @ e1ec18e (release tip),
  Eve sibling @ 76fd94c (0.9.3 pin). `bb boot:jank` launched detached
  on J (log ~/cohost-release-clear/logs/boot-jank.log); AOT-RECOMPILE
  under way, box load ~0.9.
- 03:55 ET: ITEM 2 chapters landed in this worktree (guide 12–15 from
  agent2/eve-cohost-docs-landing + README index 12–15), spot-checked:
  unit 6 was written "in implementation (not landed)" in ch.15's
  Not-yet list and as "not yet done" in ch.14 — both corrected to the
  collated state (unit 6 LANDED via e4773ea; pins 10/108/0/0), ch.13's
  reference updated likewise. Unit 8's coverage cell: the
  worker-realms/shared-heap row already reads LANDED in the chapter
  as written (its GATE-GREEN form lives in Agent 2's DOCS-STATUS /
  DOCS-DECLARATION, not the guide) — the named flip is satisfied in
  the landed text and recorded here. Ch.15's standing jank battery
  pair sentence is checked against ITEM 1's printed rows when the
  re-run lands, before the manifest update.
NEXT: commit + push docs; read boot:jank verdict on J.
- 04:05 ET: ITEM 1 gate verdict IN (build box): bb boot:jank on the
  merged tree @ e1ec18e code, Eve sibling @ 76fd94c pin — booted as
  stock 544, errors: 0; battery 219/221; libraries 62/62; verdict
  lines byte-identical to logs-093/jank-battery.log; red pair =
  "subs count nth first last butlast" + "Character outside ASCII"
  (the standing released-bindings pair — ch.15's sentence already
  names this pair, no chapter correction needed); shim.o produced.
  Manifest Gates + PENDING-REBIND updated; log committed at
  logs-release-clear/boot-jank.log. Unit-9 jank leg RUNNING on J
  (Agent 3's harness files overlaid on a scratch copy of the tree,
  verdict run only — nothing of Agent 3's branch merges here).
NEXT: unit-9 seam-8 verdict as printed; final manifest + declaration.

## FINAL — both rows discharged (2026-11-07 ~4:20 AM ET)

ITEM 1 — jank gate re-run on the merged tree (build box j.slaa.us):
DISCHARGED. bb boot:jank @ release code e1ec18e, Eve sibling at the
0.9.3 pin 76fd94c3dc: booted as stock 544, errors: 0; battery
219/221; libraries 62/62; shim.o produced; verdict lines
byte-identical to logs-093/jank-battery.log. Red pair as printed:
"FAIL subs count nth first last butlast" (ERR Unterminated vector)
and "FAIL Character outside ASCII" (ERR Unexpected end of file) —
the standing released-bindings pair.
Unit 9's jank harness leg — SEAM-8 VERDICT IN: the load fault does
NOT recur on the released bindings. Both seat processes (Agent 3's
harness overlaid on a scratch copy; nothing of that branch merged)
loaded and reached program evaluation, then died identically at
dev/eve_http_programs.cljc:187 — jank analyze invalid-cpp-member-call,
no member `trim` on oref<object>, from (.trim ^String s). Printed
verdict: "unit 9 gate (jank seat): 0/36 PASS, 36 not run (FAIL: the
client process ended before its work was done, exit 1; FAIL: the
serve process ended before its work was done, exit 1); JVM-only
client programs not run on this seat: 0". Remaining fix = the shared
gate programs' one JVM member call, on the leg owner's line
(Agent 3) — named in the manifest with file and line.

ITEM 2 — documentation chapters: LANDED (commit 5c79e7c): guide
12–15 from agent2/eve-cohost-docs-landing + README index 12–15,
spot-checked; unit 6 corrected from "in implementation (not
landed)" to LANDED with its pin numbers (10 tests / 108 assertions
/ 0 / 0) in ch.15, "not yet done" corrected in ch.14, ch.13
reference updated. Unit 8's named flip discharged: the
worker-realms/shared-heap coverage row reads LANDED in the landed
chapter (the GATE-GREEN form lives only in Agent 2's own
DOCS-STATUS/DOCS-DECLARATION on its branch).

The branch moves to cohost main ONLY on the operator's word — not
given, not taken. Nothing in this lane wrote to main.
