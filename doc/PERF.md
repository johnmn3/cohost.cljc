# Performance

How fast Clojure runs on cohost, on each runtime, against stock Clojure on the JVM.

*Measured 2026-11-02/03 on cohost `main` @ ff30ce1 (the program columns on 6eec4e2, whose code is ff30ce1's for
every runtime except jank's `sort`; see [jank](#jank)). Two 4-CPU Linux boxes; every ratio is against stock Clojure
on the JVM measured on the same box, so ratios compare across boxes and milliseconds do not. Raw records:
`doc/perf/release-perf/`. cohost is pre-alpha; these numbers will move.*

## At a glance

| runtime | startup: a hello-world `-main` | programs, against stock Clojure on the JVM (geomean) | the runtime's own Clojure, same programs (geomean) |
|---|---:|---:|---:|
| stock Clojure on the JVM (the reference) | 1.5 s | 1 | |
| cohost `:jvm` (runs the real Clojure CLI) | 1.4 s | 1 | |
| cohost `:jank` (jank 0.1, apt) | 2.6 s | x10.0 (24 of 28 programs) | x6.3 compiled ahead of time (14 of 28) |
| cohost `:jolt` (jolt 0.8.23, Chez Scheme) | 4.5 s | x9.6 (28) | x2.9 (28) |
| cohost `:cljs` (ClojureScript 1.12.42 on Node 24) | 8.9 s | x9.1 (28) | x3.9 (25) |
| ClojureScript in Chromium | | not measured | x3.0 (25) |
| cohost `:lg` (let-go 1.13.0, Go) | 15.4 s | not yet measured | not yet measured |
| cohost `:phel` (Phel 0.54.0, PHP 8.5) | 47.0 s | not yet measured | not yet measured |
| cohost `:lpy` (basilisp 0.5.1, Python) | 88.6 s | not yet measured | not yet measured |
| cohost `:cljw` (ClojureWasm 1.15.4) | 94.0 s | not yet measured | x20.5 (28) |

How to read it:

- **"Programs"** is the geometric mean, over the suite's programs that ran and gave the JVM's answer, of each
  program's time per call (warmed up) divided by stock Clojure's on the JVM. x10 means ten times as long.
- **"The runtime's own Clojure"** runs the same programs on the runtime's own core library instead of on
  Clojure's, which cohost boots. It is what the runtime's own dialect costs, so the gap between the two columns is
  what running Clojure's own `clojure.core` costs on that runtime.
- **Startup** is the median of 3 wall times of `cohost :<runtime> -M -m demo.main` in `dev/cli-demo` (it requires
  one library namespace and prints a line), caches warm. cohost boots `clojure.core` from Clojure's source at every
  start, except on jank, which loads a compiled snapshot of the booted core ([Startup](#startup)).

## Startup

| command | median | the 3 runs, ms |
|---|---:|---|
| `clojure -M -m demo.main` (stock) | 1519 ms | 1428 1583 1519 |
| `cohost :jvm -M -m demo.main` | 1407 ms | 1386 1407 1414 |
| `cohost :jank -M -m demo.main` | 2550 ms | 2482 2814 2550 |
| `cohost :jolt -M -m demo.main` | 4534 ms | 4427 4534 5186 |
| `cohost :cljs -M -m demo.main` | 8853 ms | 8325 9937 8853 |
| `cohost :lg -M -m demo.main` | 15446 ms | 14042 15653 15446 |
| `cohost :phel -M -m demo.main` | 46994 ms | 47358 46159 46994 |
| `cohost :lpy -M -m demo.main` | 88557 ms | 88557 88793 86305 |
| `cohost :cljw -M -m demo.main` | 94010 ms | 94010 94479 92136 |

Box 1, `main` @ ff30ce1, 2026-11-03 12:41 AM ET (`doc/perf/release-perf/lane-c/startup.txt`).

- `:jvm` is the real Clojure CLI; cohost adds nothing.
- `:jank` loads a snapshot of the booted core, built once by `bb boot:jank-snapshot` in the cohost checkout
  (about 12 minutes) and rebuilt when cohost or jank changes. Without a fresh snapshot, jank boots from cohost's
  ahead-of-time objects, which is slower.
- Every other runtime reads, rewrites and evaluates `clojure.core` and the libraries the program requires at
  every start. That is the whole difference between 4.5 s on jolt and 94 s on ClojureWasm: how fast each one
  evaluates Clojure's own source. basilisp has a cached boot (`bb boot:lpy` uses it); the `cohost` command does not
  use it yet.
- The compiled builds (`cohost -T:build build`) start faster on some runtimes: [Compiled builds](#compiled-builds).

The programs' tables below also carry `first-output`: the wall time to print one line from a one-line program,
through the benchmark's own launch of each runtime (no project, no library), median of 3 processes.

## Programs

The suite is `bench/programs/`: fib, tak, nbody, spectral-norm, mandelbrot, binary-trees (depths 18 and 14), fannkuch,
nsieve, k-nucleotide, a lazy pipeline, map-heavy `pipeline`, `keyword-map`, protocol `dispatch`, a JSON-like
round trip, `sort`, and `sum-overflow` (sums past 2^31). Each is sized so that one call takes 100-1000 ms on the
JVM. Ten of them come twice: plain, and with primitive type hints (`^long`, `^double`, coercions) as in
performance-minded JVM code.

Method, per program and runtime: one fresh process; the first call is timed as cold; warm-up continues until the
median of the last 5 calls moves under 2% (at most 20 calls or 10 s); then 10 timed calls, or as many as fit in
15 s with at least 3, of which the table shows the median; a program's process is stopped after 15 minutes. Every
call's result is checked against the first call's, and the first call's against the JVM's. A call of several seconds
gains nothing from ten timed calls: the 15 s budget came in on 2026-11-03 at 1:37 AM ET. Boxes 1 to 3 were measured
with 10 timed calls (and `first-output` over 10 processes) every time; the medians compare all the same.

### Box 1: the JVM, ClojureScript and jolt

`bb bench:programs table jvm cljs cljs-cohost browser jolt jolt-cohost`. Median ms (xJVM). `cljs` and `jolt` are
the runtimes' own cores; `-cohost` is Clojure's core booted by cohost on them; `browser` is ClojureScript's own core
in headless Chromium.

| program | jvm | cljs | cljs-cohost | browser | jolt | jolt-cohost |
|---|---:|---:|---:|---:|---:|---:|
| fib | 164.8 | 312.0 (x1.89) | 387.7 (x2.35) | 201.9 (x1.22) | 266.1 (x1.61) | 1008.5 (x6.12) |
| fib-hinted | 90.6 | 291.5 (x3.22) | 2890.2 (x31.88) | 189.1 (x2.09) | 196.6 (x2.17) | 13080.0 (x144.30) |
| tak | 457.0 | error | 1651.8 (x3.61) | error | 550.1 (x1.20) | 2897.7 (x6.34) |
| tak-hinted | 229.1 | error | 12980.9 (x56.66) | error | 525.3 (x2.29) | 54551.6 (x238.10) |
| nbody | 225.6 | 1117.4 (x4.95) | 2019.1 (x8.95) | 875.7 (x3.88) | 826.0 (x3.66) | 1699.3 (x7.53) |
| nbody-hinted | 256.3 | 986.2 (x3.85) | 2283.6 (x8.91) | 814.7 (x3.18) | 734.5 (x2.87) | 1455.5 (x5.68) |
| spectral-norm | 241.9 | 1992.3 (x8.24) | 3365.4 (x13.91) | 1219.5 (x5.04) | 1232.8 (x5.10) | 2175.5 (x8.99) |
| spectral-norm-hinted | 143.8 | 1924.1 (x13.38) | 4239.7 (x29.49) | 1217.8 (x8.47) | 977.3 (x6.80) | 6177.0 (x42.97) |
| mandelbrot | 245.7 | 3045.4 (x12.39) | 5412.8 (x22.03) | 2360.0 (x9.60) | 1956.7 (x7.96) | 2512.5 (x10.23) |
| mandelbrot-hinted | 236.9 | 3197.7 (x13.50) | 5182.9 (x21.88) | 2662.1 (x11.24) | 293.4 (x1.24) | 2787.6 (x11.77) |
| binary-trees | 249.0 | 821.1 (x3.30) | 1411.9 (x5.67) | 521.8 (x2.10) | 1327.6 (x5.33) | 2480.5 (x9.96) |
| binary-trees-hinted | 253.1 | 861.2 (x3.40) | 1958.2 (x7.74) | 468.2 (x1.85) | 1386.5 (x5.48) | 4727.6 (x18.68) |
| binary-trees-14 | 12.2 | 29.2 (x2.38) | 66.8 (x5.46) | 16.8 (x1.37) | 53.3 (x4.36) | 101.7 (x8.31) |
| binary-trees-14-hinted | 9.9 | 30.2 (x3.05) | 91.2 (x9.21) | 23.2 (x2.34) | 59.8 (x6.04) | 203.4 (x20.53) |
| fannkuch | 229.0 | 776.1 (x3.39) | 1026.2 (x4.48) | 573.5 (x2.50) | 399.2 (x1.74) | 538.6 (x2.35) |
| fannkuch-hinted | 198.2 | 812.6 (x4.10) | 1048.8 (x5.29) | 592.4 (x2.99) | 425.4 (x2.15) | 484.5 (x2.44) |
| nsieve | 273.8 | 871.6 (x3.18) | 2365.7 (x8.64) | 541.4 (x1.98) | 1747.0 (x6.38) | 2199.7 (x8.03) |
| nsieve-hinted | 282.8 | 805.5 (x2.85) | 2354.9 (x8.33) | 527.9 (x1.87) | 1684.3 (x5.96) | 2191.2 (x7.75) |
| k-nucleotide | 156.4 | 948.3 (x6.06) | 1522.5 (x9.74) | 597.8 (x3.82) | 437.8 (x2.80) | 1063.6 (x6.80) |
| lazy-pipeline | 32.8 | 131.6 (x4.02) | 302.8 (x9.24) | 94.2 (x2.87) | 62.3 (x1.90) | 214.1 (x6.53) |
| pipeline | 216.7 | 667.8 (x3.08) | 1045.2 (x4.82) | 622.7 (x2.87) | 1157.5 (x5.34) | 1270.6 (x5.86) |
| keyword-map | 130.6 | 471.5 (x3.61) | 905.8 (x6.94) | 333.0 (x2.55) | 193.0 (x1.48) | 568.6 (x4.35) |
| dispatch | 175.2 | 854.0 (x4.88) | 3801.8 (x21.70) | 713.9 (x4.08) | 2336.4 (x13.34) | 2342.2 (x13.37) |
| json | 285.1 | wrong answer | 2421.2 (x8.49) | wrong answer | 693.9 (x2.43) | 7541.5 (x26.45) |
| sort | 42.9 | 119.2 (x2.78) | 103.9 (x2.43) | 85.7 (x2.00) | 69.1 (x1.61) | 78.0 (x1.82) |
| sum-overflow | 9.9 | 9.1 (x0.92) | 8.0 (x0.81) | 6.1 (x0.62) | 25.9 (x2.63) | 28.6 (x2.89) |
| sum-overflow-hinted | 0.9 | 50.0 (x58.05) | 72.0 (x83.57) | 38.2 (x44.31) | 1.6 (x1.87) | 8.5 (x9.85) |
| first-output (ms per process) | 815.0 | 139.0 (x0.17) | 7761.1 (x9.52) | 834.6 (x1.02) | 202.1 (x0.25) | 3847.5 (x4.72) |
| **geomean xJVM** (programs that ran) | 1 | **x3.93** (25) | **x9.13** (28) | **x3.01** (25) | **x2.91** (28) | **x9.57** (28) |

Box 1: Intel Xeon @ 2.80 GHz, 4 CPUs, 15 GB; Linux; OpenJDK with Clojure 1.12.0; runs one at a time, nothing else
on the box (load about 1). Records: `doc/perf/release-perf/lane-c/`.

### Box 2: the JVM and jank

`bb bench:programs table jvm jvm-cohost jank-jit jank-aot jank`. `jvm-cohost` is Clojure's core booted by cohost on
the JVM (not what `cohost :jvm` runs; it shows cohost's own overhead on the reference host). `jank-jit` and
`jank-aot` are jank's own core (`jank run`; `jank compile-module -O3`); `jank` is cohost on jank, from its snapshot.

| program | jvm | jvm-cohost | jank-jit | jank-aot | jank |
|---|---:|---:|---:|---:|---:|
| fib | 174.0 | 629.3 (x3.62) | 185.4 (x1.07) | 148.6 (x0.85) | 187.3 (x1.08) |
| fib-hinted | 86.7 | 321.6 (x3.71) | 340.9 (x3.93) | 329.5 (x3.80) | 312.0 (x3.60) |
| tak | 361.0 | 372.0 (x1.03) | 786.1 (x2.18) | 718.7 (x1.99) | 770.8 (x2.14) |
| tak-hinted | 217.2 | 1595.6 (x7.35) | 1586.1 (x7.30) | 1360.1 (x6.26) | 1644.5 (x7.57) |
| nbody | 191.9 | 287.4 (x1.50) | error | error | 2869.0 (x14.95) |
| nbody-hinted | 197.7 | 229.5 (x1.16) | error | error | 2803.3 (x14.18) |
| spectral-norm | 202.2 | 269.4 (x1.33) | error | error | 3763.9 (x18.61) |
| spectral-norm-hinted | 89.2 | 272.9 (x3.06) | error | error | 4386.8 (x49.19) |
| mandelbrot | 264.4 | 262.4 (x0.99) | error | error | 1331.7 (x5.04) |
| mandelbrot-hinted | 248.4 | 264.4 (x1.06) | error | error | 1229.2 (x4.95) |
| binary-trees | 226.7 | 215.5 (x0.95) | 3370.7 (x14.87) | 3312.8 (x14.61) | 3199.8 (x14.11) |
| binary-trees-hinted | 225.9 | 242.7 (x1.07) | 3149.8 (x13.94) | 3152.9 (x13.96) | 2692.3 (x11.92) |
| binary-trees-14 | 15.4 | 15.4 (x1.00) | 154.9 (x10.07) | 133.7 (x8.69) | 91.9 (x5.98) |
| binary-trees-14-hinted | 20.8 | 17.6 (x0.85) | 141.8 (x6.82) | 142.7 (x6.87) | 94.5 (x4.55) |
| fannkuch | 209.2 | 217.3 (x1.04) | error | error | 2664.7 (x12.74) |
| fannkuch-hinted | 189.8 | 223.5 (x1.18) | error | error | 2578.3 (x13.58) |
| nsieve | 151.1 | 345.9 (x2.29) | error | error | 1066.6 (x7.06) |
| nsieve-hinted | 203.5 | 397.0 (x1.95) | error | error | 1406.0 (x6.91) |
| k-nucleotide | 262.2 | 283.7 (x1.08) | 755.5 (x2.88) | 731.4 (x2.79) | 2794.8 (x10.66) |
| lazy-pipeline | 41.7 | 58.0 (x1.39) | 1069.4 (x25.65) | 996.8 (x23.91) | 3140.9 (x75.34) |
| pipeline | 212.6 | 310.4 (x1.46) | wrong answer | wrong answer | wrong answer |
| keyword-map | 156.6 | 149.2 (x0.95) | 498.8 (x3.19) | 508.5 (x3.25) | 1105.7 (x7.06) |
| dispatch | 343.0 | 810.7 (x2.36) | error | error | wrong answer |
| json | 508.8 | 608.4 (x1.20) | 2188.8 (x4.30) | 1867.2 (x3.67) | 5706.0 (x11.21) |
| sort | 45.8 | 47.5 (x1.04) | 16136.9 (x351.99) | 27603.6 (x602.11) | 35.8 ms after the sort fix (release ff30ce1, box 1) |
| sum-overflow | 17.0 | 15.2 (x0.89) | wrong answer | wrong answer | wrong answer |
| sum-overflow-hinted | 1.0 | 14.7 (x14.22) | wrong answer | wrong answer | wrong answer |
| first-output (ms per process) | 1104.0 | 7991.5 (x7.24) | 695.7 (x0.63) | 667.5 (x0.60) | 6792.7 (x6.15) |
| **geomean xJVM** (programs that ran) | 1 | **x1.68** (28) | **x6.45** (14) | **x6.26** (14) | **x8.89** (22) |

Box 2: 4 CPUs, Linux, jank 0.1-noble from jank's apt repository (what `dev/install/jank.sh` installs; no patches),
`main`'s code at 6eec4e2, 2026-11-02 evening. Records: `doc/perf/release-perf/` (muse/release-perf).

### Box 3: ClojureWasm

Box 3: 4 CPUs, Linux, ClojureWasm 1.15.4, nothing else running; the JVM column is this box's own. `cljw` is
ClojureWasm's own core, all 28 programs (2026-11-03 12:39 to 1:19 AM ET). Median ms of 10 timed calls after warm-up
(xJVM in parens). cohost on ClojureWasm (`cljw-cohost`) is running and fills in here when it finishes. Records:
`doc/perf/release-perf-d/` (lane D, d/release-perf @ 8c83afe).

| program | jvm | cljw |
|---|---:|---:|
| binary-trees | 257.5 | 11297.2 (x43.87) |
| binary-trees-14 | 11.4 | 454.1 (x39.99) |
| binary-trees-14-hinted | 9.4 | 463.8 (x49.48) |
| binary-trees-hinted | 245.7 | 10026.0 (x40.81) |
| dispatch | 138.0 | 2835.2 (x20.54) |
| fannkuch | 148.0 | 2811.3 (x18.99) |
| fannkuch-hinted | 165.6 | 3049.3 (x18.41) |
| fib | 163.6 | 6167.9 (x37.70) |
| fib-hinted | 99.8 | 5362.0 (x53.70) |
| first-output | 647.3 | 21.1 (x0.03) |
| json | 234.0 | 4149.4 (x17.73) |
| k-nucleotide | 131.1 | 1887.1 (x14.40) |
| keyword-map | 85.7 | 832.3 (x9.71) |
| lazy-pipeline | 32.1 | 119.9 (x3.74) |
| mandelbrot | 206.4 | 37737.1 (x182.86) |
| mandelbrot-hinted | 219.0 | 37954.1 (x173.32) |
| nbody | 188.4 | 2757.8 (x14.64) |
| nbody-hinted | 190.0 | 2654.6 (x13.97) |
| nsieve | 142.5 | 1742.7 (x12.23) |
| nsieve-hinted | 163.0 | 1636.1 (x10.04) |
| pipeline | 143.0 | 2372.0 (x16.59) |
| sort | 36.9 | 92.4 (x2.50) |
| spectral-norm | 189.1 | 9853.7 (x52.10) |
| spectral-norm-hinted | 146.6 | 11221.8 (x76.54) |
| sum-overflow | 8.9 | 25.3 (x2.85) |
| sum-overflow-hinted | 0.6 | 58.8 (x95.08) |
| tak | 317.9 | 25820.4 (x81.23) |
| tak-hinted | 196.5 | 22689.9 (x115.49) |
| **geomean xJVM** (programs that ran) | 1 | **x20.48** (28) |

let-go and Phel are measured on Box 4 and basilisp on Box 1; their tables go in as each column finishes.

### Type hints

On the JVM, primitive hints make fib, tak and spectral-norm about twice as fast (hinted/unhinted median 0.44-0.60),
sum-overflow 12-16 times as fast, and leave the rest within 0.8-1.4. On cohost they often do the opposite:
`fib-hinted` is x31.9 the JVM on `cljs-cohost` against x2.35 unhinted, `tak-hinted` x238 on `jolt-cohost` against
x6.34. cohost gives a hinted parameter the JVM's semantics (a `^long` parameter is coerced to a long on every call,
on every runtime), and that coercion is paid on every call. On jank, hints compile to native types where jank has
them (`:native-hints`, [How it works](guide/08-how-it-works.md#performance-keys)), and the hinted rows run at
0.84-2.13 times the unhinted ones. For speed on cohost today, leave hints off except on the JVM.

### Programs that do not run, or give another answer

- **ClojureScript, `tak`** (its own core, Node and Chromium): `TypeError: Cannot read properties of undefined
  (reading 'call')`. The program runs through cohost on ClojureScript.
- **ClojureScript, `json`** (its own core): returns 0, where the JVM returns 870000. It runs through cohost.
- **jank's own core, `nbody`, `spectral-norm`** (both forms): `The symbol 'Math/sqrt' (or 'Math/round') could not
  be resolved`; **`dispatch`**: `The symbol 'defprotocol' could not be resolved`; **`mandelbrot`, `fannkuch`,
  `nsieve`** (both forms): jank 0.1's C++ code generation fails (`use of undeclared identifier 's28'`) on a `loop`
  inside a `loop` that rebinds a name of the outer one. All of them run through cohost on jank, which rewrites the
  interop and the loops.
- **jank, `sum-overflow` and `pipeline`** (own core and cohost): jank 0.1 adds and multiplies two integers that fit
  in 32 bits in 32 bits, so a sum past 2^31 wraps (1783293664 where the JVM gives 499999500000). **cohost on jank,
  `dispatch`**: the same wrap (1691001973 against 12500045833333).

## jank

- **cohost on jank against jank's own core** (`bb bench:jank`, 30 small cases of `clojure.core` use, Box 2): geomean
  x0.92, so Clojure's own core booted by cohost is slightly faster overall than jank's own. The worst five for
  cohost: reduce over a range x44.7 (68.6 ms against 1.5), `clojure.string/join` x39.3, a multimethod call x27.9,
  keyword lookup x4.0, map lookup x1.9.
- **sort.** jank 0.1's own `sort` result walks quadratically (each `next` copies the buffer), which is the x352-x602
  in jank's own columns. cohost on jank uses Clojure's own sort there (a boot probe picks; `main` since ff30ce1):
  SORT-ROW.
- **Patched jank.** cohost carries a patch series against jank (`upstream/jank/`) that fixes the 32-bit wrap,
  the sort walk and more; it is used only when `JANK_BIN` names a build of it. These tables are the jank that ships.

## Compiled builds

Not yet measured (`bb bench:programs <runtime>-built`, the artifact `cohost -T:build build` makes for each
runtime, run as `target/<name>/run`).

## What makes a runtime fast or slow on cohost

- cohost runs Clojure 1.12's own `clojure.core` source, compiled or interpreted by the runtime, with Clojure's JVM
  interop rewritten to a shim (`clojure.lang.*`, `java.*`) over the runtime's own data structures. A program's speed
  is the runtime's speed on that code, plus the shim on interop-heavy paths (multimethods, protocols, string
  building).
- **Natives**: a runtime can hand a stock function to its own implementation, one name at a time (`:natives`,
  [Adding a host](guide/09-adding-a-host.md)); each is gated by the batteries and the bench.
- **Performance keys**: seven switches per runtime, [How it works](guide/08-how-it-works.md#performance-keys).
- **Startup** is the boot of `clojure.core` from source, except where a snapshot or a cached boot loads instead.

## Measure your machine (add a box)

Every table above is one box: its own JVM column and the runtimes measured on it, one at a time, nothing else
running. To add a box, run the steps below and add a section like [Box 1](#box-1-the-jvm-clojurescript-and-jolt).
Box 1 is the complete example: its records are in `doc/perf/release-perf/lane-c/` (one `.edn` per column plus
`startup.txt`), and its table is the output of step 4 pasted in.

1. **Checkout and runtimes.** A cohost checkout of the commit you measure, and each runtime on `PATH`:

   ```bash
   # not checked: clones and installs
   git clone https://github.com/johnmn3/cohost.cljc ~/cohost && cd ~/cohost
   bash dev/install/jank.sh        # likewise jolt.sh, cljs.sh, lg.sh, phel.sh, basilisp.sh, cljw.sh
   bb boot:jank-snapshot           # jank only: builds its core snapshot once (2 to 11 minutes)
   ```

2. **The JVM column first.** Every ratio is against this box's own JVM:

   ```bash
   # not checked: about 2 minutes
   bb bench:programs jvm
   ```

3. **One column per runtime, one at a time.** `<runtime>` is the runtime's own core; `<runtime>-cohost` is
   Clojure's core booted by cohost on it (on jank, `jank` is cohost; `bb bench:jank` compares it with jank's own
   core). The compiled builds are `<runtime>-built`. Each column writes `target/bench-programs/results/<column>.edn`.

   ```bash
   # not checked: minutes (jolt, cljs) to an hour or more (cljw, lpy) per column
   bb bench:programs jolt
   bb bench:programs jolt-cohost
   bb bench:programs jank
   bb bench:programs cljs              # then cljs-cohost, browser, lg, lg-cohost, phel, phel-cohost,
                                       # lpy, lpy-cohost, cljw, cljw-cohost, jank-built, jolt-built, ...
   ```

   Never run two at once: two ClojureScript compiles at once can corrupt `~/.cljs/.aot_cache`, and any other load
   skews the times.

4. **The table.** Paste its output into your box's section:

   ```bash
   # not checked: reads the records step 3 wrote
   bb bench:programs table jvm jolt jolt-cohost jank cljs cljs-cohost
   ```

5. **Startup.** Three wall times per runtime of a hello-world `-main` in `dev/cli-demo`, after one untimed run
   (the first run of jank and basilisp builds their caches); the table shows the median:

   ```bash
   # not checked: minutes; prints one line per run
   cd dev/cli-demo
   for rt in jvm jank jolt cljs lg phel lpy cljw; do
     ../../bin/cohost :$rt -M -m demo.main warm > /dev/null 2>&1
     for i in 1 2 3; do
       bb -e "(let [t (System/nanoTime)] (babashka.process/shell {:out :string :err :string} \"../../bin/cohost\" \":$rt\" \"-M\" \"-m\" \"demo.main\" \"me\") (println \"STARTUP $rt\" (quot (- (System/nanoTime) t) 1000000) \"ms\"))"
     done
   done
   cd ../..
   ```

6. **The box's section and records.** Head the section with the box: CPUs, OS, each runtime's version, the
   cohost commit (`git rev-parse --short HEAD`), the date and time, and the load average when each column started.
   Copy `target/bench-programs/results/*.edn` and the startup lines into `doc/perf/<box-name>/`, commit both on a
   branch and push it.

The history of how these numbers came to be is in [`doc/perf/HISTORY.md`](perf/HISTORY.md).
