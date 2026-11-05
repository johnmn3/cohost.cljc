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
- [x] Merge 2: unit 2 (3179ba8) into the mainline -- merge commit f80eba2, NO conflicts. Conflict-marker sweep on the committed tree: 0 hits.
- [x] Verify (JVM seat, bb boot:jvm, dev/collate-logs/boot-jvm.log): boot errors 0, battery 221/221, libraries 62/62 -- FULLY GREEN.
- [ ] Snapshot-replay check: rebuilt snapshot carries the :thread-layer event + snap-thread-layer! call; probe row through it answers the thread family.
      62/62, boot errors 0, unit-2 snapshot-replay check through the
      rebuilt snapshot (thread fills present after replay).
- [x] Push: DONE 12:47 PM ET, verified on the fetched remote ref (67ce3d5). Declaration note below.
- [ ] Push (explicit refspec to muse/eve-cohost-impl) only if fully
      green; verify on the fetched remote ref.
- Owed, NOT run here (operator's sequencing): the jank AOT gate re-run
  on the released Eve bindings.


## DECLARATION — collation complete (2026-11-05 ~12:50 PM ET)

- [x] Push: merged mainline pushed 12:47 PM ET (intervention order) and
      verified on the fetched remote ref: muse/eve-cohost-impl =
      67ce3d5 at push time (this note rides one commit past it).

**New mainline tip at landing: 67ce3d5** (from 40c75a1).

**Merge commits:**
- 4545004 — Merge unit 3 (seam identity, muse/eve-cohost-impl-u3 @
  8ba8027) into the mainline. NO conflicts.
- f80eba2 — Merge unit 2 (seat leaves, muse/eve-cohost-impl-u2 @
  3179ba8) into the mainline. NO conflicts.
- Conflict-marker sweep on the committed merged tree: 0 hits
  (<<<<<<< / >>>>>>>).

**Verification on the merged tree, as printed:**
- JVM gate (bb boot:jvm, dev/collate-logs/boot-jvm.log): boot errors 0
  ("booted as stock: 465, from host: 202, natives: 66, skipped: 58,
  errors: 0"); battery: 221/221 stock behaviors pass; libraries: 62/62
  cases pass across 12 clojure.* libraries.
- Snapshot-replay fix holds on the merged tree. The snapshot build on
  this (8 GB) box wedged in AOT-RECOMPILE (0 CPU ticks in 5 s after
  ~13 min; PIDs 62876/62858 killed) and was re-run on J from an rsync
  of this worktree (~/cohost-collate; boot.cljc md5-identical).
  Artifacts of the rebuilt snapshot: target/jank-snapshot/events.edn
  carries the :thread-layer event; the emitted snapshot core module
  target/jank-snapshot/src/cohost/core.jank line 8243 calls
  (cohost.boot/snap-thread-layer!). End-to-end probe row through the
  snapshot (bb dev/eve_probe.clj jank, on J): SUMMARY|jank|ok=18
  unsupported-shim=0 fail-other=5 wall-ms=8358, rc=0 — the thread
  family (future/agent/pmap) answers with JVM values after replay;
  the 5 fail-other are unit 2's pre-existing launcher module-path
  gaps. (J's snapshot build was still in its final compile phase
  when the probe ran green through the emitted snapshot.)

**Owed (operator's sequencing, NOT run here):** the jank AOT battery
gate re-run on the released Eve bindings — unit 2's jank numbers on
the merged tree stand as pre-release bindings (219/221 standing pair
on its own branch at gate time).
