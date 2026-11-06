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
