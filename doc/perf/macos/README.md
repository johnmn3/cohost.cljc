# macOS box (Apple M5): records

The box: Apple M5, 10 cores (4 performance + 6 efficiency), 32 GB, macOS 26.5. A desktop in use (Chrome and VS Code
open): load average 3.2–4.8 when each column started (`c0431c6/progress.log`). The JVM column was measured under the
same load, so ratios compare; milliseconds are probably a little high. Runtime versions: `box.txt`. Written up in
[doc/PERF.md](../../PERF.md) under "macOS (Apple M5)".

## Built apps, cohost `coord/docs6` @ c0431c6 (2026-11-03, 11:36 AM to 12:58 PM ET)

`bb bench:programs <runtime>-built`, full sizes, each column against this box's `jvm` column (11:36 AM). Table:
`c0431c6/table.txt`.

| column | geomean xJVM | programs that ran |
|---|---:|---:|
| jank-built | **x3.40** (unhinted x3.29, hinted x3.61) | 28 |
| jolt-built | **x7.13** (unhinted x4.53, hinted x16.15) | 28 |
| cljs-built | **x10.33** (unhinted x7.21, hinted x19.74) | 28 |
| cljw-built | **x111.9** (unhinted x75.1, hinted x229.7) | 28 |
| lg-built | x167.7 (unhinted x94.2, hinted x354.8) | 23 of 28 (stopped partway: not run on k-nucleotide, pipeline, dispatch, json, first-output) |
| phel-built | none | 0: the build fails on macOS (below) |

Every row that ran gives the JVM's answer (no DIFFER).

**phel-built and `docs:check phel` fail on macOS** for a reason specific to macOS. The Phel build runs
`ulimit -s 65536 && exec phel …` (`src/cohost/build.clj:314` and `:318`, and `dev/perf/phel_snapshot.sh`). macOS's
hard stack limit is 65520 KB, so the `ulimit` fails ("Operation not permitted") and Phel never starts. The rest of
cohost already uses a form that tolerates this (`ulimit -s 1048576 2>/dev/null; exec …`). A fix:
`ulimit -s 65536 2>/dev/null || ulimit -s hard; exec …`.

Not measured on c0431c6 (the run was stopped for the launcher test): jvm-built, the small-size basilisp pair,
the own-core and through-cohost columns, startup.

## docs:check on c0431c6

jank, jolt, lpy, cljw, lg, cljs and jvm pass, 0 failing commands. Phel: 4 failing, both `cohost -T:build build` and
both built-app `run you` (the `ulimit` above). Output: `c0431c6/docs-check.txt`.

## The jank launcher, `coord/jank-launcher` @ c415244 (`main`), 2026-11-03 1:05 PM ET

- `bb boot:jank-snapshot`: SNAPSHOT-BUILT in **156.9 s** (3 m 07 s for the task), the launcher included. Battery
  214/216, libraries 62/62. Peak about 2.9 GB for jank, clang and ld; memory never under 52% free.
- `cohost :jank -m demo.main you`: `hello, you!` in **2.35 s**, then **1.34 s**. `cohost :jank -e '(+ 1 2)'`: `3` in
  **0.58 s**. `target/jank-snapshot/aot/cohost-jank`: **91.5 MB**. Output: `c415244/launcher-check.txt`.
- `bb bench:programs jank-built` on c415244: **x3.36** over 28 (unhinted x3.22, hinted x3.62), against the same
  `c0431c6/jvm.edn`; the rows agree with c0431c6's within 5%. Record: `c415244/jank-built.edn`.

## jank's own core, for comparison (`jank-own-0c8834f/`)

`jank-jit` and `jank-aot` measured on this box the night before (coord/release3 @ 0c8834f, the same bench method,
the same jank binary) with that run's own `jvm.edn`. jank's own core doesn't depend on cohost's code. Over the 13
programs jank's own core runs and every column answers as the JVM does:

| column | geomean xJVM (13 programs) |
|---|---:|
| jank-jit (jank's own core, JIT) | x6.66 |
| jank-aot (jank's own core, AOT -O3) | x6.47 |
| jank-built (cohost on jank, one native executable, c0431c6) | **x2.37** |

## Not in these records

The runs on 2704c21 and 9c4ac15 that were stopped. On 2704c21, jank's snapshot and built apps did not compile on
stock jank: A7-3's inline `=` passed a raw int literal to `jank::runtime::equal`, an ambiguous overload at -O3
(`analyze/invalid-cpp-call`). 8fcb495 keeps that inline out of a snapshot recording.
