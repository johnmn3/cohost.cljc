# STATUS — F-1 AGENT-DRAIN-JANK fix lane (Lane M)

Operator order (John Newman, 2026-11-06 9:41 AM ET): fix the defect
UNDERNEATH the STM on the jank seat — agent sends never drain
(F-1, filed by the STM jank chase, FINAL in STATUS-jank-chase.md).

Branch: `muse/agent-drain-jank-fix` off `origin/muse/stm-jank-probe`
@ 9afbb3b. Worktree: ~/workspace/wt/agent-drain-fix.
Acceptance test in miniature: dev/drain/diag2.jank (copied from the
chase's banked repro).

## Milestone 1 — 2026-11-06 ~9:45 AM ET — setup + firsthand code read

Read firsthand: STATUS-jank-chase.md FINAL (F-1 evidence), diag2.jank,
the executor in src/cohost/thread.cljc (new-executor / ensure-workers! /
signal-workers! / run-worker / submit!, lines 266-335), claim! (86ff),
and the jank seat's doorbell: a C++ sticky flag + condition_variable
keyed by integer id in a lazily-built registry
(src/cohost/hosts/jank_host.jank, namespace cohost_bell ~line 388);
caps at jank_host.jank:2831-2833.

Static read: the doorbell C++ is sound (sticky signal, timed wait_for
with predicate). The worker loop ignores doorbell-wait's return and
re-claims every <=100 ms regardless, so a merely-broken wait cannot
starve the drain by itself. Live suspects, in order:
(1) worker never actually spawns from ensure-workers! on this seat;
(2) worker spawns but dies silently on its first claim!/loop pass
    (run-worker's try has only a finally — an exception in claim! or
    the ctl deref kills the worker with no record, :running dec'd);
(3) claim! misbehaves on jank (swap! fn side-effect `got` atom).
Futures STM thread sections pass because they use spawn directly,
never the long-lived executor worker — consistent with all three.

Next: reproduce diag2 as baseline, then bisect with small probes
(doorbell roundtrip; raw executor submit + ctl/queue inspection;
claim! alone).

## Milestone 2 — 2026-11-06 ~9:55 AM ET — DIAGNOSIS (measured end to end)

Baseline reproduced firsthand: diag2 → "plain send NEVER landed
within 8s; state: []" (+ known F-2 teardown segfault).

Bisect probes (dev/drain/probe1-5.jank, all run under pty):
- probe1: the doorbell is innocent — signal wakes a parked waiter
  (true), unsignalled timed wait returns false. The RAW executor is
  innocent too: submit! to a fresh solo executor ran the item
  immediately (flag :ran, queue drained, worker alive).
- probe2: after a plain send, the agent's queue is EMPTY (the action
  was claimed), :queued is stuck TRUE, :errors is EMPTY, state
  unchanged. So drain claimed the action and died before running it —
  silently (the worker's try-all eats the throw; agent-failed! is
  never reached, so nothing is recorded anywhere).
- probe3: synchronous calls of drain's unguarded pieces —
  adapter/resolve-core => nil (fine); capture-bindings fine;
  **adapter/interned-var THROWS**; agent-var-handle THROWS (inherits);
  a manual run-action THROWS the same way.
- probe4: the throw is jank's ns-interns error: "The provided value
  was not resolvable as a namespace." {:value cohost.core}.
- probe5: find-ns answers nil for an unloaded ns (no throw);
  ns-interns works for loaded namespaces; a find-ns-guarded lookup
  answers nil / the var correctly in both cases.

**Mechanism:** cohost.thread's run-action calls agent-var-handle,
which calls host/interned-var BEFORE its try-all (thread.cljc:629ff).
The cap contract (adapter.cljc:47) says :interned-var answers the
handle "or nil". The DEFAULT (adapter.cljc:350) goes through the
default :ns-interns = plain (ns-interns ns-sym), which on jank
THROWS when the namespace is not resolvable in the process (here
cohost.core, unbooted in the probe processes). The JVM seat carries
a nil-safe :interned-var override (hosts/jvm.clj:36, find-ns
guarded); the glj seat received the identical fix during the Go
chase (registry-miss answers {}/nil, "the caps' stated contract").
The jank seat never got one — it defines neither :interned-var nor
:ns-interns, so the throwing default is what runs. Every agent
action on jank dies in run-action; drain's :queued sticks true; no
send ever lands, in or out of a transaction. The STM, the executor,
the doorbell and claim! are all exonerated by direct measurement.

**Fix (seat-scoped, jank_host.jank, beside :resolve-core):** add
:interned-var and :ns-interns caps that guard with find-ns and
answer nil / {} when the namespace is not resolvable — the cap
contract, mirroring the JVM and glj seats. No shared code touched.

## Milestone 3 — 2026-11-06 ~10:05 AM ET — FIX IN; acceptance (1) MET

Fix in src/cohost/hosts/jank_host.jank (beside :resolve-core):
:interned-var and :ns-interns caps guarded by find-ns, answering
nil / {} when the namespace is not resolvable — the cap contract.
Seat-scoped only; no shared code touched.

diag2.jank now prints: "DIAG2 plain send landed after(ms):
4534.02..." — the send drains. (The ms includes the worker's
first-action JIT of the drain path; the poll grain is 250 ms.)
The F-2 teardown segfault still follows, as documented — separate
mechanism (runtime shutdown with live threads), not this defect:
the drain failure is fully explained and closed by the cap throw.
