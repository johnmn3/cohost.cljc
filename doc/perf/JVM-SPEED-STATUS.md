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
- Environment note (corrected in bank 1): the hatch box DOES have
  `clojure` — at ~/.local/bin (the first probe's PATH lacked it), and
  its resolver needs CLJ_CONFIG=~/workspace/eve-env/cljconfig (the
  curl-seeded /home/hatch/m2repo; direct Maven TLS is intercepted).
- Next: push branch, submit calm job via job agent, collect, bank §4.

## Bank 1 — 2026-11-08 ~14:35 ET — driver smoke-tested both sides; load-stamp fix

- Host side smoke (fn-call only): runs end-to-end, EDN shape correct,
  value "100000" as the refresh's. Stock side smoke (fn-call only):
  boot! + make-user-ns! + load-libs! + eval-str path all run, value
  "100000" correct. Both under hatch load ~6.6 — validation only, no
  numbers recorded as evidence (the stock reps spread 0.41–8.65 ms on
  the same case, which is itself the calm-gate argument in miniature).
- Fix found by the smoke: a JVM here cannot read /proc/loadavg through
  slurp (IOException "Invalid argument"; bash reads it fine), so the
  driver now takes the load from the runner as an argument (the runner
  reads it with awk, which works) and only falls back to slurp.
- Calm job (cohost-jvm-speed-calm1.json) is in the job-agent inbox and
  clones this branch at run time, so it picks up bank 1 automatically.
