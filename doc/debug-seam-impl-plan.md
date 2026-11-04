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
