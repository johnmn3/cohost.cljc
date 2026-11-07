# Release-cleanup collation — landed on release/eve-in-cohost (Nov 7 2026)

Coordinator collation of `lane-m/cohost-release-cleanup` (tip a194f31, 6 commits)
into `release/eve-in-cohost`.

- **Landing shape: clean fast-forward.** Release tip verified firsthand at
  60353a0 before landing; the cleanup branch descends directly from it
  (merge-base check passed). `git merge --ff-only` — no merge commit, no
  conflicts, nothing to resolve. Release branch HEAD is now the cleanup tip
  plus this report commit.
- **The six commits:** 92e0574 (item 1: 10 scratch files removed), 6c859f5
  (item 2: media-type portable form), 40ec3a8 + 2ee922c + 6fedd0e (item 3:
  jank pair, three commits), 60b20d9 (item 4: boot:jvm -XX:hashCode=3),
  a194f31 (the lane's declaration, RELEASE-CLEANUP-STATUS.md).
- **Conflict-marker sweep on the committed tree:** `git grep -l '<<<<<<<'`
  and `>>>>>>>` over the whole tree, no extension filters. The sweep matches
  10 files — all pre-existing `doc/eve-cohost/*.md` release-record documents
  whose prose *quotes* the marker strings while describing the sweep
  procedure itself (e.g. RELEASE-MANIFEST.md:172, COLLATE-STATUS.md:41). The
  matching file set is byte-identical before and after this landing
  (verified against 60353a0): **this landing introduced zero marker files,
  and there are no unresolved conflict markers in any source file.**
- **Tree identity:** the landed tree's file list is identical to the cleanup
  branch's verified tree. Sanity spot-checks on the landed tree: all 10
  scratch files absent (probe_atom/probe_collections/probe_memory/
  probe_thread/probe_tier.clj, eve_cohost_check.clj, eve_cohost_check_u5.clj,
  eve_cohost_check_u7.clj, eve_http_check.clj, LANDING-U4-STATUS.md);
  `bb.edn` carries the `-J-XX:hashCode=3` boot:jvm flags (lines 69/80);
  RELEASE-CLEANUP-STATUS.md present; src/cohost/unicode_ranges.cljc created.
- **Gates:** NOT re-run. The landing is a pure fast-forward to the tree the
  cleanup lane itself gated on j.slaa.us (JVM 221/221 + libraries 62/62 ×2;
  jank 221/221 + 62/62; cljs 219/221 + 62/62, the pair dispositioned as stock
  platform facts) — tree identity is the proof, per the collation brief.
- **Push verification:** pushed from the collation worktree and verified on
  the FETCHED remote ref (fetch + rev-parse + file read of this report from
  FETCH_HEAD), not from push output.
- **main untouched.** The move to main remains the operator's separate word.
