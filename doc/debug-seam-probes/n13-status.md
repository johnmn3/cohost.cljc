# #13 lane STATUS — muse/debug-seam-n13 (base: muse/debug-seam-native @ eef1ab4)

## 2026-11-05 00:31 ET — lane start, cold pickup

- Worktree ~/workspace/cohost-n13 created from eef1ab4 (doors IN this tip:
  a185cd5 rw-try native doors + eef1ab4 name discipline).
- Read: doc/debug-seam-probes/n13-diagnosis.md (fchain unit: wrapper landed
  in boot.cljc wrap-macro/expand-error; JVM headline residual = two
  host-type table gaps), dev/debug_seam_check.clj #13 probes
  (seam.badmacro load + seam.throwmacro body-throw; needle "macroexpanding"
  or "Expanded from this macro" over stdout+stderr+report files), the
  J gate line @ 358048e (impl-plan Status): #13 PASS jvm / FAIL jank,
  "macro attribution blocked by the native escape"; harness line @ 29cfac1:
  "on jank the fixture's macro throw crosses as a native C++ exception".

## Mechanism read (code, this tree)

- Expansion path on jank: rewrite calls ctx :expand-macro =
  jank_host.jank's expand-macro, which applies (var-get v). User macros
  are wrapped by boot's scan-macros! (macro-wrap-style :root, the adapter
  default; jank does not override) -> var root is wrap-macro's fn.
- wrap-macro calls the macro fn under host/try-all (adapter.cljc). The
  jank arm of try-all catches cpp/jank.runtime.object_ref (a thrown
  ex-info IS one) and hands it to the handler -> expand-error attaches
  {:clojure.error/phase :macroexpansion :clojure.error/symbol ...}.
  On paper the attribution should now survive; the doors do not touch
  this path (they are in rw-try's lowering of user try forms).
- OPEN QUESTION the probe must answer: where the throw actually goes
  native on jank -- candidate seams: (a) the fixture macro's throw is
  never under wrap-macro (jank's own analyzer expands (m 1) instead of
  the codemod), (b) try-all's compiled form in the snapshot loses the
  object_ref catch, (c) attribution attached but the report/triage on
  jank drops it before the needle text is produced.

## Probe vehicle

- ~/workspace/cohost-native (same tip eef1ab4) has a snapshot build
  RUNNING NOW (bb boot:jank-snapshot, compile-module cohost.snapshot
  -O3 in flight at lane start). Not mine; do not disturb. When it
  lands, run seam.throwmacro + seam.badmacro through that launcher --
  REPL-scale verdict on the first question.
- J box: takeover lane active there (introspect + traces); my full
  jank runner run queues behind it.

## Next

1. JVM ground truth: bb debug-seam:check jvm (expect #13 PASS; no regress).
2. Probe on the native snapshot when built.
3. Fix in cohost layers only, if a fix is needed.

## 2026-11-05 00:41 ET — probe vehicle reassessed; J queue position known

- The cohost-native snapshot build: its LAUNCHER compile was OOM-KILLED
  on this box (native-snapshot3.log: "clang frontend command failed
  with exit code -2"; the script fell back to compiling the modules
  alone). Same wall the introspection lane (x4) and gateprobes lane
  hit locally. Local snapshot = dead end; J is the probe vehicle.
- J box (TAKEOVER-STATUS.md, read 00:41 ET): Remainder 1 DONE --
  introspect snapshot built on J in 524,475 ms, #11 PASS; full runner
  7/14 PASS / 4 FAIL / 3 SKIP, "only #11 moved vs the 358048e gate"
  (so #13 still FAILs on jank on the introspect tree, which has NO
  doors -- consistent with the wrapper alone not fixing jank).
  Takeover has switched to the traces tree (Remainder 2, snapshot
  building). My jank run queues behind Remainder 2.
- Code read since pickup (all in-tree): load-source! keeps the FIRST
  failure's original exception object and load-lib! rethrows it
  untouched -> the attributed ex-info, if produced, reaches the CLI
  report layer intact. cli throwable->map takes :phase from the FIRST
  :via entry's booted-ex-data. So the jank break is at or before
  wrap-macro's handler: (a) jank's analyzer expands (m 1) instead of
  the codemod, (b) try-all's object_ref catch misses the macro throw
  in the snapshot's compiled boot (falls to the :default native
  sentinel), or (c) booted-ex-data reads nil for the attributed
  ex-info on jank.
- JVM check running detached (log cohost-install/n13-jvm-check.log).

## 2026-11-05 00:46 ET — JVM verdict IN; J snapshot building

- JVM GROUND TRUTH (this base, full runner): "debug-seam:check jvm:
  10/14 PASS, 0 FAIL"; "#13 PASS load exit 1, throw exit 1; report
  names the macroexpansion". No regression; the JVM half stands.
- J takeover COMPLETE (both remainders filed; #1/#6 PASS, #7 needle-
  shape FAIL is theirs). Box free; my branch synced to ~/cohost-cpp
  (J tree at 46aaf98), target/ wiped, bb boot:jank-snapshot launched
  detached (log ~/jgate-logs/n13-snapshot.log on J; ~9 min by the two
  takeover builds: 524/531 s).
- Next when the snapshot lands: probe seam.throwmacro + seam.badmacro
  via bin/cohost :jank -m in dev/cli-demo (exact gate shape), read
  stderr + report EDN -- answers candidate (a)/(b)/(c) above.

## 2026-11-05 01:05 ET — mechanism FOUND by probe bisection; fix applied

Snapshot built on J in 531,474 ms. Gate shape reproduced on this base:
throwmacro exit 1, headline "a native C++ exception" (report via =
  the {:cohost/native true} sentinel only; the macro's "bad" ex-info
  nowhere in the chain). badmacro fine (CompilerException headline).

Bisection (all REPL-scale, through the built launcher):
- P2: host/try-all catches a directly thrown ex-info on jank. OK.
- P4: fixture-style macro var IS :cohost/wrapped on jank. OK.
- P13: wrapped call of a non-throwing macro works. P16: the macro
  body's own catch sees a clean ExceptionInfo "bad" {:k 2}.
- P17: the REAL wrap-macro with a hand throwing fn reproduces the
  escape exactly (std::exception "An object of type
  `persistent_string` was expected here, but a `nil` was provided").
- P19: hand fn throws an ex-info ALREADY carrying the phase ->
  expand-error pass-through -> caught cleanly. So try-all, the
  handler, and (ex-data e) are all fine; the broken step is
  expand-error's CONSTRUCTION.
- Root cause: expand-error builds (ex-info nil {...} e). In cohost's
  own compiled code the plain ex-info reference reaches the host's
  NATIVE constructor; jank's native ex-info rejects a nil message
  (jank_host.jank:2726 says so in its own comment) and throws a
  std::exception out of the handler -- the next try-all upstream
  converts it to the :cohost/native sentinel and the attribution is
  lost. User code never hits this: the booted ex-info routes through
  the adapter :ex-info cap, which stores "" and marks
  :cohost/nil-message. expand-error is the ONLY (ex-info nil ...) in
  src/ (grep-verified).
- NOT the doors (they were never in this path) and NOT candidate
  (a)/(b)/(c) as framed: the wrapper ran; its construction died.

Fix (boot.cljc, expand-error): construct via host/ex-info* (the
adapter cap) instead of plain ex-info. On the JVM the cap IS plain
ex-info (nil message preserved); on jank it is the nil-safe wrapper.
Paren check OK (133 forms). Rebuild + probe + full runner on J next;
JVM runner re-check after (must stay 10/14, #13 PASS).

## 2026-11-05 01:20 ET — LANE COMPLETE

- Fixed snapshot on J built 523,068 ms (stamp 00dd8851), boot
  report errors: 0. Fixture stderr now: "Unexpected error
  (ExceptionInfo) macroexpanding seam.throwmacro/m at (REPL:1)." /
  "bad" -- attribution lands (the "Unexpected error" wording is the
  port's standing phase wording, cli.cljc:210, all hosts).
- JANK RUNNER (full): "#13 PASS load exit 1, throw exit 1; report
  names the macroexpansion"; tally 8/14 PASS, 4 FAIL. Vs the
  358048e gate baseline (6/5/3): #13 FAIL->PASS (this fix), #3
  SKIP->PASS (doors, this base); #1/#6/#7/#11 FAILs are the
  traces/introspect fixes, not on this branch -- collation composes.
- JVM RUNNER (post-fix): "debug-seam:check jvm: 10/14 PASS, 0 FAIL",
  #13 PASS -- identical to pre-fix. No regression.
- Lane report: doc/debug-seam-probes/n13-lane-report.md (mechanism,
  probe trail, quoted verdicts, named remainders: the JVM headline
  host-type-table residual stays a coordinator routing item -- not
  needed by #13 on either host; the general (ex-info nil ...) hazard
  in cohost's own compiled code, this being its only site).
- Status gate line appended to doc/debug-seam-impl-plan.md.

## 2026-11-05 01:30 ET — UNIT 2 (#9 AOT exit 0): design read, implementing

- Gate probes' measurement (their report, read firsthand): AOT route
  bb build:jank -> cohost.build-jank/build!; generated app-main
  (jank_snapshot_emit.clj) is (boot-from-snapshot! {}) (make-user-ns!)
  (apply <app>/-main args) -- no report wrapper; jank's generated
  main prints the escape as a #error value and exits 0.
- The launcher's app-main instead applies cohost.cli/-main, whose
  run-cli! wraps the run in host/try-all -> report-error! -> exit 1.
- Fix shape (cohost layers only):
  1. cli.cljc gains a public AOT entry run-app-main!: resets
     cli-config to {:report "file" :report-dir <host temp dir>} (the
     launcher's config values; bin/cohost sets report-dir to
     java.io.tmpdir) and (exit! (run-cli! {:inits [] :main {:type
     :main :ns ... :args ...}})) -- run-main! resolves the app
     through load-lib!'s snapshot registry, so the whole report
     contract is reused, not duplicated. Temp dir on jank via a
     cohost_io cpp/raw helper (TMPDIR, else /tmp), beside cli's
     existing cpp/raw block.
  2. jank_snapshot_emit.clj: generated app-main requires [cohost.cli]
     (jank compiles it from repo src at build step 4, as the launcher
     build already does) and ends with (cohost.cli/run-app-main!
     (quote <m>) args).
  3. build_jank.clj unchanged; its input key already covers
     cli.cljc (src/cohost) and the emit script, so stale exes rebuild.
- Verify: bb build:jank dev/cli-demo seam.throwmain throwmain in this
  worktree (detached; gateprobes measured ~37 min under contention),
  run the binary from a scratch dir, quote exit + lines; JVM runner
  re-check (cli.cljc is loaded by every JVM session).

## 2026-11-05 ~01:40-01:50 ET — UNIT 2: implemented; verification moved to J (operator steer)

- Impl committed: 8932728 (cli run-app-main! + temp-dir + emit
  change). JVM runner re-check on this box: 10/14 PASS, 0 FAIL --
  no regression from the cli.cljc change.
- Local AOT build leg: FAILED at the emit step for an unrelated
  reason -- RECORD-LIB-ERROR seam.throwmain "could not be found":
  the probe-9 fixture dev/cli-demo/src/seam/throwmain.cljc exists
  only on the gate probes branch (their commit), not on this base.
  Fixture taken onto this branch @ 841cb76 (tip). Steps 1-2 of the
  local build (AOT ensure + stock record) ran clean with the fix in
  the tree; the only record error was the missing fixture.
- Operator steer (John, 01:46 ET): run the verification build on J
  (checked idle, load ~3). Local leg already dead (above); the
  native lane's snapshot build in ~/workspace/cohost-native is NOT
  mine and was left untouched. Sync = bundle of tip 841cb76 -> J;
  JVM re-check on J, then bb build:jank detached (log
  ~/jgate-logs/n13-u2-aot-build.log), then the binary from a
  scratch dir: owed verdict = exit 1 + "Execution error" headline
  + "Full report at:" + report EDN present.
- JVM re-check on J: 10/14 PASS, 0 FAIL (banked, ~/jgate-logs/
  n13-u2-jvmcheck.log) -- second green confirmation of the cli.cljc
  change on the synced tree d9deefd.
- AOT build leg LAUNCHED on J: bb build:jank dev/cli-demo
  seam.throwmain throwmain, bb PID 582450, started 2026-11-05
  07:00:13 UTC = 02:00:13 ET; log ~/jgate-logs/n13-u2-aot-build.log
  (first line: JANK-EXE-BUILD ... (new)). Verdict run follows when
  it completes.

## 2026-11-05 ~02:07-02:15 ET — UNIT 2 (#9) VERDICT: PASS

- Build on J: JANK-EXE-BUILT in 407,275 ms; binary 44,331,608
  bytes (gate probes' pre-fix: 44,572,208). Record events 1,457
  (the fixture's 4 over the local run's 1,453).
- The throwing binary, run from a scratch dir (verbatim):
    AOT_EXIT=1
    -- stdout: (empty)
    -- stderr:
    Execution error (ExceptionInfo) at object-ref::call/ (oref.hpp:566).
    kaboom from seam.throwmain

    Full report at:
    /tmp/cohost-1793862429787-1.edn
  The report EDN (1,953 bytes) opens {:clojure.main/message
  "Execution error ... kaboom from seam.throwmain ..." ...} and its
  :message \"kaboom from seam.throwmain\" + :trace are present.
  Pre-fix (gate probes, twice): exit 0, stdout only the raw #error
  map, no headline, no report. The headline's first frame
  (object-ref::call/ oref.hpp:566) matches the launcher's own #1
  headline shape at the J gate -- parity, not a new format.
- Clean control: demo.main built AOT through the same route
  (81,127 ms, warm cache) and run from a scratch dir:
  CLEAN_EXIT=0, stdout "hello, JVM-USER!", stderr empty -- a
  non-throwing app is unchanged by the new entry.
- JVM runner: 10/14 PASS, 0 FAIL on this box AND on J.
- Unit 2 DONE. Next: unit 3 (cpp/ divide-by-zero disposition).

## 2026-11-05 ~02:20 ET — UNIT 3 (cpp/ divide-by-zero): probing

- Established from the tree: the seam's own division is guarded
  (check #10 PASS both hosts; booted core checks before native
  division). rewrite.cljc passes cpp/raw through verbatim (its
  args are not rewritten) -- the passthrough puts nothing around
  user C++ by contract. The research doc measured plain-jank
  integer div-by-zero as SIGFPE / exit 136 (UB in C++).
- Probes to measure firsthand on J: (a) plain jank, a fn whose
  cpp/raw body evaluates 5 / 0; (b) a cohost session (snapshot
  launcher) evaluating the same through user code. Then the
  disposition: what the adapter/boot layers can and cannot do
  (signal-level and boundary-level options examined), banked in
  the lane report.
- Snapshot build for this tree launched on J (detached, log
  ~/jgate-logs/n13-u3-snapshot.log) for probe (b).
