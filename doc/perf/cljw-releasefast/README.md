# ClojureWasm ReleaseFast against the shipped ReleaseSafe (D-16 step 2, 2026-11-03 ET)

Lane D's box (Linux x86_64, 4 CPUs, nothing else running). How the ReleaseFast binary was built:
INSTALL.md (Zig 0.16.0, ClojureWasm v1.15.4 at its tag, `zig build -Doptimize=ReleaseFast`).
Each run puts one binary first on PATH; `bb bench:programs` (coord/release3 @ ffcc0cb's method).
Median ms.

| program | ReleaseSafe (shipped) | ReleaseFast | ratio |
|---|---:|---:|---:|
| fib | 6,325 | 5,317 | 0.84 |
| fib-hinted | 5,480 | 4,478 | 0.82 |
| nbody | 2,777 | 2,398 | 0.86 |
| mandelbrot | 37,963 | 35,812 | 0.94 |
| spectral-norm | 10,286 | 8,969 | 0.87 |
| binary-trees | 10,276 | 8,764 | 0.85 |
| json | 4,236 | 3,466 | 0.82 |
| sort | 104 | 93 | 0.89 |
| geomean of the 8 | | | **0.862** |
| first-output (`cljw`, a script) | 23.1 | 20.2 | 0.87 |
| the built app (cljw-built, this branch's artifact: the recorded boot as cljw modules), first-output | 2,074 | 1,871 | 0.90 |
| the built app, fib | 8,492 | 6,674 | 0.79 |

Records: cljw-safe.edn, cljw-fast.edn (the 9 rows above), cljw-built-safe.edn, cljw-built-fast.edn.
Binary size: 9,616,336 (ReleaseSafe) against 6,590,416 bytes (ReleaseFast).

Seen again while measuring: cljw writes to a file at offset 0 and ignores append (AOT-PLAN.md item 5):
`cljw --version >> log` overwrote the start of this run's log.
