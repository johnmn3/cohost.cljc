# S6 — Cohost core benches: venue columns (perf-matrix campaign)

Suite: cohost `bench:jvm` / `bench:jolt` / `bench:jank` / `bench:cljs`
(+ `bench:lpy` as the separate S6-lpy cell on the list-star tip).
Tree pin for S6 proper: cohost.cljc main @ a956f21. Every figure
below carries its label set: venue + box, tree tip, date (ET), form,
median basis, and the result pointer it was read from. Figures are
the campaign's labeled measurements as run — the boxes are shared,
and PERF.md's standing caveat applies (median noise floor ~×2 per
the JVM-speed lane's attribution record); nothing here is a
calm-box price.

## Mac column — COMPLETE

- Venue + box: Mac runner (John's Mac, macOS, arm64); runner bb
  1.12.218 (Mac class — its own version class vs fleet 1.13.226).
- Tree: cohost.cljc main @ a956f21 (`:git "a956f21"` inside each EDN).
- Date: 2026-11-09 03:22 ET (Mac mailbox RESULT #23; ACK load1 3.93).
- Form: the bench harness's own EDNs (per-case medians, min-runs 3,
  max-runs 2000, settle 250 ms, budget 1000 ms; boot/libs are the
  harness's single readings).
- Result pointer: this branch @ adc3354 —
  doc/perf/perf-matrix/mac/bench-{clj,jolt,cljs}.edn (+ .log.txt).

| bench | boot-ms | libs-ms | cases same? | geomean host÷stock (case medians) |
|---|---:|---:|---:|---:|
| bench:jvm (clj) | 420.7 | 364.5 | 30/30 | 0.82 |
| bench:jolt | 1,050.8 | 1,165.1 | 30/30 | 0.54 |
| bench:cljs | 1,954.6 | 1,944.7 | 29/30 | 0.52 |
| bench:lpy | — | — | RC=1 | — |

- bench:cljs divergent case: `format` — host side :error (stock side
  ran); recorded in bench-cljs.edn.
- bench:lpy RC=1 is expected on this pin: main @ a956f21 predates
  the list-star fix, so `list*` is unresolved at
  cohost/thread.cljc:738. The lpy bench on the fixed tree is the
  separate S6-lpy cell (list-star tip 6754b40a).

## Grok column — PENDING EDN PULL

- Venue + box: Grok runner box (8-core). Tree @ a956f21.
- Corrected leg (pm2-s6-grok) ran as Grok DISPATCH #68; RESULT
  posted 2026-11-09 03:31:23 ET, rc=0. The complete four-bench EDN
  set is the deliverable on the box at
  /workspace/jobs/vol/pm-cohost2-out — the mailbox RESULT tail is
  partial (bench:jank only). A pull leg (pm3-s6grok-pull) is filed
  with the job agent and awaiting a Grok slot; this column is
  written in full when the EDNs land.
- Partial, from the mailbox tail (labeled partial — do not quote as
  the column): the bench:jank EDN's medians are real, :same? true
  except two cases — atom-swap (host value 704982704 vs stock
  4999950000) and protocol (host :error). Both are cohost-jank
  findings, carried here until the full set lands.
- Mechanism note from the first attempt (#65): the benches run on
  Grok once PATH survives — the original exit=127s were the login
  shell resetting PATH and dropping the staged toolchain dir.

## J column — RUNNING

- Wave-1 S6-j leg, J job 20261109T081252: queued through the J SSH
  outage, RUNNING on J (j.slaa.us) as of 2026-11-09 ~04:00 ET.
  Column written from its result when it lands.

## vm column — PARTIAL (RESULT collected; EDN pull filed)

- Venue + box: vm (fleet VM, root runner). Tree @ a956f21.
- Date: 2026-11-09 ~04:12 ET (vm mailbox RESULT #5; ACK load1
  0.00, run load ~1.03). Toolchain: bb 1.13.226 (fleet), JDK
  21.0.12.1, node v22.23.4, jolt v0.8.23 (no jank installed).
- Form: the original filing's bench loop; per-bench exit codes +
  EDN verdict counts read from the RESULT. The full EDNs are
  banked on the box only ($HOME/pm-cohost-out — the dispatch
  named no lane branch); a read-only pull (pm3-s6vm-pull) is
  filed, and this column's boot/libs/geomean figures are written
  when it lands.
- bench:jvm exit=0 — bench-clj.edn: 30 cases, 30/30 :same? true.
- bench:cljs exit=0 — bench-cljs.edn: 29/30 :same? true; the one
  divergence is `:format` (host :error "Cannot read properties of
  undefined (reading 'call')") — the same format-case host error
  as the Mac cljs column: a cohost-cljs finding, not a box fluke.
- bench:jolt exit=1 — entry-point lookup: "Could not locate
  cohost/run.jolt (or .clj/.cljc) on the source roots" at this
  pin, per the runner's diagnosis (jolt v0.8.23 is installed;
  the lookup, not the install, failed). Recorded as measured.
- bench:jank exit=1 — jank not installed on this box (expected
  absent-host failure, recorded as such).

## cc-vm / vm-b columns — PENDING

- Original legs filed Wave 1: cc-vm / vm-b legs still
  router-pending behind full venue caps at last read.
  Columns written from their RESULTs; any exit=127 names its
  executable and the filings are corrected from that evidence.
