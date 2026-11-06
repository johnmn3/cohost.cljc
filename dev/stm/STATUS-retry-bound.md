# STATUS — STM retry-bound lane (Lane M)

Operator decision (2026-11-06 9:44 AM ET), filed in REPORTS.md on
muse/cohost-reports ("DECISION RECORDED — STM unbounded-retry
disposition", 9:45 AM ET): disposition (1) of the STM lane's banked
STATUS 14 item is ADOPTED with stock Clojure's scoping; disposition
(2) (re-base the read) is REJECTED. This lane makes it so.

Branch: `muse/stm-retry-bound` off `origin/muse/eve-cohost-stm-agents`
@ 228e0c1. Worktree: ~/workspace/wt/stm-retry.

## The ruled semantics (implement exactly)

- Contract, stated in cohost.tx docs: an STM-managed ref's cell is
  written only through transactions (and layer-controlled agent
  sends); state needing uncoordinated writers is a plain Eve atom.
- Ordinary tx-vs-tx contention retries stay UNBOUNDED — no global
  attempt cap on the normal retry loop.
- Impossible progress faults, bounded: when a transaction cannot
  obtain/keep a consistent basis — its read point trimmed from a
  ref's history (::too-short) via movement the layer's commit path
  did not produce, or validation failing on such movement — it
  retries a bounded number of times, then THROWS an
  IllegalStateException-shaped exception in stock's words
  ("Transaction failed after reaching retry limit"). No re-basing.

## Milestone 0 — 2026-11-06 ~9:50 AM ET — pickup + diagnosis

Read: STM-AGENTS-REPORT.md STATUS 14 (the fault manufacture and the
two dispositions), src/cohost/tx.cljc (532 lines), the filed decision.

Diagnosis, from the code: the layer's commit path is the ONLY legal
writer of a cell, and it always installs at a commit point it claimed
from the layer clock (`commit!` step 4: cp = (swap! commit-point inc),
then `install!` writes :point cp). The clock never decreases, so a
layer-produced cell ALWAYS satisfies (:point cell) <= @commit-point.
A cell with (:point cell) > @commit-point is therefore movement the
commit path did not produce — a precise, checkable signature of the
out-of-band writer STATUS 14 manufactures (cell bumped beyond the
clock over history that does not reach back).

Where the spin lives today:
- `touch!`: value-at = ::too-short → throw retry-ex → the runner
  recurs identically forever WHEN the ::too-short carries the
  out-of-band signature. (::too-short with (:point cell) <= clock is
  the legal history-trim case: a fresh attempt's read point advances
  to the clock and is served — self-healing, stays a plain retry.)
- `commit!` validation: base-point ≠ current point → retry-ex. If
  the current point carries the out-of-band signature, the retry is
  equally hopeless.
- `install!` CAS give-up (100 failures) → retry-ex; same signature
  check applies to the last cell seen.

Plan: a distinct basis-fault signal (ex-info marked
:cohost.tx/basis-fault) thrown at those three sites when the
signature holds; the runner counts basis faults per
run-in-transaction invocation (contention retries neither increment
nor reset the count); at the named bound — `basis-fault-limit`,
stock's RETRY_LIMIT spirit (10,000) — the runner throws
(illegal-state "Transaction failed after reaching retry limit")
instead of recurring. Contract text goes in the tx ns docstring and
the STM doc.

Acceptance: (1) probe_tx 23/23 unmoved on the JVM seat;
(2) new probe dev/stm/probe_tx_retry_bound.clj — the STATUS 14
manufacture (cell bumped beyond the clock, history unreachable)
faults with the exception in bounded time, printing exception +
elapsed + attempts (pre-fix non-return is banked firsthand evidence
in STATUS 14, incl. on the JVM seat — cited, not re-run: no spinning
processes); (3) contention control in the same probe — two honest
transactions in the transfer shape complete.

## Milestone 1 — 2026-11-06 ~10:0x AM ET — implementation + probes, ALL GREEN

Implemented in src/cohost/tx.cljc (committed):
- The single-writer contract stated in the ns docstring (cell written
  only through transactions and layer-released sends; uncoordinated
  writers belong on a plain Eve atom — stock's type-level choice),
  and in run-in-transaction's docstring.
- `beyond-clock?`: the out-of-band signature — (:point cell) >
  @commit-point, unreachable by the layer's commit path.
- A distinct basis-fault signal (ex-info, :cohost.tx/basis-fault),
  thrown INSTEAD of the retry signal at the three failure sites when
  the signature holds: touch! (::too-short), commit! validation,
  install! CAS give-up. ::too-short / validation failures WITHOUT
  the signature keep the plain retry signal (the legal history-trim
  case still self-heals on a fresh read point).
- The runner counts basis faults per invocation (contention retries
  neither increment nor reset the count); at `basis-fault-limit`
  (10000 — stock's RETRY_LIMIT number, scope deliberately narrower)
  it throws (illegal-state "Transaction failed after reaching
  retry limit"). No re-basing anywhere.

Probe results, JVM seat, as printed:
- Baseline (pre-change) probe_tx: ALL 23 CHECKS PASS.
- dev/stm/probe_tx_retry_bound.clj: ALL 8 CHECKS PASS —
  contention control: 2000 transfers landed [8000 12000], invariant
  20000, with 3,896 attempts (retries included) and NO fault;
  the poisoned cell (point = Long/MAX_VALUE, history emptied)
  faulted with IllegalStateException "Transaction failed after
  reaching retry limit" after exactly 10,001 attempts in ~129 ms
  (pre-fix: never returns, STATUS 14's banked JVM observation);
  the poisoned cell stood unmoved afterwards (no re-basing);
  an honest transaction committed after the fault.
- probe_tx post-change: ALL 23 CHECKS PASS, unmoved.

Named, not run here: probe_tx over Eve domain cells
(dev/stm/run-probe-tx-eve.clj) needs Eve's sources on the classpath,
not assembled in this worktree; the change is cell-kind-agnostic
(the signature reads the cell through cell-deref for both kinds).
Note for the STM line: dev/stm/probe_throw_jolt.clj's deep-conflict
manufacture IS the out-of-band shape (a cell bumped past the clock
without claiming a point); post-fix, its first section faults with
the retry-limit exception instead of hanging — the disposition
working, recorded here rather than by editing that lane's evidence
probe.

## FINAL — 2026-11-06 — lane complete

Disposition (1) is implemented as ruled: contract stated, contention
retries unbounded, impossible progress bounded and faulting with
stock's exception, no re-basing. Acceptance: (1) probe_tx 23/23
unmoved ✓; (2) the out-of-band manufacture faults in bounded time
with exception + elapsed + attempt count printed ✓ (pre-fix spin
cited from STATUS 14's firsthand JVM observation, not re-run);
(3) the transfer-shape contention control completes ✓.
