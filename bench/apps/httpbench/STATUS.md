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

## 2026-11-05 03:02 ET — DIAGNOSIS CLOSED: named cause + proven cure (repro scale)
- sh! (build_jank.clj:34) merges the full parent env with record-env —
  the CPLUS_INCLUDE_PATH delivery is CORRECT; suspect (i) dead.
- The cohost JIT-boot probe (record_probe.jank) died silently under box
  contention (load 13, ~2.4 GB free; consistent with the box's known
  OOM pattern) — no output; not retried in that form.
- Discriminating repros (plain jank, no cohost boot), all with
  CPLUS_INCLUDE_PATH set to the three :cpp dirs:
  * t1 (trivial raw): passes. Also passes with two of the three
    ~/.cache/jank AOT dirs on --module-path — AOT-baked paths are NOT
    the wall.
  * t2 (the FULL 6,905-byte raw from native.cljc + a call): compiles,
    then fails at materialization: "JIT session error: Symbols not
    found: [us_socket_context, us_create_loop, ..., simdjson...]".
  * t3 (the full raw, NO calls): fails identically AT THE cpp/raw
    EVAL. ==> jank's JIT materializes a raw block's translation unit
    when the form evaluates; external symbols the block references
    must be resolvable inside the JIT process itself.
- NAMED CAUSE: build_jank compiles the project's :cpp :sources only
  at step 3.5 — after the record step — into an archive used solely
  by the final AOT link. The record (JIT) session therefore cannot
  eval httpbench.native's cpp/raw: the uSockets/simdjson externs are
  unresolvable there. The record harness records the failed eval
  under its generic ":error \"Unable to compile C++ source.\"", emit
  silently drops the form, and the AOT compile dies two steps later
  with analyze/unresolved-cpp-symbol "Unable to find hb_serve".
  c9ad1d6's CPLUS_INCLUDE_PATH fix was necessary but not sufficient:
  headers were the first wall; symbol resolution is the second.
- CURE PROVEN at repro scale (both routes): build a shared lib from
  the :sources (cc/c++ -fPIC, c++ -shared -> libcpp.so, 195 T syms),
  then either `jank -L <dir> -l cpp run t3.jank` or LD_PRELOAD=<so>
  makes the full raw eval cleanly ("raw-eval-ok", exit 0). jank's own
  -L/-l flags are the clean route.
- FIX PLAN (only src/cohost/build_jank.clj, the :cpp key's file):
  hoist the :cpp source compilation before the record step, compile
  once with -fPIC, produce libcpp.so in work/record-lib/ (record step
  gets -L work/record-lib -l cpp) and libcpp.a in work/ as today for
  the final static link (the .so must NOT sit where step 4's -l cpp
  can find it first — a dynamic dependency would break the
  run-from-another-directory gate).

## 2026-11-05 03:10 ET — FIX COMMITTED (9a0e41e); BUILD 7 LAUNCHED on this box
- build_jank.clj (the one permitted src/cohost file): the :cpp sources
  now compile ONCE with -fPIC at step 0.5, before the record step;
  the archive still serves step 4's static link from work/, and a
  shared twin goes to work/record-lib/libcpp.so, passed to the record
  jank as -L work/record-lib -l cpp. Old step 3.5's duplicate compile
  is gone (flags only). Paren check OK; file parses (13 forms).
- Venue: J's heavy slot is still the debug-seam final gate's (its
  snapshot log touched 07:48 UTC, tree at the collated tip be95bb2),
  so build 7 runs HERE (box load ~1 at launch): bin/cohost -T:build
  build in bench/apps/httpbench/, detached, log bench/apps/httpbench/
  build7.log. First attempt died on a wrong relative path to
  bin/cohost (mine, cost ~0); relaunched absolute. JANK-EXE-BUILD
  .../target/httpbench/run (new) is in the log — the exe is named
  "run" (that also identifies Nov 4's 18:20 binary as a build product
  of this same path).

## 2026-11-05 03:10 ET — FIX COMMITTED (9a0e41e); BUILD 7 LAUNCHED on this box
- build_jank.clj (the one permitted src/cohost file): the :cpp sources
  now compile ONCE with -fPIC at step 0.5, before the record step;
  the archive still serves step 4's static link from work/, and a
  shared twin goes to work/record-lib/libcpp.so, passed to the record
  jank as -L work/record-lib -l cpp. Old step 3.5's duplicate compile
  is gone (flags only). Paren check OK; file parses (13 forms).
- Venue: J's heavy slot is still the debug-seam final gate's (its
  snapshot log touched 07:48 UTC, tree at the collated tip be95bb2),
  so build 7 runs HERE (box load ~1 at launch): bin/cohost -T:build
  build in bench/apps/httpbench/, detached, log bench/apps/httpbench/
  build7.log. First attempt died on a wrong relative path to
  bin/cohost (mine, cost ~0); relaunched absolute. JANK-EXE-BUILD
  .../target/httpbench/run (new) is in the log — the exe is named
  "run" (that also identifies Nov 4's 18:20 binary as a build product
  of this same path).

## 2026-11-05 03:11 ET — BUILD 7: RUNNING, step 1 of 4 (AOT ensure), ~19 min in
- State: RUNNING on this box (launched 02:52 ET, detached, log
  bench/apps/httpbench/build7.log). Not finished, not failed.
- Done: step 0.5 (the fix's new step) — JANK-EXE-CPP 7 objects
  (archive + record shared lib); record-lib/libcpp.so exists.
- NOW: step 1, the AOT ensure. That is ONE jank invocation —
  `jank --module-path src compile-module cohost.run -O3` — which
  recompiles the whole clojure+cohost module graph into
  target/jank-aot/_cache/<key>/ (all-or-nothing: recompile! deletes
  the tree and the stamp is written only at the end). 6 objects
  banked + 1 in flight (cohost/policy.o the newest finished). The
  build log prints nothing during this step by design (build_jank
  logs only at step boundaries; the ensure prints AOT-DIR when
  done) — the log's quiet since 02:56 is this step, not a hang: the
  clang children are accumulating CPU and objects are landing.
- Why slow: box contention (load ~13 at this writing: the 1BRC
  lane, eve-jank tests, and two long wasmtime processes are all
  live). Memory is currently OK (~2.7 GB available).
- Still ahead: step 2 record (the step the fix changes — first real
  test of -L/-l in situ), step 3 emit, step 4 the -O3 static
  compile + link. On a green JANK-EXE-BUILT: loopback smoke, then
  jank -X:test, per the spec order.
- J remains the final gate's (its snapshot still in flight at
  08:07 UTC; gate's JVM check reported 10/14 PASS 0 FAIL).
