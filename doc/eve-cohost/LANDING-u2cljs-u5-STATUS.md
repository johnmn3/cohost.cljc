# Landing STATUS — unit 2 cljs half + unit 5 into muse/eve-cohost-impl

Landing lane (Lane M collation), started 2026-11-05 ~1:50 PM ET.
Base: muse/eve-cohost-impl @ 0fa021f (verified on the fetched remote ref).
Collation branch: muse/collate-u2cljs-u5 (worktree ~/workspace/wt/collate-u2cljs-u5).
Logs: ~/workspace/wt/collate-u2cljs-u5-logs/.

- MERGE 1 DONE: muse/eve-cohost-cljs-dep @ e1de808 merged clean, no
  conflicts — merge commit 96cd795, pushed + verified on the remote ref.
- MERGE 2 DONE: muse/eve-cohost-impl-u5 @ b142ffe — merge commit d177972,
  pushed + verified. Conflicts in 4 files, resolved:
  - src/cohost/boot.cljc (:host-lacks line): UNION — :eve-atoms state key
    kept from u5; :host-lacks = host-lacks ∪ (host/core-lacks) ∪
    (atom-surface-names when :eve-atoms). Matches unit 7's recorded shape.
  - src/cohost/shim.cljc: u5's added [cohost.atom :as atom-layer] require.
  - src/cohost/memory.cljc + probe_memory.clj: doc/header-only differences
    (code identical both sides); mainline (unit 3, heap-ruling) wording kept.
  - Paren checks after resolution: shim 881 forms OK, boot 138 OK,
    memory 8 OK, probe_memory 58 OK.
- Marker sweep on committed tree d177972: line-anchored <<<<<<< / >>>>>>>
  / ======= = 0 / 0 / 0. (One unanchored hit: prose in the previous
  landing's COLLATE-STATUS.md, present on base 0fa021f.)
- GATES RUNNING on the merged tree (detached, logs in the logs dir):
  bb boot:jvm; then bb cli:check cljs and bb boot:cljs.
