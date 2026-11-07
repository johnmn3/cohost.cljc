# COLLATION — lane-m/cohost-on-eve-093 into release/eve-in-cohost

Order: John, Nov 7 2026 ~1:35 AM ET — "Get the latest collated into the
release branch of cohost."

## Preconditions (verified before touching anything)

- Worktree `~/workspace/wt/eve-cohost-release`, branch
  `release/eve-in-cohost`, working tree clean, no merge/rebase in progress.
- Release tip (local == origin, verified on fetched refs):
  `18496c4e7476c3cb97224a78c8889322b1eb57a6`.
- Incoming tip: `origin/lane-m/cohost-on-eve-093` =
  `5c2c81c52eaa2e6a6211ffc56073bfc77abe4dd4`.
- Divergence: release has 37 commits lane-m lacks (Nov 5–6 release
  manifest/sequencing work — preserved); lane-m has 7 commits the release
  branch lacks (below). This is a MERGE, never a rebase.
- Merge base: `5bcbd10228603faeef4ac18eca2095e32f83b1cd`.

## Incoming commits (oldest → newest)

1. `5c50680` — unit 9 Node-seat HttpHeaders fix: sorted-guarded transient
   check in the shim (the unit-9 landing at the series' base).
2. `de9dca1` — cohost on Eve 0.9.3: :cljs git pin d241de3 (0.9.1) ->
   76fd94c3dc (v0.9.3 release); binding census + assembly STATUS (base
   muse/eve-cohost-impl @ 5c50680).
3. `05cab56` — REBASE-093 STATUS: Eve-alone seat probes green on 0.9.3
   sibling (check 21/21, u5 31/0, u7 17/0).
4. `0ace4a8` — REBASE-093 STATUS: JVM battery 221/221 + libraries 62/62 on
   0.9.3 sibling; cljs pin fetched at 76fd94c3dc.
5. `7d04b2f` — REBASE-093 STATUS: cljs seat battery 219/221 + libraries
   62/62 on 0.9.3 pin; the 2 rows are the documented cljs pristine-base
   pair, not new-on-0.9.3.
6. `3554a80` — REBASE-093 STATUS: unit-6 refinement pins re-run green on
   assembled bindings (10 tests / 108 assertions / 0 / 0); jank battery
   running on J.
7. `5c2c81c` — REBASE-093 COMPLETE: jank battery 219/221 + 62/62 on
   assembled bindings, standing pair reproduced verbatim (known-standing,
   not new-on-0.9.3); REPORT with full gate table.

## Merge result

- Pre-merge note banked as `447065696d8cd1a0095276f75ba78c786c8ddbe5`.
- **Merge commit `e4773eafd999742cd92fb584e1cac2949a37f637`** (parents:
  4470656 + 5c2c81c), made by the ort strategy with **zero conflicts** —
  no textual or semantic conflict arose. 22 files changed: `deps.edn`
  (the 0.9.3 :cljs pin), `src/cohost/shim.cljc` (unit-9 fix), the
  REBASE-093 REPORT/STATUS, the unit-9 doc, and the gate logs.
- **Conflict-marker sweep on the committed merge tree** (`git grep`
  over the whole tree, no extension filter): `=======` (line-anchored)
  zero. Raw `<<<<<<<` / `>>>>>>>` substring hits: 9 files, all
  `doc/eve-cohost/` landing records whose prose quotes the sweep
  markers themselves (e.g. RELEASE-MANIFEST.md:128 quoting the sweep
  command). The hit set is **identical to the pre-merge release tip**
  (18496c4) — the merge introduced zero markers.

## Manifest dispositions (addendum rows)

- **Unit-8 matrix cell "gate-green" -> "landed": CANNOT be flipped on
  this branch — named, not dropped.** The cell lives in the coverage
  chapter `doc/guide/15-eve-coverage-and-status.md` (status vocabulary
  at line 13; unit 8 written GATE-GREEN per
  `doc/eve-cohost/DOCS-STATUS.md` lines 4–5) on
  `origin/agent2/eve-cohost-docs-landing`. Neither file exists on
  `release/eve-in-cohost` (the documentation chapters are still the
  manifest's PENDING row, owner Agent 2). The flip rides with the docs
  landing, or with a steer to edit that branch; this lane's brief
  confines it to the release worktree.
- **"Eve 0.9.3 released-bindings adoption" row:** satisfied by this
  collation — the merged `deps.edn` carries the v0.9.3 release pin
  `76fd94c3dc541a1e915d6759652114c9f6b392a8` in `:cljs` (was d241de3,
  the 0.9.1 line); the `:eve` sibling mechanism is unchanged.
- **Unit 6 row / PENDING-REBIND rows:** disposition after the gate
  re-runs on the merged tree (below).

## Verification

- **JVM battery on the merged tree — GREEN.** `bb boot:jvm`
  (Babashka 1.13.226, `~/.local/bin` on PATH for the `clojure` CLI;
  `:eve` sibling `../eve/src` = `~/workspace/wt/eve` at `c7dc390a`,
  Eve main's shipped 0.9.3 line; its only dirty file is a README.md
  modification, not on the source paths). As printed (log:
  `logs-collation/boot-jvm.log`):
  - `booted as stock: 465   from host: 202   natives: 66   skipped: 58   errors: 0`
  - `battery: 221/221 stock behaviors pass`
  - `libraries: 62/62 cases pass across 12 clojure.* libraries`
  The release gate bar (221/221 + 62/62) is met on the merged tree.
- **Jank battery — OWED, with reason (not a quiet skip).** Box load
  average 24.41 / 17.32 / 16.41 on 4 cores / 8 GB at gate time, with
  the flagship, STM and shootout lanes live; a full jank AOT battery
  is a heavy compile leg whose home is the build box (j.slaa.us) in
  this line's own practice. It runs after the load clears or on the
  build box. Routed owner: this release lane's next gate session.
  Next action: re-run `bb boot:jank` (and unit 9's harness leg,
  below) on this merged tree under low load / on the build box and
  bank the numbers in the manifest's Gates section.
- **Unit 9's jank harness leg** (`dev/eve_http_seats.clj`, the
  seam-8 row): not run in this collation's verification scope — it
  rides with the owed jank re-run; its manifest row stays PENDING,
  naming that it still awaits its seam-8 verdict on the released
  bindings.
- **Manifest updates landed with this collation** (RELEASE-MANIFEST.md):
  dated STATUS entry for the collation; unit 6 row LANDED with the
  refinement-pin numbers as printed (10 tests / 108 assertions /
  0 failures / 0 errors, `logs-093/jank-pins.log`); bindings-adoption
  row SATISFIED with the pin commit `76fd94c3dc`; Gates collation
  block recording the JVM released-bindings verdict and the owed
  jank re-run; PENDING-REBIND rows updated (unit 6's rows rode in;
  unit 9's leg still PENDING, seam-8 verdict owed); the unit-8
  GATE-GREEN -> landed flip recorded as a landing chore on the
  documentation-chapters row (the cell is not on this branch — see
  above).

## Declaration

The collation is complete: `lane-m/cohost-on-eve-093` is merged into
`release/eve-in-cohost` whole (merge `e4773ea`), the marker sweep is
clean of merge-introduced markers, the JVM gate reproduces the
release bar exactly on the merged tree (221/221 + 62/62, errors 0),
and the manifest rows this collation satisfies are updated in the
same landing. Owed, named, with owners: the jank battery re-run on
this tree (load; build-box leg) and with it unit 9's seam-8 harness
verdict; the unit-8 coverage-cell flip, which rides with Agent 2's
documentation landing. Nothing moved to cohost main.
