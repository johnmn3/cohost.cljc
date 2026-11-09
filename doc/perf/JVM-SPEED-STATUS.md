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

## Bank 2 — 2026-11-08 ~16:05 ET — calm1 collected; §4 verdicts banked; toggles + calm2 filed; CLJS verdict discharged

- calm1 collected firsthand (job `20261108T193500-jobagent-jvmspeed-calm1`,
  EXIT=0, 19:36:50→19:50:56 UTC; EDNs on J at
  /home/john/j/cohost-jvm-speed-results/calm1/). All four processes
  started at load1 2.80–2.99 — the gate held. Values equal on all
  8 cases × 4 processes.
- ATTRIBUTION.md §4 now carries the results. Verdicts (ratios are
  booted ÷ plain; "stock" side = the booted core, "host" side =
  plain JVM Clojure — the driver source settles the naming):
  fn-call ×0.19 RETRACTED (parity, ×0.91 at median; the catalog's
  prediction), partition ×0.63 RETRACTED (direction flips between
  windows; the case is bimodal inside single processes),
  string/join ×0.81 RETRACTED (parity, as C8 predicts), hinted-loop
  min-gap ×2.86 RETRACTED (booted min 0.065 ≈ plain 0.069; a ×1.31
  median tendency remains, unseparated). **multimethod CONFIRMED —
  the one real cell: ×4.32 at median, ×5.93 at min, fully separated
  (booted min 9.261 > plain max 2.570); carrier by elimination is
  C7, the shim MultiFn.** Controls behaved (pr-str: same-fn process
  pairs disagree ×2.1 — the calm noise floor; loop-arith: parity).
  transduce is an UNCONFIRMED loss candidate (×2.44 at median,
  direction consistent in both ABBA pairings, not separated).
  The ×1.1 geomean is not re-derived from 8 of ~30 cases; its
  checked cells were mostly artifacts, and its one real cell is a
  loss it understated.
- §5 toggles filed with the job agent (scratch seds on the job's own
  clone of this branch; each run carries its control):
  `cohost-jvm-speed-t1` (:multimethod-cache? true→false in
  hosts/cljw.clj; cases multimethod,pr-str — prices how much of the
  ×4.3 the one-entry cache already buys back),
  `cohost-jvm-speed-t3` (:inline-meta :keep→:strip in adapter.cljc
  defaults; cases hinted-loop,loop-arith,fn-call — the C10
  sensitivity control), `cohost-jvm-speed-t2` (:host-aliases?
  true→false; cases partition,transduce,string-join — prices C5's
  aggregate inside booted bodies, transduce's toggle).
  T4 (real MultiFn via policy take) is a patch, not a sed — designed
  in §5, filed when T1's number says how much room is left.
- calm2 filed (`cohost-jvm-speed-calm2`): transduce,hinted-loop,pr-str
  on the unmodified branch — settles transduce's candidacy by
  repetition and re-checks hinted-loop's median tendency.
- CLJS swap! verdict DISCHARGED — the calm re-run the refresh owed
  had in fact run and banked (job …191202, first run EXIT=0;
  bench-cljs-grok.edn on lane-m/cohost-perf-refresh). Verdict:
  the anomaly is real, ~2.2× overstated hot: booted-core swap! on
  CLJS = 5,164 ms calm median vs own core 10.51 ms (×491), values
  equal. Full note: doc/perf/CLJS-SWAP-VERDICT.md, for the perf line
  to lift into PERF.md. (The J .result file for that job reads
  EXIT=128 — seat 5's accidental duplicate overwrote it; the first
  run stands, per the job agent's done record.)
- Next: collect t1/t2/t3 + calm2 → bank §5 verdicts → T4 patch if
  priced in → O1–O5 prototypes for the survivors → legality gate.

## Bank 3 — 2026-11-08 ~23:25 ET — successor seat: collection, §5 verdicts, T4 authored + filed

- Successor pickup: the previous seat went quiet after bank 2 with
  the four filed jobs run but uncollected. All four (calm2, T1, T2,
  T3 — jobs 20261108T210608–T210611, EXIT=0 on J) collected
  firsthand from /home/john/j/cohost-jvm-speed-results/{calm2,t1,t2,t3}/
  (EDNs + runs.logs; values equal on every case; load gates held,
  load1 2.76–2.97).
- §5 verdicts banked (ATTRIBUTION.md): **transduce CONFIRMED real
  loss** (calm2 med ×4.35, fully separated; calm1 ×2.44) — the
  lane's second confirmed cell. T1: the one-entry cache buys back
  nothing (off beats on, ×4.32 → ×1.71 at median; cyclic (mod x 3)
  dispatch; contradicts X3-1's bench:jvm read — reconciliation owed
  at the §7 gate, not hidden). T2 null: C5 exonerated on all three
  cases; transduce's carrier narrows to the booted reduce path
  (C6/C4/value-position callees). T3 positive: :inline survival is
  load-bearing (strip → loop-arith ×26.6); O5's per-var audit is
  the cheapest high-leverage probe left. hinted-loop stays
  unconfirmed (the booted side itself flips between windows).
- T4 authored: dev/perf/t4-real-multifn.patch (:multifn-ctor
  capability + JVM :multifn-op; booted defmulti builds
  clojure.lang.MultiFn). Hatch smoke passed (boot + libraries,
  bench value exact, every member op live incl. a defmethod on the
  host-taken print-method); the patch was generated on J from files
  sha256-identical to the smoke tree. Calm job filed:
  cohost-jvm-speed-t4. Decomposition job filed alongside:
  cohost-jvm-speed-t5 (dev/perf/t5-transduce-decomp.patch adds
  transduce-id/-map/-filter cases on the job's clone only).
- Next: collect t4 → close §5 with the real-MultiFn price → collect
  t5 → §6 (O1/O3 toggles for the transduce remainder, O5 audit) →
  legality gate (§7) incl. the cache A/B reconciliation under
  bench:jvm.

## Bank 4 — 2026-11-08 ~23:45 ET — O5 audit: no :inline lost; O5 closes

- O5 (§6) run: dev/perf/o5_inline_audit.clj (banked with this
  entry) enumerates core.clj's 93 inline-carrying names (definline
  + defn :inline attr maps) and checks the booted vars' meta after
  a full boot + library load: **93 checked, :missing []** — every
  booted var retains :inline. Firsthand run on hatch (T4-treated
  tree; T4 does not touch the inline path); pristine-tree
  confirmation filed as job cohost-jvm-speed-o5. §6 updated in
  ATTRIBUTION.md: O5 closes as a null result.
- In flight: cohost-jvm-speed-t4 (real-MultiFn price) and
  cohost-jvm-speed-t5 (transduce decomposition), filed last bank.
- Next: collect t4/t5/o5 -> close §5 -> §6 remainder (O1/O3 toggles
  aimed by the t5 decomposition) -> legality gate (§7).

## Bank 5 — 2026-11-09 ~00:35 ET — t4/t5/o5 queue-blocked on J; collection instructions

- State: jobs cohost-jvm-speed-t4 (20261109T041522), -t5
  (20261109T041524), -o5 (20261109T041725) were routed to J and
  sha-verified into ~/j-queue/inbox; they were claimed once and
  returned to the inbox unstarted, and at this bank they have sat
  ~55 min behind the fleet-wide restaffing storm (J load1 > 4,
  inbox ~24 deep, slots held by long jobs). No result dirs exist
  yet (/home/john/j/cohost-jvm-speed-results/{t4,t5,o5}/ absent —
  checked, not assumed). The filings are valid; this is congestion,
  not a defect. They stay filed.
- COLLECTION (whoever picks this up): when the jobs land in
  ~/workspace/job-agent/done/, read the EDNs at
  /home/john/j/cohost-jvm-speed-results/{t4,t5}/ (medians + mins,
  pooled per side, values equal per case — the parser used for
  banks 3-4 is a ~30-line EDN-lite regex over :per-call-ms/:case)
  and o5/audit.log (summary line must read :checked 93,
  :missing []). Then: t4's multimethod ratio closes §5 (the
  real-MultiFn price; compare calm1 ×4.32 / T1-off ×1.71);
  t5's decomposition aims §6 (whichever of transduce-id/-map/
  -filter carries the ×4 names the O1/O3 target — :core-value-
  aliases for the xform callees if map/filter arms carry it,
  direct-statics/C6 if the identity arm carries it); o5 closes
  its confirmation line in bank 4. Then §7: JVM battery + library
  suite on the T4-treated tree, the cache A/B re-run under
  bench:jvm (the T1/X3-1 instrument conflict), and the ranked
  landing set.
- Banks this seat: 3 (§5 verdicts + T4/T5 patches), 4 (O5 audit +
  verdict), 5 (this note). Branch tip at this bank = this commit.
