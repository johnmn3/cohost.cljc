# MAIN-MOVE — release/eve-in-cohost moved to main

2026-11-07 ~07:30 AM ET, on the operator's word (John Newman, voice call,
Nov 7 2026: "Go ahead and merge to main").

- From: 5f58f3a5160e942bda71914a98aae24416042abe (main, 2026-11-05)
- To:   6d596d9439b90d7d71e9408e5a82cde2eb571695 (release/eve-in-cohost tip)
- Shape: pure fast-forward, no merge commit. Main was an ancestor of the
  release tip at move time (0 behind / 166 ahead); the fast-forward was
  verified on the remote ref after the push (main == release tip ==
  6d596d9, read back via ls-remote).
- Gate state carried (run on this exact tree by the cleanup lane on
  j.slaa.us): JVM battery 221/221 + libraries 62/62 (twice), jank
  221/221 + 62/62, ClojureScript 219/221 + 62/62 (the pair = stock
  platform facts, proven on the bare seat). Eve pin: exactly the 0.9.3
  release commit.
- The cleanup collation record is doc/eve-cohost/CLEANUP-COLLATION.md;
  the audit verdict was READY with named exceptions, all cleared or
  dispositioned with proof by the cleanup lane
  (doc/eve-cohost/RELEASE-CLEANUP-STATUS.md).
- No tag was created by this move.
