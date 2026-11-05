# U6 / check #11 — transform introspection: lane report (FINAL)

Branch muse/debug-seam-introspect, base muse/debug-seam @ 030cc0a.
Fix @ 37d340c (bin/cohost + doc/CLI.md). Status: DONE locally —
diagnosis proven, fix landed, routing verified end-to-end on the
shapes this box can run; the one remaining verification is the
full runner on a fresh snapshot, which routes to the j gate via
lane M's bundle (four local snapshot builds failed tonight, all
environmental — see "local venue").

## The question

Runner check #11 runs `bin/cohost :jank -e "(+ 1 2)"` in dev/cli-demo
twice: once with `JANK_PRINT_CODEGEN=1` (the captured out+err must
contain a line naming cohost) and once with `COHOST_JANK_PROFILE=1`
(a non-empty `jank.profile` must appear). At the j gate on the
collated tree the profile half passed and the codegen half failed:
"codegen exit 0, matched line: none". The analysis lane had measured
the codegen dump naming cohost locally (1,572 hits). Same runner,
same tree family — the difference is the launch shape.

## Probe evidence

Shape A — the runner's exact probe on a warm snapshot with the
compiled launcher (the gate shape):

- Measured at the j gate, collated tree @ 358048e: codegen exit 0,
  matched line: none — zero lines naming cohost across out+err.
  Profile half PASS in the same run (jank.profile written: true).
- Local reproduction of shape A is blocked by the local snapshot
  build (four failures, all environmental — see "local venue").
  The gate's own run is the shape-A measurement of record.

Shape B — the source boot (snapshot dir moved aside,
`COHOST_JANK_SNAPSHOT=off` so `jank-cli-launch` falls to the AOT
path and core boots from source), pre-fix code, measured locally
2026-11-05 on this branch @ 030cc0a:

- exit 0; stdout 557,866 bytes, **1,571 lines naming cohost**;
  stderr 88 bytes, 0 cohost lines. The dump is on **stdout**,
  which the runner captures — capture and needles are not the bug.
  (The analysis lane's earlier measurement of this shape: 1,572
  hits; the off-by-one is run variance.)
- First dump line naming cohost:
  `#line 283 "/home/hatch/workspace/cohost-introspect/src/cohost/cli.cljc"`

Shape C — `run-main` over the snapshot's objects (launcher binary
skipped, snapshot module path kept): not measured. The fix does not
depend on it: even if evaluating the user form alone dumped code,
it could not name the rewritten core forms, which is what
doc/CLI.md promises ("the forms as cohost rewrote them — shim and
cohost.core names included").

## Diagnosis

Under the compiled snapshot launcher nothing compiles at run time:
cohost.cli and the booted core load as compiled objects, so
`JANK_PRINT_CODEGEN` has nothing to print and the run exits 0 with
no dump. The variable itself reaches the process (bin/cohost
inherits its environment into the child, and the profile route —
which depends on the same inheritance — works at the gate). It is
not a lost env var, not a wrong stream, and not a runner needle
problem: it is the absence of compilation in the launcher shape.
U6's passthrough was spiked and verified on the JIT/source-boot
path only (5fde5fb: "dumps jank's IR on the JIT path"); the
launcher shape was never measured until the gate.

## Fix @ 37d340c (bin/cohost only; no patches on jank)

When `JANK_PRINT_CODEGEN` or `JANK_PRINT_IR` is set (any value but
"0"/""), the run takes the source boot — the same treatment
`COHOST_JANK_PROFILE=1` already gets, one layer deeper:

- `jank-introspect?` (new, bin/cohost): either var set as above.
- `jank-launch`: under introspection the launch map is overridden
  to the source boot (cohost's -O3 AOT objects + `src`,
  `:snapshot? false`) and the per-project snapshot is skipped, so
  core and the project's namespaces compile in the run, dumping
  as they go.
- `launch!`: the compiled launcher is not exec'd for such a run
  (it would ignore the launch map's module path entirely).
- doc/CLI.md: the introspection paragraph now says an
  introspection request takes the source boot for that run, like
  a profile request takes the JIT path.

Paren check on bin/cohost: OK, 31 forms.

## Verification (local, this tree @ 37d340c)

- Post-fix routing probe — the runner's exact invocation
  (`bin/cohost :jank -e "(+ 1 2)"`, dev/cli-demo,
  `JANK_PRINT_CODEGEN=1`; the half-built snapshot dir in place,
  `COHOST_JANK_SNAPSHOT=off` so the stale snapshot does not trigger
  a rebuild first — on a fresh-snapshot machine that step is a
  no-op check and the override replaces the launcher launch):
  **exit 0, stdout 557,866 bytes (byte-identical to shape B),
  1,571 lines naming cohost, stderr empty.**
- Ordinary run unchanged: same invocation without the vars →
  exit 0, prints `3`, no dump.
- JVM runner suite: `bb debug-seam:check jvm` →
  **10/14 PASS, 0 FAIL**; `#11 SKIP jank-only (no transform to
  introspect on the JVM host)`; no other check moved.
- OWED (j gate, via lane M's bundle of this branch): build the
  snapshot on j from 37d340c, run `bb debug-seam:check jank`;
  expected #11 PASS with the dump's own line quoted by the runner
  (the codegen half now boots from source; the profile half is
  untouched and already passes). Every other jank check should
  read exactly as at the 358048e gate — the fix is inert unless
  an introspection var is set.

## Local venue (why the definitive verification runs on j)

Four local `bb boot:jank-snapshot` failures tonight, four distinct
environmental causes, none in the seam's code:

1. Stale pre-move `~/.cache/jank` PCH (baked /usr/local header
   paths) → jtl redefinition errors. Fixed by clearing the cache.
2. Launcher link: clang frontend "unable to execute command:
   Killed" (OOM) at box load ~9–12 with three lanes building.
3. After a box reboot (04:41 UTC): the PCHs built 03:49–03:51 UTC
   carry GCC-13 header paths while jank now detects GCC 14
   (`jank print-cflags` shows `/usr/lib/gcc/x86_64-linux-gnu/14/…`)
   → `'stdlib.h' file not found` on every TU. Fixed by clearing
   `~/.cache/jank` again; also recorded for doc/REPORT.md host
   facts (coordinator addendum §6).
4. Rebuild after the clear: record + emit clean (PLACEMENT 858 of
   873, 15 unplaceable), PCH rebuilt, then the launcher compile
   was SIGTERM'd (exit 143) at ~13 min with load ~2–3.5 and
   2.3 GB free; terminator unknown (not OOM — that is SIGKILL).
   Another lane's gdb probe processes vanished in the same window.

Per the standing venue order, the snapshot + runner verification
of the fixed tree runs on j.slaa.us from lane M's bundle of this
branch.
