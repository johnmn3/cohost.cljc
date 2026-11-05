# Eve-in-cohost, unit 2: the other seats' adapter leaves — STATUS

Branch `muse/eve-cohost-impl-u2`, off `muse/eve-cohost-impl` @ 19256e5
(unit 1: the layer, the JVM seat, the shim dispatch, the boot fill).
Unit 2 supplies the leaves for the remaining seats, **jank first**,
then **cljs** (the design's executable test: the probe's cljs row,
`doc/eve-cohost-probe/REPORT.md` on `muse/eve-cohost-probe`, fails
7 of 7 cross-thread cases identically at the executor/latch seam).

## Measured starting state (probed firsthand, this lane)

Jank's own core (`clojure/core.jank` of the installed jank 0.1) —
the names `policy/thread-names` names, classified by reading the bodies:

- **TODO stubs** (each body is `(throw (ex-info "TODO: port X" {}))`):
  agent, set-agent-send-executor!, set-agent-send-off-executor!,
  send-via, send, send-off, release-pending-sends, agent-error,
  restart-agent, set-error-handler!, error-handler, set-error-mode!,
  error-mode, clear-agent-errors, shutdown-agents, await, await1,
  await-for, pmap, future?, future-done?. (agent-errors is defined
  over agent-error, so it throws through the stub too.)
- **Real**: future-call / future / future-cancel / future-cancelled?
  (jank runtime future objects), promise / deliver (jank runtime
  promise), pcalls / pvalues (defined over jank-core's own pmap —
  a call still lands on the stub, so they move with pmap).

Jank facilities, JIT-probed (`dev/u2-probe1.jank`, `jank run`):
`get-thread-bindings` returns the {var value} map (6 entries at rest;
a `binding` shows in it); jank's future conveys bindings (42);
promise/deliver answer. `clojure.lang.PersistentQueue/EMPTY` is an
unresolved symbol — **jank has no persistent queue type** (no
persistent_queue among its runtime objects either).

## Design (jank seat)

- **Caps in `src/cohost/hosts/jank_host.jank`**:
  - `:thread-spawn` — jank's own future engine:
    `(cpp/jank.runtime.future thunk)`. Deliberately not a raw
    std::thread: jank's future object keeps the fn and its bindings
    on the GC heap, while a raw std::thread stores its callable in
    unscanned C++ heap (the hazard jank's future.hpp comment names).
    Named distance: a jank future conveys its creator's binding frame
    as the thread's base frame, where the JVM seat's raw Thread
    starts at the roots; the layer's own conveyance pushes its
    captured frame over it, and agent actions bind their machinery
    fresh per action.
  - `:thread-self` — a C++ thread-local id from a global atomic
    counter (boxed integer): stable per thread, never reused.
  - `:doorbell` / `:doorbell-wait` / `:doorbell-signal` — a C++
    registry (mutex + condition_variable + sticky flag per id; the
    registry holds no jank objects, only C++ state, like a monitor).
    Wait with nil timeout blocks; a timed wait returns false.
  - `:thread-bindings` — `(get-thread-bindings)`.
  - `:core-lacks` — see below.
- **`:core-lacks` (new adapter capability)**: names the host's core
  has only as stubs, which the boot treats as lacked (the boot's
  `:host-lacks` state, until now set only from the `:host-lacks`
  boot opt — the exercise path). The jank adapter declares
  `policy/thread-names` (the whole concurrency set, for coherence:
  the layer's future?/future-done? read layer futures, and jank's
  pcalls/pvalues bind jank-core's pmap, so the families move
  together). Boot skips those names; `install-thread-layer!` fills
  them from the layer once the caps make `thread/available?` true.
- **The layer's queue**: `cohost.thread` takes its queues from the
  `:empty-queue` cap; jank has no queue value to give. The layer
  gains a portable fallback — a two-list queue (front list + reversed
  back list) held in a tagged map — used only when the host's cap
  yields nil; every queue call site in the layer goes through
  q-conj/q-peek/q-pop/q-empty? helpers that use the host value's own
  conj/peek/pop/empty? when it is one. JVM seat behavior unchanged
  (its cap yields PersistentQueue/EMPTY).

## Plan

1. Jank caps + `:core-lacks` + layer queue fallback. Local JIT probes
   of the caps standalone; the booted check shape on the JVM
   (exercise path) to confirm no layer regression.
2. J gate (on J, serial — this box OOM-kills the snapshot build):
   `bb boot:jank` AOT compile incl. the new shim/layer code (target:
   boot errors 0), battery 219/221 (standing pair only), then the
   probe program's jank row: agent and pmap flip from the TODO stubs
   to the JVM's values (agent 1, pmap (2 3 4)); future/promise/delay
   keep answering, now through the layer.
3. Cljs seat leaves (cljs.thread shape); the probe's cljs row is the
   test. Blockers, if any, banked with the actual error.

## State

- Design + probes banked @ 0f93f4c.
- **Layer queue fallback landed** @ 17a16b8 (`empty-queue` falls back
  to the two-list queue when the host cap yields nil; q-* helpers at
  every queue call site). JVM helper probe: FIFO order held under
  interleaved pops/conjs; host queue values pass through untouched.
- **Two pre-existing executor races found and fixed** @ 17a16b8 —
  they are in unit 1's layer code (pristine unit-1 code fails the
  same way, ~1 run in 3 on this box; unit 1's banked 29/29 was one
  sample). Found by instrumented stress (agent traffic + pmap
  rounds, per-item submit/claim/start accounting):
  1. `run-worker`'s idle branch re-claimed as a lost-wakeup guard
     and **discarded what it claimed**: a submit landing between the
     two claims lost its item — future pending forever, queue empty,
     workers idle (the probe's intermittent "restart-agent resumes"
     FAIL and pmap HANG are both this). Fix: the branch waits on the
     generation bell only (sticky signal; the 100 ms timeout bounds
     the rest).
  2. `claim!` leaked a discarded `swap!` run's head when the
     committed run saw an empty queue: the same item was delivered
     to two claimers (traced items claimed x2). Fix: the result atom
     is reset on every swap-fn run.
  After: layer probe **29/29 x6 runs**; instrumented stress (50
  agent dispatches + 200 pmap rounds) clean; the standalone
  restart-agent repro passes.
- **`:core-lacks` + jank leaves landed** @ bf4dc25: adapter contract
  + default `#{}`, boot unions it into `:host-lacks`, jank adapter
  declares `policy/thread-names` and supplies the five caps +
  `:thread-bindings`. No policy change was needed (the names already
  decide `:host`; lacked + in thread-names = skipped at boot, then
  filled by `install-thread-layer!`). Caps JIT-probed standalone
  (`dev/u2-probe2.jank`): self ids stable/distinct per thread,
  future deref answers the worker's id, doorbell timeout / sticky
  signal / cross-thread release all correct.
- JVM regression so far: booted check **21/21** with the new code
  (the JVM adapter's `:core-lacks` is the default `#{}`).
- In flight: the booted check on the jank seat, JIT
  (`dev/u2-jank-check.jank` — same 21 checks, boot! with NO
  `:host-lacks` opt, so the adapter declaration alone must route
  the family). Then the J AOT gate + battery, then the probe row,
  then cljs.

## Coordinator banking, 2026-11-05 ~10:55 AM ET (verified firsthand from J's logs + this worktree)

The three doorbell fixes below were committed locally but unpushed and
unreported; the coordinator pushed them (tip now includes 0cbd476) and
banks the gate state from J's ~/u2-gate-logs directly:

- 43f7f75 — doorbell registry lazily constructed (AOT SIGFPE).
- 566e4f3 — lazy init via atomic flag, not std::call_once (its
  __once_call emulated-TLS symbols are unresolvable by jank's module
  loader).
- 0cbd476 — doorbell reg() resolved before locking reg_m (the lazy-init
  call under the held mutex self-deadlocked make/get). This was the
  hang: the stage-4 probe stopped after "self 1" (10:41, empty log).
- AOT gate after the fixes (aot-rebuild3.log, 10:48 ET): boot 821
  top-level forms, errors 0; battery 219/221 — the only FAILs are the
  standing named pair (subs count nth first last butlast /
  Character outside ASCII); libraries 62/62. AOT cache holds 15 files.
- Remaining: the probe row, then the cljs half (real Eve SAB slab
  atom). The hang-hunt diag scripts in dev/ are untracked scratch.

## Continuation lane pickup, 2026-11-05 ~11:15 AM ET

The previous lane went silent (~24 min, two coordinator steers
unactioned) and was closed. A fresh continuation lane picks up here
at tip 768aa52 in a new worktree (~/workspace/wt/eve-cohost-u2-cont).
Verified at pickup: remote tip 768aa52 (ls-remote); AOT gate state as
banked above stands (the code at this tip is unchanged since the
10:48 AM gate run on J). Remaining, in order: (1) the probe row on
the jank seat, (2) the cljs half on real Eve (SAB slab atom), (3)
final gates + UNIT2-REPORT.md.

## Probe row, attempt: snapshot replay drops the thread-layer fill (2026-11-05 ~11:35 AM ET)

The probe row (bb dev/eve_probe.clj jank on J, via bin/cohost :jank)
failed at the program form: "The symbol `future` could not be
resolved" (cohost.scan/with-source, reading the -e program);
SUMMARY|jank|ok=0, wall 395,143 ms (that wall is the snapshot build).
Minimal repro on J, seconds each, snapshot path: `(println :hi)`
answers; `(println @(future 3))` fails the same way; ns-map of
clojure.core shows future/agent/pmap ABSENT, inc present.

Diagnosis (verified against the code): bin/cohost :jank boots from
the AOT snapshot, which REPLAYS a recorded boot instead of running
boot!. install-thread-layer! interns the layer's fn values directly
(host/intern!) -- no form is evaluated, so the snapshot recorder
(events of evaluated forms + snap-* ops) never sees the fills, and
the replayed core has no thread family. Fresh boots are unaffected
(the AOT gate's boot runs boot! itself).

Fix (cohost's own layers): install-thread-layer! now records
{:op :thread-layer} when it fills; the emitter
(dev/perf/jank_snapshot_emit.clj) maps it to
(cohost.boot/snap-thread-layer!); the new snap-thread-layer! redoes
the fill at replay against the snapshot report (snap-end! restores
the recorded report as data, so nothing double-prints -- the same
pattern as snap-take!/snap-scan!). Next: JIT booted check (fresh
boot) to confirm the other half, then rebuild the snapshot on J
(content key covers src/cohost, so the fix self-invalidates) and
re-run the probe row.

## Probe row RESULT (2026-11-05 ~12:05 PM ET, J, snapshot rebuilt at 9a8c0e2)

Minimal repro first: bin/cohost :jank -e with future/agent/pmap
answers ":future 3 :agent 1 :pmap [2 3 4]" -- the replay fix works.
Full row (bb dev/eve_probe.clj jank):

SUMMARY|jank|ok=18 unsupported-shim=0 fail-other=5 wall-ms=4720
(Original probe row, for contrast: ok=16, fail-other=7.)

Threading surface -- all 8 answer with the JVM's values, through the
layer: future-basic 3, future-capture-local 42, future-capture-atom
1, future-binding 3, promise 7, delay 2, agent 1 (was "TODO: port
agent"), pmap (2 3 4) (was "TODO: port pmap"). spec-conform 2,
test-stacktrace 0 (both as before).

Loads 7/12 (walk, set, string, data, template, test, stacktrace).
The same 5 fail as in the original probe row, verbatim: "The module
`clojure.zip` could not be found." -- likewise clojure.edn,
clojure.math, clojure.repl, clojure.spec.alpha. The snapshot
launcher's compiled-module set gap, not a threading failure:
spec-conform answers 2 in the same run, spec reached through the
eval/loader path.

Also banked from this leg: the booted check's documented JIT
invocation (jank --module-path src run dev/u2-jank-check.jank) is
not a usable vehicle on this tree -- the JIT boot reports 54 errors
in stock core forms (defmulti, fn, loop: jank JIT analyzer
overload/var_unbound_root failures) and the run died rc=139. The
seat's vehicles are the AOT modules and the snapshot; the probe row
above exercises the booted layer end-to-end through the CLI, which
is the check's intent by another route.

## Final gates at the fixed code (2026-11-05 ~12:15 PM ET)

- JVM (bb boot:jvm, this box): booted errors 0; battery 221/221;
  libraries 62/62.
- Jank (bb boot:jank, J, through the rebuilt snapshot): booted
  errors 0; battery 219/221 -- only the standing named pair (subs
  count nth first last butlast / Character outside ASCII);
  libraries 62/62.
- The snapshot chain is verified in the artifacts: events.edn
  carries the :thread-layer event; the emitted snapshot core module
  (target/jank-snapshot/src/cohost/core.jank) calls
  (cohost.boot/snap-thread-layer!).

## Cljs half: NOT-YET, with the blocking work named (2026-11-05 ~12:25 PM ET)

Verified against Eve's own tree (~/src/eve, main) and this branch:

What is real and present: Eve's cljs substrate exists --
eve.shared-atom (src/eve/shared_atom.cljs) is a shared atom over a
SharedArrayBuffer, with cross-thread watch notification built in
(Atomics.notify + Atomics.waitAsync loops, lines 2391-2458), and
eve.util/yield-cpu already handles the Atomics.wait rules (workers
wait; the browser main thread may not). The doorbell-grade
primitives exist inside Eve.

What does not exist, anywhere:
1. A worker spawn facility. Nothing in Eve's cljs sources spawns a
   worker (no worker_threads, no Worker construction); Eve's thread
   work lives on other seats. Cohost's cljs seat likewise has no
   spawn: the adapter (src/cohost/hosts/cljs.cljs) supplies
   :thread-bindings and :empty-queue, no thread caps.
2. A spawn contract that could carry the layer's work even if a
   worker existed. Unit 1's layer hands the adapter an opaque
   closure over main-realm state (thread.cljc spawn: (fn [] ...));
   a worker is a separate realm -- the closure cannot cross. The
   cljs.thread design crosses with FORMS + bindings conveyed
   through the shared atom, workers booting the same image. The
   layer has no such contract today; adding one (a spawn-by-form
   cap, or a layer-level change) is design work, not a leaf.
3. Eve in the seat at all. Cohost's cljs build (cljs-cli.edn)
   compiles src/cohost only; Eve is not yet a dependency of the
   cljs seat, so there is no Eve atom in the process for a leaf to
   sit on. Wiring Eve's cljs sources into the seat build is the
   dependency step the cohost 0.9 tag exists for.

Per the no-substitutes ruling, no cljs thread leaves are landed: a
doorbell over a privately allocated SAB would be a parallel
substrate next to Eve, not Eve. The cljs half is therefore banked
as a named NOT-YET. Blocking work, named: (a) Eve as a dependency
of cohost's cljs build; (b) a spawn-by-form conveyance contract in
the layer/adapter (unit 8's shared-heap-illusion design: workers
boot the same image, work crosses as a form, bindings conveyed
through the runtime atom domain -- eve.shared-atom over SAB);
(c) the leaves themselves then sit on eve.shared-atom: doorbell
state in the shared atom's control region, wait on workers via
Atomics.wait, signal via Atomics.notify. Unit 8 owns (b) by the
plan's construction (it depends on units 2, 5, 7); (a) is the
dependency step. The probe's cljs row stands as the test that
flips when this lands (today: every executor case fails with the
undefined-call NPE; delay answers).
