# httpbench

An HTTP/1.1 benchmark utility built with cohost on the jank target. One
binary, two modes: `serve` runs a uWebSockets server that answers every
GET with a fixed 1024-byte JSON body; `bench` is a keep-alive load
generator (uSockets client) that validates every response body with
simdjson and reports a latency histogram. The hot half is C++ reached
through cohost's `cpp/` passthrough; everything else is ordinary
Clojure. There is no shelling out and no file/pipe between the halves:
Clojure calls C++ through `cpp/` or it is not this program.

## How to build

From the cohost checkout root:

    bin/cohost -T:build build        # run inside bench/apps/httpbench

That produces `bench/apps/httpbench/target/httpbench/run`, a static
jank-runtime executable. It reads nothing from the checkout or the
project when it runs (verified: the binary copied alone to another
directory serves correctly).

The C++ half's build needs are declared in the project's `deps.edn`
under `:cpp`, which `cohost.build-jank` consumes:

- `:include-dirs` — the vendored headers (uWebSockets, uSockets,
  simdjson). They reach the vendored sources' own compilation, the
  record step (as `CPLUS_INCLUDE_PATH`), and the app's `cpp/raw`
  blocks.
- `:sources` — the vendored uSockets C sources and `simdjson.cpp`,
  compiled once with `-fPIC` before the record step, into both a
  static archive (the final link) and a shared twin in
  `cohost-build/record-lib/`. The shared twin exists because both JIT
  sessions in the build — the snapshot record and `jank compile`'s
  own evaluation of the app module — materialize a `cpp/raw` block's
  translation unit when the form evaluates, and its external symbols
  must resolve inside those processes; a static archive cannot be
  loaded there, so both sessions get `-L record-lib -l cpp`. The
  archive stays first on the link path, so the shipped binary has no
  dependency on the shared twin (`ldd` shows only system libraries).
- `:defines` — `LIBUS_NO_SSL`: the vendored uSockets is the no-SSL
  configuration (see `cpp/vendor/VERSIONS.md`); every SSL call site
  in its core sources sits inside `#ifndef LIBUS_NO_SSL`, so the
  define removes the references and no SSL backend is compiled or
  linked. The server is plain HTTP only.
- `:libs` — `z` (uWebSockets' PerMessageDeflate uses zlib).

## How to run

    run serve --port 8080
    run bench --url http://127.0.0.1:8080/ --requests 100000 --concurrency 1000
    run bench --url http://127.0.0.1:8080/ --requests 100000 --concurrency 1000 --json

`bench` prints requests, wall time, requests/s, errors, bytes, and
p50/p90/p99/p99.9/max latency in ns (and ms); `--json` prints the
same as one JSON object. Tests (the Clojure-only parts: argument and
URL parsing, percentile math, report formatting):

    bin/cohost -X:test          # on jank (the project's target)
    bin/cohost :jvm -X:test     # on the JVM
    # both: Ran 4 tests containing 17 assertions. 0 failures, 0 errors.

On the other seven hosts the namespaces load and `-main` prints
"httpbench needs jank".

## The numbers

Measured 2026-11-05 on j.slaa.us ("box 4", Linux): GCP,
Linux 6.14.0-1021-gcp x86_64, 4 vCPUs, load average 1.58/0.91/0.98 at
the start of the run. Commit `b3da4ac` on `muse/httpbench`. jank 0.1
(binary stamp `x86_64-unknown-linux-gnu-cf627abb…`), uWebSockets
v20.81.0, uSockets @ 86097c4 (no-SSL, native epoll), simdjson
v5.1.1. Protocol: `sh measure.sh` — server and bench are separate
processes on the same box over loopback; `ulimit -n 200000`; every
row is 100,000 requests; each figure below is the **median of 3
runs** of that metric. Latencies are per-request, write-of-request
to last byte of response, from the bench's C++ histogram (log-linear
buckets, so percentile values are bucket boundaries).

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

The concurrency cap: the box's ephemeral port range is
32768–60999 (28,232 ports) and the bench is a single loopback
client, so the spec's 100,000-concurrency row cannot run here. At
25,000 the bench completed a median 82,004 of 100,000 requests with
a median 17,996 connect-side errors (listen-backlog/connect path
saturation on one source address); 10,000 is the highest
error-free concurrency on this box. The 25,000 figures are reported
as measured, not adjusted.

What each number measures: req/s and latencies for the httpbench
rows are the bench client's own accounting of validated responses
only (a response counts when simdjson confirms `ok: true` and
exactly 48 items — all rows above had 0 validation errors). The wrk
row is wrk's accounting against the same server. The nginx row is
the same bench client, same validation, against nginx returning the
byte-identical body, and is the server-reference: on this box the
cohost/jank server and nginx sit in the same band at c=1000
(84,481.9 vs 71,441.0 req/s by the same client; 80,501.97 by wrk).

## What the C++ half does

All of it lives in `#?(:jank (cpp/raw "…"))` blocks in
`src/httpbench/native.cljc`, reached from Clojure through three
entry points (`hb_serve`, `hb_bench`, and the `hb_stat_*` readers):

- **Server**: a uWebSockets `App` on 127.0.0.1 answering any GET
  with the fixed 1024-byte JSON body, keep-alive. uSockets provides
  the loop and sockets (epoll on Linux).
- **Bench client**: a raw uSockets loop with C keep-alive
  connections (one request in flight per connection). Each
  connection accumulates its response, splits headers/body on
  Content-Length, and validates the body with simdjson On-Demand
  (`ok` must be true, `items` must count exactly 48). Latency is
  `std::chrono::steady_clock` ns from request write to the final
  response byte, recorded into a fixed log-linear histogram (no
  allocation on the hot path); bytes, error counts and the wall
  clock are accumulated alongside. One implementation note:
  uSockets calls its loop pre/post callbacks unconditionally, so the
  bench passes no-op callbacks to `us_create_loop` — passing
  `nullptr` segfaulted inside `us_loop_run` before the fix.

## What the Clojure half does

Ordinary cohost Clojure on stock core (`src/httpbench/core.cljc`,
`src/httpbench/stats.cljc`):

- tools.cli parsing for both modes (`serve --port`,
  `bench --url --requests --concurrency --json`) and URL parsing.
- The calls across the boundary: `serve!` → `hb_serve`; `bench!` →
  `hb_bench`, then the result is read back through the `hb_stat_*`
  readers into a plain map.
- Percentile/report logic: `httpbench.stats/summarize` computes
  count, req/s, percentiles and max from the histogram the C++ half
  filled; `core/format-report` renders the text table and
  `core/format-json` the `--json` object (stock core only — no
  simdjson on the output side).
- The `:default` arms of every `#?` make the namespaces load on the
  other hosts and print "httpbench needs jank" from `-main`.
