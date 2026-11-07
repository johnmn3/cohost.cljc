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
