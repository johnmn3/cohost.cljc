# Eve-in-cohost, unit 1 — REPORT: Eve's thread layer behind the Java shim

Branch `muse/eve-cohost-impl` off `muse/debug-seam` @ 2563eee (the
cohost 0.9.3 prep line). Seat: JVM, with the portable shape kept — the
layer is one implementation in `cohost.thread`; only its leaves are
per-host.

## What was built

**Adapter leaves** (`cohost.adapter` contract + defaults, JVM impls in
`cohost.hosts.jvm`): `:thread-spawn`, `:thread-self`, `:doorbell`,
`:doorbell-wait`, `:doorbell-signal` — nil on hosts that cannot supply
them. JVM seat: a real daemon `java.lang.Thread` per spawn; a doorbell
is an atom used as its own monitor with sticky signal semantics;
`:thread-bindings` reads `Var/getThreadBindings` for conveyance
capture.

**The layer** (`src/cohost/thread.cljc`, new): registry, executors,
futures, promises, CountDownLatch, agents, pmap/pcalls/pvalues. All
state lives in atoms (one registry atom; per-value state, queue and
control atoms); values cross a thread only by deref or swap. No
journals, no liveness scans, no counters outside atoms, no arenas.

- Executors: work queue atom + control atom + a *generation doorbell*
  (swapped fresh at every signal, so a worker never misses work
  enqueued between its claim check and its wait). `send` pool: 8
  workers; `send-off`: 1 worker (serial, as stock's solo executor).
- Conveyance (the cljs.thread shape, transparent under the hood): a
  future-call or agent action captures the caller's binding frame
  through the adapter's `:thread-bindings`; the worker restores it
  through `:with-thread-bindings!`. Agent actions additionally run
  with `*agent*` bound to the agent (the handle is the host core's
  referred `*agent*` var).
- Agents: per-agent queue drained by at most one worker; actions
  carry their executor, with drain hand-off between executors; sends
  made inside an action are held in `*pending-sends*` and released
  when the action ends (stock's nested sends); `:continue` records
  errors and proceeds, `:fail` stops the agent until `restart-agent`;
  validators enforced; `await`/`await-for` ride a latch action.

**Shim entries** (`cohost.shim`): `new-Thread`, `new-CountDownLatch`,
`Executors-newFixedThreadPool` / `-newCachedThreadPool` /
`-newSingleThreadExecutor` dispatch to the layer when it is available
and throw the previous unsupported error when it is not;
`Thread-currentThread` returns the layer's per-thread record;
`Agent-shutdown` / `Agent-releasePendingSends` call the layer.
Methods: `-start`, `-get`, `-deref` gained layer branches;
`-countDown`, `-await`, `-getCount`, `-submit`, `-execute`,
`-shutdown`, `-join`, `-isAlive`, `-interrupt`, `-cancel` are new;
`-isDone`, `-isCancelled`, `-isRealized` answer for layer futures.
The `IDeref` / `IBlockingDeref` / `IPending` instance predicates
recognize layer futures, promises and agents — stock `deref` branches
on those, and routes what fails them to core's `deref-future`, whose
`.get` the shim now serves through `cohost.thread/get-future`
(timed form throws `TimeoutException`, as `Future.get` does).

**Core fill** (`cohost.boot/install-thread-layer!`,
`cohost.policy/thread-names`): after the companions, boot interns
`cohost.thread/core-impls` and `core-macros` under every concurrency
name the host's core left uninterned — future, future-call,
future-done?, future-cancel, future-cancelled?, future?, pmap,
pcalls, pvalues, promise, deliver, agent, send, send-off, send-via,
set-agent-send-executor!, set-agent-send-off-executor!,
release-pending-sends, agent-error, restart-agent,
set-error-handler!, error-handler, set-error-mode!, error-mode,
agent-errors, clear-agent-errors, shutdown-agents, await, await-for,
await1 — then re-runs the macro scan so `future`/`pvalues`
expansions are codemod-wrapped like any booted macro. A new boot
opt, `:host-lacks`, boots names as if the host lacked them: the
exercise path used for verification here, and the bring-up tool for
the next seat. `realized?` gained a policy replacement (stock's bare
`^IPending` cast survives no stand-in value; the shim's `-isRealized`
answers by predicate).

## Numbers, as printed

- Layer probe, host-side JVM seat (`probe_thread.clj`):
  **29 / 29 passed** — leaves, spawn/join/current-thread identity,
  latch open + timeout, future value with a conveyed dynamic binding,
  error rethrow, deref timeout, cancel pending/done, promise
  deliver/timeout/first-wins, agent order, send-off, nested send held
  and released, `:continue`, `:fail` + send-throws + restart, pmap,
  pcalls.
- Booted-context check (`eve_cohost_check.clj`; boot with
  `policy/thread-names` host-lacked, programs in the booted user ns):
  **21 / 21 passed** — future deref, future?/future-done?, binding
  conveyance (42), error rethrown as ArithmeticException,
  future-cancel/cancelled?, pmap, pvalues, pcalls, promise deliver
  via future, promise deref timeout, agent send + await (11),
  `*agent*` inside an action, send-off + await-for, `:continue`
  errors [2 1], send-via a shim executor, CountDownLatch
  [true 0], Thread start/join/isAlive [5 false],
  Thread/currentThread stable, Executors submit/get/isDone
  [99 true], realized?, shutdown-agents. Boot line for that run:
  821 forms, booted as stock 495, from host 172, natives 66,
  skipped 88, errors 0.
- Standing battery, normal JVM boot (`bb boot:jvm`):
  **battery: 221/221 stock behaviors pass; libraries: 62/62 cases
  pass across 12 clojure.* libraries; boot errors 0** — no
  regression; on this boot the concurrency names come from the host
  and the fill does not fire.
- Jank AOT gate (`bb boot:jank` on J, j.slaa.us; the local box
  killed the -O3 compile under contention twice): the snapshot
  AOT-compiles with `cohost.thread` and the new shim code in it —
  **boot errors 0; battery: 219/221; libraries: 62/62**. The 2 FAIL
  rows are the standing jank-only pair, verbatim: `subs count nth
  first last butlast` ("ERR Unterminated vector.") and `Character
  outside ASCII` ("ERR Unexpected end of file."). One analyzer
  lesson banked on the way: jank cannot resolve a reader-conditional
  catch class inside a macro-expanded form, so the layer's
  catch-everything sites go through `cohost.adapter/try-all`.

## Named distances (unit 1 remainders)

- Layer values are tagged maps: equality is map equality, not object
  identity (the shim's own stand-in idiom).
- The pooled executor is bounded (8 workers); stock's agent pooled
  executor is an unbounded cached pool. The queue is unbounded, so
  work is never refused — only concurrency is capped.
- `future-cancel` does not interrupt a running task; `Thread.interrupt`
  is accepted and ignored (no interrupt leaf yet).
- `Future.get`'s timed form reads its first argument as milliseconds;
  the TimeUnit argument is not modeled.
- `seque` remains host-only and unfilled.
- Thread name access (`.getName`/`.setName`) is not layered; the
  shim's `-getName` is multimethod machinery and was left alone.
- Other seats (jank first) need their `:thread-spawn` / `:doorbell`
  leaves; the layer above them is written host-neutral CLJC and is
  expected to run unchanged — that is unit 2's verification, not
  claimed here.
