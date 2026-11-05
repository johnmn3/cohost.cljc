# Debugging

*2026-11-05. States below are measured, not aspirational: both
columns are the final gate run of 2026-11-05 on the collated
`muse/debug-seam` tree (tip be95bb2) — the JVM column is
`bb debug-seam:check jvm` locally (10/14 PASS, 0 FAIL; the 4
SKIPs are jank-only checks by design), the jank column is
`bb debug-seam:check jank` on the build server (12/14 PASS,
0 FAIL, with checks #9 and #12 PASS by their dedicated probes —
14 of 14 accounted). The one remaining boundary is written as a
boundary: arithmetic written directly in `cpp/` interop (see
Divide by zero).*

Debugging a cohosted program is debugging Clojure: cohost boots the
stock `core.clj` and libraries, so the facilities are the stock
ones — the clojure.main error report, `clojure.repl/pst`,
`clojure.stacktrace`, the REPL's `*1`/`*2`/`*3`/`*e`. This chapter
says how each behaves through the seam, and where a host's depth
differs.

## The error report

An uncaught error is reported as clojure.main reports it. The
headline and the cause go to **stderr** (stdout stays clean):

```
Execution error (ExceptionInfo) at user/boom (REPL:1).
kaboom
```

The full report is written to a file and its path printed on
stderr (`Full report at:`): `<tmpdir>/cohost-<millis>-<n>.edn`,
holding `{:clojure.main/message :clojure.main/triage
:clojure.main/trace}`. The trace, the ex-data and the cause in the
report are the **root cause's** — a wrapped exception reports what
actually failed, not the wrapper. `COHOST_MAIN_REPORT=stderr|none`
sends the report to stderr or suppresses the file instead. If the
report itself cannot be built (circular ex-data under `pr-str`, a
trace that throws), a two-line class-and-message fallback prints —
the run never fails silently.

The format is one on every host; the depth is per-host. On a host
with no stack trace the report's `:trace` is empty (doc/CLI.md).
Core frames are shown in reports and traces under their Clojure
names (`clojure.core/map`), even though ordinary printing still
shows the `cohost.*` homes.

**Jank:** the headline names the throwing function, as on the
JVM. An earlier build could name a host trampoline frame —
`object_ref::call (oref.hpp:566)` — instead of the throwing
function, because jank's exception-construction frames led the
trace and the headline picks the first frame it cannot drop.
Those frames are now elided in the trace data itself (below),
and the final gate's check #1 PASSes on jank with the headline
`Execution error (ExceptionInfo) at user/boom`.

## Traces as data

A caught exception's trace is walkable in the program, the way
stock code expects: `(.getStackTrace e)` yields frames of
`[class method file line]`, and the stock `clojure.stacktrace`
(`print-stack-trace`, `print-cause-trace`) prints over the same
data. The seam's trace (the adapter's `:ex-trace`) is what the
report file, the headline, `pst` and `.getStackTrace` all read.

On jank the trace is elided **at the data layer**: the
construction and plumbing frames at its head (jank runtime
frames, `object_ref::call`, the shim's and adapter's own
`ex-info` construction) are dropped once, in `:ex-trace` itself,
so every consumer sees the clean trace whose first frame is user
code. Verified on both hosts at the final gate (check #6: the
first `.getStackTrace` frame is `[user$inner invokeStatic
"NO_SOURCE_FILE" 0]` on the JVM, `[user$inner_11_1 invoke
"NO_SOURCE_FILE" 1]` on jank).

## pst and print-stack-trace

`(require 'clojure.repl)` then `(clojure.repl/pst)` — or `(pst e)`
on a caught exception — prints the exception's header (class,
message, ex-data) and its frames, tab-indented and demunged:

```
ExceptionInfo r {:x 2}
	user/inner (NO_SOURCE_FILE:0)
```

Verified on both hosts (check #7). On jank, source-less frames
carry their file as `"nil"`, where the stock printer only
demunges files ending `.clj`/`.cljc` or named `NO_SOURCE_FILE`;
the seam maps source-less files to `NO_SOURCE_FILE` and elides
the head (above), so the lines render demunged there too. One
host truth to know: a jank-compiled function's name carries
jank's own suffix — the frame above prints as
`user/inner-11-1 (NO_SOURCE_FILE:1)` on jank — and that suffix
is jank's real naming, not a rendering defect; the seam's
checker absorbs the host shape rather than falsify the name.

## REPL vars

The REPL maintains the clojure.main vars: after each successful
form, `*3` `*2` `*1` shift and `*1` is the form's value; after a
throw, `*e` is bound to the exception and the history does not
shift. Verified on the JVM and on jank (check #5).

## *err* separation

Reports go to stderr, always. A program's `*out*` writes land on
stdout, its `*err*` writes on stderr, and `binding [*out* *err*]`
behaves as in stock Clojure. Verified on the JVM and on jank
(check #8: the `*err*` write and the report on stderr, neither on
stdout, exit 1).

## Errors at load

A definition that names a symbol nothing provides **fails the
load**, with the symbol named — `The symbol
this-var-does-not-exist could not be resolved.` — and exit 1. It
no longer loads silently and explodes at first call. Verified on
the JVM and on jank (check #3, load half).

Reader errors carry their phase and position:

```
Syntax error reading source at (REPL:1:1).
```

Verified on the JVM and on jank (check #4).

Positions are real on jank's read end: a form read from a file
carries the file's path and its file-absolute line in its
`:jank/source` meta (check #2 at the gate: the probe defn's meta
named the file and line 3 exactly). On the JVM host the read end
is the JVM's own reader, whose positions are native.

## Macro-expansion errors

On both hosts, an error thrown while a macro expands is
reported with the macroexpansion phase: the report names the
macroexpansion and carries the macro's provenance, and a macro
that expands to an unresolved symbol fails the load with that
provenance (check #13 PASS on the JVM and on jank).

On jank this once lost the attribution entirely: the attributed
error was built with a nil message, jank's native exception
constructor rejects a nil message, and the std::exception thrown
out of the handler replaced the attribution with the native
sentinel. The construction is now nil-safe in the adapter (a nil
message is stored as "" with a `:cohost/nil-message` mark), and
the final gate's check #13 PASSes on jank: load exit 1, throw
exit 1, the report names the macroexpansion.

## Catching native errors (jank)

A stock `try` catches Clojure exceptions on every host —
including, on jank, native C++ exceptions thrown by jank's own
core (an index error inside jank internals, a macro throw as
above). The seam's `try` rewriting adds sibling catch clauses
that translate jank's `error_ref` and `std::exception` into an
ex-info — the message read from the caught value in the clause —
so the catch fires with the native message intact. Verified at
the final gate (check #3 on jank: the load half names the
unresolved symbol, and the native half's catch catches the
native throw's message). Before this fix the native class
escaped every Clojure catch, and the run ended with the generic
"a native C++ exception" report.

## Divide by zero

`(/ 5 0)` and `(quot 5 0)` on integers throw
`ArithmeticException` — "Divide by zero" — and exit 1, never a
signal. Jank's native integer division is undefined behavior on
a zero divisor (plain jank dies with SIGFPE, exit 136), so the
guard sits in cohost's numeric layer, which stock `/`, `quot`,
`rem` and `mod` all route through. Verified on the JVM and on
jank (check #10). Arithmetic written directly in `cpp/`
interop is the caller's own C++: the passthrough is verbatim by
contract, so a zero divisor there is a native SIGFPE (exit
136), exactly as in a plain jank program and exactly as across
the JVM's JNI boundary — measured both ways (plain jank, and a
cohost session evaluating cpp/raw division) against the seam
division's ArithmeticException + exit 1 in the same run. This
is the seam's stated boundary, not a gap: no cohost layer can
guard code it does not rewrite without breaking the passthrough
contract. Probe: `doc/debug-seam-probes/probe-div0-cpp.sh`.

## Exit codes

Verified on the JVM and on the jank launcher (check #14):

| Case | Exit |
|---|---|
| Uncaught throw (`-e`, `-m`, script, `-main`) | 1 |
| `-X` naming a missing function | message (`function not found: …`) + nonzero |
| A script calling `(System/exit 3)` | 3 |

A statically built AOT binary behaves the same — it routes
through the report machinery and exits 1 on an uncaught throw.
See the jank section below.

## Debugging on the jank pathway

Jank brings its own debugging facilities, and the seam passes
them through. This section is the jank pathway only.

### Seeing the transform: codegen and IR printing

`JANK_PRINT_CODEGEN=1` prints the C++ jank generates;
`JANK_PRINT_IR=1` prints its IR. Through cohost the dump shows
the forms **as cohost rewrote them** — shim and `cohost.core`
names included — which makes it direct evidence of what the
host compiler actually received:

```
JANK_PRINT_CODEGEN=1 bin/cohost :jank -e "(+ 1 2)"
```

Through the compiled snapshot launcher (the default
`bin/cohost :jank` shape) nothing compiles at run time — the
booted core loads as compiled objects — so `bin/cohost` routes
any run with either variable set to the source boot, like a
profile request, and the dump prints there. Verified at the
final gate (check #11 PASS on jank: codegen exit 0, the dump
naming cohost's own `cli.cljc`; profile written). The fix's own
gate verification measured the codegen dump at 556,660 bytes
with 1,571 lines naming cohost, and the IR dump at 3,623,586
bytes with 9,346 lines.

### Profiling

`COHOST_JANK_PROFILE=1` runs the program under jank's own
profiler and writes `jank.profile` in the working directory
(a boot run's profile measured 87 MB). The profile route takes
the JIT path rather than the compiled launcher. Verified at the
gate (the profile half of check #11 passed: the file is written
and non-empty).

### AOT binaries vs the launcher

The two jank run shapes report uncaught throws the same way:

- **Snapshot launcher** (`bin/cohost :jank …`): the seam report
  engages — headline, `Full report at:`, report EDN, exit 1.
- **Static AOT binary** (`bb build:jank`, `cohost -T:build
  build`): the same report engages — the generated app entry
  routes through `cohost.cli/run-app-main!`, the CLI's report
  wrapper, so an uncaught throw prints the headline, writes the
  report EDN and exits 1 (check #9, verified by its probe at
  the final gate: exit 1, headline, report present). Before
  this fix the binary printed only jank's raw `#error` map and
  exited 0 — a CI script or shell pipeline saw success.

Positions survive into AOT: the snapshot emitter keeps position
meta and writes each form at its original line in the generated
module. Measured: 858 of 873 targeted forms at their original
lines; 15 sit above the generated header's end and could not be
placed. Native backtraces therefore resolve to user `.cljc`
lines, as the gdb probe below shows.

### gdb break-on-throw

On an AOT binary, gdb's C++ throw catchpoint works today
(check #12 PASS, measured 2026-11-05, twice with identical
results). Either shape stops at the throw:

```
gdb -batch -nx \
  -ex "set pagination off" \
  -ex "catch throw" \
  -ex run \
  -ex "bt 15" \
  --args ./throwmain
```

```
Thread 1 "throwmain" hit Catchpoint 1 (exception thrown), 0x00007ffff7d0035a in __cxa_throw () from /lib/x86_64-linux-gnu/libstdc++.so.6
#1  0x0000555555581495 in seam_throwmain$_main_8_1 (_main=..., args=...) at dev/cli-demo/src/seam/throwmain.cljc:7
```

A breakpoint on `__cxa_throw` itself (`-ex "break __cxa_throw"`)
gives the same stop. Frame #1 names the throwing Clojure
function and maps to its `.cljc` source line; jank runtime
frames are named below it. The probe scripts are committed at
`doc/debug-seam-probes/probe9-aot-throw.sh` and
`probe12-gdb-throw.sh`. This half of the seam needs no work.

## The seam checks, as a table

`bb debug-seam:check <jvm|jank>` runs the 14 checks
(`dev/debug_seam_check.clj`). State at the final gate,
2026-11-05, on the collated tree:

| # | Check | JVM | Jank |
|---|---|---|---|
| 1 | Uncaught-throw report | PASS | PASS |
| 2 | Source meta at read | SKIP (jank-only probe) | PASS (file + line exact) |
| 3 | Unresolved symbol; native catch | PASS | PASS (native catch included — the hole is closed) |
| 4 | Reader error | PASS | PASS |
| 5 | REPL `*1`/`*e` | PASS | PASS |
| 6 | In-program trace access | PASS | PASS |
| 7 | `pst` | PASS | PASS (frame lines carry jank's own `-N-N` suffix; see pst, above) |
| 8 | `*err*` separation | PASS | PASS |
| 9 | AOT uncaught throw | SKIP in the runner; probed separately | PASS by probe — exit 1, headline, report EDN |
| 10 | Divide by zero | PASS | PASS (cpp/ passthrough division is the stated boundary; see Divide by zero) |
| 11 | Transform introspection | SKIP (jank-only) | PASS |
| 12 | gdb break-on-throw | SKIP in the runner; probed separately | PASS (probed 2026-11-05, twice) |
| 13 | Macro-expansion errors | PASS | PASS |
| 14 | Exit codes | PASS | PASS (launcher and AOT alike) |
