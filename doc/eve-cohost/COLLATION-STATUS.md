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

PENDING — merge not yet performed at this bank.

NEXT: perform the merge of origin/lane-m/cohost-on-eve-093 into
release/eve-in-cohost; on textual conflicts resolve preserving both sides'
intent; on semantic conflicts STOP and bank the conflict list verbatim.
