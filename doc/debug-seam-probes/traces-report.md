# Traces lane report — how traces are PRESENTED on jank (#1, #6, #7)

**Status: IMPLEMENTATION LANDED @ 4d0de34 + correction @ 099a2a2;
JVM SIDE VERIFIED (below). Jank side QUEUED for the coordinator's
j run against this tree** (branch muse/debug-seam-traces).

## The mechanism (measured, not inferred)

A local fallback-path run of the base tree (uncaught
`(throw (ex-info "boom" {}))` via `bin/cohost :jank -e`) reproduced
the gate's #1 failure byte-for-byte — headline
"Execution error (ExceptionInfo) at object-ref::call/ (oref.hpp:566)"
— and its report file preserved the full `:ex-trace`. The head:

```
[cohost.hosts.jank.host$fn  invoke "jank_host.jank" 2729]   <- adapter :ex-info wrapper
[object_ref::call           invoke "oref.hpp" 566]          <- apply trampoline
[cohost.adapter$ex_info*    invoke "adapter.cljc" 808]
[object_ref::call           invoke "oref.hpp" 566]
[cohost_core$ex_info_5111_2 invoke "nil" 4837]
[user$throw_1_0             invoke "nil" 1]                  <- the user's frame
[jank::evaluate::eval ...] ...
```

Every ex-info construction on jank funnels through the adapter's
`:ex-info` wrapper (it adds the nil-message mark), so the wrapper's
frame — cpptrace name `cohost.hosts.jank.host/fn`, the MODULE
spelling (the file loads as module `cohost.hosts.jank.host`) —
heads every trace. It matched **no** prefix in F2's
`ctor-head-prefixes`, so `drop-while` stopped on it and the elision
dropped **nothing**: the machinery F2 meant to remove sat below it,
intact. The three failures are that one fact, three times:

- **#1**: `ex-triage` drops `cohost.`-prefixed frames for its
  headline pick, so it stepped over the adapter frame and landed on
  `object_ref::call` — not a core frame by its prefixes — and
  headlined it.
- **#6**: the shim's `-getStackTrace` IS `(host/ex-trace e)`, so
  the adapter frame simply led, exactly as the gate measured
  (jank_host.jank:2729 is the `:ex-info` line, verified in source).
- **#7**: two stacked causes. pst (stock `clojure.repl/pst`) prints
  the first 12 elements of that same trace via
  `clojure.repl/stack-element-str`, which renders the demunged class
  ONLY when the frame's file ends .clj/.cljc or equals
  `NO_SOURCE_FILE`; otherwise it prints `class.method` raw.
  A source-less frame on jank carries file `"nil"` (the module
  string-eval compiled under — see the user frame above), so pst
  printed `user$inner.invoke (nil:1)` and the demunged frame line
  never appeared.

## The implementation (all in the jank adapter's `:ex-trace`)

`src/cohost/hosts/jank_host.jank` only; the JVM adapter is untouched.

1. `ctor-head-prefixes` gains the adapter's own spellings:
   `cohost.hosts.jank.host$` (module, as measured),
   `cohost.hosts.jank_host$` and `cohost.hosts.jank-host$` (the ns
   spelling, munged and raw).
2. `ctor-frame?` also drops C++ frames in any spelling: a class
   carrying `::` is runtime machinery (`jank::...`,
   `object_ref::call`, the exception ctor); a Clojure frame's class
   — an `ns$name` symbol — never carries `::`. (First version used
   `neg?` on `string/index-of`, which returns nil when absent;
   corrected to `some?` in 099a2a2 before any jank run saw it.)
3. `frame-file`: a frame whose file is `"nil"` (or nil) becomes
   `NO_SOURCE_FILE`, the JVM's spelling for source-less code — the
   key pst's demunged rendering branches on.
4. Guard: if elision would take every frame, the unelided trace
   stands.

With the head elided the trace leads with the user's frame, so the
headline pick (#1), `.getStackTrace` (#6) and pst's frame lines
(#7) all present the user's code first, as on the JVM; the report
`:trace` that already passed keeps the same frames below the head.

## Probe/venue note (as ordered)

The probe script's first command completed on the fallback path
after the one-time AOT ensure (~40 min under contention) and
produced the report above. Its second command wandered into
building the core snapshot locally ("building jank's core snapshot
and launcher"); per the venue note I killed the probe tree at that
point rather than let a local snapshot build ride, and did the
remaining work from the banked trace + source. No local snapshot
builds from this lane; local box = source work + JVM gates.

## Verification

- **Local JVM, banked 2026-11-04** (logs traces-seamcheck-jvm.log,
  traces-boot-jvm.log in ~/workspace/cohost-install/):
  `bb debug-seam:check jvm` **10/14 PASS, 0 FAIL** (exit 0) —
  including #1 PASS (stderr matched "Execution error (ExceptionInfo)
  at user/boom"), #6 PASS (first frame `[user$inner invokeStatic
  "NO_SOURCE_FILE" 0]`), #7 PASS (frame line
  "user/inner (NO_SOURCE_FILE:0)"); `bb boot:jvm` **battery 221/221
  stock behaviors pass** (exit 0). The change touches only the jank
  adapter, so these are the no-regression numbers, and they hold.
- **Jank: QUEUED for the coordinator's j run against this tree** —
  the snapshot build of muse/debug-seam-traces (which also proves
  the adapter change passes `bb boot:jank`'s AOT compile) +
  `bb debug-seam:check jank`, to read #1/#6/#7 PASS with quoted
  evidence and #2/#4/#5/#8/#10/#14 still passing. Slot order per
  lane M: native lane, introspection lane, then this lane. Nothing
  on the local box blocks that run; the local snapshot path is
  deliberately not used (venue note).
