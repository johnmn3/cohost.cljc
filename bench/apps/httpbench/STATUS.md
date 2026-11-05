# httpbench capstone — lane status

## 2026-11-05 02:31 ET — PICKUP (revival lane)
- Branch muse/httpbench verified: origin == local tip c9ad1d6.
- Uncommitted instrumentation in src/cohost/build_jank.clj (one-line spit of
  record output to record-out.txt, from build 6) REMOVED; worktree clean.
  It must never be committed.
- Build 6's events.edn (target/httpbench/cohost-build/events.edn, 21:59
  Nov 4) read firsthand: httpbench.native entry :ok 4 :error 1 — the one
  errored form is the big cpp/raw block: :error "Unable to compile C++
  source." No compiler diagnostic is captured anywhere (record stdout was
  boot summary + SNAPSHOT-EVENTS only). So build 5/6's failure is: the
  native cpp/raw block fails to compile inside the record (JIT) session,
  its functions never register, emit drops it, AOT link dies on hb_serve.
- Open question (diagnosing now, light): WHY does the raw fail in the
  record session when c9ad1d6 already forwards the :cpp include dirs as
  CPLUS_INCLUDE_PATH to that session (build_jank.clj:136-139)? Repro the
  exact block compile outside the build with stderr visible.
- Venue: J's heavy slot is held by the debug-seam final gate; this box
  hosts the collation's JVM legs. Diagnosis/prep here (light); the AOT
  build launches where a slot frees, detached, log in this worktree.

## 2026-11-05 02:47 ET — DIAGNOSIS: the raw block is fine; the failure is in the record session
- Extracted the exact cpp/raw text (6,905 bytes) from native.cljc and
  compiled it as a standalone TU (ns (:include) headers + raw text) with
  g++ -fsyntax-only -std=c++17 -I<the three :cpp dirs>: exit 0, zero
  diagnostics. The C++ code itself is NOT the failure.
- Minimal jank JIT repro (scratch-diag/jit/t1.jank: (:include "App.h"
  "simdjson.h") + a one-line cpp/raw): WITHOUT CPLUS_INCLUDE_PATH it
  fails with the build's exact error — "fatal error: 'App.h' file not
  found" -> "error: Unable to compile C++ source." WITH
  CPLUS_INCLUDE_PATH=<the three dirs> it runs (probe: 42). So jank's JIT
  honors CPLUS_INCLUDE_PATH, and the include path is the whole mechanism.
- Therefore build 6's record session must NOT have had the include dirs
  in its effective CPLUS_INCLUDE_PATH, despite build_jank.clj:136-139
  constructing record-env with them (c9ad1d6). Remaining suspects, in
  order: (i) sh!'s env handling drops/replaces rather than merges;
  (ii) the 21:59 run's events are stale/not from the fixed code path;
  (iii) JIT compile cache/PCH keyed without the env.
- Decisive probe launched (detached, pid 154059, logs in scratch-diag/):
  boot cohost in a plain JIT session (module-path src only, no AOT
  objects), CPLUS_INCLUDE_PATH set exactly as record-env builds it,
  load-lib! httpbench.native with stderr captured — jank prints the real
  clang diagnostic to stderr when a raw fails (the record harness only
  kept stdout + the generic :error string).
- VENUE: J's heavy slot is held by the debug-seam final gate (snapshot
  building now); this box is loaded with other lanes (eve-jank tests,
  collation legs). No heavy build launched yet; the AOT-ensure objects
  are stale (AOT-RECOMPILE) after the box cycle and will be refreshed
  when a slot frees.
