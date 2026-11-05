# Gate probes #9 + #12 — report (gate-probes lane, 2026-11-05)

Base: muse/debug-seam @ 030cc0a. Probes are committed scripts:
`probe9-aot-throw.sh`, `probe12-gdb-throw.sh` (this directory).
Fixture: `dev/cli-demo/src/seam/throwmain.cljc` — `-main` throws
`(ex-info "kaboom from seam.throwmain" {:fixture :aot-throw})`.
All measurements local, run twice with identical results; durable logs in
`~/workspace/cohost-install/probe9-rerun/` (aot.out, aot.err,
gdb-catch.log, gdb-break.log) and `probes-build-jank.log`,
`probes-jit.err`.

## #9 AOT uncaught throw — GAP (measured)

Route: `bb build:jank dev/cli-demo seam.throwmain throwmain`
(cohost.build-jank/build!: AOT objects, boot record, one module per
namespace + cohost.app-main, `jank -O3 compile cohost.app-main
--runtime static`). Binary: `dev/cli-demo/target/jank/throwmain`,
44,572,208 bytes, built in 2,232,262 ms on a box running three lanes'
jank compiles concurrently.

Run from a scratch directory (the static binary reads nothing from the
checkout):

- **Exit code: 0** — not 1. A throwing AOT app reports success to the shell.
- **stdout (986 bytes, the whole output; stderr empty):** jank's own
  `#error` print of the exception — no seam headline, no report file:

```
#error {
 :cause "kaboom from seam.throwmain"
 :data {:fixture :aot-throw}
 :via
 [{:at ["jank::runtime::ex_info" "throwmain" 1], :message "kaboom from seam.throwmain", :data {:fixture :aot-throw}}]
 :trace
 [["jank::runtime::ex_info" "throwmain" 1]
  ["clojure_core$ex_info_32861_2" "throwmain" 1]
  ["cohost.hosts.jank.host/fn (arity 2)" "jank_host.jank" 2729]
  ["cohost.adapter/ex-info* (arity 2)" "adapter.cljc" 808]
  ["object_ref::call" "oref.hpp" 566]
  ["seam.throwmain/-main (arity 1)" "throwmain.cljc" 7]
  ["object_ref::call" "oref.hpp" 566]
  ["jank::runtime::apply_to" "throwmain" 1]
  ["object_ref::call" "oref.hpp" 566]
  ["jank_call2" "throwmain" 1]
  ["cohost.app.main/-main (arity 1)" "app_main.jank" 6]
  ["main::$_0::operator()" "jank-main-p4E9V7" 74]
  ["main::$_0::__invoke" "jank-main-p4E9V7" 48]
  ["jank_init_static" "throwmain" 1]]}
```

- **No "Execution error" headline, no "Full report at:", no report EDN
  written.** The seam's report machinery lives in cohost.cli's
  run/report path; the AOT entry (jank_init_static → generated main →
  cohost.app-main/-main → the app's -main, per the trace above) never
  passes through it, and jank's generated main prints the uncaught
  exception as a value and exits 0.

Comparison, launcher path: at the j gate (358048e) the runner's #1 on
the snapshot launcher measured exit 1, headline
`Execution error (ExceptionInfo) at object-ref::call/ (oref.hpp:566).`,
and a written report EDN — the seam report engages there. The local
same-app launcher run did not complete: this worktree's first snapshot
build compiled every module (log: `PLACEMENT 858 of 873 targeted forms
at their original lines, 15 unplaceable`; `MODULE cohost.snapshot
target/jank-snapshot/src/cohost/snapshot.jank 10`) but the launcher
step failed — `SNAPSHOT-LAUNCHER not built (exit 1 ); compiling the
modules alone`, with no error text recorded in
target/jank-snapshot.log — and the fallback app-main compile was still
running after ~1 h under contention when it was killed. Leg 2 of
probe9 runs the same-app launcher comparison wherever a launcher
exists (at the j gate it is prebuilt).

**What a user gets today from an AOT build:** a stack-bearing #error
map on stdout and exit 0. CI and shell scripts see success; the seam's
headline + report file do not exist on this path.

## #12 gdb break-on-throw — PASS (measured)

On the #9 AOT binary, gdb batch mode, both shapes stop AT the throw
with a named, source-mapped backtrace.

`catch throw`:

```
Thread 1 "throwmain" hit Catchpoint 1 (exception thrown), 0x00007ffff7d0035a in __cxa_throw () from /lib/x86_64-linux-gnu/libstdc++.so.6
#0  0x00007ffff7d0035a in __cxa_throw () from /lib/x86_64-linux-gnu/libstdc++.so.6
#1  0x0000555555581495 in seam_throwmain$_main_8_1 (_main=..., args=...) at dev/cli-demo/src/seam/throwmain.cljc:7
#2  jank::runtime::apply_to(...)
#3  jank::runtime::oref<...>::call ... at .../jank/runtime/oref.hpp:566
#4  cohost_app_main$_main_8_1 ... at dev/cli-demo/target/jank/cohost-build/src/cohost/app_main.jank:6
...
#12 _start
```

`break __cxa_throw`: same stop (`hit Breakpoint 1, 0x...348 in
__cxa_throw`), same frame #1 at throwmain.cljc:7. GDB_EXIT=0 both.

**What a user debugging with gdb gets today:** a working break on the
throw, with the throwing Clojure function named and mapped to its
.cljc source line, jank runtime frames named, and the app-main frame
mapped to the generated app_main.jank. This half of the seam needs no
work.

## Verdicts

- #9: **GAP** — AOT uncaught throw exits 0, prints only the raw #error
  map; no headline, no "Full report at:", no report EDN. Fix surface
  (not attempted here): the AOT entry in cohost.app-main / build-jank's
  generated main would need the CLI's report wrapper + a nonzero exit.
- #12: **PASS** — gdb stops at the throw; backtrace usable and named.
- JVM gates untouched: no runner or src changes in this lane (probes +
  one fixture only).
