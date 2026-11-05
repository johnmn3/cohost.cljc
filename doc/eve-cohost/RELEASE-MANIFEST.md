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
- **Eve 0.9.3 released-bindings adoption.** The `:eve` pin in `deps.edn`
  currently names Eve main's 0.9.1-line commit (its own comment says the
  pin moves to the Eve 0.9.3 release commit when that release lands).
  Blocked on the Eve 0.9.3 cut (the Jank release, collating in the
  atom.broker channel). Owner: this release lane, on the operator's
  word once 0.9.3 exists.
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
- Jank (`bb boot:jank`): RUNNING — any row the pre-release `:eve`
  bindings make meaningless is recorded PENDING-REBIND, not forced.
