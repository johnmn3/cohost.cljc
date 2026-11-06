# STM Go chase lane — goroutine-shaped thread leaves for the glj seat

Branch `muse/stm-go-threads` off `muse/eve-cohost-stm-agents`
@ 228e0c1. Operator (2026-11-06): the Go and jank stops "don't
seem like real blockers" — chase them down. This lane is the Go
half: STATUS 16 stopped probe_tx at its thread sections because
glojure has no java.lang.Thread/Runnable class surface. The
operator's point stands: goroutines + channels ARE a thread
surface; what was missing is leaves in that shape, not a JVM API.

## STATUS 1 — 2026-11-06 ~9:2x AM ET — pickup; the seat's real concurrency surface, measured

Read first: STATUS 15–18 (STM-AGENTS-REPORT.md), hosts/glj.clj,
probe_tx_glj.clj, probe_tx_glj_core.clj (6/6 pre-thread prefix),
cohost.thread's leaf contract (adapter caps :thread-spawn,
:thread-self, :doorbell, :doorbell-wait, :doorbell-signal — the
layer needs nothing else host-side; join and the cooperative
interrupt are layer code over those leaves).

Facilities measured on the shipped glj-eve binary (glojure
v0.7.22 + Eve's completion patches; scratch probes under
~/workspace/stm-go-chase/scratch/, logs to follow in this lane's
commits):

- `future` / `future-call` RUN and are genuinely concurrent:
  a promise delivered from a future is received by the main
  goroutine; `pmap` works. glojure's future-call is
  `(github.com:glojurelang:glojure:pkg:lang.AgentSubmit f)`
  (clojure/core.glj:6864) — Go: `go func(){ res = fn.Invoke();
  close(done) }()` (pkg/lang/agent.go). **That host call is the
  spawn leaf**: it runs a thunk on a fresh goroutine and
  returns the future handle.
- `Thread/sleep` and `Thread/currentThread` resolve (javacompat
  statics), but `(Thread. f)` throws ("new thread.Thread with
  args unsupported") and Runnable does not resolve — STATUS 16's
  measurement stands for the class surface.
  `Thread/currentThread` returns a FRESH object per call
  (pkg/javacompat/thread: `return &Thread{}`), so it cannot key
  cohost.thread's per-thread registry — unusable as :thread-self.
- **Goroutine identity exists and is Eve's own provision**:
  `(eveglj:host.GoroutineID)` — the eveglj/host Go package in
  glj-eve (Eve's goroutine-id.patch to glojure's lang), the same
  call eve.process/thread-id uses on this seat. Measured:
  main goroutine id 1, stable across calls; spawned goroutines
  24, 25 (distinct); stable inside one goroutine across sleeps.
  **That is the :thread-self leaf** — an int64 handle, exactly
  what the registry wants.
- **Doorbell**: a glojure promise is a channel-backed blocking
  reference (deref parks until deliver; deref-with-timeout is a
  select against a timer in the runtime). Measured: timed wait
  leaves it unrealized, deliver realizes it, realized stays
  realized (sticky), second deliver is a no-op. doorbell-wait =
  deref-with-timeout then (realized? db) — NOTE: glojure's timed
  deref returns its own :clojure.core/timeout rather than the
  caller's timeout value, so the leaf must answer (realized? db),
  never the deref value (measured, leaf_probe.clj).
  Raw Go channels also exist ((go/make (go/chan-of T) n),
  go/recv, go/send) but typed sends fight Clojure's int64
  (reflect assignability) — not needed; the promise IS the
  channel receive with a timeout case, in the runtime's own
  implementation.
- Sleep: `Thread/sleep` works from any goroutine and does not
  block others (measured: main proceeded at 10 ms while a
  spawned goroutine slept 300 ms). User/layer code that sleeps
  by that name is served; the layer itself needs no sleep leaf.
- `(agent 0)` on the raw runtime throws (host Agent unbuilt) —
  irrelevant here: cohost's agents are the layer's own
  (cohost.thread), which is what probe_tx exercises.

Design (next step): replace glj.clj's JVM-shaped thread leaves
(which cannot run: Thread. throws at call time) with
goroutine-shaped ones — spawn = AgentSubmit, self =
eveglj:host.GoroutineID, doorbell = promise triple. The layer
above does not change: join is layer code over bell+state, and
the cooperative interrupt (cancel flag + bell, checked in the
layer's sliced waits and once per tx attempt) rides the same
leaves. The one honest difference to name when it lands: no
preemption (Go cannot kill a goroutine) — which is the STM
design's standing contract on every seat, JVM included.

## STATUS 2 — 2026-11-06 ~9:4x AM ET — leaves BUILT; probe_tx ALL 23 PASS on glojure; one seat cap gap found and fixed on the way

**The leaves** (src/cohost/hosts/glj.clj, replacing the JVM
shapes that could not run — (Thread. f) throws on this runtime):
- :thread-spawn = lang.AgentSubmit (the call clojure.core's own
  future-call makes; a fresh goroutine per thunk).
- :thread-self = (eveglj:host.GoroutineID) — an int64, stable
  per goroutine, distinct across them. Thread/currentThread was
  measured unusable: the runtime's javacompat returns a fresh
  object per call, which would mint a new layer thread record
  (and a new cancel flag) at every lookup.
- :doorbell = a promise; :doorbell-wait = deref-with-timeout,
  answering (realized? db) (glojure's timed deref returns its
  own :clojure.core/timeout, not the caller's timeout value —
  measured); :doorbell-signal = deliver. Sticky, channel-backed
  in the runtime — the channel receive with a timeout case.
cohost.thread and cohost.tx are untouched: join, executors,
agents and the cooperative interrupt are layer code and ride
these leaves as they ride every seat's.

**Two vehicle/seat findings on the way:**
1. probe_tx_glj.clj installed glj BEFORE probe_tx.clj's
   (require 'cohost.hosts.auto), whose :clj arm loads
   cohost.hosts.jvm — and jvm.clj installs at namespace load,
   clobbering glj. STATUS 16's thread stop was therefore the
   JVM adapter's Runnable resolution, reached through the
   wrong adapter (its core prefix artifact never required
   auto, so its 6/6 genuinely ran under glj). The vehicle now
   loads auto first, installs glj over it, then loads the
   probe (auto's require is a no-op the second time). Wiring
   a :glj arm into auto remains mainline's call (STATUS 16).
2. **Agent sends never fired** — drained silently. Diagnosis
   (bisect probes dev/stm/bisect*.clj): drain claimed the
   action and died in run-action at (agent-var-handle):
   glojure's ns-interns/ns-publics consult a registry only
   stdlib namespaces are in — for a source-loaded namespace
   (cohost.core) they THROW "No namespace: cohost.core found"
   although find-ns sees it and its code runs. The default
   :interned-var cap throws instead of answering nil, so the
   layer's designed fallback (resolve-core '*agent* — which
   works: clojure.core/*agent* resolves, binds, reads) was
   never reached, and run-worker's try-all swallowed the
   throw. Fix, in the seat adapter where seat quirks belong:
   glj.clj gains :ns-interns / :interned-var entries that
   answer {}/nil when the runtime's registry has no entry —
   the caps' stated contract. No layer code changed.

**Runs (logs in dev/stm/logs/):**
- probe_tx on glojure, FULL: **PROBE-TX: ALL 23 CHECKS PASS**
  (probe-tx-glj-go-chase.log), exit 0. Thread sections
  verbatim: snapshot read point stable; skew bare vs ensure;
  8 threads x 200 commutes = 1600, none lost; transfer
  invariant 20000 with 2441 attempts for 2000 transactions
  (retries real and scoped). Sends: held in tx, fired once
  on commit, never on abort. Interrupt sections:
  "interrupt wakes a blocked latch wait with the interrupt
  exception" PASS; "interrupted tx thread abandons" PASS;
  "abandoned tx wrote nothing" PASS.
- probe_tx_glj_core (regression): 6 checks, 0 failures.
- probe_interrupt_glj (new, dev/stm/probe_interrupt_glj.clj):
  ALL 4 PASS — a polling compute goroutine stops at its next
  poll after interrupt-thread!; a NON-polling compute
  goroutine (3M iterations) runs to completion with the flag
  set and unobserved. That is the cooperative contract
  stated plainly: no preemption on this seat, exactly the
  JVM's distance; Go cannot kill a goroutine and the layer
  does not pretend otherwise.

Remaining: the Eve hosting joint on this seat (domains probe
over Eve domains on glojure) — next step.

## STATUS 3 — 2026-11-06 ~9:5x AM ET — Eve hosting joint: domains probe attempts 1–2 fail in Eve's completion load; diagnosis banked before attempt 3

probe_tx_domains on glojure needs eve.atom loadable in the
same process as cohost. Vehicle: dev/stm/probe_tx_domains_glj
.clj; Eve classpath = the 0.9.3 tree's hosts.edn :glj roots
(the roots Eve's own test:glj composes), logs
probe-tx-domains-glj.log / -glj2.log.

- Attempt 1 (no Eve preamble): loading eve.atom dies in
  eve.guard's load — nil-pointer at eve/host_completion.glj
  :132 `(core a f)`, called from :95 (the completion's
  protocol-registry swap!) during LazyMap's extend.
- Attempt 2 (require eve.host-completion before eve.atom,
  but AFTER cohost.hosts.auto/glj): identical fault, same
  two lines.
- Diagnosis: Eve's runner docstring states the contract --
  "eve.host-completion loads first: a namespace refers the
  names the completion adds to clojure.core only when it is
  made after them." Attempt 2 did not meet it: cohost's
  namespaces loaded (and cohost.adapter's install! ran
  swap!) before the completion's alter-var-root wrappers
  captured clojure.core's roots. What changes in attempt 3:
  eve.host-completion is the FIRST require in the process,
  before cohost.hosts.auto — the exact order Eve's own glj
  runner uses. If attempt 3 fails at the same lines, the
  joint is named: Eve's glj completion cannot load in a
  process that also loads cohost's layer in the probe's
  order, and the fix belongs to the cohost-boot/Eve-
  completion load-order question (mainline), not the
  thread leaves — which are already proven by probe_tx.
