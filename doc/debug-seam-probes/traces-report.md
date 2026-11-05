# Traces lane report — how traces are PRESENTED on jank (#1, #6, #7)

**Status: IN PROGRESS** (lane opened 2026-11-04, base muse/debug-seam @ 030cc0a)

## The unit

Three j-gate FAILs, one family — all three pass on the JVM:

- **#1 uncaught-throw headline**: the report file is written and its
  `:trace` matched, but the printed headline's first line was
  "Execution error (ExceptionInfo) at object-ref::call/ (oref.hpp:566)"
  — a host frame — where the JVM prints the user's frame.
- **#6 in-program trace access**: the first `(.getStackTrace e)` frame
  on jank was `cohost.hosts.jank.host$fn invoke "jank_host.jank" 2729`
  — the host adapter's own frame leads; on the JVM the user's leads.
- **#7 pst**: on the launcher pst RUNS (header "ExceptionInfo r {:x 2}"
  matched) but its frame lines render in a shape the check's needle
  does not find.

## Established before any change (source reading)

All three presentations derive from ONE datum: the jank adapter's
`:ex-trace` (`src/cohost/hosts/jank_host.jank`).

- The CLI headline's frame is `throwable->map`'s root `:ex-trace`,
  first frame after `ex-triage`'s `core-frame?` drop
  (`src/cohost/cli.cljc`). `core-frame?` drops clojure./java./jdk./
  sun./cohost./jank. prefixes only — a class like `object_ref::call`
  is NOT a core frame there, so if `:ex-trace` leads with it, it
  headlines.
- The shim exception's `-getStackTrace` is `(host/ex-trace e)`
  (`src/cohost/shim.cljc`) — so #6, and pst (#7, stock
  clojure.stacktrace over the shim's elements), see exactly what
  `:ex-trace` returns.
- `:ex-trace` already drops a construction head (`drop-while
  ctor-frame?`, F2) keyed on munged-class prefixes: `jank::`,
  `clojure_core$`, `cohost_core$`, `object_ref::`, `cohost.shim$`,
  `cohost.adapter$`, … — but NO spelling of the adapter's own
  namespace (`cohost.hosts.jank-host`, the .jank file's ns) is in the
  list, and the gate's #6 head frame sits at jank_host.jank:2729 —
  the adapter's own `:ex-info` wrapper, through which every ex-info
  construction on jank funnels.

First probe (local fallback path, log traces-probe1.log) is running to
capture the actual head frames this box's jank produces for the #1
uncaught throw, a caught `(.getStackTrace e)`, and pst — before any
implementation is written.
