# Debugging

*2026-11-05. States below are measured, not aspirational: the JVM
column is `bb debug-seam:check jvm` on this branch (10/14 PASS,
0 FAIL); the jank column is the j gate run on the build server
2026-11-04 (6 PASS, 5 FAIL, 3 SKIP) plus the separate gate probes
of 2026-11-05. Where a fix has landed on a side branch since the
gate but is not yet collated into `muse/debug-seam`, the text says
which branch and what verification it has. Gaps are written as
gaps.*

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

**Jank, as of the 2026-11-04 gate:** the headline on the snapshot
launcher could name a host trampoline frame —
`object_ref::call (oref.hpp:566)` — instead of the throwing
function, because jank's exception-construction frames led the
trace and the headline picks the first frame it cannot drop. The
fix (those frames are now elided in the trace data itself, below)
landed on branch `muse/debug-seam-traces` on 2026-11-04 and is
verified on the JVM; its jank verification run was still queued
as of 2026-11-05.

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
code. Verified on the JVM (check #6: the first `.getStackTrace`
frame is `[user$inner invokeStatic "NO_SOURCE_FILE" 0]`). On jank
the gate measured the un-elided head; the elision fix's jank run
is the same queued run as the headline's, above.

## pst and print-stack-trace

`(require 'clojure.repl)` then `(clojure.repl/pst)` — or `(pst e)`
on a caught exception — prints the exception's header (class,
message, ex-data) and its frames, tab-indented and demunged:

```
ExceptionInfo r {:x 2}
	user/inner (NO_SOURCE_FILE:0)
```

Verified on the JVM (check #7). On jank at the gate, `pst` ran on
the launcher but its frame lines rendered raw `class.method` for
source-less frames — jank carries their file as `"nil"`, where
the stock printer only demunges files ending `.clj`/`.cljc` or
named `NO_SOURCE_FILE`. The traces fix maps source-less files to
`NO_SOURCE_FILE` and elides the head; jank re-verification is
queued with the rest of that branch (2026-11-05).

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

On the JVM, an error thrown while a macro expands is reported
with the macroexpansion phase: the report's `:via` names the
macro with its symbol, source and line, and a macro that expands
to an unresolved symbol fails the load with that provenance
(check #13 PASS).

**Jank gap (2026-11-05):** a macro function's throw during JIT
compilation crosses as a native C++ exception rather than a
Clojure exception object, so no Clojure-level catch ever holds it
and the report reads only "a native C++ exception" — the macro
attribution is lost and check #13 fails on jank. Open.

## Catching native errors (jank)

A stock `try` catches Clojure exceptions on every host. On this
branch, one class still escapes on jank: a native C++ exception
thrown by jank's own core (an index error inside jank internals,
a macro throw as above) is not an object a Clojure `catch` can
hold. The run ends with exit 1 and a full report, but the message
is the generic "a native C++ exception" and the catch never
fires (check #3, native half: SKIP on jank, the hole named).

Fix status, 2026-11-05: sibling catch clauses that translate
jank's `error_ref` and `std::exception` into an ex-info — message
read from the caught value in the clause — landed on branch
`muse/debug-seam-native` and were verified there on the build
server: the snapshot builds clean, `cli:check` 7/7, and a thrown
`std::runtime_error` is caught by `catch Throwable` with its
message intact. Not yet collated into `muse/debug-seam`.

## Divide by zero

`(/ 5 0)` and `(quot 5 0)` on integers throw
`ArithmeticException` — "Divide by zero" — and exit 1, never a
signal. Jank's native integer division is undefined behavior on
a zero divisor (plain jank dies with SIGFPE, exit 136), so the
guard sits in cohost's numeric layer, which stock `/`, `quot`,
`rem` and `mod` all route through. Verified on the JVM and on
jank (check #10). Arithmetic written directly in `cpp/`
interop is the caller's own C++: a zero divisor there is a
native SIGFPE, exactly as in a plain jank program — cohost
cannot guard code it does not rewrite.

## Exit codes

Verified on the JVM and on the jank launcher (check #14):

| Case | Exit |
|---|---|
| Uncaught throw (`-e`, `-m`, script, `-main`) | 1 |
| `-X` naming a missing function | message (`function not found: …`) + nonzero |
| A script calling `(System/exit 3)` | 3 |

A statically built AOT binary is the exception — it exits 0 on
an uncaught throw. See the jank section below.

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

State, 2026-11-05: through the compiled snapshot launcher (the
default `bin/cohost :jank` shape) nothing compiles at run time —
the booted core loads as compiled objects — so the gate
measured **no dump** on that shape (check #11 FAIL there). On
the source-boot (JIT) path the same command prints the dump:
1,571 lines naming cohost, measured locally. A fix on branch
`muse/debug-seam-introspect` routes any run with either
variable set to the source boot, like a profile request; its
end-to-end gate verification is still pending.

### Profiling

`COHOST_JANK_PROFILE=1` runs the program under jank's own
profiler and writes `jank.profile` in the working directory
(a boot run's profile measured 87 MB). The profile route takes
the JIT path rather than the compiled launcher. Verified at the
gate (the profile half of check #11 passed: the file is written
and non-empty).

### AOT binaries vs the launcher

The two jank run shapes report uncaught throws differently:

- **Snapshot launcher** (`bin/cohost :jank …`): the seam report
  engages — headline, `Full report at:`, report EDN, exit 1.
- **Static AOT binary** (`bb build:jank`, `cohost -T:build
  build`): **gap, measured 2026-11-05.** The binary prints
  jank's raw `#error` map to stdout — `:cause`, `:data`,
  `:via` and `:trace` are all correct and the user frame is
  present (`seam.throwmain/-main` at `throwmain.cljc:7`) — but
  there is no headline, no `Full report at:`, no report file,
  stderr is empty, and **the exit code is 0**. A CI script or
  shell pipeline sees success. The AOT entry path never passes
  through the CLI's report machinery, and jank's generated main
  prints the exception as a value. The fix surface is named —
  the AOT entry would need the CLI's report wrapper and a
  nonzero exit — and is not yet implemented (check #9).

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
(`dev/debug_seam_check.clj`). State at this chapter's date:

| # | Check | JVM | Jank |
|---|---|---|---|
| 1 | Uncaught-throw report | PASS | FAIL at gate — headline named a host frame; fix on `muse/debug-seam-traces`, jank run queued |
| 2 | Source meta at read | SKIP (jank-only probe) | PASS (file + line exact) |
| 3 | Unresolved symbol; native catch | PASS | load half PASS; native half SKIP — hole open here, closed on `muse/debug-seam-native` (verified on the build server) |
| 4 | Reader error | PASS | PASS |
| 5 | REPL `*1`/`*e` | PASS | PASS |
| 6 | In-program trace access | PASS | FAIL at gate — host frames led; same queued fix as #1 |
| 7 | `pst` | PASS | FAIL at gate — frame lines mis-shaped; same queued fix as #1 |
| 8 | `*err*` separation | PASS | PASS |
| 9 | AOT uncaught throw | SKIP in the runner; probed separately | **GAP** — exit 0, raw `#error` only (probed 2026-11-05) |
| 10 | Divide by zero | PASS | PASS |
| 11 | Transform introspection | SKIP (jank-only) | FAIL at gate on the launcher shape; fix on `muse/debug-seam-introspect`, gate verification pending |
| 12 | gdb break-on-throw | SKIP in the runner; probed separately | PASS (probed 2026-11-05) |
| 13 | Macro-expansion errors | PASS | FAIL — native escape loses the attribution |
| 14 | Exit codes | PASS | PASS (launcher; AOT is #9's gap) |
