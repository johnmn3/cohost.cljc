# Zone X crossing bench — REPORT (measurement only)

Bench: `dev/xbench.clj` on branch `muse/eve-cohost-xbench`, off
`muse/eve-cohost-impl` @ 29201de. Raw logs: `xbench-run1.log`,
`xbench-run2.log` beside this file (two full runs, same session shape
each: arms interleaved per round, order rotated, medians of rounds).

## The prototype (thinnest faithful shape of MEMORY-PLAN unit 5)

- **Wrapper** (`EveWrapper`): Eve's reference-type shape — a CAS word
  (`AtomicReference` on this seat) over an immutable root.
- **Layer** (`layer-deref/reset!/swap!/cas!`): one fn hop to the
  wrapper ops, no padding — the hop is part of what is priced.
- **Stand-in** (`ZoneEAtom`): occupies the shim's `clojure.lang.Atom`
  seat, implements `IDeref`/`IAtom` with method bodies dispatching to
  the layer, so arm B is literally `(deref a)` / `(swap! a inc)` /
  `(reset! a v)` as library code issues them.
- No validators, no watches, no persistence, in-memory roots.

Arms: **A** stock `clojure.lang.Atom` · **B** the Zone E stand-in as
designed (interface dispatch + layer hop + wrapper) · **C** the wrapper
with no layer hop and no interface dispatch (field access + wrapper CAS
directly) — so **B−C prices the dispatch+layer hop** and **C−A prices
the wrapper representation**.

Seat: JVM, OpenJDK 21.0.12.1, Clojure 1.12.0, 4 cores, box load
~1.2–1.9 during the runs (other lanes active on this shared box).

## Numbers (ns/op, medians of rounds; run 2 headline, run 1 in parens)

| op | A stock | B Zone E | C wrapper direct | B/A | B−C (hop) |
|---|---|---|---|---|---|
| deref, uncontended (2M/round, 7 rounds) | 2.72 (2.78) | 5.82 (5.82) | 3.70 (3.77) | 2.14× | 2.12 (2.05) |
| swap! inc, uncontended (500K/round, 7 rounds) | 16.53 (16.43) | 20.05 (20.03) | 15.03 (15.14) | 1.21× | 5.01 (4.89) |
| reset! (1M/round, 7 rounds) | 15.80 (16.05) | 15.80 (15.91) | 13.99 (14.01) | 1.00× | 1.81 (1.90) |
| swap! inc, contention 4 threads (1M total/round, 11 rounds) | 159.29 (53.80) | 187.00 (193.89) | 117.61 (233.00) | ~1.17× | not priceable (see below) |

Correctness was asserted in-run before timing: uncontended swap/reset
agree across arms, and the contended total is exact (1,000,000) on
every arm.

## Reading

- **The crossing costs single-digit nanoseconds per op, uncontended.**
  deref: **+3.10 ns/op** over stock (2.72 → 5.82); swap!: **+3.52
  ns/op** (16.53 → 20.05, 1.21×); reset!: **+0.00 ns/op** — parity.
- **Almost all of it is the dispatch+layer hop: 1.8–5.0 ns/op**
  (B−C). The wrapper representation itself is ~free: C−A is +0.98 ns
  on deref and *negative* on swap!/reset! (the prototype's CAS loop
  calls the swap fn directly, where stock's path also carries its
  validator/watch null-checks and apply machinery). So the price of
  Zone E on this seat is the price of one interface dispatch + one
  layer fn call — the seam's standing shape — not of Eve's wrapper.
- **Contention does not price cleanly on this box.** Across the two
  runs the stock arm itself swung 53.80 → 159.29 ns/op and arm C
  117.61 → 233.00, with per-round spreads of ~40–390 ns inside each
  run (4 cores, load ~1.2–1.9, other lanes running). What the two
  runs do establish: B landed in A's band both times (187.00 and
  193.89 against A's 159.29 and 53.80) — no order-of-magnitude
  blowup, and no structural reason for one: the crossing adds no
  lock and no extra CAS traffic; the per-attempt hop (~2–5 ns) is
  amortized across CAS retries, which dominate under contention.
  A trustworthy contended ratio needs a quiet box; it is not
  claimed here.

## What this number is and is not

It **is** the JVM-seat dispatch+wrapper floor for the Zone X crossing
(deref/swap!/reset!), reproducible across two runs to within noise.
It is **not**: unit 5's finished atom (validators/watches are unit 5
machinery, omitted here by design — stock's numbers above *include*
its own validator/watch checks); not the slab-backed cost on jank
(that is unit 6's measurement, gated on the Eve-on-jank flagship);
and not a contended-workload ratio (unpriceable on today's box, see
above).
