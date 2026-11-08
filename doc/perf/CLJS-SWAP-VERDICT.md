# Verdict — the CLJS `swap! an atom` anomalous cell (owed to PERF.md)

From: cohost-jvm-speed lane, 2026-11-08. For: the perf line, to lift
into `doc/PERF.md` §"Refresh — 2026-11-08" at its next bank. That
section flags the cell ⚠ *"Anomalous cell — do not cite: CLJS `swap!
an atom` at 11,563 ms median (own-core ×1056.5) is not a plausible
steady state … a calm-window re-run of that cell is owed."* The re-run
has run and banked; this is its verdict.

## The calm re-run

Job `20261108T191202-jobagent-cohost-cljs-calm` (job agent → J),
first run EXIT=0, 19:12:13→19:15:45 UTC, load window calm (bench
started at load1 1.10; the run's own 183 s of work heated its tail, as
its log records). Deliverable banked on `lane-m/cohost-perf-refresh`:
`doc/perf/refresh-2026-11-08/bench-cljs-grok.edn` (+ `.log`).

The cell, n = 100000 swaps, values equal (`:same? true`):

| side | median | min | runs |
|---|---:|---:|---:|
| CLJS own core (`:host`) | 10.51 ms | 10.10 | 95 |
| stock core booted through cohost (`:stock`) | **5,164.24 ms** | 4,487.77 | 3 |

## Verdict

**The anomaly is real, and the hot-window figure overstated it
~2.2×.** The booted core's `swap!` on CLJS is genuinely pathological:
5,164 ms calm median against the own core's 10.51 ms — a ×491 gap at
calm medians (the bench's own summary line: `atom-swap host 10.51
stock 5164.24 x491.33`). It is *not* a hot-window artifact and not a
noise cell: the own-core side is sane in both windows (10.9 hot,
10.51 calm), the booted side is seconds-scale in both (11,563 hot,
5,164 calm), and the case's values check equal.

So the ⚠ text should be replaced, not just lifted: the cell is a
confirmed defect-shaped result for the booted core's CLJS atom path
(~52 µs per swap where the own core pays ~0.1 µs), with 11,563 ms
corrected to a calm 5,164 ms median. The mechanism hunt belongs to
the CLJS seat's line, not this (JVM) lane; this note discharges the
"calm-window re-run is owed" flag.

## Record-keeping footnote

The J queue's `.result` file for this job id currently reads EXIT=128
(19:35) — that is job-agent seat 5's accidental duplicate re-run
(clone collision), which overwrote the result file. The first run's
EXIT=0 (19:12–19:15 UTC) is the run of record: it is the one whose
deliverable banked above, and the job agent's `done/` record carries
it correctly. No further re-run is owed.
