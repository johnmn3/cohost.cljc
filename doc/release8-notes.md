# cohost 0.9 — release notes

Internal round: `coord/release8`, assembled from the collated
debug-seam line @ 2563eee (README template pin f025976).
Previous shipped release: the release7 round (the C++ passthrough for jank). Cohost releases carried no version number before this one; 0.9.2/0.9.3 are Eve's release line, not cohost's (corrected 2026-11-05).

0.9 is the debug seam: the debugging facilities of stock
Clojure — the clojure.main error report, traces as data,
`clojure.repl/pst`, the REPL vars — working through the seam on
every host, with jank's own debugging facilities (codegen/IR
printing, profiling, gdb break-on-throw) passing through.
Guide: `doc/guide/11-debugging.md` was rewritten to the
verified state.

## What ships

- **The catch hole is closed.** Native C++ exceptions thrown on
  jank used to escape every Clojure catch and end the run with
  a generic report. Stock `try`/`catch` now holds them: the
  native "doors" translate the exception to an ex-info with its
  message intact, for both of jank's native exception families
  (error_ref and std::exception). Doors emit once, last, and
  skip types the user already caught.
- **Reports headline the root cause.** A wrapped exception
  reports what actually failed: the report's trace, ex-data and
  cause are the root cause's. If a report itself cannot be
  built (circular ex-data, a trace that throws), a two-line
  class-and-message fallback prints — a run never fails
  silently.
- **Traces are presentable on jank.** Host construction frames
  no longer lead the trace or steal the headline; the trace is
  walkable in the program via `.getStackTrace`, and pst prints
  jank's own demunged frame names (e.g. `user/inner-11-1`) —
  the host's true spelling, per the operator's ruling.
- **Macro-expansion errors are attributed.** An error thrown
  while a macro expands names the macroexpansion in the report
  on both hosts, and exits 1.
- **AOT binaries report like the launcher.** A statically built
  jank binary whose program throws now prints the headline,
  writes the report EDN and exits 1 (it previously printed
  jank's raw #error map and exited 0). Source positions
  survive into AOT: 858 of 873 targeted forms sit at their
  original lines, so native backtraces resolve to user .cljc
  lines.
- **Reader errors and source positions.** Reader errors carry
  their phase and position; form meta is available at read on
  jank (file + line exact).
- **Transform introspection on jank.** `JANK_PRINT_CODEGEN=1`
  and `JANK_PRINT_IR=1` show the forms as cohost rewrote them;
  `COHOST_JANK_PROFILE=1` writes jank's profile. Introspection
  runs route to the source boot automatically.
- **gdb break-on-throw** works on AOT binaries today:
  `catch throw` stops at the throw, frame #1 names the
  throwing Clojure function at its .cljc line. Probe scripts:
  `doc/debug-seam-probes/`.
- **Exit codes** verified on JVM and jank, launcher and AOT
  alike: uncaught throw 1, script `(System/exit 3)` 3.
- **The seam checklist is a runner**: `bb debug-seam:check
  [jvm|jank]` runs the 14 checks; checks that can fail were
  hardened to quote what they matched.

## A stated boundary, not a gap

Clojure integer division by zero throws `ArithmeticException`
and exits 1 on every host — the guard sits in cohost's numeric
layer. Arithmetic written directly in `cpp/` interop is the
caller's own C++, passed through verbatim by contract: a zero
divisor there is a native SIGFPE (exit 136), exactly as in a
plain jank program and exactly as across the JVM's JNI
boundary. No cohost layer can guard code it does not rewrite
without breaking the passthrough contract. Measured both ways;
probe: `doc/debug-seam-probes/probe-div0-cpp.sh`.

## Verification (final gate, collated tree @ 2563eee)

- JVM: debug-seam runner 10/14 PASS, 0 FAIL (4 SKIP are
  jank-only by design); battery 221/221; libraries 62/62.
- Jank (on the build server): runner 12/14 PASS, 0 FAIL;
  checks #9 and #12 PASS by their dedicated probes — 14/14
  accounted. Snapshot built in 362,325 ms; boot 576.8 ms.
  cli:check 7/7 SAME. docs:check 22 PASS, 0 FAIL.
- Jank battery: 219/221. The two FAIL rows are standing,
  named, and not seam regressions:
  `subs count nth first last butlast` ("ERR Unterminated
  vector.") and `Character outside ASCII` ("ERR Unexpected
  end of file.") — present at every prior gate on this line.

Release-tree re-verification for 0.9 is recorded in
`doc/release8-STATUS.md` and the readiness report on this
branch.

## NOT in this release (held, untouched)

- The #13 headline table fixes (a CompilerException arm in
  java.clj's exception-class table; IExceptionInfo in the
  shim's token-ancestors) — held, never cleared.
- The U4c position-meta shrink to :file+:line — held, never
  cleared.
