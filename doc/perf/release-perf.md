# Release perf -- the jank half (M-12, 2026-11-02)

Base: cohost `claude/youthful-euler-gquikf` @ 6eec4e2 (the release + the
cohost-max fold), clean checkout. Box: 4 CPUs, hatch build box, load ~0 at
bench start (the M-11 gate sweep was stopped for a quiet box; one VM cycle
hit mid-run -- jank was reinstalled from the cached deb and the unfinished
columns re-run; prog-jvm and the snapshot boot predate the cycle, the rest
postdate it). Jank: the apt jank at /usr/local/bin/jank, no JANK_BIN.
Method: C-1's steady-state program bench (warmup until settled, first
output included in the records; medians reported, xJVM against this box's
own jvm column). Raw records: doc/perf/release-perf/*.edn.

## Programs (median ms; xJVM in parens)

| program | jvm (stock) | jvm-cohost | jank-jit | jank-aot | jank (snapshot) |
|---|---|---|---|---|---|
| binary-trees | 226.7 | 215.5 (x0.95) | 3370.7 (x14.87) | 3312.8 (x14.61) | 3199.8 (x14.11) |
| binary-trees-14 | 15.4 | 15.4 (x1.00) | 154.9 (x10.07) | 133.7 (x8.69) | 91.9 (x5.98) |
| binary-trees-14-hinted | 20.8 | 17.6 (x0.85) | 141.8 (x6.82) | 142.7 (x6.87) | 94.5 (x4.55) |
| binary-trees-hinted | 225.9 | 242.7 (x1.07) | 3149.8 (x13.94) | 3152.9 (x13.96) | 2692.3 (x11.92) |
| dispatch | 343.0 | 810.7 (x2.36) | FAIL error: The symbol `defprotocol` could not be resolved. | FAIL error: The symbol `defprotocol` could not be resolved. | DIFFER (host 1691001973 vs stock 12500045833333) median 1223.3 |
| fannkuch | 209.2 | 217.3 (x1.04) | FAIL target/bench-programs/6543/fannkuch.jank:9:1: error: use of  | FAIL /home/hatch/workspace/cohost-release/target/bench-programs/a | 2664.7 (x12.74) |
| fannkuch-hinted | 189.8 | 223.5 (x1.18) | FAIL target/bench-programs/6543/fannkuch-hinted.jank:7:1: error:  | FAIL /home/hatch/workspace/cohost-release/target/bench-programs/a | 2578.3 (x13.58) |
| fib | 174.0 | 629.3 (x3.62) | 185.4 (x1.07) | 148.6 (x0.85) | 187.3 (x1.08) |
| fib-hinted | 86.7 | 321.6 (x3.71) | 340.9 (x3.93) | 329.5 (x3.80) | 312.0 (x3.60) |
| first-output | 1104.0 | 7991.5 (x7.24) | 695.7 (x0.63) | 667.5 (x0.60) | 6792.7 (x6.15) |
| json | 508.8 | 608.4 (x1.20) | 2188.8 (x4.30) | 1867.2 (x3.67) | 5706.0 (x11.21) |
| k-nucleotide | 262.2 | 283.7 (x1.08) | 755.5 (x2.88) | 731.4 (x2.79) | 2794.8 (x10.66) |
| keyword-map | 156.6 | 149.2 (x0.95) | 498.8 (x3.19) | 508.5 (x3.25) | 1105.7 (x7.06) |
| lazy-pipeline | 41.7 | 58.0 (x1.39) | 1069.4 (x25.65) | 996.8 (x23.91) | 3140.9 (x75.34) |
| mandelbrot | 264.4 | 262.4 (x0.99) | FAIL target/bench-programs/6543/mandelbrot.jank:16:1: error: use  | FAIL /home/hatch/workspace/cohost-release/target/bench-programs/a | 1331.7 (x5.04) |
| mandelbrot-hinted | 248.4 | 264.4 (x1.06) | FAIL target/bench-programs/6543/mandelbrot-hinted.jank:16:1: erro | FAIL /home/hatch/workspace/cohost-release/target/bench-programs/a | 1229.2 (x4.95) |
| nbody | 191.9 | 287.4 (x1.50) | FAIL error: The symbol `Math/sqrt` could not be resolved. | FAIL error: The symbol `Math/sqrt` could not be resolved. | 2869.0 (x14.95) |
| nbody-hinted | 197.7 | 229.5 (x1.16) | FAIL error: The symbol `Math/sqrt` could not be resolved. | FAIL error: The symbol `Math/sqrt` could not be resolved. | 2803.3 (x14.18) |
| nsieve | 151.1 | 345.9 (x2.29) | FAIL target/bench-programs/6543/nsieve.jank:11:1: error: use of u | FAIL /home/hatch/workspace/cohost-release/target/bench-programs/a | 1066.6 (x7.06) |
| nsieve-hinted | 203.5 | 397.0 (x1.95) | FAIL target/bench-programs/6543/nsieve-hinted.jank:11:1: error: u | FAIL /home/hatch/workspace/cohost-release/target/bench-programs/a | 1406.0 (x6.91) |
| pipeline | 212.6 | 310.4 (x1.46) | DIFFER (host 1336595249 vs stock 374998750001) median 1307.5 | DIFFER (host 1336595249 vs stock 374998750001) median 1324.3 | DIFFER (host 1336595249 vs stock 374998750001) median 1971.1 |
| sort | 45.8 | 47.5 (x1.04) | 16136.9 (x351.99) | 27603.6 (x602.11) | 10304.8 (x224.78) |
| spectral-norm | 202.2 | 269.4 (x1.33) | FAIL error: The symbol `Math/round` could not be resolved. | FAIL error: The symbol `Math/round` could not be resolved. | 3763.9 (x18.61) |
| spectral-norm-hinted | 89.2 | 272.9 (x3.06) | FAIL error: The symbol `Math/round` could not be resolved. | FAIL error: The symbol `Math/round` could not be resolved. | 4386.8 (x49.19) |
| sum-overflow | 17.0 | 15.2 (x0.89) | DIFFER (host 1783293664 vs stock 499999500000) median 113.6 | DIFFER (host 1783293664 vs stock 499999500000) median 84.5 | DIFFER (host 1783293664 vs stock 499999500000) median 7.6 |
| sum-overflow-hinted | 1.0 | 14.7 (x14.22) | DIFFER (host 1783293664 vs stock 499999500000) median 14.4 | DIFFER (host 1783293664 vs stock 499999500000) median 14.3 | DIFFER (host 1783293664 vs stock 499999500000) median 14.5 |
| tak | 361.0 | 372.0 (x1.03) | 786.1 (x2.18) | 718.7 (x1.99) | 770.8 (x2.14) |
| tak-hinted | 217.2 | 1595.6 (x7.35) | 1586.1 (x7.30) | 1360.1 (x6.26) | 1644.5 (x7.57) |


FAIL/DIFFER notes (the full error strings are in the records):

- dispatch, nbody, nbody-hinted, spectral-norm, spectral-norm-hinted FAIL
  on the raw jank columns (jit and aot): "The symbol `defprotocol` could
  not be resolved" / "The symbol `Math/sqrt` could not be resolved" /
  "The symbol `Math/round` could not be resolved". The bench programs are
  raw host source, not cohost-rewritten; the snapshot column runs them
  through cohost, where they run.
- fannkuch, fannkuch-hinted, mandelbrot, mandelbrot-hinted, nsieve,
  nsieve-hinted FAIL on raw jank jit and aot: "error: use of undeclared
  identifier 's28'" (aot nsieve: 's56'; did you mean 's16'?) -- a raw-jank
  analysis failure on these programs. They run on the snapshot column.
- dispatch DIFFERs on the snapshot column only: host result 1691001973,
  stock 12500045833333.
- pipeline DIFFERs on all three jank columns: host 1336595249, stock
  374998750001. sum-overflow and sum-overflow-hinted DIFFER on all three:
  host 1783293664, stock 499999500000 (jank's wrapping integer arithmetic).
- first-output on the snapshot column (6792.7 ms) includes the snapshot
  boot per process; jit/aot first-output (695.7 / 667.5 ms) is faster than
  this box's stock JVM (1104.0 ms).

## bench:jank (the 30 cases, cohost on jank vs stock jank)

boot-ms 1083.7, libs-ms 1228.4. Geomean host/stock **x0.92** (cohost is
faster overall). Worst five by ratio:

| case | host ms | stock ms | ratio |
|---|---|---|---|
| reduce-range | 68.647 | 1.536 | x44.69 |
| string-join | 3291.038 | 83.674 | x39.33 |
| multimethod | 153.301 | 5.488 | x27.93 |
| keyword-lookup | 68.632 | 17.128 | x4.01 |
| map-lookup | 26.082 | 13.795 | x1.89 |

## Startup

- Snapshot boot (bb boot:jank-snapshot, fresh build 584963 ms):
  SNAPSHOT-BOOT-MS **1144.8 ms**.
- `clojure -M -m demo.main jank-user` in dev/cli-demo, median of 3 warm:
  **2032 ms** (2194 / 2032 / 1921).
- `cohost :jank -M -m demo.main jank-user`: **DIFFER -- does not run on
  this box.** bin/cohost throws at line 135:
  `IllegalArgumentException: /home/hatch/workspace/cohost-release/dev/cli-demo/greeter/src is not a relative path`
  (babashka v1.3.190; the demo's :local/root greeter path reaches the CLI
  as an absolute path and fs/path rejects it). The repo's own
  `bb cli:check jank` reproduces the same DIFF on all four cases
  (-X:run, -e, script, -Spath) while clojure answers all four. No cohost
  startup timing exists on this box until that is fixed; the snapshot
  boot ms above is the jank startup figure this box can give.
