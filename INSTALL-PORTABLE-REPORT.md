# Cohost portable installer — final report

Branch `lane-m/cohost-install-portable`, based on the verified
completion of `lane-m/cohost-install-jank`
(`7a02d2cffaf97b1c69d55eacef6a6e6406193154`). All proofs ran on
j.slaa.us under a claimed heavy-queue slot (released at close).
Nothing was merged to main or to `release/eve-in-cohost`.

## What shipped

`install.cljc` now runs on any of three Clojure-likes — Jank,
Babashka, or the Clojure CLI — and that seat bootstraps cohost.
The file stays single and self-contained: only the host shim
(capture, sh!, getenv, self-exe) is factored into per-seat
reader-conditional arms; the orchestration is the jank-only
installer's, preserved. The build step dispatches per seat:
jank → `dev/install/jank_build.cljc`; bb → the original bb
snapshot build (`dev/perf/jank_snapshot_ensure.clj`); clj → the
same build under the Clojure CLI with `-Sdeps` pinning
babashka/process 0.5.22 + babashka/fs 0.5.24 (both resolve from
Clojars, verified). The tool itself is compiled with jank on
every seat; a non-jank seat without jank on PATH gets it from
the checkout's `dev/install/jank.sh` (step 1.5). install.edn
records `{:repo :jank :bin :seat}`, plus the driver path under
its own key on non-jank seats. README and
`doc/guide/01-getting-started.md` now word the prerequisite as
exactly the three proven seats, with jank named as the tool's
compiler.

Dialect discipline held: one file reads and loads on all three
readers (arm order :jank, :bb, :clj); no new regex literals —
the file's only two are the original's escape-free patterns.

## Proof verdicts (logs in the J staging, summarized in STATUS)

- **jank seat (regression control): PASS.** The completed
  lane's poisoned-HOME proof, strengthened (poisons up for the
  install too, installer run from a neutral cwd): exit 0,
  SNAPSHOT-FRESH/BUILT on
  `df88adacda69c0e2d4ac53df68476d9d5b6ea40d7dbac44bb5d9c1bbc87bde39`,
  deps-new installed at `{:git/tag "v0.17.0", :git/sha
  "657fca6"}` with the skipping re-run, `cohost -e "(+ 1 2)"`
  → 3, clojure and bb never invoked.
- **bb seat: PASS.** clojure poisoned throughout, never
  invoked. The bb-driven original build produced the identical
  snapshot key (its own timing line: 1,009,571 ms under load),
  placement 857/872; battery on that snapshot: **219/221 stock
  behaviors, 62/62 library cases**. Boundary probe: with jank
  poisoned, the build tripped at its first jank touch
  (`jank_snapshot_key.clj:45` shelling `jank
  print-binary-version`) and the installer failed there, rc 1.
- **clj seat: PASS.** Stage A (cold build driven by the JVM
  Clojure): the identical snapshot key (830,844 ms), battery
  **219/221 + 62/62**, deps-new parity, `-e` → 3. Stage B
  (fresh snapshot, bb poisoned): install completes, bb never
  invoked. Boundary, proven live: a clj-driven *cold* build
  reaches the emit and shells `bb
  dev/perf/jank_snapshot_emit.clj …` — dev/perf's emit step is
  hardcoded to bb (see Boundaries).

## Defects found and fixed on the way

1. **The inherited argv parse dropped every flag value**
   (filter-before-partition), so `--repo`/`--bin` never took
   effect in the jank-only installer either; all its proofs
   were masked by cwd == --repo. Fixed with a pair loop;
   proven by every seat honoring `--repo` from a neutral cwd.
2. **jank's JIT cannot realize a factored failure handler**:
   the handler is inline again, in the original's exact shape,
   with only the catch header and exit call armed per seat.
3. **jank's JIT codegen for `case*` on keywords is broken**
   (errors point into `clojure/core.jank:4531`, the case
   macro's own machinery). All seat dispatch is if-chains;
   the ban is commented at the seat def.

## Boundaries, stated plainly

- The installed `cohost` command is a jank-compiled program on
  every seat — that is scope item 4's "the same tool as
  today's". The bb/clj seats generalize who *drives* the
  install, not what compiles the tool; the boundary probes
  above show exactly where jank is touched.
- The original snapshot build's emit step shells `bb`
  (hardcoded in `dev/perf/jank_snapshot_key.clj`). On the bb
  seat that is the seat itself; on the clj seat a cold build
  therefore also needs bb present for the emit (a fresh
  snapshot needs neither). Untangling that is a dev/perf
  change that would disturb the cross-flow snapshot-key
  parity, so it is named here, not taken.
- A JVM-driver wart: when the ensure throws, clojure.main's
  error *report* can itself crash pretty-printing a
  babashka.process.Process ex-datum (pprint simple-dispatch
  ambiguity). The underlying error remains visible in the
  build output, and the installer reports the failure and
  exits 1 correctly.
- Stretch seats (:lpy, :clje, :cljs) were **not** armed:
  future arms, named per the brief. The three-seat pattern is
  the proven shape for adding them.

## Environment notes (for the coordinator)

- The root disk on J sat at 100% for much of the proof window
  (other lanes' trees: ~/ds11 ~3.6G, ~/u22-collate-j ~1.4G,
  ~/u22-finish ~1.0G). It cost one truncated tool copy
  (installer failed the cp cleanly) and two clj stage-A
  attempts (a SIGTERMed clang at peak contention; an ENOSPC
  in the record stage). The final clj proofs were staged on
  the box's second disk, `/home/john/j` (/dev/sdb, 393G,
  ~373G free), which removed the variable entirely.
- One harness "terminal result" delivered to this lane
  contained output for steps that never ran (a bb process it
  reported as exited rc 1 was still alive in `ps`). Every
  claim in this report is from log files read directly off
  the box.
