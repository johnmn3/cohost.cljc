# Performance

How fast Clojure runs on cohost, on each runtime, against stock Clojure on the JVM.

Two things are measured, per box, per way of running a program:

1. **Startup**: the wall time of a hello-world `-main` (`dev/cli-demo`, `demo.main`), median of 3 runs after one
   untimed run.
2. **Speed**: the time per call of 28 benchmark programs, as the geometric mean of each program's ratio to stock
   Clojure on the JVM on the same box (x3 = three times as long as the JVM).

The ways of running:

| mode | command | what it is |
|---|---|---|
| **from source** | `cohost :<runtime> ...` | boots `clojure.core` from Clojure's own source on every start; what the batteries test |
| **cached boot** | `cohost :jank ...`, `cohost :lpy ...` | the booted core saved once (jank: a snapshot and a native launcher; basilisp: a cached boot) and loaded instead; the default after `dev/install/<runtime>.sh` |
| **built app** | `cohost -T:build build`, then `target/<app>/run` | the app in the runtime's own compiled form ([doc/CLI.md](CLI.md#building), [doc/AOT-PLAN.md](AOT-PLAN.md)); what a deployment runs |
| **own Clojure** | `jank run`, `jolt`, `cljw`, ... | the runtime's own core library, not cohost: the reference for what running Clojure's `clojure.core` costs on it |

Every ratio is against the JVM measured on the same box, so ratios compare across boxes and milliseconds do not.
Cells: `—` not measured; `fails` the program does not run or build there (why: [Known problems](#known-problems));
`differs` it runs but gives another answer; `> x1000` cut off and unrecorded (a bug, listed below), left out of the
geomean, whose count of programs follows it. cohost is pre-alpha; these numbers move with each release.

## The boxes

| box | hardware | runtimes | cohost | records |
|---|---|---|---|---|
| macOS | Apple M5, 10 cores, 32 GB, macOS 26.5; a desktop in use (load 3.2-4.8 at each column's start, the JVM column under the same load) | jank 0.1 (Homebrew tap), jolt 0.8.23, ClojureWasm 1.15.4, ClojureScript 1.12.42 on Node 22, let-go 1.13.0, OpenJDK 26 | c0431c6; the launcher on c415244 | `doc/perf/macos/` |
| Box 4 | Linux, Intel Xeon 2.10 GHz, 4 CPUs; the coordinator's box, other work running | jank 0.1 (apt), jolt 0.8.23, let-go 1.13.0, Phel 0.54 on PHP 8.3, basilisp 0.5.1 | 4497c85 (run A), c0431c6 (run B) | `doc/perf/box4/` |
| Box 3 | Linux, 4 CPUs; nothing else running | ClojureWasm 1.15.4, jolt, ClojureScript | 4497c85 | `doc/perf/release-perf-d/`, `doc/perf/built-d/` |
| Box 2 | Linux, 4 CPUs | jank 0.1 (apt) | 6eec4e2 | `doc/perf/release-perf/` |
| Box 1 | Linux, Intel Xeon 2.80 GHz, 4 CPUs, 15 GB; nothing else running | jolt, ClojureScript 1.12.42 (Node and headless Chromium), Phel 0.54, let-go, basilisp, ClojureWasm | ff30ce1 (startup), c/aot-phel (Phel's built app) | `doc/perf/release-perf/lane-c/` |

The benchmark programs and the method: [Method](#method). To measure your own machine: [Add a box](#add-a-box).

## macOS (Apple M5)

### Startup

| runtime | from source | cached boot | built app (`run you`) |
|---|---:|---:|---:|
| jank | — | 0.55 s (`-m demo.main`, warm), `-e` 0.59 s | — |
| jolt | — | — | 0.37 s |
| basilisp | — | — | 1.9 s |
| ClojureWasm | — | — | 0.9 s |
| JVM, ClojureScript, let-go, Phel | — | — | — |

jank's cached boot is the launcher with the per-project snapshot (main d016cf5): the first `cohost :jank` command of
a project took 219 s here (155.5 s the snapshot and launcher, `SNAPSHOT-BUILT`; about 64 s the project's namespaces
compiled ahead), then 0.54-0.55 s per run. On the launcher alone (c415244) `-m demo.main` took 1.34 s. The snapshot
and launcher: batteries 214/216 and 62/62, the binary 91.5 MB. The built apps' `run you` are the README's app, run
from another directory (2026-11-03, aot/native-builds).

### Speed (geomean xJVM, programs counted)

| runtime | own Clojure | cohost, from source | cohost, built app |
|---|---:|---:|---:|
| jank | x6.66 (13, JIT); x6.47 (13, AOT -O3) | — | x3.40 (28); x2.37 over the 13 the own core runs |
| jolt | — | — | x7.13 (28) |
| ClojureScript | — | — | x10.33 (28) |
| ClojureWasm | — | — | x91.56 (26; 2 cut) |
| let-go | — | — | x89.11 (18; 5 cut; 23 of 28 run) |
| Phel | — | — | fails (the build) |
| basilisp, JVM | — | — | — |

The built apps were measured on c0431c6 (2026-11-03, 11:36 AM to 12:58 PM ET); jank's again on c415244 (the launcher
release): x3.36, every row within 5%. jank's own core was measured the night before (0c8834f, the same method and
binary, against that run's JVM column). cohost's built app on jank is at or under the JVM on the call-heavy programs:
`tak-hinted` x0.56, `fib` x0.91, `first-output` x0.97.

### Per program: built apps (median ms, xJVM)

| program | jvm | jank-built | jolt-built | cljs-built | cljw-built | lg-built |
|---|---:|---:|---:|---:|---:|---:|
| fib | 75 | 68 (x0.91) | 131 (x1.75) | 322 (x4.31) | 5,098 (x68.24) | 2,425 (x32.46) |
| fib-hinted | 34 | 35 (x1.03) | 4,206 (x122.75) | 1,398 (x40.80) | > x1000 | 23,993 (x700.15) |
| tak | 167 | 237 (x1.42) | 247 (x1.48) | 1,181 (x7.07) | 16,034 (x96.03) | 8,450 (x50.61) |
| tak-hinted | 96 | 53 (x0.56) | 17,271 (x179.51) | 5,739 (x59.65) | > x1000 | > x1000 |
| nbody | 106 | 321 (x3.03) | 321 (x3.03) | 657 (x6.20) | 4,666 (x44.06) | 5,714 (x53.95) |
| nbody-hinted | 104 | 313 (x3.02) | 308 (x2.97) | 657 (x6.32) | 4,482 (x43.15) | 5,786 (x55.72) |
| spectral-norm | 47 | 472 (x10.04) | 535 (x11.38) | 1,072 (x22.80) | 7,475 (x158.93) | 31,896 (x678.13) |
| spectral-norm-hinted | 36 | 359 (x10.02) | 1,805 (x50.34) | 1,419 (x39.59) | 20,887 (x582.61) | > x1000 |
| mandelbrot | 75 | 315 (x4.22) | 634 (x8.49) | 2,000 (x26.78) | 23,033 (x308.42) | > x1000 |
| mandelbrot-hinted | 67 | 314 (x4.66) | 654 (x9.68) | 1,977 (x29.29) | 24,831 (x367.96) | > x1000 |
| binary-trees | 86 | 318 (x3.69) | 615 (x7.14) | 617 (x7.17) | 7,654 (x88.92) | 10,758 (x124.98) |
| binary-trees-hinted | 82 | 313 (x3.80) | 1,381 (x16.74) | 861 (x10.44) | 14,470 (x175.40) | 14,825 (x179.70) |
| binary-trees-14 | 4.9 | 17 (x3.40) | 29 (x5.92) | 28 (x5.73) | 345 (x70.92) | 514 (x105.67) |
| binary-trees-14-hinted | 4.6 | 17 (x3.65) | 65 (x14.03) | 39 (x8.40) | 716 (x154.84) | 695 (x150.20) |
| sum-overflow | 2.3 | 12 (x5.29) | 8.9 (x3.96) | 2.9 (x1.29) | 9.1 (x4.05) | 18 (x7.89) |
| sum-overflow-hinted | 0.3 | 5.0 (x18.38) | 2.0 (x7.26) | 28 (x102.75) | 33 (x122.57) | > x1000 |
| sort | 12 | 17 (x1.39) | 30 (x2.52) | 36 (x2.95) | 2,793 (x232.08) | 522 (x43.35) |
| lazy-pipeline | 11 | 50 (x4.50) | 42 (x3.81) | 96 (x8.59) | 5,176 (x465.51) | 2,181 (x196.12) |
| keyword-map | 43 | 164 (x3.82) | 156 (x3.63) | 269 (x6.27) | 2,160 (x50.36) | 9,036 (x210.73) |
| fannkuch | 85 | 259 (x3.04) | 103 (x1.20) | 285 (x3.33) | 3,149 (x36.85) | 2,602 (x30.45) |
| fannkuch-hinted | 67 | 264 (x3.97) | 104 (x1.57) | 282 (x4.24) | 3,162 (x47.49) | 2,611 (x39.22) |
| nsieve | 45 | 210 (x4.65) | 655 (x14.50) | 619 (x13.70) | 5,716 (x126.51) | 4,282 (x94.77) |
| nsieve-hinted | 46 | 212 (x4.56) | 657 (x14.14) | 614 (x13.21) | 5,596 (x120.49) | 4,360 (x93.89) |
| k-nucleotide | 52 | 202 (x3.88) | 344 (x6.61) | 505 (x9.70) | 3,411 (x65.52) | — |
| pipeline | 60 | 304 (x5.03) | 339 (x5.61) | 249 (x4.12) | 2,911 (x48.17) | — |
| dispatch | 70 | 331 (x4.70) | 431 (x6.12) | 1,084 (x15.39) | 13,115 (x186.23) | — |
| json | 74 | 461 (x6.28) | 1,535 (x20.88) | 789 (x10.73) | 17,993 (x244.78) | — |
| first-output | 195 | 190 (x0.97) | 339 (x1.74) | 1,838 (x9.41) | 850 (x4.35) | — |
| **geomean xJVM** (recorded programs) | 1 | **x3.40** (28) | **x7.13** (28) | **x10.33** (28) | **x91.56** (26; 2 cut) | **x89.11** (18; 5 cut) |

## Box 4 (Linux, Xeon 2.10 GHz)

### Startup

| runtime | from source | cached boot | built app (`run you`) |
|---|---:|---:|---:|
| jank | — | 2.1 s (`-m demo.main`; the first run 37 s), `-e` 2.0 s | — |
| basilisp | — | 18.4-21.0 s (the first command 225 s) | — |
| the others | — | — | — |

jank: the launcher with the per-project snapshot (main after 0b6a476; the first `-m` of a project compiles its
namespaces ahead). On the launcher release before it (c415244) `-m` took 3.6 s and `-e` 1.9 s, and through `jank
run-main` (no launcher) 2.07 s and 2.06 s. basilisp: release candidate b2ec699, 1:48 AM ET. The box was busy with other
work for every measurement here.

### Speed (geomean xJVM, programs counted)

Run A, cohost 4497c85, the JVM column of 1:18 AM ET; run B, cohost c0431c6, the JVM column of 10:19 AM ET.

| runtime | own Clojure | cohost, from source | cohost, built app |
|---|---:|---:|---:|
| JVM | 1 | — | x1.01 (28) (A) |
| jank | — | — | x4.40 (28) (B) |
| jolt | — | — | x6.28 (28) (A); x6.57 (28) (B) |
| let-go | x19.32 (28) (A) | x100.96 (24; 1 cut) (A) | — |
| Phel | x23.70 (15) (A) | — | — |
| basilisp | — | — | unrecorded: > x1000 on 24 of the 25 programs at the smaller sizes (A) |
| ClojureScript, ClojureWasm | — | — | — |

basilisp's built app is cohost's cached boot, not compiled code; at the suite's sizes one call takes minutes, and at
lane C's smaller sizes (`doc/perf/box4/jvm-small.edn`, `lpy-built-small.edn`) every ratio but `pipeline` (x325) is
over x1000. Phel's own core runs 15 of the 28 (12 fail on its own dialect, `json` differs).

### Per program, run B (c0431c6): built apps (median ms, xJVM)

| program | jvm | jank-built | jolt-built |
|---|---:|---:|---:|
| fib | 118 | 153 (x1.30) | 294 (x2.50) |
| fib-hinted | 77 | 64 (x0.83) | 8,909 (x116.31) |
| tak | 265 | 492 (x1.86) | 437 (x1.65) |
| tak-hinted | 147 | 128 (x0.87) | 37,246 (x252.69) |
| nbody | 206 | 1,182 (x5.74) | 941 (x4.57) |
| nbody-hinted | 202 | 1,185 (x5.88) | 685 (x3.40) |
| spectral-norm | 223 | 816 (x3.65) | 1,380 (x6.18) |
| spectral-norm-hinted | 120 | 1,165 (x9.68) | 4,304 (x35.77) |
| mandelbrot | 172 | 752 (x4.38) | 1,463 (x8.53) |
| mandelbrot-hinted | 170 | 785 (x4.63) | 1,337 (x7.88) |
| binary-trees | 242 | 1,374 (x5.68) | 1,411 (x5.83) |
| binary-trees-hinted | 263 | 1,384 (x5.26) | 2,964 (x11.26) |
| binary-trees-14 | 12 | 75 (x6.02) | 59 (x4.77) |
| binary-trees-14-hinted | 11 | 71 (x6.71) | 134 (x12.67) |
| sum-overflow | 11 | 35 (x3.22) | 23 (x2.11) |
| sum-overflow-hinted | 0.5 | 8.0 (x16.00) | 2.9 (x5.80) |
| sort | 33 | 63 (x1.92) | 65 (x1.99) |
| lazy-pipeline | 27 | 212 (x7.86) | 110 (x4.09) |
| keyword-map | 95 | 653 (x6.85) | 327 (x3.44) |
| fannkuch | 135 | 953 (x7.07) | 254 (x1.88) |
| fannkuch-hinted | 154 | 997 (x6.48) | 249 (x1.62) |
| nsieve | 183 | 704 (x3.85) | 1,616 (x8.84) |
| nsieve-hinted | 176 | 696 (x3.96) | 1,681 (x9.56) |
| k-nucleotide | 134 | 755 (x5.62) | 680 (x5.06) |
| pipeline | 150 | 1,638 (x10.94) | 995 (x6.64) |
| dispatch | 141 | 1,109 (x7.88) | 944 (x6.70) |
| json | 204 | 1,923 (x9.44) | 4,105 (x20.15) |
| first-output | 667 | 790 (x1.18) | 1,165 (x1.75) |
| **geomean xJVM** (recorded programs) | 1 | **x4.40** (28) | **x6.57** (28) |

### Per program, run A (4497c85) (median ms, xJVM)

| program | jvm | lg | phel | lg-cohost | jvm-built | jolt-built |
|---|---:|---:|---:|---:|---:|---:|
| fib | 122 | 4,792 (x39.34) | 3,169 (x26.02) | 5,237 (x43.00) | 117 (x0.96) | 289 (x2.37) |
| fib-hinted | 76 | 4,787 (x62.74) | fails | 57,107 (x748.45) | 86 (x1.13) | 8,802 (x115.36) |
| tak | 260 | 15,315 (x58.90) | 5,407 (x20.79) | 19,483 (x74.93) | 250 (x0.96) | 440 (x1.69) |
| tak-hinted | 162 | 16,066 (x99.17) | fails | fails | 156 (x0.96) | 36,048 (x222.52) |
| nbody | 213 | 2,267 (x10.65) | fails | 15,261 (x71.68) | 188 (x0.88) | 748 (x3.52) |
| nbody-hinted | 213 | 2,228 (x10.49) | fails | 15,203 (x71.54) | 203 (x0.95) | 720 (x3.39) |
| spectral-norm | 222 | 7,794 (x35.06) | fails | 80,771 (x363.34) | 230 (x1.03) | 1,402 (x6.31) |
| spectral-norm-hinted | 134 | 8,986 (x67.16) | fails | 100,725 (x752.80) | 130 (x0.97) | 4,151 (x31.02) |
| mandelbrot | 188 | 20,079 (x106.86) | 103,937 (x553.15) | fails | 170 (x0.91) | 1,434 (x7.63) |
| mandelbrot-hinted | 175 | 20,330 (x116.44) | fails | fails | 172 (x0.98) | 1,309 (x7.50) |
| binary-trees | 247 | 9,834 (x39.85) | 16,357 (x66.28) | 26,348 (x106.76) | 247 (x1.00) | 1,276 (x5.17) |
| binary-trees-hinted | 240 | 9,781 (x40.81) | fails | 36,094 (x150.58) | 250 (x1.04) | 2,919 (x12.18) |
| binary-trees-14 | 10 | 464 (x44.64) | 662 (x63.61) | 1,341 (x128.89) | 16 (x1.49) | 64 (x6.12) |
| binary-trees-14-hinted | 14 | 472 (x33.24) | fails | 1,835 (x129.23) | 11 (x0.75) | 153 (x10.75) |
| sum-overflow | 13 | 42 (x3.24) | 775 (x60.04) | 52 (x3.99) | 16 (x1.25) | 24 (x1.84) |
| sum-overflow-hinted | 0.5 | 82 (x164.80) | fails | > x1000 | 0.5 (x1.00) | 2.9 (x5.80) |
| sort | 37 | 252 (x6.76) | 121 (x3.23) | 1,350 (x36.18) | 46 (x1.23) | 73 (x1.97) |
| lazy-pipeline | 34 | 850 (x24.92) | fails | 5,442 (x159.57) | 27 (x0.78) | 122 (x3.58) |
| keyword-map | 95 | 1,172 (x12.32) | 4,305 (x45.27) | 22,304 (x234.54) | 113 (x1.19) | 354 (x3.72) |
| fannkuch | 142 | 1,617 (x11.38) | 3,792 (x26.69) | 7,294 (x51.33) | 147 (x1.03) | 291 (x2.05) |
| fannkuch-hinted | 163 | 1,605 (x9.84) | 3,641 (x22.32) | 7,053 (x43.24) | 161 (x0.99) | 280 (x1.72) |
| nsieve | 213 | 774 (x3.64) | 3,844 (x18.09) | 12,435 (x58.52) | 180 (x0.84) | 1,707 (x8.03) |
| nsieve-hinted | 180 | 802 (x4.45) | 3,915 (x21.72) | 12,616 (x70.01) | 181 (x1.00) | 1,696 (x9.41) |
| k-nucleotide | 129 | 2,029 (x15.78) | 4,974 (x38.68) | 41,268 (x320.90) | 134 (x1.04) | 660 (x5.13) |
| pipeline | 141 | 6,684 (x47.41) | 5,146 (x36.50) | 10,935 (x77.55) | 152 (x1.08) | 997 (x7.07) |
| dispatch | 153 | 3,182 (x20.82) | fails | 54,027 (x353.58) | 176 (x1.15) | 954 (x6.24) |
| json | 220 | 4,136 (x18.81) | differs | 58,805 (x267.42) | 209 (x0.95) | 3,817 (x17.36) |
| first-output | 805 | 12 (x0.02) | 92 (x0.11) | 9,656 (x12.00) | 711 (x0.88) | 1,075 (x1.34) |
| **geomean xJVM** (recorded programs) | 1 | **x19.32** (28) | **x23.70** (15) | **x100.96** (24; 1 cut) | **x1.01** (28) | **x6.28** (28) |

## Box 3 (Linux)

### Speed (geomean xJVM, programs counted)

| runtime | own Clojure | cohost, from source | cohost, built app |
|---|---:|---:|---:|
| JVM | 1 | — | x1.15 (28) |
| jolt | — | — | x7.03 (28) |
| ClojureScript | — | — | x10.66 (28) |
| ClojureWasm | x20.50 (28) | x79.34 (27) | x72.14 (27) |
| the others | — | — | — |

ClojureWasm's built app is the recorded boot as ClojureWasm modules on the shipped ReleaseSafe `cljw`; it skips the boot
from source and runs the programs about as fast as cohost from source. A `cljw` built with `-Doptimize=ReleaseFast`
takes x0.862 of ReleaseSafe's time on its own core (8 programs; `doc/perf/cljw-releasefast/`). Startup: not measured.

### Per program (median ms, xJVM)

| program | jvm | cljw | cljw-cohost | jvm-built | jolt-built | cljs-built | cljw-built |
|---|---:|---:|---:|---:|---:|---:|---:|
| fib | 164 | 6,168 (x37.70) | 8,247 (x50.41) | 150 (x0.92) | 315 (x1.92) | 757 (x4.63) | 8,007 (x48.94) |
| fib-hinted | 100 | 5,362 (x53.73) | 82,116 (x822.80) | 99 (x0.99) | 9,971 (x99.91) | 3,394 (x34.01) | 81,056 (x812.18) |
| tak | 318 | 25,820 (x81.22) | 25,635 (x80.64) | 367 (x1.16) | 506 (x1.59) | 2,277 (x7.16) | 26,214 (x82.46) |
| tak-hinted | 197 | 22,690 (x115.47) | fails | 249 (x1.26) | 42,078 (x214.14) | 11,925 (x60.69) | fails |
| nbody | 188 | 2,758 (x14.64) | 9,090 (x48.25) | 230 (x1.22) | 928 (x4.93) | 1,658 (x8.80) | 8,257 (x43.83) |
| nbody-hinted | 190 | 2,655 (x13.97) | 8,483 (x44.64) | 216 (x1.14) | 794 (x4.18) | 1,695 (x8.92) | 8,331 (x43.85) |
| spectral-norm | 189 | 9,854 (x52.11) | 12,814 (x67.76) | 249 (x1.32) | 1,407 (x7.44) | 2,972 (x15.71) | 12,875 (x68.08) |
| spectral-norm-hinted | 147 | 11,222 (x76.55) | 37,976 (x259.05) | 159 (x1.09) | 4,773 (x32.56) | 3,710 (x25.31) | 37,298 (x254.42) |
| mandelbrot | 206 | 37,737 (x182.83) | 45,245 (x219.21) | 218 (x1.05) | 1,531 (x7.42) | 5,198 (x25.19) | 46,419 (x224.90) |
| mandelbrot-hinted | 219 | 37,954 (x173.31) | 45,521 (x207.86) | 217 (x0.99) | 1,403 (x6.40) | 5,058 (x23.10) | 45,755 (x208.93) |
| binary-trees | 258 | 11,297 (x43.87) | 14,358 (x55.76) | 306 (x1.19) | 1,585 (x6.15) | 1,659 (x6.44) | 14,426 (x56.02) |
| binary-trees-hinted | 246 | 10,026 (x40.81) | 28,770 (x117.09) | 301 (x1.23) | 3,330 (x13.55) | 2,131 (x8.67) | 28,215 (x114.84) |
| binary-trees-14 | 11 | 454 (x39.83) | 724 (x63.49) | 19 (x1.68) | 71 (x6.22) | 87 (x7.61) | 649 (x56.96) |
| binary-trees-14-hinted | 9.4 | 464 (x49.34) | 1,359 (x144.52) | 12 (x1.32) | 161 (x17.07) | 103 (x10.90) | 1,357 (x144.37) |
| sum-overflow | 8.9 | 25 (x2.84) | 24 (x2.74) | 13 (x1.47) | 28 (x3.17) | 7.1 (x0.80) | 24 (x2.74) |
| sum-overflow-hinted | 0.6 | 59 (x98.00) | 58 (x96.67) | 1.1 (x1.83) | 5.5 (x9.17) | 77 (x128.33) | 54 (x90.00) |
| sort | 37 | 92 (x2.50) | 5,856 (x158.70) | 51 (x1.38) | 79 (x2.13) | 98 (x2.66) | 5,818 (x157.66) |
| lazy-pipeline | 32 | 120 (x3.74) | 7,736 (x240.98) | 37 (x1.17) | 122 (x3.79) | 235 (x7.33) | 8,229 (x256.34) |
| keyword-map | 86 | 832 (x9.71) | 3,277 (x38.24) | 90 (x1.05) | 404 (x4.71) | 768 (x8.96) | 5,126 (x59.81) |
| fannkuch | 148 | 2,811 (x19.00) | 6,169 (x41.68) | 162 (x1.10) | 291 (x1.97) | 921 (x6.23) | 6,664 (x45.03) |
| fannkuch-hinted | 166 | 3,049 (x18.41) | 6,312 (x38.12) | 159 (x0.96) | 328 (x1.98) | 906 (x5.47) | 6,509 (x39.30) |
| nsieve | 143 | 1,743 (x12.23) | 9,426 (x66.15) | 132 (x0.93) | 1,641 (x11.52) | 1,704 (x11.96) | 9,075 (x63.69) |
| nsieve-hinted | 163 | 1,636 (x10.04) | 9,440 (x57.91) | 145 (x0.89) | 1,707 (x10.47) | 1,712 (x10.50) | 9,536 (x58.50) |
| k-nucleotide | 131 | 1,887 (x14.39) | 4,684 (x35.73) | 131 (x1.00) | 734 (x5.60) | 1,378 (x10.51) | 7,342 (x56.01) |
| pipeline | 143 | 2,372 (x16.59) | 5,315 (x37.17) | 151 (x1.06) | 969 (x6.78) | 857 (x6.00) | 5,867 (x41.03) |
| dispatch | 138 | 2,835 (x20.54) | 13,419 (x97.24) | 178 (x1.29) | 1,058 (x7.67) | 3,291 (x23.85) | 17,447 (x126.43) |
| json | 234 | 4,149 (x17.73) | 31,890 (x136.28) | 245 (x1.04) | 4,279 (x18.29) | 2,029 (x8.67) | 32,821 (x140.26) |
| first-output | 647 | 21 (x0.03) | 87,356 (x134.96) | 700 (x1.08) | 893 (x1.38) | 6,664 (x10.30) | 2,206 (x3.41) |
| **geomean xJVM** (recorded programs) | 1 | **x20.50** (28) | **x79.34** (27) | **x1.15** (28) | **x7.03** (28) | **x10.66** (28) | **x72.14** (27) |

## Box 2 (Linux)

### Speed (geomean xJVM, programs counted)

| runtime | own Clojure | cohost, cached boot | cohost, built app |
|---|---:|---:|---:|
| JVM | 1 | x1.68 (28): Clojure's core booted by cohost on the JVM (cohost's own overhead, not what `cohost :jvm` runs) | — |
| jank | x6.44 (14, JIT); x6.26 (14, AOT -O3) | x8.74 (23): from its snapshot | — |

Measured 2026-11-02 on 6eec4e2, before the sort fix (ff30ce1) and the release of jank's built app. Startup: not
measured.

### Per program (median ms, xJVM)

| program | jvm | jvm-cohost | jank-jit | jank-aot | jank |
|---|---:|---:|---:|---:|---:|
| fib | 174 | 629 (x3.62) | 185 (x1.07) | 149 (x0.85) | 187 (x1.08) |
| fib-hinted | 87 | 322 (x3.71) | 341 (x3.93) | 330 (x3.80) | 312 (x3.60) |
| tak | 361 | 372 (x1.03) | 786 (x2.18) | 719 (x1.99) | 771 (x2.14) |
| tak-hinted | 217 | 1,596 (x7.35) | 1,586 (x7.30) | 1,360 (x6.26) | 1,645 (x7.57) |
| nbody | 192 | 287 (x1.50) | fails | fails | 2,869 (x14.95) |
| nbody-hinted | 198 | 230 (x1.16) | fails | fails | 2,803 (x14.18) |
| spectral-norm | 202 | 269 (x1.33) | fails | fails | 3,764 (x18.61) |
| spectral-norm-hinted | 89 | 273 (x3.06) | fails | fails | 4,387 (x49.18) |
| mandelbrot | 264 | 262 (x0.99) | fails | fails | 1,332 (x5.04) |
| mandelbrot-hinted | 248 | 264 (x1.06) | fails | fails | 1,229 (x4.95) |
| binary-trees | 227 | 216 (x0.95) | 3,371 (x14.87) | 3,313 (x14.61) | 3,200 (x14.11) |
| binary-trees-hinted | 226 | 243 (x1.07) | 3,150 (x13.94) | 3,153 (x13.96) | 2,692 (x11.92) |
| binary-trees-14 | 15 | 15 (x1.00) | 155 (x10.06) | 134 (x8.68) | 92 (x5.97) |
| binary-trees-14-hinted | 21 | 18 (x0.85) | 142 (x6.82) | 143 (x6.86) | 95 (x4.54) |
| sum-overflow | 17 | 15 (x0.89) | differs | differs | differs |
| sum-overflow-hinted | 1.0 | 15 (x14.70) | differs | differs | differs |
| sort | 46 | 48 (x1.04) | 16,137 (x352.33) | 27,604 (x602.70) | see note |
| lazy-pipeline | 42 | 58 (x1.39) | 1,069 (x25.65) | 997 (x23.90) | 3,141 (x75.32) |
| keyword-map | 157 | 149 (x0.95) | 499 (x3.19) | 509 (x3.25) | 1,106 (x7.06) |
| fannkuch | 209 | 217 (x1.04) | fails | fails | 2,665 (x12.74) |
| fannkuch-hinted | 190 | 224 (x1.18) | fails | fails | 2,578 (x13.58) |
| nsieve | 151 | 346 (x2.29) | fails | fails | 1,067 (x7.06) |
| nsieve-hinted | 204 | 397 (x1.95) | fails | fails | 1,406 (x6.91) |
| k-nucleotide | 262 | 284 (x1.08) | 756 (x2.88) | 731 (x2.79) | 2,795 (x10.66) |
| pipeline | 213 | 310 (x1.46) | differs | differs | differs |
| dispatch | 343 | 811 (x2.36) | fails | fails | differs |
| json | 509 | 608 (x1.20) | 2,189 (x4.30) | 1,867 (x3.67) | 5,706 (x11.21) |
| first-output | 1,104 | 7,992 (x7.24) | 696 (x0.63) | 668 (x0.60) | 6,793 (x6.15) |
| **geomean xJVM** (recorded programs) | 1 | **x1.68** (28) | **x6.44** (14) | **x6.26** (14) | **x8.74** (23) |

## Box 1 (Linux, Xeon 2.80 GHz)

### Startup (from source, `cohost :<runtime> -M -m demo.main`)

| runtime | from source | cached boot | built app (`run you`) |
|---|---:|---:|---:|
| stock `clojure -M -m demo.main` | 1.52 s | — | — |
| JVM (`cohost :jvm`, the real Clojure CLI) | 1.41 s | — | — |
| jank | 2.55 s (the snapshot, before the launcher) | — | — |
| jolt | 4.53 s | — | — |
| ClojureScript | 8.85 s | — | — |
| let-go | 15.4 s | — | — |
| Phel | 47.0 s | — | 1.3-2.0 s |
| basilisp | 88.6 s (from source; the cached boot came later) | — | — |
| ClojureWasm | 94.0 s | — | — |

`main` @ ff30ce1, 2026-11-03 12:41 AM ET (`doc/perf/release-perf/lane-c/startup.txt`). Every runtime without a cached
boot reads, rewrites and evaluates `clojure.core` and the program's libraries at each start; the spread from 4.5 s on
jolt to 94 s on ClojureWasm is how fast each one evaluates Clojure's own source.

### Speed (geomean xJVM, programs counted)

| runtime | own Clojure | cohost, from source | cohost, built app |
|---|---:|---:|---:|
| jolt | x2.91 (28) | x9.56 (28) | — |
| ClojureScript | x3.93 (25, Node); x3.00 (25, Chromium) | x9.12 (28, Node) | — |
| Phel | — | — | x121.12 (27; 1 cut) |
| the others | — | — | — |

Phel's built app is `phel build` of the recorded boot, run under OPcache with the tracing JIT (c/aot-phel). Every
program gives the JVM's answer; `lazy-pipeline` is over x1000.

### Per program (median ms, xJVM)

| program | jvm | cljs | cljs-cohost | browser | jolt | jolt-cohost | phel-built |
|---|---:|---:|---:|---:|---:|---:|---:|
| fib | 165 | 312 (x1.89) | 388 (x2.35) | 202 (x1.23) | 266 (x1.61) | 1,009 (x6.12) | 7,252 (x44.00) |
| fib-hinted | 91 | 292 (x3.22) | 2,890 (x31.90) | 189 (x2.09) | 197 (x2.17) | 13,080 (x144.37) | 46,850 (x517.11) |
| tak | 457 | fails | 1,652 (x3.61) | fails | 550 (x1.20) | 2,898 (x6.34) | 13,625 (x29.81) |
| tak-hinted | 229 | fails | 12,981 (x56.66) | fails | 525 (x2.29) | 54,552 (x238.11) | 188,885 (x824.46) |
| nbody | 226 | 1,117 (x4.95) | 2,019 (x8.95) | 876 (x3.88) | 826 (x3.66) | 1,699 (x7.53) | 35,374 (x156.80) |
| nbody-hinted | 256 | 986 (x3.85) | 2,284 (x8.91) | 815 (x3.18) | 735 (x2.87) | 1,456 (x5.68) | 34,704 (x135.40) |
| spectral-norm | 242 | 1,992 (x8.24) | 3,365 (x13.91) | 1,220 (x5.04) | 1,233 (x5.10) | 2,176 (x8.99) | 53,851 (x222.62) |
| spectral-norm-hinted | 144 | 1,924 (x13.38) | 4,240 (x29.48) | 1,218 (x8.47) | 977 (x6.80) | 6,177 (x42.96) | 62,463 (x434.37) |
| mandelbrot | 246 | 3,045 (x12.39) | 5,413 (x22.03) | 2,360 (x9.61) | 1,957 (x7.96) | 2,513 (x10.23) | 96,934 (x394.52) |
| mandelbrot-hinted | 237 | 3,198 (x13.50) | 5,183 (x21.88) | 2,662 (x11.24) | 293 (x1.24) | 2,788 (x11.77) | 95,527 (x403.24) |
| binary-trees | 249 | 821 (x3.30) | 1,412 (x5.67) | 522 (x2.10) | 1,328 (x5.33) | 2,481 (x9.96) | 35,481 (x142.49) |
| binary-trees-hinted | 253 | 861 (x3.40) | 1,958 (x7.74) | 468 (x1.85) | 1,387 (x5.48) | 4,728 (x18.68) | 37,067 (x146.45) |
| binary-trees-14 | 12 | 29 (x2.39) | 67 (x5.48) | 17 (x1.38) | 53 (x4.37) | 102 (x8.34) | 1,191 (x97.60) |
| binary-trees-14-hinted | 9.9 | 30 (x3.05) | 91 (x9.21) | 23 (x2.34) | 60 (x6.04) | 203 (x20.55) | 1,761 (x177.92) |
| sum-overflow | 9.9 | 9.1 (x0.92) | 8.0 (x0.81) | 6.1 (x0.62) | 26 (x2.62) | 29 (x2.89) | 1,749 (x176.65) |
| sum-overflow-hinted | 0.9 | 50 (x55.56) | 72 (x80.00) | 38 (x42.44) | 1.6 (x1.78) | 8.5 (x9.44) | 36 (x39.67) |
| sort | 43 | 119 (x2.78) | 104 (x2.42) | 86 (x2.00) | 69 (x1.61) | 78 (x1.82) | 1,578 (x36.78) |
| lazy-pipeline | 33 | 132 (x4.01) | 303 (x9.23) | 94 (x2.87) | 62 (x1.90) | 214 (x6.53) | > x1000 |
| keyword-map | 131 | 472 (x3.61) | 906 (x6.94) | 333 (x2.55) | 193 (x1.48) | 569 (x4.35) | 7,935 (x60.75) |
| fannkuch | 229 | 776 (x3.39) | 1,026 (x4.48) | 574 (x2.50) | 399 (x1.74) | 539 (x2.35) | 23,483 (x102.55) |
| fannkuch-hinted | 198 | 813 (x4.10) | 1,049 (x5.29) | 592 (x2.99) | 425 (x2.15) | 485 (x2.44) | 19,500 (x98.39) |
| nsieve | 274 | 872 (x3.18) | 2,366 (x8.64) | 541 (x1.98) | 1,747 (x6.38) | 2,200 (x8.03) | 25,145 (x91.84) |
| nsieve-hinted | 283 | 806 (x2.85) | 2,355 (x8.33) | 528 (x1.87) | 1,684 (x5.96) | 2,191 (x7.75) | 17,520 (x61.95) |
| k-nucleotide | 156 | 948 (x6.06) | 1,523 (x9.73) | 598 (x3.82) | 438 (x2.80) | 1,064 (x6.80) | 11,714 (x74.90) |
| pipeline | 217 | 668 (x3.08) | 1,045 (x4.82) | 623 (x2.87) | 1,158 (x5.34) | 1,271 (x5.86) | 28,527 (x131.64) |
| dispatch | 175 | 854 (x4.87) | 3,802 (x21.70) | 714 (x4.07) | 2,336 (x13.34) | 2,342 (x13.37) | 89,712 (x512.05) |
| json | 285 | differs | 2,421 (x8.49) | differs | 694 (x2.43) | 7,542 (x26.45) | 80,520 (x282.43) |
| first-output | 815 | 139 (x0.17) | 7,761 (x9.52) | 835 (x1.02) | 202 (x0.25) | 3,848 (x4.72) | 1,694 (x2.08) |
| **geomean xJVM** (recorded programs) | 1 | **x3.93** (25) | **x9.12** (28) | **x3.00** (25) | **x2.91** (28) | **x9.56** (28) | **x121.12** (27; 1 cut) |

## Known problems

Each cut or failed cell above is one of these; they are bugs until fixed.

- **Over x1000 (cut):** basilisp's built app at the smaller sizes, 24 of 25 programs (Box 4); let-go's built app on
  `tak-hinted`, `mandelbrot`, `mandelbrot-hinted`, `spectral-norm-hinted`, `sum-overflow-hinted` and ClojureWasm's on
  `fib-hinted`, `tak-hinted` (macOS); Phel's built app on `lazy-pipeline` (Box 1); cohost on let-go from source on
  `sum-overflow-hinted` (Box 4).
- **Type hints.** On the JVM, primitive hints make fib, tak and spectral-norm about twice as fast. On cohost they often
  do the opposite (jolt's `tak-hinted` x252 against x1.65 unhinted, Box 4 run B): a hinted parameter keeps the JVM's
  semantics (coerced on every call), and that coercion is paid on every call. jank compiles hints to native types
  (`:native-hints`), and its hinted rows run at 0.84-2.13 times the unhinted ones. For speed on cohost today, leave
  hints off except on the JVM and jank.
- **Phel's built app does not build on macOS** (fixed on this branch): the build ran `ulimit -s 65536 && exec phel
  ...`, and macOS's hard stack limit is 65520 KB, so `ulimit` failed and Phel never ran (`docs:check phel` failed its
  two build steps there). Now `ulimit -s 65536 2>/dev/null || ulimit -s hard; exec ...` (`src/cohost/build.clj`,
  `dev/perf/phel_snapshot.sh`), as `bin/cohost` already did.
- **let-go's built app on macOS is partial** (23 of 28): the run was stopped for the launcher test before
  k-nucleotide, pipeline, dispatch, json and first-output.
- **jank's own core:** `nbody`, `spectral-norm` (`Math/sqrt`, `Math/round` unresolved), `dispatch` (`defprotocol`),
  `mandelbrot`, `fannkuch`, `nsieve` (jank 0.1's C++ code generation fails on a `loop` inside a `loop` that rebinds an
  outer name); all run through cohost, which rewrites the interop and the loops. `sum-overflow` and `pipeline` wrap
  at 32 bits on jank 0.1 (own core and cohost from source; the built app gives the JVM's answer on every program), as
  does cohost on jank's `dispatch`. jank 0.1's own `sort` walks its result quadratically (x352-x602 on Box 2); cohost
  uses Clojure's own sort there since ff30ce1. cohost carries a patch series against jank (`upstream/jank/`) for the
  wrap, the sort walk and more; it is used only when `JANK_BIN` names a build of it. These tables are the jank that
  ships.
- **ClojureScript's own core:** `tak` fails (`TypeError: Cannot read properties of undefined`), `json` returns 0;
  both run through cohost.
- **Phel's own core** runs 15 of 28: type hints, Java's `Math`, `dispatch`'s `w`, a crash in `lazy-pipeline`;
  `json` gives another answer. `1N` reads as a long on Phel.

## Method

The suite is `bench/programs/`: fib 36, tak 30 20 10, nbody 50000, spectral-norm 600, mandelbrot 1500, binary-trees
18 and 14, sum-overflow 1000000 (sums past 2^31), sort 100000, lazy-pipeline 1000000, keyword-map 1000000, fannkuch 8,
nsieve 20, k-nucleotide 2000000, pipeline 500000 (map-heavy), dispatch 5000000 (protocols), json 30000 (a round
trip); each sized so one call takes 100-1000 ms on the JVM. Ten come twice: plain, and with primitive type hints
(`^long`, `^double`, coercions) as in performance-minded JVM code. `first-output` is the wall time to print one line
from a one-line program through the benchmark's own launch of each runtime (no project, no library), median of 3
processes.

Per program and runtime (`dev/bench_programs.clj`): one fresh process; the first call is timed as cold; warm-up
continues until the median of the last 5 calls moves under 2% (at most 20 calls or 10 s); then 10 timed calls, or as
many as fit in 15 s with at least 3, of which the table shows the median; a program's process is stopped after 15
minutes. Every call's result is checked against the first call's, and the first call's against the JVM's. The 15 s
budget came in on 2026-11-03 at 1:37 AM ET; Boxes 1 to 3 were measured with 10 timed calls every time, and the
medians compare all the same.

What makes a runtime fast or slow on cohost: cohost runs Clojure 1.12's own `clojure.core` source, compiled or
interpreted by the runtime, with Clojure's JVM interop rewritten to a shim over the runtime's own data structures; a
program's speed is the runtime's speed on that code plus the shim on interop-heavy paths (multimethods, protocols,
string building). A runtime can hand a stock function to its own implementation one name at a time (`:natives`,
[Adding a host](guide/09-adding-a-host.md)), and seven performance switches exist per runtime
([How it works](guide/08-how-it-works.md#performance-keys)). `bb bench:jank` (30 small cases of `clojure.core` use,
Box 2) puts Clojure's core booted by cohost at x0.92 of jank's own core overall; the worst five for cohost: reduce
over a range x44.7, `clojure.string/join` x39.3, a multimethod call x27.9, keyword lookup x4.0, map lookup x1.9.

## Add a box

Every table above is one box: its own JVM column and the runtimes measured on it, one at a time. To add one, run
the steps below and add a section like [macOS](#macos-apple-m5): a startup table, a speed table, the per-program
tables, and the box's description (CPUs, OS, each runtime's version, the cohost commit, the date and time, the load
when each column started). The macOS records (`doc/perf/macos/`) are the complete example.

1. **Checkout and runtimes.** A cohost checkout of the commit you measure, and each runtime on `PATH`:

   ```bash
   # not checked: clones and installs
   git clone https://github.com/johnmn3/cohost.cljc ~/cohost && cd ~/cohost
   bash dev/install/jank.sh        # likewise jolt.sh, cljs.sh, lg.sh, phel.sh, basilisp.sh, cljw.sh
   bb boot:jank-snapshot           # jank only: builds its snapshot and launcher once (157 s on the M5, 11 min on a busy Linux box)
   ```

2. **The JVM column first.** Every ratio is against this box's own JVM:

   ```bash
   # not checked: about 2 minutes
   bb bench:programs jvm
   ```

3. **One column per runtime, one at a time.** `<runtime>` is the runtime's own core; `<runtime>-cohost` is
   Clojure's core booted by cohost on it (on jank, `jank` is cohost from its snapshot; `bb bench:jank` compares it
   with jank's own core); `<runtime>-built` is the built app. Each column writes
   `target/bench-programs/results/<column>.edn`. A program over x1000 is a bug, not a number: stop that column and
   record it as cut.

   ```bash
   # not checked: minutes (jolt, cljs) to an hour or more (cljw, lpy) per column
   bb bench:programs jank-built
   bb bench:programs jolt-built        # then cljs-built, cljw-built, lg-built, phel-built, jvm-built,
                                       # jolt, jolt-cohost, jank, cljs, cljs-cohost, browser, lg, lg-cohost, ...
   ```

   Never run two at once: two ClojureScript compiles at once can corrupt `~/.cljs/.aot_cache`, and any other load
   skews the times.

4. **The table.** Paste its output into your box's section:

   ```bash
   # not checked: reads the records step 3 wrote
   bb bench:programs table jvm jank-built jolt-built cljs-built cljw-built
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

6. **Records.** Copy `target/bench-programs/results/*.edn` and the startup lines into `doc/perf/<box-name>/`, commit
   them with the doc on a branch and push it.

The history of how these numbers came to be is in [`doc/perf/HISTORY.md`](perf/HISTORY.md).

## Release 0.9.3.2: old vs new (first banked pass, 2026-11-10)

These are the in-process core microbenchmarks (`bb bench:*`), not the built-app tables above. `host` means the runtime's own core; `stock` means cohost's booted core. All five new EDNs parse, each has 30 cases at scale 1, on branch `release/cohost-on-eve-0932-bench` at `a00651b36007b375387d9e0c1ad2416b1a308925`, under `doc/perf/release-0932/`. Files say `hatch`; the actual box was **grok-bot-vm-524857728**, 8 cores, not hatch or J.

- Old: `doc/perf/before/` is the historical `4ce56eb` perf baseline, not a measured 0.9.3.1 release baseline. It has 29 cases and basilisp scale 0.05; the new basilisp row is scale 1. Do not infer a release speed-up or compare raw basilisp milliseconds across those scales/boxes. A matched old-release/new-release run is still owed.
- New #427: cohost `8233562c`, Eve sibling `73a64110`; cljs actually used the `cae5db96` git dependency. Runs were not an exclusive quiet-box comparison (load1 1.51-4.01 in the later legs).
- New #436: cohost `d64fe778`, Eve `a11a1820`; basilisp began at load1 2.99 (wrapper 3.08), ended at 3.43, rc=0, wall 1082s, boot errors 0. Functional proof, not a quiet-box performance claim.

| new row | core boot (s) | equal host/stock answers | receipt |
|---|---:|---:|---|
| jvm | 0.718 | 30/30 | #427 |
| cljs | 3.658 | 29/30 | #427 |
| jolt | 1.731 | 30/30 | #427 |
| jank | 12.731 | 24/30 | #427 |
| lpy | 23.448 | 30/30 | #436 |

Gaps: #427 `bb bench:browser` failed rc=1 after 10s and produced no EDN because `dev/browser_boot.py:10` raised `ModuleNotFoundError: No module named 'playwright'` (retained `queue-427/home-427/cohost-0932-browser/run-browser.log`); no install attempted. CLJS `format` has unequal answers/error; jank has unequal answers/errors in loop-arith, hinted-loop, reduce-range, transduce, atom-swap and protocol. Exclude those from speed ratios. muse #22 on J has no ACK/RESULT yet (latest routing CORRECTION 3, latest pin CORRECTION 5). Next: matched old/new stock-core tables and labelled per-case ratios; no causal performance improvement claimed here.

## Release 0.9.3.2: matched old vs new, jvm and cljs (grok #445 vs #427, 2026-11-11)

This is the matched comparison owed by the section above. The same box ran both sides, **grok-bot-vm-524857728**, with the same `bb bench:*` harness at scale 1 and 30 cases per host. The ratio is new stock median over old stock median, so below 1.00 means the new release is faster. `stock` is cohost's booted core, which is what the release changes; `host` is the runtime's own core and only serves as a control.

**About the "hatch" file names.** Every `bench-*-hatch.{edn,log}` in `doc/perf/release-0932/` came from grok, not hatch: #427 produced the jvm, cljs, jolt and jank files, and #436 produced the lpy files. Their own logs say `box=grok-bot-vm-524857728`. The files keep those names because the release notes cite them, so read "hatch" in those file names as grok. The fixed harness (a985099, release/cohost-on-eve-0932) names files by `$BOX`, which is why the newer files say `grok`.

- Old: grok #445 (2026-11-11 00:45–00:49 ET), cohost main `a956f21`, Eve v0.9.3 `76fd94c3`, files `bench-{jvm,cljs}-grok-base-a956f21.edn`. load1 2.20 / 3.10 at start, but the 15-minute load average was still 26–29 from an earlier box spike, and there were 7 cores.
- New: grok #427 (2026-11-10 20:52–20:56 ET), cohost `8233562c`, Eve `73a64110` (cljs took the `cae5db96` git dep), files `bench-{jvm,cljs}-hatch.edn`. load1 2.52 / 3.00, 8 cores.
- Neither run had the box to itself. Treat a single-case ratio within ±10% as noise. The new release side is not yet re-measured at the current release tip (cohost `2be13796`, Eve `9c9fef4a`); grok #456 re-gates that tip and banks a fresh cljs row.

| host | cases compared | geomean stock new/old | core boot old → new (ms) | excluded |
|---|---:|---:|---:|---|
| jvm | 30/30 | 0.98 | 761 → 718 | none |
| cljs | 29/30 | 0.97 | 3919 → 3658 | `format` (no host-side value on ClojureScript: cljs.core has no `format`) |

Single cases outside ±10%, stock new/old:
- jvm: `assoc-map` 0.88 (10.52 → 9.24 ms). Its host control moved by the same amount (9.11 → 7.94 ms), so this is box variance, not cohost.
- cljs: `reduce-range` 0.60 (3.04 → 1.84 ms); its host control is flat (1.80 → 1.74).
- cljs: `assoc-map` 1.21 (25.90 → 31.43 ms); its host control is flat (14.57 → 14.55).
- cljs: `multimethod` 0.87 (25.78 → 22.38 ms).
- cljs: `distinct` 0.89 (92.23 → 81.85 ms). Its host control went the other way (87.23 → 100.26), so it is noisy.

In short, on this box the new release's stock core is at parity with v0.9.3 on jvm and cljs: geomeans 0.98 and 0.97, and boot is a few percent faster. There is one real-looking cljs regression to watch, `assoc-map` +21%, and one real-looking win, `reduce-range` −40%. Both stand out against a flat host control, but each comes from a single run, so neither is claimed as caused by the release until a quiet-box repeat confirms it.
