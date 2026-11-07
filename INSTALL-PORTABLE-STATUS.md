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

## Implementation (~15:05 ET) — install.cljc generalized in place

Read end to end firsthand: install.cljc (136 lines), jank_build.cljc,
cohost.tool/tool_main, bin/cohost, bb.edn, dev/perf/
jank_snapshot_key.clj + jank_snapshot_ensure.clj + jank_aot_ensure.clj.

Design, as built:

- `seat` is a def from a reader conditional, arms ordered :jank,
  :bb, :clj (jank and bb also read :clj arms; their own features
  come first — the house reader-order rule, cf. bb.edn's
  lint:reader-order task).
- The five shim services (capture, sh!, env, exit!, self-exe) are
  armed per seat. :jank keeps the cpp/raw layer byte-identical and
  the same wrapper defns; :bb uses babashka.process/shell over
  "sh -c" (popen parity: stdout captured, stderr inherited) +
  System/getenv + /proc/self/exe with a `command -v bb` fallback;
  :clj uses java.lang.ProcessBuilder ("sh -c", stderr inherited for
  capture, inheritIO for sh!) + System. env/exit! share a :default
  arm (System) under the :jank arm.
- The bottom try/catch is spliced per seat (:default / Exception /
  Throwable); the ex-message guard is preserved in install-fail.
- Build dispatch in -main step 2, by seat: :jank ->
  jank_build.cljc (command byte-identical to the completed lane's);
  :bb -> `bb -e (load-file "dev/perf/jank_snapshot_ensure.clj")`
  (the original bb build); :clj -> the same ensure under the
  Clojure CLI with -Sdeps pinning babashka/process 0.5.22 +
  babashka/fs 0.5.24 (key.clj's only non-core requires; versions to
  be confirmed at the clj proof).
- Step 3 (tool compile) is jank on every seat — the installed
  command is the same cohost.tool as the completed lane's. Step 1.5
  (new): on a non-jank seat with no jank on PATH, the installer runs
  the checkout's dev/install/jank.sh, then re-checks; still absent,
  it dies naming the gap. install.edn now also records :seat and,
  on non-jank seats, the driver path under its own key; :repo/:jank/
  :bin are unchanged, and the tool reads only those three.
- Dialect: no new regex literals; the file's two existing patterns
  (#"/+$", #":") are escape-free and mean the same on every seat.

## Findings that shape the proofs (from the tree, firsthand)

- The original snapshot build (jank_snapshot_key.clj rebuild!):
  step 1 record is a jank run, step 2 emit is a `bb` process
  (hardcoded in rebuild!), step 3 compile is jank. The bb seat's
  dispatch therefore uses bb exactly where the original did (driver
  + emit); the clj driver's emit still rides that hardcoded `bb`
  call — a wart in dev/perf, NOT fixed by this lane (touching
  key.clj would break the cross-flow snapshot-key parity). Named
  here so the clj proof is read correctly: on the clj seat, bb is
  the build's emit hand, never the driver.
- Consequence for the poison proofs, stated before running them:
  jank is invoked by the build's record/compile steps and by step
  3 on EVERY seat, because the installed tool is a jank-compiled
  program (scope item 4: the same tool as today's). The bb/clj
  seats generalize who DRIVES the install, not what compiles the
  tool. So each non-jank proof runs in two parts: (a) the seat
  proof — the other Clojure-like driver poisoned, full install +
  acceptance; (b) a boundary probe with the seat's build
  collaborators poisoned, logging exactly where the build stops.
  The jank-seat regression proof is unchanged from the completed
  lane (clojure+bb poisoned, never invoked).

## J staging + probes (~15:20–15:45 ET; slot claimed)

- Slot `slot.cohostinstall-portable` claimed under the heavy-queue
  protocol (CAP=5; active slots at claim time: ds-perf-bench,
  u22-collate, stm-collate — four further slot.* entries are hours
  stale; 1-min load 3.44). Checkout: ~/cohost-portable on J, git
  clone of this branch @ e7c6a696.
- bb on J is /home/john/.local/bin/bb (v1.3.190), NOT /usr/local/bin.
  jank + clojure are /usr/local/bin. Root disk 96% full (~4.7G
  free) — one shared checkout for all proofs, watched.
- Reader probes (probe.cljc, per-seat arm + *command-line-args* +
  spliced catch): bb arm PERFECT (rc 0); clj arm PERFECT via
  `clojure -M probe.cljc` (:clj features, args, catch Throwable);
  jank arm selected + args correct. Two jank quirks, neither in the
  installer's shape: throwing an ex-info DIRECTLY in a top-level
  try body binds a nil-ish catch value (ex-message then faults) —
  the installer's throws always originate inside defns, and its
  handler shape (defn call) probed fine (probe2 P3/P4 pass).
- INSTALLER SEMANTICS FOUND FIRSTHAND (original behavior,
  preserved): --repo pointing at a non-checkout FALLS BACK to the
  cwd when the cwd is a checkout (the `(if (file? ...) repo (cwd))`
  step). A "bogus --repo" check run from inside a checkout
  therefore does NOT die — it installs from the cwd. My first
  load-check batch was invalid for exactly this reason (and a bb
  run from it started a real build in the shared checkout — killed,
  target/ cleaned). Load checks are rerun from a non-checkout cwd.
- A first jank-seat proof attempt FAILED in jank's JIT while
  realizing the install-fail defn (core.jank compile errors,
  truthy symbol missing). Bisected from the load-check log: the
  ORIGINAL file's handler, inline in the bottom let's catch,
  prints "cohost install failed: stopped" cleanly in the same
  environment; the generalized file's factored install-fail defn
  fails to JIT-realize deterministically (install.cljc:229,
  "Failed to find symbol: cohost_install$install_fail_370_1").
  FIXED: the handler is inline again, in the original's exact
  shape, with only the catch header and the exit call armed per
  seat; the install-fail defn and the exit! defn are gone.
- LATENT BUG FOUND IN THE ORIGINAL (inherited verbatim, now
  fixed): -main's argv parse filtered args to "--" tokens BEFORE
  partitioning into pairs, dropping every value — so --repo and
  --bin never took effect; explicit-repo was always nil and the
  installer always resolved via COHOST_HOME / cwd / the default
  clone. Every proof of the jank-only lane ran with cwd == the
  --repo value, masking it. All of this lane's early confusion
  (a bogus --repo "installing" the cwd checkout) traces to it.
  FIXED: pairs are consumed two at a time in a loop; the code
  comment at the binding tells the story. This lane's proofs run
  the installer from a neutral cwd so --repo is really exercised.

- Load checks rerun from a neutral cwd (~15:5x ET): bb seat
  PERFECT (banner, "no cohost checkout at /nonexistent-bogus",
  "cohost install failed: install: stopped", rc 1); clj seat
  PERFECT (same, banner "Clojure CLI version 1.12.6.1673"). The
  parse fix is proven on both.
- JANK SEAT: still failing at -main realization — and bisected to
  the construct: my file used `case` on the seat keyword (four
  sites); the original uses none. The JIT errors point into
  clojure/core.jank line 4531, which is the `case` macro's own
  machinery (the case* :identity prep) — this jank build's
  codegen for case* on keywords is broken. FIXED: all seat
  dispatch is if-chains on (= seat :jank) / (= seat :bb); a
  comment at the seat def records the ban. (The bottom handler
  printed "cohost install failed: stopped" even in the broken
  runs — the inline-handler fix held throughout.)

- Jank load check re-verified after the case fix (~16:0x ET):
  banner, "no cohost checkout at /nonexistent-bogus",
  "cohost install failed: stopped", rc 1, ~4 s. All three seats
  now pass their load checks from a neutral cwd.
- README + doc/guide/01-getting-started.md prerequisite wording
  rewritten locally (uncommitted until the proofs land): one
  Clojure-like to run the installer (Jank / Babashka / Clojure
  CLI) + Jank itself compiling the tool, found on PATH or
  installed from the checkout on the non-jank seats.
- Jank-seat proof (regression control, the completed lane's
  poisoned-HOME shape, strengthened: poisons up for the install
  too, installer invoked by absolute path from a neutral cwd)
  RUNNING on J since 20:19Z in the shared checkout; stage-1 AOT
  compile under heavy box load (load ~9, other lanes). Disk
  watched: ~2.6–2.8G free.

- Jank proof, first full run (~16:2x ET): the BUILD completed
  end to end — SNAPSHOT-BUILT df88adacda69c0e2d… (the completed
  lane's proven snapshot key), PLACEMENT 857 of 872 — then the
  box disk hit 100% (0 bytes) during step 3's copy: the 71 MB
  tool binary truncated at 12 MB, installer failed the cp
  cleanly ("command failed (1): cp …", rc 1). The installer's
  behavior was correct; the failure is the box's disk (my whole
  footprint was ~0.5G of the ~3G that vanished to other lanes'
  concurrent builds). The truncated binary segfaulted when the
  script's acceptance ran it — a disk artifact, not a tool
  defect. Freed my scratch HOME's jank caches (161M) and reran:
  with the snapshot fresh, the rerun skips the build (also
  proving the fresh path) and redoes step 3 + acceptance.

- JANK SEAT PROOF: PASS (rerun, ~16:41 ET). SNAPSHOT-FRESH on
  df88adacda69c0e2d4ac53df68476d9d5b6ea40d7dbac44bb5d9c1bbc87bde39;
  INSTALLER-RC=0; install.edn records :seat :jank; deps-new
  installed at the reference record {:git/tag "v0.17.0",
  :git/sha "657fca6"} with the full-sha checkout
  657fca6c33cd…, re-run prints the skipping line; cohost -e
  "(+ 1 2)" -> 3; -Spath correct; poison verdict PASS (clojure
  and bb never invoked, poisons up for install + acceptance).
  The regression control is green: the generalized file on the
  jank seat reproduces the completed lane's proof exactly.
- run-rest launched: bb boundary probe, bb proof, battery on
  the bb-built snapshot, clj stage A, battery, clj stage B.

- BB BOUNDARY PROBE: PASS — with jank poisoned, the bb-driven
  build tripped at its first jank touch (jank_snapshot_key.clj:45
  shelling `jank print-binary-version` for the snapshot key),
  installer rc 1, invocations.log = exactly that one call.
- BB SEAT PROOF: PASS (~17:59 ET). The original build, driven by
  bb, produced SNAPSHOT-BUILT df88adacda69c0e2d… — the identical
  key to the jank flow (build's own timing line: 1,009,571 ms
  under load) — PLACEMENT 857 of 872; INSTALLER-RC=0;
  install.edn {:seat :bb, :bb "/home/john/.local/bin/bb",
  :jank "/usr/local/bin/jank"}; deps-new at the reference
  record + skipping re-run; -e -> 3; -Spath correct; poison
  verdict PASS (clojure never invoked). Disk brushed 100%
  again after this proof (61M free); freed 269M of my scratch
  JIT caches; the clj stage's own wipe frees its build room.

- Battery on the bb-built snapshot: 219/221 stock behaviors,
  62/62 library cases, rc 0 — the completed lane's exact
  numbers on the jank-built snapshot.
- CLJ STAGE A, ATTEMPT 1: FAILED in the build, installer
  correct throughout. The -Sdeps resolution worked (fs 0.5.24
  + process 0.5.22 downloaded from Clojars — the pins are
  right, and they live on Clojars, not Central). The ensure
  reached AOT-RECOMPILE; a clang cc1 compiling a stage-1 TU
  was SIGTERMed ("unable to execute command: Terminated") at
  peak box contention (load 14+, 16 users, disk at 100%).
  No oomd/earlyoom on the box; memory is comfortable between
  peaks; the sender is unidentified — recorded as a transient,
  not a code path: babashka.process/shell in jank_aot_ensure.clj
  has no timeout or destroy, and the same TUs compiled clean
  under the jank- and bb-driven builds. Secondary wart, named
  for honesty: when the ensure's exception reached
  clojure.main, its error REPORT crashed in clojure.pprint
  (simple-dispatch ambiguity on babashka.process.Process,
  which is both IPersistentMap and IDeref) — the real error
  was still fully visible in the log, and the installer
  reported "command failed (1)" + rc 1 correctly.
  battery(clj) in that sequence is VOID (no snapshot existed).
  clj stage B, launched by the driver against the wiped tree,
  is itself running a clj-driven cold build with bb poisoned:
  if it reaches the emit it trips the poison (the boundary
  evidence); stage A gets ONE rerun after it (two-attempt
  rule, diagnosis banked here), then stage B as designed.

NEXT: clj B (boundary) verdict; clj A rerun; battery(clj);
clj B as designed (fresh snapshot, bb poisoned). Then the
final STATUS + report, docs commit, slot release.
