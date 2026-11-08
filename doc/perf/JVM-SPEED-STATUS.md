# cohost-jvm-speed lane — STATUS

Charter (John, Nov 8 2026): find all opportunities to make Clojure go
faster on JVM through cohost than on JVM stock. Deliverable:
doc/perf/ATTRIBUTION.md on this branch (lane-m/cohost-jvm-speed, cohost
repo — the ground-truth branch lane-m/cohost-perf-refresh lives in this
repo's origin; the lane brief's "(atom.broker)" is read as the lane-m
branch family, and this is flagged in the lane's first report).
Bench legs through the job agent only. Landing is not this lane's act.

## Bank 0 — 2026-11-08 14:21 ET — stood up; catalog banked; calm job designed

- Ground truth read firsthand: PERF.md refresh section + raw
  bench-clj-j.edn on lane-m/cohost-perf-refresh (remote tip e419278;
  the brief's fe41e20f verified an ancestor of it — the refresh content
  is unchanged by the one later bank, a cljs-on-Grok row).
- Pipeline read firsthand: boot.cljc, policy.cljc, rewrite.cljc (ctx,
  rw-static, core-alias family, infer-here?), adapter.cljc capability
  defaults, hosts/jvm.clj, hosts/java.clj, bench.cljc, run.cljc.
- Catalog banked as ATTRIBUTION.md §0–§3. Headline conclusions:
  (1) bench case code compiles identically on both sides (every
  call-shape lever is inert on the JVM: :core-aliases #{},
  :core-value-aliases {}, :core-expansions {}, :direct-var-calls nil,
  :inline-fn nil); (2) the refresh's own pr-str cell — the same fn
  object on both sides — spreads ×2.15 at median, so the hot window's
  noise floor is ~×2 and the ×0.19 fn-call cell has no catalog carrier
  (its mins are at parity: 0.276 vs 0.256); (3) the real-shaped gaps in
  the table are stock-side LOSSES at min (hinted-loop ×2.9, multimethod
  ×2.3) with named carriers to test (C10 :inline survival, C7 shim
  MultiFn).
- Calm re-measurement built: dev/perf/jvm_speed.clj (one side per
  process, full rep distributions) + dev/perf/jvm_speed_run.sh (ABBA,
  load1 < 3.0 gate per process). Cases: fn-call, partition, string-join,
  hinted-loop, multimethod, pr-str, loop-arith, transduce.
- Environment note: the hatch box has no `clojure` CLI on PATH; driver
  smoke test runs on J inside the bench job itself (the job's driver.log
  is the first check). No local measurement was attempted or claimed.
- Next: push branch, submit calm job via job agent, collect, bank §4.
