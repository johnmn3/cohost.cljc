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

- Design + probes banked (this file). Implementation next.
