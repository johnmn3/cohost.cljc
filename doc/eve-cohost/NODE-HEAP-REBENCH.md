# Node/cljs heap re-bench — Eve on vs Eve off, fresh numbers (2026-11-05)

Supersedes the Node figures in `HEAP-SPIKE.md` §5.2 as the current
numbers (the spike text itself is untouched; a dated supersession
note is added there pointing here).

## What was run

The spike's own harness, `dev/heap_cljs_bench.cljs` (banked with
the spike, commit bd5a08c), re-run as the spike ran it: placed as
`test/heap_bench/main.cljs` in an Eve checkout, a `:heap-bench`
`:node-script` build added, `shadow-cljs release heap-bench`,
`node target/heap-bench.js`. Same loop shapes, same iteration
counts, stock cljs controls in the same process, warmup inside
each timed block as the harness defines it. Three passes per
version; medians of the three below, ranges in the logs.

Two Eve versions, same box, same day, back to back:

- **Spike code**: Eve @ `c7dc390` (the version the spike measured),
  harness exactly as banked.
- **Current pin**: Eve @ `d241de3` — the sha the impl branch's
  `:cljs` alias pins (`deps.edn`: "pinned to Eve main d241de3,
  the 0.9.1 release line").

Box: this box, 2026-11-05 ~22:05–22:25 ET, Node v24.22.1 (same
as the spike), load average ~5–8 during the runs (other lanes
active — the spike also ran shared, at load ~5). Numbers are
medians of 3 passes; full logs:
`doc/eve-cohost/logs/node-rebench-{d241de3,c7dc390}-run{1,2,3}.log`.

Exact commands (per Eve worktree, env
`PATH=$HOME/.local/bin:/usr/local/bin:$PATH`,
`JAVA_HOME=$HOME/opt/jdk-21.0.7+6-jre`,
`CLJ_CONFIG=$HOME/workspace/eve-env/cljconfig`):

```
cp dev/heap_cljs_bench.cljs <eve>/test/heap_bench/main.cljs   # from the impl branch
# add :heap-bench {:target :node-script :main heap-bench.main/main
#                  :output-to "target/heap-bench.js"} to <eve>/shadow-cljs.edn
npx shadow-cljs release heap-bench
node target/heap-bench.js        # x3 passes
```

### Harness adaptations (current pin only; both marked in the source)

1. **init! moved house.** At c7dc390 the bench called
   `eve.deftype-proto.alloc/init!`; between c7dc390 and d241de3
   that fn moved to `eve.process-slab` ("Moved from
   eve.deftype-proto.alloc's ClojureScript arm" — process_slab.cljs;
   Eve's own test mains now call `ps/init!`). The require follows
   it: `[eve.process-slab :as eve-alloc]`. Same fn, same promise
   shape, no bench shape changed. (Without it the current pin
   faults at startup: the process slab never registers, and the
   heap domain opens over a null region.)
2. **reset! rows added** — the spike bench had no reset! row and
   the re-bench brief asks for one: `stock-atom-reset` (200k) and
   `eve-sab-atom-reset` (100k), same loop shape as the deref/
   swap rows. Marked ADDED in the source; they have no spike-era
   counterpart and are a new baseline, not a comparison.

## Numbers (ns/op, medians of 3 passes)

### Eve OFF — stock ClojureScript controls (same process)

| op | spike print (c7dc390, spike day) | today, c7dc390 build | today, d241de3 build |
|---|---|---|---|
| atom deref | 9.0–9.3 | 21.9 | 12.9 |
| atom swap! inc | 29–31 | 71.8 | 52.6 |
| atom reset! | — (no row) | — (no row) | 16.6 |
| map get (1k-entry map) | 48–52 | 411 | 362 |
| map assoc (per op, 1k build) | 433–486 | 1,060 | 976 |

### Eve ON — Eve atom / Eve map on the cljs seat

| op | spike print (c7dc390) | today, c7dc390 (A/B control) | today, d241de3 (current pin) |
|---|---|---|---|
| atom deref, scalar root | 708–737 | 2,435 | 4,498 |
| atom swap! inc | 4,347–4,511 | 12,776 | 20,304 |
| atom reset! | — (no row) | — (no row) | 18,012 |
| map get (1k-entry Eve map) | 1,970–2,335 | 4,913 | 4,119 |
| map assoc (per op, 1k build) | 9,340–9,607 | 24,915 | 18,811 |
| deref, 100-entry map root (materializes) | 96,600–105,000 | 255,173 | 216,971 |
| capacity: 10k map / 1k vec build | both OK | both OK | both OK |

Correctness as printed, every pass: `eve-atom after swaps:
100010` (CAS-exact), Eve map count 1000, get500 = 500, map-root
deref get42 = 42.

## Reading

- **The spike's absolute numbers do not reproduce on today's
  box — for either code version, stock controls included.**
  The spike's own code, re-run today, reads 2–3.4× its printed
  Eve numbers and up to ~8× on stock map get. Today's box is
  carrying other heavy lanes; the spike ran shared too, but
  plainly under different conditions. Treat the spike prints
  as spike-day numbers, not a baseline absolutes can be held
  to. The only sound comparison is same-box, same-day.
- **Same-day A/B, current pin vs spike commit:** the atom path
  moved the wrong way — deref ~1.8× slower (4,498 vs 2,435),
  swap! ~1.6× slower (20,304 vs 12,776), consistently across
  passes (swap ranges do not overlap). The collection path
  moved the right way, modestly: map get ~0.84×, assoc ~0.76×,
  materializing map-root deref ~0.85×. Between the two commits
  `eve.atom` was re-routed through the unified heap domain and
  the journal-era commit path (the JVM-reunification line) —
  the atom rows are where that work's cost shows; this bench
  names the cost, not the mechanism.
- **Against stock, same process, today:** Eve deref is
  ~250–350× stock, swap! ~285–385× stock, map get ~11× stock,
  assoc ~19× stock. The seat was not heap-grade at the spike
  (20–145×) and is not heap-grade now; on the atom path the
  gap widened since the spike commit, on collections it
  narrowed slightly.

## Verdict

**Unchanged-to-worse, and not heap-grade.** The cljs seat is
not closer to heap-grade than the spike found it: collections
improved ~15–25% against the spike commit on a same-day A/B,
but the atom hot path — the heap's most basic operation —
regressed ~1.6–1.9× against that same commit, and against
stock the seat still pays two-plus orders of magnitude on
deref/swap!. The single number that moved most against its
spike print is **swap!: 4,347–4,511 ns → 20,304 ns median
(~4.6×)** — of which ~2.9× is box conditions (the spike's own
code re-reads 12,776 today) and ~1.6× is code, attributable
to the c7dc390→d241de3 line. New baseline where none existed:
reset! — stock 16.6 ns, Eve 18,012 ns.

## DECLARATION

Lane complete, 2026-11-05. The spike harness was re-run
unmodified against the spike's own Eve commit as a same-day
control, and against the impl branch's pinned Eve (d241de3)
with the two marked adaptations above (init!'s new home;
reset! rows added). Both arms measured both versions, three
passes each, medians reported, raw logs banked beside this
report. The stale Node figures in HEAP-SPIKE.md §5.2 carry a
dated supersession note pointing here; the spike text itself
is unrewritten.
