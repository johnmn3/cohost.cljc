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
- **Eve 0.9.3 released-bindings adoption.** PENDING on the pushed
  `release/eve-0.9.3` branch in SeniorCareMarket/eve, per the
  operator's sequencing (2026-11-05 ~7:04 PM ET): the Eve release
  branch is pushed out FIRST; this branch then rebases/adopts its
  Eve-facing parts against that latest Eve, on a follow-up steer once
  the Eve branch is verified on its remote. As of this writing the
  Eve branch is not yet on its remote (checked by ls-remote). This
  branch does NOT pin to any pre-release Eve state: the `:eve` pin it
  currently carries in `deps.edn` is the impl mainline's inherited
  pin (Eve main's 0.9.1 line; the pin's own comment already says it
  moves at the 0.9.3 release) and stands as a placeholder until the
  adoption steer lands. Owner: this release lane, on the follow-up
  steer.
- **Jank-seat gate re-runs on the released bindings** — see
  PENDING-REBIND under Gates. Includes unit 9's jank harness leg
  (owner: cohost Agent 3, `agent3/unit9-jank-seat`), which runs up to
  the seam-8 load fault until seam 8 clears in the released bindings.
- **Eve-in-cohost documentation chapters** (threading with Eve; memory
  with Eve atoms; tiered atoms; seat-by-seat coverage matrix), indexed
  from the README. Owner: cohost Agent 2 (assigned the landing after
  its heap-grade work; source branch `muse/eve-cohost-docs`). Lands
  here, not on the impl mainline, once Agent 2 banks its spot-check
  against the landed units.

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

### PENDING-REBIND — jank-seat rows that only gain meaning on the released bindings

Recorded, not forced, per the lane brief. Every row here is PENDING
on the same dependency as the bindings adoption above: the pushed
`release/eve-0.9.3` in SeniorCareMarket/eve, verified on its remote,
followed by the adoption steer.

- **Unit 9's jank harness leg** (`dev/eve_http_seats.clj`, 36-check
  shape; owner: cohost Agent 3, `agent3/unit9-jank-seat`): on the
  pre-release bindings it runs up to the seam-8 load fault and can
  read no further; its verdict belongs to the re-run against the Eve
  0.9.3 released bindings, after the pin adoption above.
- **Full jank-seat gate re-run on the 0.9.3 pin** (`bb boot:jank`,
  plus the cljs Eve-fill rows): the rows above were taken against the
  pre-release `:eve` pin (Eve main's 0.9.1 line). They stand as this
  branch's pre-release numbers; the release verdict is the same gates
  re-run once the pin moves to the 0.9.3 release commit.
- **Unit 6's jank rows** ride with unit 6 itself (PENDING, Agent 3).

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
