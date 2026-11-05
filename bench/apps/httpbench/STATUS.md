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

## 2026-11-05 03:26 ET — BUILD 7: still step 1, on the shim module (~34 min in)
- 7 AOT objects banked. The in-flight module is cohost/shim — the
  giant java-facade file and the graph's known monster at -O3. Its
  clang has ~1:20 CPU after ~10 min wall (it is getting a fraction
  of a core under load ~13) and RSS is 1.8 GB and growing — the
  step to watch: if anything OOMs on this box tonight it will be
  this compile or a neighbor of it.
- Nothing else to report: no step boundary crossed since 03:11,
  build neither finished nor failed. Steps 2-4 still ahead.

## 2026-11-05 03:33 ET — BUILD 7 DEAD (box cycle); build 8 goes to J
- At ~03:29 ET this box cycled again (uptime 2 min on first check
  after; /root/.ssh wiped and restored per the recovery notes).
  Build 7 died with it at step 1, ~37 min in: 7 AOT objects
  banked, cohost/shim still in flight. The 7 objects survive on
  disk but are unusable — the ensure is all-or-nothing (no stamp
  until the graph completes; a relaunch's recompile! wipes the
  tree). No verdict on the fix yet: the build never reached step 2.
- Pattern, stated plainly: this box has now cycle-killed builds 3,
  4 and 7. A ~40-60 min compile leg does not survive here tonight.
- BUILD 8 PLAN (re-plan under the two-attempt rule — same leg,
  new venue): run the identical build on J, where the same class
  of AOT app build measured 407 s (n13 unit 2) and snapshot
  compiles ~9 min. J's heavy slot belongs to the debug-seam final
  gate until it banks (its 08:11 UTC report: boot:jank exit 0,
  snapshot task running; no clang active on J at last check). I am
  pre-syncing this branch to a separate J tree now (the gate's
  ~/cohost-cpp is not touched) and will launch build 8 on J the
  moment the gate banks.

## 2026-11-05 03:52 ET — BUILD 8 (on J): record step PASSED with the fix; step 4 moved the wall; fixes banked
- Build 8 ran on J (~/cohost-httpbench, synced @ 9272e0a) in
  ~7 min: step 0.5 OK, step 1 AOT ensure OK, **step 2 RECORD
  PASSED — SNAPSHOT-EVENTS written, MODULE emitted. Builds 5/6
  died exactly here; the record-lib cure works in the real path.**
  Step 3 emit OK. Step 4 (jank compile of cohost.app-main) failed:
  its own JIT session reported Symbols not found for the
  us_internal_ssl_* family (+ unlink_socket, is_shut_down).
- Diagnosis, all firsthand on J: the symbols EXIST in the archive
  and the .so — but only as UNDEFINED. (i) Step 4's JIT loads
  nothing from -L/-l (those feed the static link only); a hand
  re-run of the failed step-4 command with -L record-lib added
  materialized cleanly and reached the linker. (ii) The static
  link then failed on the real gaps: uSockets' core sources call
  us_internal_ssl_* unconditionally, but the defining file
  crypto/openssl.c was never in the app's :sources — and it is
  empty without -DLIBUS_USE_OPENSSL and needs crypto/sni_tree.cpp
  beside it; uWebSockets' PerMessageDeflate needs -lz.
- Fixes banked @ f2fa324 (build 9 runs with them):
  * src/cohost/build_jank.clj: :cpp learns :defines (passed as
    -D to the step-0.5 compiles); step 4's compile line now also
    carries -L record-lib after -L work (archive still wins the
    static link; the .so serves step 4's JIT, as it serves step 2's).
  * bench/apps/httpbench/deps.edn (the app's own config): :sources
    += crypto/openssl.c, crypto/sni_tree.cpp; :defines
    [LIBUS_USE_OPENSSL]; :libs [ssl crypto z] (all present on J).

## 2026-11-05 04:00 ET — BUILD 9 GREEN on J; loopback smoke PASS
- JANK-EXE-BUILT in 285,992 ms: target/httpbench/run, 48,209,472
  bytes. Steps: 0.5 = 9 cpp objects; record SNAPSHOT-EVENTS;
  emit MODULE; step-4 compile + static link clean.
- ldd: NO libcpp dependency (system libssl/libcrypto/libz only) —
  the archive won the static link; the record-lib .so served both
  JIT sessions (steps 2 and 4) without leaking into the binary.
- Smoke (J, 127.0.0.1:8931): GET / -> HTTP 200, body EXACTLY
  1024 bytes, the fixed JSON with 48 items; second request 200.
  Server killed by PID after the check.
- Note vs the spec's "(OpenSSL off)": the server is plain HTTP
  only, but the vendored uSockets core calls us_internal_ssl_*
  unconditionally and ships no stub file, so its real openssl.c
  backend is compiled and linked (system ssl/crypto) purely to
  satisfy the link. No TLS is served.
- Next per spec: cohost -X:test on jank and JVM; the binary from
  another directory; docs:check jank; measurements (measure.sh);
  README; CAPSTONE report.

## 2026-11-05 04:12 ET — gates on J: tests PASS both hosts; from-another-dir PASS
- cohost -X:test on J: JVM 4 tests / 17 assertions, 0 failures,
  exit 0; JANK same counts, exit 0 (fresh snapshot built in this
  tree, 364 s).
- From-another-directory: the binary copied alone to
  ~/hb-elsewhere/run and started from / served HTTP 200 with
  exactly 1024 bytes; killed by PID afterwards.
- Measurement prep on J: nginx present (/usr/sbin/nginx); wrk
  absent (building from the wrk source kept on the hatch box);
  ephemeral range 32768-60999 = 28,232 ports, so the concurrency
  series runs 100 / 1,000 / 10,000 and a capped top row at 25,000
  under the spec's cap clause; hard fd limit is ~1.07e9 (no fd cap).
- Remaining: wrk build, measure.sh run, docs:check jank, README,
  CAPSTONE report.

## 2026-11-05 04:24 ET — measurement prep on J: wrk building
- wrk was absent on J; its source (kept at
  ~/workspace/cohost-install/wrk-src on the hatch box) was
  transferred and is building on J (Makefile's unzip step done
  via python3 zipfile — no unzip on J; one stale-symlink hiccup
  from the transferred obj/ tree, removed). Bundled OpenSSL
  compile is the long part.
- This box's /tmp is 100% full (512 MB tmpfs; 496 MB is another
  lane's cargo-install scratch — left alone; nothing of this
  lane's needs /tmp here).
- Next: measure.sh on J (CAP=25000 per the ephemeral range),
  then docs:check jank, README, CAPSTONE report.

## 2026-11-05 04:38 ET — measurements: server proven by wrk; bench client segfaults 15/15
- measure.sh ran on J (quiet box, load 0.57). The SERVER half is
  proven by the external reference: wrk -t4 -c1000 -d10s against
  httpbench serve: 700,665 requests in 10.05 s = 69,715 req/s,
  76.52 MB/s, avg latency 14.38 ms.
- The BENCH half segfaulted on every one of its 15 runs (c=100,
  1000, 10000, 25000 vs its own server; c=1000 vs nginx) — dies
  before printing anything. Never exercised before (no build had
  ever completed). Diagnosing now: minimal repro + backtrace;
  the fix lands in the app, rebuild on J, re-measure.

## 2026-11-05 04:47 ET — bench fixed; config corrected to no-SSL; final gates running on J
- BENCH SEGFAULT ROOT-CAUSED AND FIXED: gdb backtrace (core from
  J, analyzed on this box) = hb_bench -> us_loop_run -> call to
  address 0. The vendored loop.c calls the loop's pre/post
  callbacks unconditionally; hb_bench passed nullptr for all three
  (the server half uses uWebSockets' own Loop, which installs real
  ones). Fix @ (native.cljc): no-op callbacks. Verified: 1,000
  requests at c=10 -> 106,632 req/s, 0 errors, --json correct.
- MEASUREMENTS, FIRST PASS (build-10 binary, J, load ~0.14):
  c=100 median 83,107 req/s; c=1000 median 96,900 req/s; c=10000
  median 56,460 req/s — all 0 errors over 100,000 requests each.
  c=25000 (the cap row: 28,232 ephemeral ports): ~82k completed,
  ~18k connect errors — past the single-IP practical cap; the last
  clean row is 10,000. wrk reference vs the server: 74,519 req/s.
- CONFIG CORRECTION (supersedes the build-8/9 note): the vendored
  uSockets is the NO-SSL configuration (cpp/vendor/VERSIONS.md;
  the spec says OpenSSL off). Every core-source ssl call site is
  inside #ifndef LIBUS_NO_SSL, so the app now builds with
  :defines [LIBUS_NO_SSL] and :libs [z] only — no openssl.c, no
  OpenSSL link. The build-9/10 binary (OpenSSL-linked) is retired;
  build 11 (no-SSL) is the deliverable and ALL final numbers are
  re-measured on it.
- measure.sh fixture fixed: its nginx reference body was a
  hand-typed literal with 45 items; the bench validates 48 and
  rejected all 100,000 nginx responses. The script now embeds the
  byte-exact canonical 1024-byte body served by the app.
- Gates on J: jank -X:test re-run on the fixed tree PASS (4/17/0,
  exit 0); docs:check in flight, all PASS so far. This box cycled
  twice during this stretch (builds 7's killer); all lane work is
  on J / in git, nothing lost.

## 2026-11-05 05:02 ET — CAPSTONE COMPLETE: build 11 (no-SSL) green, all gates pass, numbers in README
- BUILD 11 on J @ b3da4ac (no-SSL config): JANK-EXE-BUILT in
  114,898 ms; binary 48,158,120 bytes; ldd shows libz only (no
  ssl/crypto, no libcpp). Smoke: HTTP 200, exactly 1024 bytes.
- GATES (all on J, this branch): cohost -X:test JVM 4/17/0 exit 0;
  jank 4/17/0 exit 0; binary from another directory (copied alone,
  run from /) 200/1024; docs:check exit 0, 22 PASS 0 FAIL — its
  examples include a full -T:build build, so the build path itself
  was exercised there. Blast radius since c9ad1d6: src/cohost
  changed ONLY in build_jank.clj; everything else is inside
  bench/apps/httpbench/.
- FINAL NUMBERS (build-11 binary, measure.sh, medians of 3 x
  100,000 requests, J load 1.58): c=100 -> 84,879 req/s; c=1000 ->
  84,482 req/s; c=10000 -> 61,239 req/s; all 0 errors. wrk
  reference vs the server: 80,502 req/s. Same bench vs nginx
  1.26.3 (identical body): 71,441 req/s, 0 errors. Cap row
  c=25000 (28,232 ephemeral ports): 82,004 completed, 17,996
  connect errors — reported as measured; 10,000 is the highest
  error-free concurrency here. Full tables in README.md.
- The 14 debug-seam checks are NOT run on this branch: they live
  on the debug-seam branch (dev/debug_seam_check.clj) and are
  gated by that lane's final gate; this branch's tree predates
  them. Stated here so the gap is explicit, not silent.
- Lane done: README.md written (build, run, numbers, C++ half,
  Clojure half), CAPSTONE entry filed in the coordinator channel.
