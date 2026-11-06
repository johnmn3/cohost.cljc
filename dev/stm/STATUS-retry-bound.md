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
