# Eve-in-cohost collation — STATUS

Worktree: ~/workspace/wt/eve-cohost-collate (detached at the mainline tip;
the branch muse/eve-cohost-impl is checked out by an older worktree at
aa5d7e9, left untouched — merges land via explicit refspec push after
verification, with the remote tip re-checked first).

## Base (verified by ls-remote, 2026-11-05 ~12:17 ET)
- muse/eve-cohost-impl: 40c75a1209485a1d294ad5046c9b192cf85f5f28
- muse/eve-cohost-impl-u3: 8ba8027cacb3cafcf759ffd82d0020f9d363cd57 (unit 3, COMPLETE)
- muse/eve-cohost-impl-u2: 3179ba8c426e8dbb03ebb2662a1954408bd20629 (unit 2, COMPLETE)

## Sequence
- [x] Merge 1: unit 3 (8ba8027) into the mainline -- merge commit 4545004, NO conflicts (7 files: memory.cljc + memory_eve.clj new, probe_memory.clj, UNIT3 docs).
- [ ] Merge 2: unit 2 (3179ba8) into the mainline.
- [ ] Verify on the merged tree (JVM seat): battery 221/221, libraries
      62/62, boot errors 0, unit-2 snapshot-replay check through the
      rebuilt snapshot (thread fills present after replay).
- [ ] Push (explicit refspec to muse/eve-cohost-impl) only if fully
      green; verify on the fetched remote ref.
- Owed, NOT run here (operator's sequencing): the jank AOT gate re-run
  on the released Eve bindings.
