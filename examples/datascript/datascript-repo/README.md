# datascript.cljc

**One DataScript codebase. Every host, both planes.**

This is DataScript (upstream 1.8.1, by Nikita Prokopov) ported onto
Eve's value substrate — the same namespaces, the same public API, the
upstream test suite as its conformance bar — maintained here as a
single `.cljc` codebase that spans **both** host sets:

- **Plane A — directly on Eve.** Every Clojure-like Eve supports loads
  this library as-is; host differences are absorbed by Eve's per-seat
  completion layers, never by forking the library. A database is an
  Eve value: it can live in a persistent Eve atom, in a journaled
  domain on disk, shared by other processes — including processes on
  *different host runtimes at once*, all reading back byte-identical
  state (the flagship contract, `test/drivers/contract/ds_flagship.cljc`).
- **Plane B — through cohost.** Every Clojure-like cohost supports
  runs the same library inside a cohost body (stock Clojure `core.clj`
  hosted by the body) over the Eve data backend. Bodies are separate
  processes opening the same Eve domain file; the same database is
  transacted through any body and read back through any other.

One library, written once, as plain `.cljc` — running everywhere both
substrates run. Where it came from and under what license:
[docs/PROVENANCE-DS.md](docs/PROVENANCE-DS.md). The full guide:
[docs/GUIDE.md](docs/GUIDE.md). Measured results, per host per plane:
[RESULTS.md](RESULTS.md).

## Layout

| Path | What |
|---|---|
| `lib-ds/` | The library: `datascript/*`, `data_readers.cljc`. Consume this. |
| `lib/` | `me.tonsky/persistent-sorted-set`, the Eve port — DataScript's index substrate. |
| `lib-ds-deps/` | `extend-clj`, the protocol-extension overlay the port builds on. |
| `test/drivers/` | The shipped contract drivers (flagship, one-datum, suites) — the lib's conformance proof. |
| `harness/` | This repo's own drivers: contract check, people-1k perf, shootout runners. |
| `docs/` | Guide, porting notes, provenance, licenses. |

## Consume it

```clojure
;; the whole library in one dep (this repo's root deps.edn aggregates)
datascript/datascript {:git/url "https://github.com/johnmn3/datascript.cljc"
                       :git/sha "<pin>"}

;; or exactly the arrangement the Eve example shipped:
datascript-on-eve/datascript-on-eve
  {:git/url "https://github.com/johnmn3/datascript.cljc"
   :git/sha "<pin>" :deps/root "lib-ds"}
```

Eve itself is a dependency of the library, pinned in
`lib-ds/deps.edn` to the shipped Eve tree
(`SeniorCareMarket/eve` @ 0.9.3.1). Bumping Eve is a one-line pin
change here — never a copy.

## Support matrix

Status as measured in [RESULTS.md](RESULTS.md); a row exists only
where a run backs it, and a failure is recorded with its wall, never
smoothed over.

| Host | Plane A (on Eve) | Plane B (cohost body) |
|---|---|---|
| JVM (Clojure) | see RESULTS | see RESULTS |
| Babashka | see RESULTS | — |
| Basilisk (lpy) | see RESULTS (defect D1) | see RESULTS |
| Jolt | carried from the Eve 0.9.3.1 record | — |
| nbb / ClojureScript | carried from the Eve 0.9.3.1 record | — |
| Glojure | carried from the Eve 0.9.3.1 record | — |
| Jank | carried from the Eve 0.9.3.1 record | — |
| ClojureCLR | carried from the Eve 0.9.3.1 record | — |
| ClojureDart (cljd) | the port is being folded there (separate lane) | — |
| let-go (lg) | Eve seat in progress (separate lane) | — |
| ClojureScript (browser) | persistent writes proven on Node; browser seat not attempted | — |

## The proofs that live here

- **Contract** — `harness/ds_harness/contract_check.cljc`: create →
  transact → query → pull against one journaled disk atom, then a
  fresh-process readback against the same expected values.
- **Shootout** — `harness/shootout.sh`: cold process, one datum on a
  disk-backed DB, cap 10,000 ms, median of 5, shipped form per host.
- **Perf** — `harness/ds_harness/perf.cljc`: people-1k build with all
  seven build keys asserted exact against in-process ground truth,
  then find-datom / pull / datoms-sequence / transact timings.
