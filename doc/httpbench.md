# httpbench: how fast Clojure goes through cohost on jank

*2026-11-05. The example lives at [`bench/apps/httpbench/`](../bench/apps/httpbench/);
its README carries the build internals and the full measurement record. For
cohost's speed on every runtime, see [Performance](PERF.md).*

httpbench is an HTTP/1.1 benchmark utility written in Clojure on cohost,
targeting jank. One binary, two modes:

- `serve` runs a uWebSockets server that answers every GET with a fixed
  1024-byte JSON body, keep-alive.
- `bench` is a keep-alive load generator (a uSockets client) that validates
  every response body with simdjson and reports a latency histogram.

It is the capstone of cohost's C++ passthrough work: the project that shows
the whole `cpp/` interop path — the `#?(:jank …)` ranking, the `cpp/`
passthrough, the `cpp/raw` blocks — carrying a real, hot program. The hot
half is C++ inside `#?(:jank (cpp/raw "…"))` blocks, reached from Clojure
only through `cpp/` calls. There is no shelling out, no separate C++
binary, and no file or pipe between the halves: Clojure calls C++ through
`cpp/`, or it is not this program. Everything else — argument parsing,
configuration, the report, the JSON output — is ordinary cohost Clojure on
stock core.

## Layout

| path | what it is |
|---|---|
| `src/httpbench/native.cljc` | the C++ half: all of it in `cpp/raw` blocks — the uWebSockets server, the uSockets bench client, simdjson validation of every response, a fixed log-linear latency histogram with no allocation on the hot path. Clojure reaches it through three entry points: `hb_serve`, `hb_bench`, and the `hb_stat_*` readers |
| `src/httpbench/core.cljc`, `src/httpbench/stats.cljc` | the Clojure half: tools.cli parsing for both modes, URL parsing, the calls across the boundary, percentile math over the histogram the C++ half filled, the text and `--json` reports |
| `test/httpbench/core_test.cljc` | tests for the Clojure-only parts: argument and URL parsing, percentile math, report formatting |
| `deps.edn` | the project's `:cpp` key — `:include-dirs`, `:sources`, `:defines`, `:libs` — which `cohost.build-jank` consumes; the one change this capstone needed under `src/cohost` |
| `cpp/vendor/` | the vendored C++: uWebSockets v20.81.0, uSockets (no-SSL configuration), simdjson v5.1.1 |
| `measure.sh` | the measurement protocol below, as a script |

The `:default` arms of every `#?` make the namespaces load on the other
hosts too; there, `-main` prints "httpbench needs jank".

## Build and run

From the cohost checkout root, build inside the project:

```bash
(cd bench/apps/httpbench && ../../../bin/cohost -T:build build)
# => bench/apps/httpbench/target/httpbench/run
```

`run` is a static jank-runtime executable. It reads nothing from the
checkout or the project when it runs — copied alone to another directory,
it serves correctly.

```bash
run serve --port 8080
run bench --url http://127.0.0.1:8080/ --requests 100000 --concurrency 1000
run bench --url http://127.0.0.1:8080/ --requests 100000 --concurrency 1000 --json
```

`bench` prints requests, wall time, requests/s, errors, bytes, and
p50/p90/p99/p99.9/max latency; `--json` prints the same as one JSON object.

Tests, on jank (the project's target) and on the JVM:

```bash
bin/cohost -X:test          # Ran 4 tests containing 17 assertions. 0 failures, 0 errors.
bin/cohost :jvm -X:test     # Ran 4 tests containing 17 assertions. 0 failures, 0 errors.
```

## The numbers

Measured 2026-11-05 on j.slaa.us ("box 4", Linux): GCP, Linux
6.14.0-1021-gcp x86_64, 4 vCPUs, load average 1.58/0.91/0.98 at the start
of the run. The capstone branch's commit `b3da4ac`; jank 0.1, uWebSockets
v20.81.0, uSockets @ 86097c4 (no-SSL, native epoll), simdjson v5.1.1.
Protocol: `sh measure.sh` — server and bench are separate processes on the
same box over loopback; `ulimit -n 200000`; every row is 100,000 requests;
each figure is the **median of 3 runs** of that metric. A response counts
only when simdjson confirms `ok: true` and exactly 48 items; all rows
below had 0 errors. Latencies are per-request, write-of-request to last
byte of response, from the bench's C++ histogram (log-linear buckets, so
percentile values are bucket boundaries).

httpbench bench → httpbench serve:

| concurrency | req/s | p50 (ns) | p99 (ns) | p99.9 (ns) | max (ns) | errors |
|---|---|---|---|---|---|---|
| 100 | 84,879.3 | 1,179,648 | 2,359,296 | 3,932,160 | 5,728,639 | 0 |
| 1,000 | 84,481.9 | 10,485,760 | 23,068,672 | 25,165,824 | 28,732,165 | 0 |
| 10,000 | 61,238.9 | 134,217,728 | 184,549,376 | 184,549,376 | 187,755,956 | 0 |

Reference rows (same box, same protocol):

| load generator → server | req/s | notes |
|---|---|---|
| wrk (wg/wrk master source, built on the box; -t4 -c1000 -d10s) → httpbench serve | 80,501.97 | 812,411 requests in 10.09 s; wrk's own avg latency 11.64 ms |
| httpbench bench (c=1000) → nginx 1.26.3 | 71,441.0 | median of 3 × 100,000, 0 errors; p50 11,534,336 ns, p99 31,457,280 ns; nginx returns the identical 1024-byte body |

The concurrency cap: the box's ephemeral port range is 32768–60999
(28,232 ports) and the bench is a single loopback client, so a
100,000-concurrency row cannot run here. At 25,000 the bench completed a
median 82,004 of 100,000 requests with a median 17,996 connect-side
errors (listen-backlog/connect path saturation on one source address);
10,000 is the highest error-free concurrency on this box. The 25,000
figures are reported as measured, not adjusted.

## What it demonstrates

- **The server Clojure writes is in nginx's band.** By the same client,
  same validation, same byte-identical body, on the same box: the
  cohost/jank server answers 84,481.9 req/s at concurrency 1,000 where
  nginx 1.26.3 answers 71,441.0. An independent client agrees: wrk
  measures the cohost/jank server at 80,501.97 req/s.
- **The interop boundary costs nothing structural.** The hot path —
  sockets, parsing, validation, timing — is C++ the Clojure program owns
  outright through `cpp/raw`; Clojure keeps the parts Clojure is good at
  (configuration, orchestration, reporting) and calls across only at
  three entry points.
- **The build is declarative.** A project states its C++ needs once, in
  `deps.edn` under `:cpp`, and `cohost -T:build build` produces one
  static executable that runs anywhere, the same shape as every other
  cohost build.

## Landing

This capstone was built on the branch `muse/httpbench` and landed on
`main` on 2026-11-05, unchanged from the measured branch state, as
cohost's public example of the C++ interop path. The only change under
`src/cohost` in the landing is the one the branch itself carried:
`build_jank.clj`'s `:cpp` build key.
