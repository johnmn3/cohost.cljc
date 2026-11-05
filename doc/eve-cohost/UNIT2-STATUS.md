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
