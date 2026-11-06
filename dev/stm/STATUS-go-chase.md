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
