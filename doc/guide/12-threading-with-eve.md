# Threading with Eve

*2026-11-05. The implementation record is `doc/eve-cohost/` — `MEMORY-PLAN.md`
and the per-unit reports (`UNIT1-REPORT.md`, `UNIT2-REPORT.md`,
`CLJS-DEP-REPORT.md`, `unit8/REPORT.md`). Every claim below is from those
reports or from a gate they print; per-seat status is collected in
[Eve coverage and status](15-eve-coverage-and-status.md).*

Some of cohost's runtimes have no concurrency family of their own: jank's core
carries `future`, `agent` and `pmap` only as "TODO: port" stubs, and
ClojureScript has no threads at all. On those seats cohost fills the whole
family from one portable layer, `cohost.thread`, whose state lives in Eve
atoms and whose per-host leaves are supplied by the seat's adapter. The
program a user writes is stock Clojure throughout — the fill interns the
ordinary `clojure.core` names, so library code that uses futures or agents
loads and runs unchanged.

## What is filled

The boot interns the layer under exactly these names (the set is
`cohost.policy/thread-names`):

- **Futures**: `future`, `future-call`, `future-done?`, `future-cancel`,
  `future-cancelled?`, `future?`
- **Promises**: `promise`, `deliver`
- **Agents**: `agent`, `send`, `send-off`, `send-via`, `await`, `await-for`,
  `await1`, `agent-error`, `agent-errors`, `clear-agent-errors`,
  `restart-agent`, `set-error-handler!`, `error-handler`, `set-error-mode!`,
  `error-mode`, `set-agent-send-executor!`, `set-agent-send-off-executor!`,
  `release-pending-sends`, `shutdown-agents`
- **Parallel calls**: `pmap`, `pcalls`, `pvalues`

The Java-shaped forms stock programs use are served by the same layer through
the shim: `Thread` (start, join, `isAlive`, `currentThread`), `CountDownLatch`,
and the `Executors` factories (`newFixedThreadPool`, `newCachedThreadPool`,
`newSingleThreadExecutor`) with `submit`/`execute`/`shutdown`.

`seque` is not filled: it stays host-only, and a host that lacks it still
lacks it.

## How it behaves

The semantics are stock's, because the programs are stock's:

- **Binding conveyance.** A `future` or agent action captures the caller's
  binding frame and runs with it:
  `(binding [*x* 42] @(future *x*))` answers `42` on every seat where the
  fill is in force. Agent actions additionally run with `*agent*` bound to
  the agent.
- **Errors.** A future's exception is rethrown at `deref`, as stock's is; a
  timed `deref` on an unfinished future behaves as stock's does.
- **Agents.** Sends made inside an action are held and released when the
  action ends (stock's nested sends). `:continue` records the error and
  proceeds; `:fail` stops the agent until `restart-agent`. Validators are
  enforced. `await` and `await-for` wait for the actions sent from the
  current thread.
- **Executors.** The `send` pool has 8 workers; `send-off` has 1, serial, as
  stock's solo executor. The queue is unbounded, so work is never refused —
  only concurrency is capped (stock's pooled executor is an unbounded cached
  pool; that is a real difference, named in unit 1's report).
- **Cancellation.** `future-cancel` cancels a pending future; it does not
  interrupt a task already running. The timed form of `Future.get` reads its
  argument as milliseconds; the `TimeUnit` argument is not modeled.

Underneath, all layer state lives in atoms — a registry atom, and per-value
state, queue and control atoms — and a value crosses a thread only by `deref`
or `swap!`. There is no other channel.

## The mesh: a core thread first

On hosts with real threads the layer rides them directly: on the JVM seat a
spawn is a real daemon `java.lang.Thread`; on jank it is jank's own future
engine, with a C++ doorbell registry (mutex, condition variable, sticky flag)
per wait point.

ClojureScript has no threads, so the mesh is explicit there, and it stands up
the same way everywhere it exists: **a core thread comes up first and
controls the other threads from itself**, and once it is up it has
synchronous access over the rest — `deref`, `join` and `await` complete
synchronously from the controlling flow. On Node, the other threads are
worker realms: separate V8 isolates booted from the same image as the core.
What crosses to a worker is described in [Memory with Eve
atoms](13-memory-with-eve-atoms.md#the-shared-heap-on-clojurescript): the
work travels as a form, and its captured values and binding frame travel
through the shared Eve atom.

Boot parity is part of the contract: a program boots with the same thread
facilities from the REPL and from the CLI. This has been measured, not
assumed — the same probe program run through the REPL-side bundle and the
CLI bundle gives a case-for-case identical result vector, and the jank
seat's AOT snapshot replays the fill at boot (an early snapshot build
silently dropped it; the probe caught that, and unit 2's report records the
fix).

## The probe program

`dev/eve_probe_program.cljc` is the seat-crossing check the units gated on.
Its threading cases, with the answers every Eve seat gives:

```clojure
@(future (+ 1 2))                                    ;=> 3
(let [x 41] @(future (inc x)))                       ;=> 42
(let [a (atom 0)] @(future (swap! a inc)) @a)        ;=> 1
(binding [*print-length* 3] @(future *print-length*)) ;=> 3
(let [p (promise)] (deliver p 7) @p)                 ;=> 7
(let [ag (agent 0)] (send ag inc) (await ag) @ag)    ;=> 1
(doall (pmap inc [1 2 3]))                           ;=> (2 3 4)
```

## Per seat

- **JVM.** The fill fires for a host whose core lacks the names; a plain JVM
  boot takes its concurrency from the host, and the fill is exercised
  through the boot option that boots the names as host-lacked (`:host-lacks`
  in `cohost.boot/boot!`). Exercised that way: layer probe 29/29, booted
  programs 21/21 (unit 1's report).
- **jank.** The fill *is* the seat's threading — the adapter declares the
  whole family core-lacked, and the AOT snapshot carries the layer.
  Battery 219/221 with the standing named pair of failures, libraries
  62/62 (unit 2's report).
- **ClojureScript (Node).** Eve is a dependency of the seat's build, and
  the layer runs over Eve's thread facilities. Futures whose work can
  travel run in worker realms; futures whose captures cannot cross (a
  plain closure, a host object) run in the core realm instead, and the
  layer counts them — in the gate runs the count was 0. Promises and
  delays need no thread. Agents stay in the core realm: their values are
  the JVM's, but their actions do not cross to a worker. Probe row after
  unit 8: `ok=23` — the row matches the JVM's values on 22 of 23 cases;
  the one exception is `test-stacktrace`, which counts a JVM stack's
  frames and has no honest ClojureScript answer (the seat answers an
  empty trace). That case awaits the operator's ruling and is named in
  unit 8's report.
- **Other seats** (basilisp, jolt, ClojureWasm, Phel, let-go) have no Eve
  thread leaves; their concurrency is their host's, unchanged by this
  work.

The browser build of the ClojureScript seat is unchanged: no Eve in it.
