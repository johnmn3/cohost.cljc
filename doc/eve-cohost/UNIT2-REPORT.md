# Eve-in-cohost, unit 2: the other seats' adapter leaves — REPORT

Branch `muse/eve-cohost-impl-u2`. Unit 2 supplies the thread
layer's leaves for the seats unit 1 did not cover: **jank first,
then cljs**. Work was done by two lanes: the original lane (design,
jank leaves, the doorbell deadlock chain, the first AOT gate) and a
continuation lane that picked the branch up at `768aa52` after the
first lane went silent (probe row, snapshot fix, final gates, cljs
assessment). play-by-play is in UNIT2-STATUS.md.

## The jank seat — DONE

The jank adapter (`src/cohost/hosts/jank_host.jank`) declares
`:core-lacks` = `policy/thread-names` (jank's core has the whole
concurrency family only as "TODO: port" stubs) and supplies the
caps: `:thread-spawn` over jank's own future engine,
`:thread-self` (C++ thread-local id), `:doorbell` /
`:doorbell-wait` / `:doorbell-signal` (a C++ registry: mutex +
condition_variable + sticky flag per id), `:thread-bindings`. The
layer gained a portable two-list queue fallback for hosts with no
queue value (jank has no persistent queue type). Two pre-existing
executor races in unit 1's layer code were found by instrumented
stress and fixed (lost wakeup discarding a claimed item; `claim!`
double-delivery).

The doorbell registry cost three fixes, each measured: lazy
construction (a namespace-scope unordered_map sits in
zero-initialized storage under jank's AOT linking; first insert is
a SIGFPE), an atomic-flag lazy init instead of std::call_once (its
`__once_call` emulated-TLS symbols are unresolvable by jank's
module loader), and resolving `reg()` before locking the registry
mutex (the lazy-init call under the held mutex self-deadlocked).

## The probe row — DONE, and it caught a real bug

The load probe's jank row, re-run at this branch
(`bb dev/eve_probe.clj jank`, on J):

```
SUMMARY|jank|ok=18 unsupported-shim=0 fail-other=5 wall-ms=4720
```

Original probe row: ok=16, fail-other=7. Every threading-surface
case now answers with the JVM's values, through the layer:
future-basic 3, future-capture-local 42, future-capture-atom 1,
future-binding 3, promise 7, delay 2, **agent 1** (was "TODO: port
agent"), **pmap (2 3 4)** (was "TODO: port pmap"). spec-conform 2,
test-stacktrace 0, as before. Loads 7/12; the same five libraries
fail as in the original row, verbatim "The module `clojure.X`
could not be found" (zip, edn, math, repl, spec.alpha) — the
snapshot launcher's compiled-module set gap, not threading
(spec-conform answers 2 in the same run, spec reached through the
eval/loader path).

The row's first run failed outright — "The symbol `future` could
not be resolved", ok=0 — and the diagnosis is a cohost bug, now
fixed: `bin/cohost :jank` boots from the AOT snapshot, which
**replays a recorded boot** instead of running `boot!`.
`install-thread-layer!` interns the layer's fn values directly; no
form is evaluated, so the snapshot recorder never saw the fills
and the replayed core had no thread family. Fresh boots (the AOT
gate) were unaffected. Fix, in cohost's own layers:
`install-thread-layer!` records `{:op :thread-layer}` when it
fills; the snapshot emitter maps it to
`(cohost.boot/snap-thread-layer!)`; the new `snap-thread-layer!`
redoes the fill at replay (the recorded report is restored as data
by `snap-end!`, so nothing double-prints — the `snap-take!` /
`snap-scan!` pattern). Verified in the artifacts: `events.edn`
carries the event, the emitted snapshot core module calls
`snap-thread-layer!`, and the minimal repro through the rebuilt
snapshot answers `:future 3 :agent 1 :pmap [2 3 4]`.

## Final gates (at the fixed code)

- **JVM** (`bb boot:jvm`): boot errors 0; battery **221/221**;
  libraries **62/62**.
- **Jank** (`bb boot:jank` on J, through the rebuilt snapshot):
  boot errors 0; battery **219/221** — the only failures are the
  standing named pair (`subs count nth first last butlast`,
  "Character outside ASCII"); libraries **62/62**.

## The cljs half — NOT-YET, blocking work named

No cljs thread leaves are landed, deliberately, under the
no-substitutes ruling. Verified against Eve's tree (`~/src/eve`):

- Eve's cljs substrate is real: `eve.shared-atom` is a shared atom
  over SharedArrayBuffer with cross-thread watch notification
  (Atomics.notify / waitAsync) built in — the doorbell-grade
  primitives exist inside Eve.
- But nothing in Eve's cljs sources **spawns a worker** (no
  worker_threads, no Worker construction), and cohost's cljs
  adapter has no thread caps.
- And no leaf could be used if it existed: unit 1's layer hands
  the adapter an **opaque closure** over main-realm state; a
  worker is a separate realm. The cljs.thread design crosses with
  forms + bindings conveyed through the shared atom — the layer
  has no such contract. That is a design addition, not a leaf.
- And Eve is not yet **in** the cljs seat: `cljs-cli.edn` compiles
  `src/cohost` only. A doorbell over a privately allocated SAB
  would be a parallel substrate next to Eve, not Eve — a
  substitute, so it was not built.

Blocking work, named: (a) Eve as a dependency of cohost's cljs
build (the step the cohost 0.9 tag exists for); (b) a
spawn-by-form conveyance contract in the layer/adapter — unit 8's
shared-heap-illusion design (workers boot the same image, work
crosses as a form, bindings conveyed through the runtime atom
domain); (c) the leaves then sit on `eve.shared-atom`: doorbell
state in the shared atom, waits via Atomics.wait on workers,
signals via Atomics.notify. Unit 8 owns (b) by the plan's
construction (depends on units 2, 5, 7).

Baseline confirmed on this branch: the probe's cljs row re-run is
identical to the original measurement — loads 12/12, delay 2,
test-stacktrace 0, all seven executor cases failing with
`NullPointerException :: Cannot read properties of undefined
(reading 'call')`, spec-conform failing — `SUMMARY|cljs|ok=15
fail-other=8`. That row is the test that flips when the NOT-YET
lands.

## Named remainders

- The booted check's documented JIT invocation
  (`jank --module-path src run dev/u2-jank-check.jank`) is not a
  usable vehicle on this tree: the JIT boot reports 54 errors in
  stock core forms (jank JIT analyzer overload / var_unbound_root
  failures on defmulti, fn, loop, …) and the run died rc=139. The
  seat's vehicles are the AOT modules and the snapshot; the
  check's intent is covered end-to-end by the probe row through
  the CLI.
- The five launcher module-path load gaps (above) are
  pre-existing and unchanged; they belong to the snapshot
  launcher's module set, not to this unit.
