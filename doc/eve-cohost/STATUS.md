# Eve-in-cohost, unit 1: Eve's thread layer behind the Java shim — STATUS

Branch `muse/eve-cohost-impl`, off `muse/debug-seam` @ 2563eee (cohost 0.9.3 prep line).
Seat for this unit: JVM. The shape is portable: one implementation in
`cohost.thread`, host leaves behind the adapter contract.

## Design as built

- **Leaves (adapter contract, new):** `:thread-spawn`, `:thread-self`,
  `:doorbell`, `:doorbell-wait`, `:doorbell-signal`. Defaults are nil (no
  thread layer on that host); accessors in `cohost.adapter` read nil as
  "unavailable". JVM seat supplies them in `cohost.hosts.jvm`: a real
  daemon `java.lang.Thread` per spawn; a doorbell is an atom used as its
  own monitor (sticky signal); `:thread-bindings` from
  `Var/getThreadBindings`.
- **The layer (`src/cohost/thread.cljc`, new):** registry, executors,
  futures, promises, CountDownLatch, agents, pmap/pcalls — all state in
  atoms (registry atom, per-value state atoms, queue atoms, control
  atoms); values cross threads only by deref/swap. No journals, no
  counters outside atoms, no arenas.
  - Executors: queue atom + control atom + a generation doorbell
    (swapped fresh at every signal, so no wakeup is ever missed).
    `send` executor: pooled, 8 workers (stock's is an unbounded cached
    pool — a named distance; the queue is unbounded, so work is never
    refused, only concurrency is capped). `send-off`: solo, 1 worker.
  - Conveyance (the cljs.thread shape): a work item captures the
    caller's binding frame via the adapter's `:thread-bindings` and the
    worker restores it via `:with-thread-bindings!`. Transparent:
    library code never sees it.
  - Futures: `future-call` runs on the pooled executor with conveyance;
    completion is a state atom + doorbell; deref blocks / times out /
    rethrows; cancel works while pending (no interrupt of a running
    task — named distance).
  - Agents: per-agent queue atom drained by at most one worker;
    actions carry their captured bindings and their executor, with
    drain hand-off between executors; sends made inside an action are
    held in `*pending-sends*` and released when the action ends (stock's
    nested-send semantics); `:continue` records errors and goes on,
    `:fail` stops the agent until `restart-agent`; validators enforced.
  - `await`/`await-for` ride a latch action enqueued behind the work.
- **Core fill (`cohost.thread/core-impls`, `core-macros`):** promise,
  deliver, future-call, future (macro), future-done?, future-cancel,
  future-cancelled?, future?, agent, send, send-off, send-via,
  set-agent-send-executor!, set-agent-send-off-executor!,
  release-pending-sends, agent-error, restart-agent, set-error-handler!,
  error-handler, set-error-mode!, error-mode, agent-errors,
  clear-agent-errors, shutdown-agents, await, await-for, await1, pmap,
  pcalls, pvalues (macro). Boot interns these for names the host core
  did not supply (wiring in progress — see below).

## Verified so far

- Layer probe, host-side JVM seat (`probe_thread.clj`, run with
  `clojure -Sdeps '{:paths ["src"]}' -M probe_thread.clj`):
  **29 / 29 PASS** — leaves, spawn/join/current-thread identity, latch
  (open + timeout), future value with a conveyed dynamic binding,
  future error rethrow, deref timeout, cancel pending/done, promise
  deliver + timeout + first-wins, agent order, send-off, nested send
  held and released, :continue, :fail + send-throws + restart, pmap,
  pcalls.

## In progress

- Shim wiring: new-Thread / Thread-currentThread / new-CountDownLatch /
  Executors statics fall back to the layer when the host leaves them
  unsupported; method vars -countDown / -await / -getCount / -submit /
  -execute / -shutdown and layer branches in -start / -join / -get /
  -deref / -isDone / -cancel / -isCancelled.
- Boot fill: `install-thread-layer!` after the companions step, plus a
  `:host-lacks` boot opt so a host WITH a core threading surface can be
  booted as if without (the exercise path on the JVM seat).
- Verification harness in the booted context + `bb boot:jvm` battery
  regression check (standing: 221/221).

## Named distances (unit 1)

- Layer values are tagged maps: equality is map equality, not object
  identity (same idiom as the shim's own stand-ins).
- Pooled executor bounded at 8 workers; stock's is unbounded.
- future-cancel does not interrupt a running task.
- Thread interrupt has no leaf yet, so Thread.interrupt is a no-op
  through the layer.
- `seque` stays host-only-skipped (it is not in the fill list).
