# Impl plan: preserve jank's debugging capability through the cohost seam

Basis: `jank-debugging-seam.md` (2026-11-04) — 16 features mapped; 2
preserved, **14 not**: DEGRADED #1 #3 #4 #12 #13 #15 #16, LOST #2 #6 #7,
UNKNOWN #5 #8 #9 #14. Goal (John, 2026-11-04): address all 14; preserve
as much jank capability as possible. Validation for every unit is the
research report's checklist (checks #1-#14), run on the fix branch;
the 8-host batteries + libraries must stay at standing except where a
row's expected output *is* the behavior being fixed (error-report text),
and doc/CLI.md's "class and message only" decision is superseded by
this plan wherever they conflict.

Branch: `muse/debug-seam` from the release7 tip. All fixes are in
cohost's own layers (cli.cljc, boot.cljc, hosts/jank_host.jank,
build_jank.clj, shim) — no jank changes; where jank itself is the
limit (row #15's UB), the plan says so and takes the cohost-side guard.

## U1 — the error report carries the trace (rows #1, #3, #2)

`report-error!` (src/cohost/cli.cljc) grows to clojure.main parity:
`Execution error (Class) at ns/fn (file:line).` + message, then the
trace (user frames first-class: ns/fn, file, line — from the
exception's own trace data, which on jank is the `#error` `:trace`),
then the ex-data where present. The full report is also written to a
file (row #2: a `cohost-*.edn` beside the run, path printed on
stderr), restoring the report-file workflow. All hosts: the report is
built from whatever trace the host's shim exception exposes (U5 gives
jank's a walkable shape); hosts with less data print what they have —
the format is one, the depth is per-host.
Gate: checks #1, #6, #9. Doc: doc/CLI.md's error-report section is
rewritten to the new contract; cli:check expectations updated to it.

## U2 — REPL state vars (rows #6, #7; unblocks #8)

cli.cljc `repl!` mirrors clojure.main: after each successful form,
shift `*3 *2 *1`; after a throw, bind `*e` to the exception (and do
not shift). Jank's own `safe_eval` (evaluate.cpp) is the reference for
the wrapping rules; through cohost the driver wraps eval-str results.
Small, all hosts, no other behavior changes.
Gate: check #5 (`*e` prints the ex-info, `*1` ⇒ 3).

## U3 — load-time analysis parity; deferred errors stay catchable (rows #4, #12)

The dangerous one. First a diagnosis step (no fix until it lands):
why does a defn with an unresolved symbol load through the seam when
plain jank refuses it? Repros at three depths — plain jank eval of
the same defn, seam eval of the same form, seam file load — with
JANK_PRINT_CODEGEN on, to find which layer defers the analysis
(boot's eval route, the adapter's :eval, or jank's JIT defn
compilation itself). Then:
- (a) restore load-time analysis for user code at the layer the
  diagnosis names, so the error fires at load with jank's positioned
  diagnostic (caret box intact — it must reach the user via U1's
  report, not be flattened);
- (b) independently, close the catch hole: a native C++ exception
  escaping compiled code must be catchable by stock `catch Throwable`
  through the seam. The jank adapter's catch machinery gains a
  std::exception translation (wrap into the shim's exception type,
  message preserved), so no error class escapes the language's own
  error handling.
Gate: check #3 both halves (load refuses with the diagnostic; the
call-time remainder, if any, is caught by `catch Throwable` and
reports its real message), check #13 (macro-expansion errors carry
the call site).

## U4 — positions, both ends (rows #3-AOT, #12, #5, #13-maps)

- (a) JIT read end: the jank adapter's read-forms binds jank's
  `*file*` to the file being read and tracks each form's offset in
  the file, so `:jank/source` carries the real file and file-absolute
  lines (today: `"NO_SOURCE_PATH"`, form-relative). Gate: check #2
  prints the real path and absolute line.
- (b) reader errors (row #5): probe first (check #4); expected to
  follow from (a) — whatever still differs gets its own fix in the
  adapter's scan/read path.
- (c) AOT end: snapshot recording stops dropping position meta
  (`snap-drop-meta` loses `:jank/source` and friends — keep them;
  measure the snapshot size cost and report it), and the emitter
  writes each top-level form at its original line in the generated
  module file (blank-line padding), so AOT frames and the (kept)
  debug source maps resolve to the user's line, with the ns giving
  the file. Gate: checks #9, #12 (frames cite user lines; gdb
  backtrace resolves them).

## U5 — pst and stacktrace over shim exceptions (rows #8, #9)

Probe first (checks #6, #7): what stock `clojure.stacktrace` /
`clojure.repl/pst` print over a jank shim exception today. Then give
the shim's exception types a real stack-trace surface on jank: trace
elements built from the exception's captured `#error` trace, in the
shape the stock printers walk. Depends on U1's plumbing; lands after
it. Gate: checks #6, #7 print positioned user frames.

## U6 — transform introspection and profiling passthrough (row #14)

Probe (check #11): do JANK_PRINT_CODEGEN / JANK_PRINT_IR reach the
snapshot launcher through bin/cohost today? Then: document + wire
what doesn't reach — env passthrough where it's just plumbing, and a
`:jank`-target way to pass `--profile` (env `COHOST_JANK_PROFILE=1`
or a CLI flag) so a user can get jank's profile of a cohost run.
Gate: check #11 shows the rewritten forms' codegen; a profile file
is produced on request.

## U7 — the native-crash class gets a Clojure-level signal where cohost owns the op (row #15)

Probe per path (check #10): which of `/`, `quot`, `rem` on longs
crash vs throw through the seam. Jank's native integer division is
UB on zero and cannot be fixed from cohost; but stock core's
arithmetic routes through cohost's numeric layer, so add the
divide-by-zero → ArithmeticException guard at that layer for the
paths cohost owns (matching JVM semantics), and document the routes
that still reach native UB (direct cpp/ and host ops) in
doc/REPORT.md's host-facts. Gate: check #10 — `/` and `quot` on
longs throw ArithmeticException through the seam; any path that
still signals is named in the doc.

## U8 — names in reports (row #16)

The `cohost.core` homes are the codemod's design and stay. At the
*display* layer — U1's report and U5's printers — core frames are
shown under their Clojure names (`cohost.core/map` →
`clojure.core/map`), the way jank's demunger already presents
`clojure_core$…` frames as `clojure.core/…`. User names are already
intact. Gate: checks #1/#7 output shows clojure.core names for core
frames.

## Order and shape

U2 → U1 → U4a → U3 (diagnosis, then fix) → U5 → U4c → U6 → U7, U8
folded into U1/U5. Each unit: probes first where the research says
UNKNOWN, regression rows in the battery for the behavior it changes,
a coordinator report per unit. The 14 checklist items run in full at
the end (they are also the capstone's close-out validations — the
capstone validates the *current* seam and records the same numbers
this plan then improves).

## Status

Reports live on atom.broker branch `muse/cohost-reports`,
`docs/cohost/debug-seam/REPORTS.md` (private; this repo is public).
This section keeps only the sha and the gate line per unit.

- U2 @ 892637d -- jvm 1/14 (#5 REPL vars, probed); jank unmeasured.
- U1 @ 2dc8a9c -- jvm 2/14 (#1 report parity probed, #5); jank unmeasured.
- U8 @ 3003aec -- jvm 2/14 (#1 with stock names in frames, #5); jank unmeasured.
- Runner @ badcee6 -- jvm 5/14 (#1 #5 #6 #7 #14), battery 221/221, libraries 62/62, cli:check 7/7 SAME; jank unmeasured.
- (analysis lane, branch muse/debug-seam-analysis) CLI-defect step 0 @ 5039590 -- jvm 5/14, battery 221/221, libraries 62/62 SAME; jank blocked (B1 + snapshot env).
- U3 diagnosis @ ac781ae -- doc/debug-seam-probes/u3-diagnosis.md; no code.
- U3(a) @ 4465e88 -- jvm 5/14, battery 221/221, libraries 62/62 SAME; probe project: bad load exit 0/silent BEFORE, exit 1 with the report AFTER; jank confirmation pending B1.
- U3(b) @ a01bc15 + runner correction @ 9a61e7f -- jvm 7/14 PASS, 1 FAIL (#3 #10 PASS via the live runner; #13 FAIL on the JVM host: the report carries no macro attribution -- probed by hand, see 9a61e7f; jank half pending B1), battery 221/221, libraries 62/62 SAME; jank compile of the emitted clauses pending B1.
- U6 @ 5fde5fb -- runner #11 live (codegen + COHOST_JANK_PROFILE route in bin/cohost, doc/CLI.md); jvm #11 SKIP by design; jank gate pending B1.
- U7 @ 4eed2e0 -- analysis only (doc/debug-seam-probes/u7-arithmetic.md): the zero guard already stands at three layers; runner #10 PASS on jvm; jank probe predicted PASS, pending B1.
- B1 @ a0f168d -- jank gate 1 fix (:ex-trace cast); lane R gate 2 with R-0001: battery 219/221, libraries 62/62, debug-seam:check jank 3/14; local snapshot verification pending (env).
- U4a @ b616888 + df0f3ce -- item 1 (jank read end: real file + file-absolute positions via scan/source-path + host read-forms padding/shift) and item 2 (reader errors: adapter attaches :read-source phase + position). jvm: debug-seam:check 6/14 (#4 PASS: "Syntax error reading source at (REPL:1:1)."), battery 221/221, libraries 62/62. jank (local, B1 merged): bb boot:jank battery 219/221 (the 2 = Character rows, standing), libraries 62/62, exit 0. Check #2/#4 jank verdicts: full check run in flight locally (log pos-seamcheck-jank-1.log; #2 session observed executing); the single j gate settles them.
- Runner #2 @ fd48a49 -- check #2 is a real probe on jank (read-forms over a file whose defn sits at line 3; meta must name the file, :line 3); on jvm it stays SKIP, count unchanged.
- U4c @ 3cc51bc + 24b329c -- item 3: snap-drop-meta drops nothing; emitter writes each form at its original line and asserts it (645d12a 3a). Measured locally: events.edn 763,355 -> 5,947,770 bytes (+679%) for kept position meta; generated sources 647,322 -> 4,788,569 bytes; PLACEMENT 858 of 873 targeted forms at their original lines, 15 unplaceable (targets above the generated header's end). Boot-ms cost of kept meta: not measured, owed at the j gate. Checks #9/#12: gate-owed (need an AOT app build + run at the gate). Local env note: the snapshot build completes on this box once the stale pre-move ~/.cache/jank PCH is cleared (its baked /usr/local header paths were the jtl redefinition source).
- F3 @ a127a19 -- report headlines the root cause (:trace/:data from the root); jvm probe-verified (report :cause/:data/:trace all root).
- F8 @ a197c02 -- two-line class+message fallback guards both report paths (report-error! + repl-report!): report-build failure (circular ex-data under pr-str; a throwing :ex-trace) printed nothing/escaped BEFORE, prints the fallback AFTER (unit + CLI probes); jvm 8/14 (1 FAIL = #13, standing), cli:check jvm 7/7 SAME; jank confirmation pending the j gate.
- F2 @ 55f12dd, amended @ aaa6b95 -- construction-head elision in jank's :ex-trace itself (data layer; JVM :ex-trace untouched); elision logic probe-verified on the JVM over both frame-name universes + lane R's measured head ([jank::runtime::ex_info] [clojure_core$ex_info_32861_2] [object_ref::call]); jvm 8/14 (1 FAIL = #13, standing), cli:check jvm 7/7 SAME; jank end-to-end PENDING the j gate -- local jank launch is broken on the whole collated tree (snapshot-key analyze fails on shim.cljc's U3(b) wrap-jank-error cpp call; reproduces on base a127a19).
- F9 @ 5cfc1fa -- NO CODE: diagnosis banked (doc/debug-seam-probes/f9-diagnosis.md). No shim print-method for errors exists in the *e path on any measured host: jvm *e prints through the host's own printer (true class names, un-elided head proves it never touches :ex-trace); cljw *e prints the host's native ex-info form (print-dispatch gap, not display names); where the booted core_print prints over shim statics, -getClassName has display-mapped frame names since U8 and the trace is :ex-trace's (F2). Coordinator to re-scope if the cljw dispatch gap or a cli.cljc repl!-layer interception is wanted.
- F4-F7 @ 6d881da -- runner hardening: #1 judges streams separately (stdout empty; the boom program is one do form so -e value printing stays silent), #5 probes *1 by a value only *1 can produce (:star1 3), #7 judges pst's own output shapes (header + tab-indented demunged frames), #14 runs all three exit cases (-e throw 1; -X missing fn in new fixture seam.ok: message + nonzero; script (System/exit 3): 3); every PASS line quotes its matched text and evidence claims only matches made. jvm 8/14 (1 FAIL = #13, pre-#13-unit), cli:check jvm 7/7 SAME; #14 flipped FAIL->PASS, no PASS regressed.
- #13 @ 170a1ef -- expansion-wrapper attribution LANDED (wrap-macro rethrows an ex-info with :clojure.error/phase :macroexpansion + symbol/source/line, cause chained; measured in the report's :via) and the runner's #13 gained the body-throw probe with per-probe needles (message-searched, not file-searched). JVM GATE NOT REACHED: jvm 8/14, #13 FAIL with honest evidence -- the headline phase comes from the host CompilerException's ex-data, which the seam cannot read (java.clj exception-class lacks a CompilerException arm; shim token-ancestors for it omits IExceptionInfo). Residual diagnosed to two one-line table fixes in doc/debug-seam-probes/n13-diagnosis.md, NOT made (outside the unit's named layer) -- coordinator to route. cli:check jvm 7/7 SAME, no other check moved.
- U5 @ 8c4a3a9 -- check #8 (*err* separation) is a real probe now and PASSES on jvm (in-program *err* write on stderr, *out* write on stdout, report on stderr, exit 1): runner 9/14, 1 FAIL (#13 residual), cli:check jvm 7/7 SAME. The pst half is NO CODE with a banked diagnosis (doc/debug-seam-probes/u5-pst-diagnosis.md): R's "pst could not be resolved" was measured only on the CLI's pre-existing broken fallback path; the rename/refer machinery is complete on both load paths and works in the JVM REPL session; the launcher-path measurement that would pin a real gap is named (runner #7 shapes + lib-report) and awaits a launching jank / the j gate.
- (analysis lane, post-collation -- NOT in the f1d87db tree; needs the second collation pass) Step 0 amended @ 82ad5d3 -- cli:check jvm 7/7 SAME; lg 6 command cases SAME (the -Spath case aborts in dev/cli-demo/check.clj's own path handling in this worktree); phel 6 command cases SAME with the home PHP's bundled extensions enabled via PHP_INI_SCAN_DIR (stock local env loads none -- Phar/iconv/tokenizer missing is environmental); before, on 4574e7f: lg all-DIFF ('Can't resolve *print-namespace-maps*').
- U3(b) fix @ 7cb29ec + recording gate @ f03e517 -- jvm battery 221/221, libraries 62/62, check 7/14 PASS 1 FAIL (#13) at both; jank AOT compile of the shim confirmed by the fallback-path CLI run below.
- Step 0 jank arm + CLI load @ f03e517 tree -- bin/cohost :jank -e '(+ 1 2)' prints 3, exit 0, on the fallback path (another lane's snapshot build held the lock); the snapshot LAUNCHER path on this box still builds.
- #13 attribution @ 7b3e7e3 -- jvm 8/14 PASS, 0 FAIL (#13 PASS), battery 221/221, libraries 62/62 SAME.
- Collation #2 @ babe318 -- positions (1f93a87) + fchain (21821a0) + analysis (4cd6b51) + analysis final (babe318) merged onto muse/debug-seam. #13 ships analysis's expand-error wrapper (7b3e7e3); the fchain #13 code (170a1ef) was NOT taken -- its n13 diagnosis stays as the headline-residual exhibit for the coordinator. U3(b): the doors are REMOVED (5b2cfe7) -- collation #1's a01bc15 and its fixes 7cb29ec/f03e517 are superseded; rw-try is the plain fold again, the catch hole stays OPEN with the design + member-access lead banked in doc/debug-seam-probes/u3-diagnosis.md; runner #3 reports the hole as SKIP on jank. Committed-tree conflict-marker sweep 0/0. JVM verification + the j gate run on this tip.
- J gate @ 358048e (on j, 2026-11-04): snapshot EXIT 0, battery 219/221 (standing pair), SNAPSHOT-BOOT-MS 435; cli:check jank 6/7 SAME + -Spath harness abort (fixed below); debug-seam:check jank 6 PASS / 5 FAIL / 3 SKIP. PASS: #2 (positions read-end: file + line 3 exact), #4, #5, #8, #10, #14. FAIL: #1 + #6 (host frames lead the headline and the in-program trace on jank), #7 (pst runs on the launcher; its frame lines don't render the expected shape), #11 (codegen introspection: no cohost line in the gate capture; analysis measured 1,572 hits locally -- discrepancy open), #13 (macro attribution blocked by the native escape). SKIP: #3 native half (catch hole, named), #9 + #12 (U4c-owned; the runner skips them even at the gate -- still owed as separate probes). U3(b) gate probe: launcher catches a user ex-info (:caught kaboom, exit 0); a genuine std::exception escapes as "a native C++ exception", exit 1, full report written.
- Harness @ 29cfac1 -- dev/cli-demo/check.clj -Spath no longer aborts on absolute classpath entries (abs-entry, both sides of spath-same?). Before: lg aborted at check.clj:39 and the j gate's cli:check jank exited 1 the same way; after: lg 7/7 SAME incl. -Spath, jvm 7/7 SAME. The jank re-check rides the next j visit (the harness is dev tooling; the gate snapshot is reused). On jank the fixture's macro throw crosses as a native C++ exception (release7 behavior, see the U3(b) line) -- #13's jank half waits on that, not on the wrapper.
- U3 jank confirmations @ f03e517 tree (fallback + launcher paths, this box) -- U3(a): -m seam.bad exit 1 at load, 'The symbol this-var-does-not-exist could not be resolved.'; U7: (/ 5 0) and (quot 5 0) both exit 1, 'Divide by zero' (ArithmeticException), no SIGFPE; U6: JANK_PRINT_CODEGEN exit 0 (dump names cohost, 1572 hits), COHOST_JANK_PROFILE=1 writes jank.profile (87 MB for a boot run); snapshot record errors 0 (was 3 before the recording gate).
- U3(b) doors REMOVED @ the removal commit -- both shapes measured broken post-B1 (fn-boundary: analyze/invalid-cpp-call in the AOT build; in-clause read: 'Unable to find cohost_shim_err' for runtime-compiled tries in the launcher process). rw-try is the plain fold everywhere; the catch hole is OPEN, design + member-access lead banked in doc/debug-seam-probes/u3-diagnosis.md (addendum). Final tree: jvm 8/14 PASS, 0 FAIL, battery 221/221, libraries 62/62. Runner #3 on jank: load half PASS, native half SKIP (hole open).
- Traces lane IN PROGRESS (branch muse/debug-seam-traces, base 030cc0a) -- #1/#6/#7 presentation on jank: all three presentations derive from the jank adapter's :ex-trace (cli headline = its first frame post core-frame?; shim -getStackTrace IS (host/ex-trace e); pst renders over that). F2's ctor-head prefix list carries no spelling of the adapter's own ns (cohost.hosts.jank-host), and the gate's #6 head frame sits at jank_host.jank:2729 (the adapter's :ex-info wrapper). Report: doc/debug-seam-probes/traces-report.md.
- Traces lane @ 099a2a2 -- jank :ex-trace elision completed (adapter module spellings + all :: C++ frames) and frame files normalized (nil -> NO_SOURCE_FILE) in jank_host.jank only @ 4d0de34 + 099a2a2. Gates: jvm checks 10/14 PASS, 0 FAIL; boot:jvm battery 221/221. Jank verification queued for the coordinator j run (snapshot + debug-seam:check jank, #1/#6/#7). Report: doc/debug-seam-probes/traces-report.md.
- Traces j run (takeover, on j) @ 68d806b -- fresh snapshot SNAPSHOT-BUILT 11ecc29a in 531087 ms, SNAPSHOT-BOOT-MS 480; debug-seam:check jank 8/14 PASS, 3 FAIL, 3 SKIP: #1 PASS (headline "Execution error (ExceptionInfo) at user/boom" + "Full report at:"), #6 PASS (first frame [user$inner_11_1 invoke "NO_SOURCE_FILE" 1]), #7 FAIL on the runner's needle only -- pst prints the demunged tab-indented frame line "\tuser/inner-11-1 (NO_SOURCE_FILE:1)", but the needle wants the JVM shape "\tuser/inner (" (jank's fn class user$inner_11_1 demunges with its -11-1 suffix); needle-vs-demunger is the coordinator's call. #2/#4/#5/#8/#10/#14 still PASS; #11/#13 FAIL and #3/#9/#12 SKIP as at the gate (their fixes are not on this tree). The queued jank verification in the line above is closed except the #7 needle question. Full account: REPORTS.md (muse/cohost-reports), 2026-11-05 takeover entry.
- U6/#11 fix @ 37d340c (branch muse/debug-seam-introspect) -- the gate's codegen miss was the launch shape, not a lost env var: under the compiled launcher nothing compiles at run time, so JANK_PRINT_CODEGEN prints nothing (gate: exit 0, zero cohost lines; source boot, local: exit 0, stdout 557,866 bytes, 1,571 cohost lines, dump on stdout). bin/cohost now routes JANK_PRINT_CODEGEN/JANK_PRINT_IR runs to the source boot (jank-launch override + launcher skipped; profile route unchanged); post-fix routing probe reproduces the dump byte-for-byte, ordinary runs unchanged, jvm 10/14 PASS 0 FAIL (#11 SKIP). Full jank runner on a fresh snapshot OWED at the j gate via bundle (4 local snapshot builds failed, all environmental: stale PCH x2 (one post-reboot GCC 13->14), OOM-killed link, SIGTERM'd launcher compile). Report: doc/debug-seam-probes/introspect-report.md.
- U6/#11 j gate (takeover, on j) @ e73caba -- fresh snapshot SNAPSHOT-BUILT 309e0518 in 524475 ms, SNAPSHOT-BOOT-MS 441; debug-seam:check jank 7/14 PASS, 4 FAIL, 3 SKIP with #11 PASS (codegen exit 0, matched "#line 283 .../src/cohost/cli.cljc"; profile exit 0, jank.profile written) -- the only check moved vs the J gate @ 358048e. Direct probe (dev/cli-demo): JANK_PRINT_CODEGEN=1 stdout 556,660 bytes / 1,571 cohost lines / stderr 0; JANK_PRINT_IR=1 stdout 3,623,586 bytes / 9,346 cohost lines. The OWED item in the line above is closed. Full account: REPORTS.md (muse/cohost-reports), 2026-11-05 takeover entry.
- Gate probes #9/#12 (gate-probes lane, 2026-11-05; full report doc/debug-seam-probes/gateprobes-report.md) -- #9 GAP: an AOT-compiled app (bb build:jank, seam.throwmain fixture) whose -main throws EXITS 0 and prints only jank's raw #error map to stdout -- no "Execution error" headline, no "Full report at:", no report EDN (the AOT entry never passes through cohost.cli's report path). #12 PASS: gdb `catch throw` and `break __cxa_throw` both stop at the throw on the AOT binary, backtrace named with the throwing fn at throwmain.cljc:7. Local same-app launcher comparison not completed (worktree snapshot LAUNCHER step failed exit 1, no error text in the log; fallback compile killed after ~1 h under contention) -- probe9 leg 2 runs it wherever a launcher exists; the j gate's #1 already shows the launcher engaging the seam report (exit 1, headline, report written).
- #13 jank fix @ 2c578c1 (lane muse/debug-seam-n13 on the native base eef1ab4) -- debug-seam:check jank 8/14 PASS, 4 FAIL (#13 PASS: "load exit 1, throw exit 1; report names the macroexpansion"; #3 PASS via the doors on this base; #1/#6/#7/#11 FAIL = traces/introspect fixes not on this branch), debug-seam:check jvm 10/14 PASS, 0 FAIL (#13 PASS, unchanged). Mechanism: expand-error's (ex-info nil ...) in cohost's compiled code reached jank's native constructor, which rejects a nil message -- a std::exception out of the handler, converted upstream to the :cohost/native sentinel; fix constructs via host/ex-info* (the nil-safe cap). Full account: doc/debug-seam-probes/n13-lane-report.md.
- #9 AOT fix @ 8932728 (same lane, unit 2) -- an AOT binary's uncaught throw now exits 1 with the seam report instead of jank's #error print + exit 0: the generated cohost.app-main routes through the new cohost.cli/run-app-main! (run-cli!'s own contract). Verified on J: throwmain AOT exit 1, headline "Execution error (ExceptionInfo) at object-ref::call/ (oref.hpp:566)." + "Full report at:" + report EDN naming the throw (build 407,275 ms); clean demo.main control exit 0; debug-seam:check jvm 10/14 PASS, 0 FAIL. Full account: doc/debug-seam-probes/n13-lane-report.md (Unit 2).
- cpp/ divide-by-zero (same lane, unit 3) -- MEASURED DISPOSITION, no code change: raw cpp division traps SIGFPE (exit 136) in plain jank and through a cohost session alike, against the seam division's ArithmeticException + exit 1 in the same run; the guard lives in booted core's arithmetic and cpp/raw is verbatim by contract, so no cohost layer can guard user C++ without breaking the passthrough or process soundness (options examined in the report). Boundary + probe: doc/debug-seam-probes/n13-lane-report.md (Unit 3), doc/debug-seam-probes/probe-div0-cpp.sh.
- Collation (final) @ be95bb2 -- native 6169ed7 + traces adccd2f + introspect a5d0b69 + gateprobes f3aafc7 + docs f52a2c6 + n13 9400aaa merged onto seam 030cc0a; conflicts only in this Status section (union, every line kept); committed-tree conflict-marker sweep 0/0; tip origin-verified.
- FINAL GATE @ be95bb2 (2026-11-05) -- jvm: debug-seam:check 10/14 PASS, 0 FAIL (4 SKIP jank-only by design), battery 221/221, libraries 62/62, cli:check jvm 7/7 SAME. jank (on j): snapshot cf70ff19 built in 362,325 ms, PLACEMENT 858/873, SNAPSHOT-BOOT-MS 576.8; battery 219/221 (FAILs: subs count nth first last butlast; Character outside ASCII -- the standing jank-only pair), libraries 62/62; cli:check jank 7/7 SAME; debug-seam:check jank 12/14 PASS, 0 FAIL + #9 PASS by AOT probe (exit 1 + headline + report EDN) + #12 PASS by gdb probe (twice) = 14/14 accounted; docs:check 22 PASS, exit 0. Local cli:check lpy/phel DIFFs are named local-environment failures (basilisp cached-boot build; PHP Phar extension), not seam rows. Held, unimplemented: the #13 headline table fixes and the U4c meta shrink (operator has not cleared them). Full account: REPORTS.md (muse/cohost-reports), FINAL GATE entry.
