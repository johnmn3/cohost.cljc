# Cleanup collation — lane-m/cohost-release-cleanup onto release/eve-in-cohost

Coordinator collation, 2026-11-07. The cleanup lane declared complete on its own
branch (tip a194f31; declaration: doc/eve-cohost/RELEASE-CLEANUP-STATUS.md) with
final gates on its tree run on j.slaa.us: JVM 221/221 + libraries 62/62 (twice),
jank 221/221 + 62/62, cljs 219/221 + 62/62 (the pair proven stock platform facts).

## Merge shape

Pure fast-forward. The cleanup branch descends directly from the release tip
60353a0; the release branch advanced 60353a0 -> a194f31, taking exactly the
lane's 6 commits (items 1–4 + the count qualification + the declaration). No
merge commit was needed and no conflict arose. The merged tree is
byte-identical to the lane's verified tree, so the lane's gate results stand
on the release branch unchanged; the full battery was not re-run.

## Marker sweep

`git grep -l '<<<<<<<' <merged tip>` (and `>>>>>>>`) returns 10 files, all
under doc/eve-cohost/ — historical landing/collation records that quote
conflict markers in their narratives. The set is IDENTICAL at the pre-merge
release tip (60353a0), the cleanup tip (a194f31), and the merged tip: the
merge introduced zero markers.

## Verification on the fetched remote ref

- origin/release/eve-in-cohost = a194f31 after push + re-fetch (push output
  alone not relied on).
- doc/eve-cohost/RELEASE-CLEANUP-STATUS.md present in the fetched tree.
- All 10 scratch files absent in the fetched tree (probe_atom,
  probe_collections, probe_memory, probe_thread, probe_tier,
  eve_cohost_check, eve_cohost_check_u5, eve_cohost_check_u7,
  eve_http_check, LANDING-U4-STATUS.md).
- The hashCode=3 lever present in bb.edn (boot:jvm task).
- Item-3 fixes present: src/cohost/unicode_ranges.cljc (J3) and the
  count-fast-with string semantics in the jank host/runtime (J1/J2 family).

## State

The release branch now carries the cleanup in full. Main was not touched.

Ready for the operator's word on the move to main.
