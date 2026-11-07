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

PENDING — JVM battery (`bb boot:jvm`) on the merged tree next; jank
battery after JVM green and only under low box load, else banked as
owed with the reason.

NEXT: locate a current Babashka and the `:eve` sibling state
(`../eve/src` relative to this worktree must sit at the v0.9.3 tag
76fd94c3dc), then run `bb boot:jvm` on the merged tree, alone.
