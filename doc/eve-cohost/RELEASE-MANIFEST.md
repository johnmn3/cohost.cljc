# RELEASE MANIFEST — `release/eve-in-cohost` (cohost repo)

The Eve-in-cohost release branch, under the operator's release discipline
(2026-11-05 ~6:33 PM ET): work collates onto this branch, is proven here,
and moves to cohost `main` in one move, on the operator's word only.
Nothing in this lane writes to cohost main.

## STATUS

- 2026-11-05 ~6:35 PM ET — branch created off cohost main's fetched tip
  (the cohost 0.9 line: the 0.9 release commits + the httpbench capstone
  as a public example). `muse/eve-cohost-impl` merged in whole: **zero
  textual conflicts** — the two lines' changes since their fork base are
  file-disjoint (main: httpbench, release/version, docs; impl: the
  Eve-in-cohost units). Auto-merge verified, not assumed: the merged
  `src/cohost/shim.cljc` is byte-identical to the impl line's (carries
  the unit-9 jank AOT fix), and main's httpbench + version state are
  present whole. Merge committed and pushed; gates next.
- 2026-11-05 ~6:45 PM ET — gates on the merged tree: JVM 221/221 +
  62/62, errors 0; cljs cli:check 7/7 SAME; boot:cljs 219/221 + 62/62
  (the impl line's named pristine pair only); marker sweep 0/0/0.
  Jank leg running.
- 2026-11-05 ~7:05 PM ET — jank leg landed: boot:jank rc=0, errors 0,
  219/221 + 62/62, shim.o produced; the two red rows byte-identical
  to the impl line's own banked log. PENDING-REBIND rows recorded.
  Final declaration written below. Lane complete; the branch awaits
  its PENDING rows and the operator's word.
- 2026-11-05 ~7:06 PM ET — operator sequencing note applied: the Eve
  release branch (`release/eve-0.9.3`, SeniorCareMarket/eve) is
  pushed out first; this branch then rebases/adopts its Eve-facing
  parts against it. The bindings-adoption row and every
  PENDING-REBIND row now name that branch as their dependency
  (ls-remote: not yet on the Eve remote at this check), and the
  branch pins to no pre-release Eve state. Awaiting the follow-up
  steer when the Eve branch is verified on its remote.
- 2026-11-07 ~1:35 AM ET — operator order: "Get the latest collated
  into the release branch of cohost." `lane-m/cohost-on-eve-093`
  (tip `5c2c81c`, REBASE-093 COMPLETE) merged in whole: merge commit
  `e4773ea`, zero conflicts; marker sweep zero new markers. The
  merged `deps.edn` carries the Eve v0.9.3 released pin (see the
  adoption row below — SATISFIED). Gates re-run on the merged tree:
  JVM battery 221/221 + libraries 62/62, errors 0 (the
  released-bindings verdict); the jank re-run is OWED — box load
  24.4 on 4 cores at gate time, and the leg's home is the build box
  in the line's own practice (see PENDING-REBIND). Full record:
  `doc/eve-cohost/COLLATION-STATUS.md`; gate log:
  `logs-collation/boot-jvm.log`.

## Rows

### IN — on this branch, merged from `muse/eve-cohost-impl`

- **Base (from cohost main):** the cohost 0.9 release line, incl. the
  debug-seam release and the httpbench capstone example + `doc/httpbench.md`.
- **Unit 1 — thread layer behind the shim (JVM):** `cohost.thread` +
  adapter thread leaves; probe 29/29, booted-context 21/21 on its line.
- **Unit 2 — thread-layer seat leaves (jank + cljs):** landed on all
  three seats on its line (jank probe ok=18; cljs probe ok=22 after the
  macro-fill fix).
- **Unit 3 — seam identity (equals/hasheq):** probe 111/111 on its line;
  carries the hash-facade coercion requirement (Eve's hash wins behind
  the facade).
- **Unit 4 — Eve-backed collections:** corpus 4,401 cases, 25 changed,
  all explained, on its line; the JVM-battery eduction remainder is
  CLOSED as stock Clojure 1.12.0 behavior with proof (no code change),
  `doc/eve-cohost/UNIT4-REMAINDER-REPORT.md`.
- **Unit 5 — atoms onto Eve atoms; var registry + namespace table in
  the startup domain:** layer probe 66/66, booted-context 31/31 on its
  line. No STM (operator Q2).
- **Unit 7 — tiered atoms (mem → disk → net, developer-chosen by
  metadata, nothing automatic):** JVM + jank over one disk atom, 9/9
  identical digests on its line.
- **Unit 8 — shared-heap illusion on cljs:** probe 23/23 both seats on
  its line.
- **Unit 9 — HTTP hydration + Ring:** 36/36 JVM and 36/36 cljs on its
  line (raw-text headers per the operator's ruling).
- **Unit-9 jank AOT fix:** `-getDefaultPort` string `case` → map lookup
  in `src/cohost/shim.cljc`; the merged impl tree produces
  `cohost/shim.o` under jank AOT (the artifact upstream jank defect 19
  denied), `doc/eve-cohost/LANDING-u9-aot-fix.md` on the impl line.

### PENDING — with owner

- **Unit 6 — slab residence on jank.** Owner: cohost Agent 3. Step 1
  (region leaves; cross-process region check; JVM gate) is banked on
  `agent3/eve-unit6` in this repo. Step 2 is Eve-side (operator-ruled):
  the registered host-object seam in Eve's host-completion, being
  implemented on Agent 3's `agent3/eve-u6-converge` line in the Eve
  tree; its after-arm gates land in Agent 3's ledger. Step 3 follows
  step 2. Lands here by merge when Agent 3 banks it green.
  **LANDED on this branch 2026-11-07 via the REBASE-093 collation**
  (merge `e4773ea`): unit 6's landing arrives with the
  `lane-m/cohost-on-eve-093` line, and its gated shapes were re-run
  green on the assembled 0.9.3 bindings — the unit-6 refinement pins
  (`bb test:jank eve.identity-test eve.identity-table-test
  eve.host-completion-test`, j.slaa.us, 0.9.3 mechanism tree):
  **Ran 10 tests containing 108 assertions. 0 failures, 0 errors.**
  (identity 5, identity-table 1, host-completion 4;
  `logs-093/jank-pins.log`, REBASE-093-STATUS.)
- **Eve 0.9.3 released-bindings adoption. SATISFIED by the
  2026-11-07 collation** (merge `e4773ea`). The rebase line
  (`lane-m/cohost-on-eve-093`, commit `de9dca1`) adopted the released
  bindings and this collation merged them in: `deps.edn`'s `:cljs`
  pin now names the Eve v0.9.3 release commit
  **`76fd94c3dc541a1e915d6759652114c9f6b392a8`** (tag `v0.9.3`,
  SeniorCareMarket/eve main), replacing d241de3 (the 0.9.1 line).
  The `:eve` sibling-paths mechanism is unchanged (no version lives
  in the repo for that seat). History: this row stood PENDING on the
  operator's 2026-11-05 sequencing (Eve release branch pushed first,
  adoption on a follow-up steer); Eve 0.9.3 has since shipped and the
  steer arrived 2026-11-07 ~1:35 AM ET.
- **Jank-seat gate re-runs on the released bindings** — see
  PENDING-REBIND under Gates. Includes unit 9's jank harness leg
  (owner: cohost Agent 3, `agent3/unit9-jank-seat`), which runs up to
  the seam-8 load fault until seam 8 clears in the released bindings.
- **Eve-in-cohost documentation chapters** (threading with Eve; memory
  with Eve atoms; tiered atoms; seat-by-seat coverage matrix), indexed
  from the README. Owner: cohost Agent 2 (assigned the landing after
  its heap-grade work; source branch `muse/eve-cohost-docs`).
  **LANDED on this branch 2026-11-07** (release-clear lane, commit
  `5c79e7c`): guide chapters 12–15 from
  `agent2/eve-cohost-docs-landing` + README index entries 12–15,
  spot-checked against the collated tree at landing — unit 6 was
  written "in implementation (not landed)" in ch. 15's Not-yet list
  and "not yet done" in ch. 14; both corrected to the landed state
  (unit 6 LANDED via the collation; refinement pins 10 tests /
  108 assertions / 0 / 0), and ch. 13's unit-6 reference updated
  likewise. **Landing chore DISCHARGED:** unit 8's coverage cell —
  the worker-realms / shared-heap row of
  `doc/guide/15-eve-coverage-and-status.md` — reads **LANDED** in
  the chapter as landed (the GATE-GREEN form lives in Agent 2's
  DOCS-STATUS / DOCS-DECLARATION on its own branch, not in the
  guide text). Agent 2's DOCS-STATUS/DOCS-DECLARATION remain on
  `agent2/eve-cohost-docs-landing` as its lane record.

### EXCLUDED — with reason

- None. The impl mainline was merged whole; every row of the project
  is either IN above or PENDING above with a named owner. (Heap-grade
  steps 2–3 and the Eve-on-jank seams live in the Eve repo and ship in
  Eve 0.9.3; they enter this repo only as the released `:eve` pin
  above, never as cohost-side code.)

## Gates on the merged tree

Filled as the runs land; numbers exactly as printed. Host legs run
serially on this box (8 GB).

- JVM battery + libraries (`bb boot:jvm`): **battery: 221/221 stock
  behaviors pass; libraries: 62/62 cases pass across 12 clojure.*
  libraries; booted as stock 465, errors: 0** — the impl mainline's own
  rows, reproduced on the merged tree (log:
  `eve-cohost-release-logs/boot-jvm.log` in the coordinator's
  workspace).
- cljs (`bb cli:check cljs`): **7/7 SAME, rc=0.** (`bb boot:cljs`):
  **rc=0, errors: 0; libraries: 62/62; battery: 219/221** — the two
  red rows are exactly the impl line's named pristine pair ("FAIL
  numeric tower", "FAIL defmacro &form &env"), reproduced, not merge
  damage (logs: `cli-check-cljs.log`, `boot-cljs.log`).
- Conflict-marker sweep on the committed tree: line-anchored
  `<<<<<<<` / `>>>>>>>` / `=======` over the whole tree, no extension
  filter: **0 / 0 / 0**. Raw-substring grep names 8 files, all
  `doc/eve-cohost/` landing records whose prose quotes the markers —
  the identical set exists on the impl parent; the merge added none.
- Jank (`bb boot:jank`): **rc=0, errors: 0; booted as stock 544;
  battery: 219/221; libraries: 62/62** (log: `boot-jank.log`). The
  AOT build produced `cohost/shim.o` on this tree — the unit-9 AOT
  fix's artifact, verified present in the AOT cache. The two red rows
  are the impl line's standing jank pair, byte-identical to the impl
  line's own banked `boot:jank` log (unit 8's logs): "FAIL
  Character/isWhitespace is Java's" (got "ERR Unfinished character:
  8195") and "FAIL Character outside ASCII" (got [false false true
  false]). Both are jank-seat character-literal rows; neither is merge
  damage.
- **Collation re-runs on the released bindings (2026-11-07, merge
  `e4773ea` — the REBASE-093 collation).** JVM battery + libraries
  (`bb boot:jvm`, Babashka 1.13.226, Eve sibling `../eve/src` at
  `c7dc390a`, Eve main's shipped 0.9.3 line): **battery: 221/221
  stock behaviors pass; libraries: 62/62 cases pass across 12
  clojure.* libraries; booted as stock 465, errors: 0** — the
  released-bindings verdict for the JVM rows (log:
  `logs-collation/boot-jvm.log`). Conflict-marker sweep on the
  merge's committed tree: zero new markers (the 9 raw-substring hits
  are the same prose quotations as the pre-merge tip). The jank
  re-run on this merged tree is OWED, not skipped: box load average
  24.41 on 4 cores / 8 GB at gate time, with the flagship, STM and
  shootout lanes live; the leg runs on the build box in the line's
  own practice. Its released-bindings numbers stand in the rebase
  line's own record meanwhile (`logs-093/jank-battery.log`: boot
  errors 0; battery 219/221; libraries 62/62 — the standing reader
  pair, reproduced verbatim).
- **Release-clear jank re-run on this branch's merged tree
  (2026-11-07, operator's order; build box j.slaa.us, load 0.22 at
  start).** `bb boot:jank` (Babashka 1.13.226) against this branch's
  tip (code tree of `e1ec18e`; the docs commit `5c79e7c` on top
  changes no code), Eve sibling `../eve/src` at the released 0.9.3
  pin `76fd94c3dc`: **booted as stock 544, from host 141, natives
  28, skipped 108, errors: 0; battery: 219/221 stock behaviors
  pass; libraries: 62/62 cases pass across 12 clojure.* libraries**
  — verdict lines byte-identical to the rebase line's
  `logs-093/jank-battery.log`. The two red rows are the standing
  released-bindings pair: "FAIL subs count nth first last butlast"
  (got "ERR Unterminated vector. (outer: Unterminated vector.)")
  and "FAIL Character outside ASCII" (got "ERR Unexpected end of
  file. (outer: Unexpected end of file.)"). The AOT build produced
  `cohost/shim.o` in the run's cache — the unit-9 AOT fix's
  artifact, verified present. Log: `logs-release-clear/boot-jank.log`
  (committed with this update; original on the build box at
  `~/cohost-release-clear/logs/boot-jank.log`). The OWED re-run is
  DISCHARGED.

### PENDING-REBIND — jank-seat rows that only gain meaning on the released bindings

Recorded, not forced, per the lane brief. Every row here is PENDING
on the same dependency as the bindings adoption above: the pushed
`release/eve-0.9.3` in SeniorCareMarket/eve, verified on its remote,
followed by the adoption steer.

- **Unit 9's jank harness leg** (`dev/eve_http_seats.clj`, 36-check
  shape; owner: cohost Agent 3, `agent3/unit9-jank-seat`): on the
  pre-release bindings it runs up to the seam-8 load fault and can
  read no further. **Still PENDING after the 2026-11-07 collation:**
  the pin adoption has landed (above), but the leg itself was not
  re-run in the collation's verification scope — it rides with the
  owed jank re-run and still awaits its seam-8 verdict on the
  released bindings. Not dropped; named here as owed.
- **Full jank-seat gate re-run on the 0.9.3 pin** (`bb boot:jank`,
  plus the cljs Eve-fill rows): the pin has moved (adoption row,
  SATISFIED). The rebase line's own re-runs on the assembled bindings
  are banked in `logs-093/` (jank battery 219/221 + 62/62; cljs
  219/221 + 62/62, each with its standing pair). **DISCHARGED
  2026-11-07** by the release-clear re-run on THIS branch's merged
  tree (build box; see the Gates release-clear block): errors 0,
  battery 219/221, libraries 62/62, verdict lines byte-identical to
  `logs-093/jank-battery.log`. (The cljs Eve-fill rows were re-run
  by the rebase line on the assembled bindings, banked in
  `logs-093/boot-cljs.log`: 219/221 + 62/62, standing pair.)
- **Unit 6's jank rows** rode with unit 6 itself — LANDED with the
  collation (see the unit 6 row above): refinement pins green on the
  assembled bindings, 10 tests / 108 assertions / 0 / 0.

## FINAL DECLARATION — this lane

`release/eve-in-cohost` is prepared and proven to the extent the
pre-release bindings allow:

- Base: cohost main's fetched tip (cohost 0.9 line), untouched and
  unmoved — nothing in this lane wrote to cohost main.
- The Eve-in-cohost impl mainline is merged in whole, zero textual
  conflicts, auto-merge verified file-by-file on the historically
  contended files.
- Gates on the merged tree, as printed: JVM 221/221 + 62/62, errors 0;
  cljs cli:check 7/7 SAME; boot:cljs 219/221 + 62/62 (the named
  pristine pair); boot:jank 219/221 + 62/62, errors 0, shim.o
  produced (the standing jank pair, byte-identical to the impl line's
  own log); conflict-marker sweep 0/0/0.
- Every project row is dispositioned above: units 1–5, 7–9 and the
  unit-9 AOT fix IN; unit 6, the 0.9.3 pin adoption + rebind re-runs,
  and the documentation chapters PENDING with named owners; nothing
  EXCLUDED.
- The branch moves to cohost main only on the operator's word, after
  the PENDING rows land or are explicitly waived.
