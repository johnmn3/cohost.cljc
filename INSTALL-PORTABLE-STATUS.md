# Cohost portable installer lane (Lane M)

## The order (operator, Nov 7 2026)

Generalize the Jank-only installer so `install.cljc` runs on a larger
subset of Clojure-likes: a user runs it on whatever Clojure-like their
platform already has, and that seat bootstraps cohost. Keep the single
self-contained file and its portable orchestration exactly as-is;
factor ONLY the host shim (shell-out with capture, shell run, getenv,
exit, self-exe path) into per-seat reader-conditional arms; dispatch
the build step per seat; record the installing seat in install.edn.
The prerequisite line becomes "any one Clojure-like" — worded only to
what is proven.

## Base — verified firsthand (Nov 7 2026, ~14:52 ET)

- Fetched origin in ~/src/cohost. The completed Jank-only lane's tip
  is `7a02d2cffaf97b1c69d55eacef6a6e6406193154` on
  origin/lane-m/cohost-install-jank (the local branch ref 4955c09 on
  this box was stale — an early pickup commit — as the brief warned).
- That tip's tree carries the CLOSE record: INSTALL-JANK-STATUS.md
  line 211, "## CLOSE — Nov 7 ~15:25 ET: the proof is green end to
  end; lane complete."
- install.cljc at that tip is byte-identical to the proven file in the
  old lane's worktree (sha256
  add1a3acc3f0f80728bd99659365dbce2bbaf181dc820b8f82836a980fbc0a94,
  136 lines). dev/install/ at the tip carries jank_build.cljc,
  lib/cohost/tool_main.cljc, lib/cohost/install/shim.cljc,
  shim/babashka/fs.jank.
- This branch `lane-m/cohost-install-portable` is cut from 7a02d2c in
  the NEW worktree ~/workspace/wt/cohost-install-portable. The old
  lane's worktree is never written by this lane.

## Scope

1. Per-seat shim arms in install.cljc: :jank (existing cpp/raw layer,
   unchanged behavior), :bb (babashka.process + babashka.fs), :clj
   (JVM ProcessBuilder / System). Stretch only if cheap: :lpy, :clje,
   :cljs — else named as future arms here.
2. One file must read and load on every targeted seat's reader —
   intersection dialect; jank regex literals are verbatim, so no
   regex literal or reader syntax whose semantics differ per seat.
   If a seat forces forking the file, STOP and report.
3. Build-step dispatch per seat: jank -> dev/install/jank_build.cljc;
   bb -> the original bb snapshot build; clj -> the JVM snapshot
   build path. A seat that can run the installer but has no build
   path is named run-but-can't-build here, never faked.
4. install.edn records the installing seat ({:repo :jank/:bb/:clj
   :bin}); the installed `cohost` command is the same tool.

## Proofs (venue: j.slaa.us, heavy-queue protocol)

- jank seat: original poisoned-HOME proof as regression control
  (poisoned clojure+bb never invoked, exit 0).
- bb seat: scratch HOME, poison jank+clojure; bb runs the installer.
- clj seat: scratch HOME, poison jank+bb; JVM Clojure runs it.
- Per installed tool: `cohost -Ttools install-latest :lib
  io.github.seancorfield/deps-new :as new` at the reference record
  ({:git/tag "v0.17.0", :git/sha "657fca6"}, re-run prints the
  skipping message) and `cohost -e "(+ 1 2)"` -> 3. Batteries on
  each seat-built snapshot where the seat supports them, numbers as
  printed.

## State: OPENED (~14:52 ET) — base verified, branch cut, STATUS banked

NEXT: read install.cljc + dev/install end to end; design the arm
layout; implement :bb and :clj arms; local reader checks per seat
where the seat exists; then J proofs under a claimed slot.
