# Cohost-side workarounds for jank's upstream defects (X4-1)

Plan item 2-4 of `doc/hosts/jank-MAX.md` from cohost's side: what the jank adapter can do today, without
changing what any program computes, while the upstream patches (U2, U3, U4) travel. Every workaround sits
behind the jank adapter only (`#?(:jank ...)` arms in `src/cohost/shim.cljc`, `cpp/raw` helpers in
`src/cohost/hosts/jank_host.jank`); no other host reads a changed line. None is a `:natives` entry: a native
changes the boot report's counts (booted as stock / from host), which `bb boot:jank` pins at 542/171/78/0.

Numbers: apt jank 0.1-noble, 4 CPUs / 15 GB, box shared with lanes u1 and u5 (no lock; load 3-12 during the
runs), base `max/main` @ 072c4a1 (A) against `max/x4` @ d19a2cf (B), alternated A B A B A B by
`x4-measure.sh` (below), median of three rounds, spread in brackets. `bb bench:jank` runs at -O3 (D-3);
the probes run at -O0 (`dev/research/run-cohost-jank.sh`, no `JANK_O`).

## Per defect

### (a) sort: the stock comparator, not a walk

- Finding: the order's premise (the stock side avoids a quadratic walk the host side takes) does not hold for
  the bench's `:sort` case: its host side is `(first (sort ...))`, no walk. jank's own sort's quadratic walk
  (jank-11, `native_vector_sequence`) is what P5's sort-1e5 hit; the stock `sort` never reaches it (it is
  `to-array` + the shim's `Arrays/sort` + `seq` of the array) and needs no workaround for it.
- Where the stock sort's time went: the comparator. The stock `sort` passes the stock `compare`, so every
  comparison was a stock fn call (`compare-1e6`: 41.9 ms, ~42 ns per call) plus the shim's comparator
  wrapper (`as-comparator` -> `compare-with`, two more calls).
- Workaround: the shim's `Arrays/sort` on jank recognises the stock core's `compare` (its body is
  `(Util/compare x y)`, which is the host's compare) and sorts with jank's `runtime::compare` in C++
  (`cohost_sort::sort_array_compare`); any other fn comparator goes to the adapter unwrapped, and the adapter
  reads its answer exactly as `compare-with` does (a number: its sign; anything else: truthy puts x first);
  a Comparator that is no fn still goes through `compare-with`. `-compare` (`(. cmp (compare a b))`) takes the
  same shortcut for the stock compare. A redefined `compare` is another fn and takes the general path.
- Probe (`dev/research/x4_parts.clj`): `arr-sort-stock-compare-5e4` 34.48 [33.45-48.48] -> 7.35 [7.13-7.58]
  ms; `sort-realized-5e4` 35.07 [34.34-35.67] -> 7.67 [7.24-7.71]; `sort-by-cmp-only-2e4` 28.98 -> 25.14.
- What is left of the bench's `:sort` (stock 54 ms): its input, `(map f (range 5e4))`, a stock lazy `map`
  (`gen-input-5e4` 43 ms, bimodal 33-65): see (d). So the order's "sort near x1" is not reachable from this
  arm; the bench moved x4.19 -> x3.21 and the sort program 79.88 -> 46.21 ms.
- Owner: `src/cohost/shim.cljc` (`stock-compare`, `Arrays-sort`, `-compare`), `jank_host.jank`
  (`cohost_sort`, `:sort-array!`).

### (b) reduce over vectors and ranges (jank-15)

- Workaround: the shim's `host-reduce` (the stock `reduce`'s `coll-reduce` path, so `reduce`, `transduce`,
  `into`, `group-by`, `frequencies` over them) calls the adapter's `reduce-init` / `reduce-noinit`: over an
  `integer_range` or a `persistent_vector` it loops by index in C++ (`cohost_reduce`), same elements, same
  order, `reduced` unwrapped the same way, `(f)` for an empty coll without an init and the first element as
  the init otherwise (a one-element coll returns it, f uncalled). Any other coll goes to jank's own `reduce`.
  The order suggested `reduce-kv` / a counted loop from jank; C++ index loops are faster than either
  (jank-15's own repro: reduce-kv 33 ms, nth loop 22 ms per 1e6).
- Probe: `reduce + 0` over a 1e6 vector 86.32 [82.86-159.84] -> 4.18 [4.16-4.21] ms; over `(range 1e6)`
  84.15 -> 4.18; without an init 91.71 -> 6.06 (vector), 114.33 -> 3.91 (range); results identical
  (1783293664: the i32 wrap, both sides).
- Edge cases: `dev/research/x4_edges.txt` (46 forms: empty, one element, `reduced` from range and vector,
  stepped and empty ranges, float range, list, subvec, transducers, sort comparators of every answer type)
  print the same on both trees; against JVM Clojure 1.12 44/46 equal, the 2 others (`(meta (sort ...))` nil,
  `(class (sort ...))` Cons) equal on the base too (cohost's sort, not X4).
- Owner: `src/cohost/shim.cljc` (`jank-reduce-init`, `jank-reduce-noinit`, `host-reduce`),
  `jank_host.jank` (`cohost_reduce`, `reduce-init`, `reduce-noinit`).

### (c) apply str

- None: nothing to do. `str` is a host name (`src/cohost/policy.cljc` `host-names`), so `(apply str xs)` is
  already jank's own `str` over one builder: `apply-str-2e4` 6.78 ms base, 6.50 x4, the host side's speed.
  The bench's `:str-build` gap is `(map str (range 2e4))`, the stock lazy `map` (`map-str-2e4` 68 ms against
  6.5 for the apply): see (d). The order's "str-build toward x2" is not reachable from an apply/str arm.
- The bench read str-build x6.21 -> x8.72, but its base spread is 25-67 ms (bimodal). The case alone
  (`dev/research/x4_noise.clj`, 5 interleaved rounds, -O0): 62.33 [61.14-68.36] base, 63.71 [59.20-71.20] x4;
  distinct 342.46 / 348.51, partition 147.99 / 143.39, frequencies 101.16 / 94.30, all inside their spreads.
  X4 leaves str-build where it was.

### (d) first/next on realized lazy seqs, and the stock lazy map

- None from cohost. `first`/`next`/`seq` on a lazy seq are jank's own (`RT-first`/`RT-next`/`RT-seq` reach
  jank's runtime directly); there is no shim layer to remove and no cheap test that skips work.
- The stock lazy `map` costs ~1 us per element (`map-over-vec-1e5` 104-110 ms) because on apt jank an integer
  range's seq and a vector's seq are not chunked: `(chunked-seq? (seq (range 10)))` false,
  `(chunked-seq? (seq [1 2 3]))` false, `(chunked-seq? (seq (range 0.0 10.0)))` true (`jank run`, apt). So the
  stock `map`/`filter` take the one-cell-per-element branch. Cohost could answer true for those seqs and
  serve chunks itself, but the stock chunked branch then builds jank's `chunked_cons`, which on apt realizes
  the whole chain recursively and crashes on long chains (`(count (map inc (range 0.0 1000000.0)))` exits
  139, u3; fixed in 15b-17's `chunked_cons.cpp`). That workaround would trade a crash for speed; it waits for
  U4 (jank-17 + the chunked_cons fix), after which jank's own ranges are chunked and no cohost arm is needed.

### The i32 wrap (jank-12, jank-21)

- Cannot be dodged for user arithmetic. The codemod applies `+`'s `:inline` and writes `(+ a b)` as jank's
  own `+` (`src/cohost/rewrite.cljc` `inline-expansion`: "the shim's Numbers-add, or the host's own + where the
  shim's is that"; `src/cohost/shim.cljc` `(def Numbers-add +)`), which jank compiles to its i32 small+small
  arm. A shim arm that widened to i64 would put a fn call on every arithmetic op the user writes, the cost
  the codemod's inlining exists to avoid, and still miss jank-21's literal-typed loop locals, which never
  reach cohost. The only cohost-visible place, `+` as a value (`(reduce + ...)`), would then disagree with
  inline `+`. So: none; jank-12/21 (U1-1) fix it upstream.

## Before / after

`bb bench:jank` (stock core / jank's own, -O3), median of 3 interleaved rounds, base -> x4:

| case | stock ms base | stock ms x4 | ratio base | ratio x4 |
|---|---:|---:|---:|---:|
| sort | 63.52 [56.08-76.30] | 54.38 [45.34-57.47] | x4.19 | x3.21 |
| sort-by | 44.17 [43.54-45.68] | 43.19 [40.35-55.21] | x5.06 | x4.87 |
| transduce | 9.92 [9.82-10.01] | 6.63 [6.56-6.78] | x2.21 | x1.54 |
| into-vector | 15.64 [15.47-15.74] | 12.35 [12.33-12.37] | x3.62 | x2.98 |
| lazy-pipeline | 184.63 [139.94-210.12] | 118.92 [108.18-132.45] | x1.59 | x1.57 |
| str-build | 49.31 [25.11-66.82] | 58.16 [45.22-58.89] | x6.21 | x8.72 |
| reduce-range | 6.83 [6.50-8.38] | 0.82 [0.82-0.82] | x0.99 | x0.13 |
| conj-vector | 13.26 [13.19-34.23] | 9.35 [9.24-14.11] | x1.03 | x0.29 |
| group-by | 62.10 [55.23-65.19] | 60.81 [32.23-64.76] | x2.83 | x1.10 |
| set-build | 21.82 | 18.27 | x2.49 | x2.09 |
| transient-vector | 14.38 | 10.97 | x3.81 | x3.02 |

(The lazy-pipeline ratio did not move: its host side moved too. str-build's x8.72 is noise: equal alone, see (c).)
Every other case is inside its spread.
`:format` is 308.23 [291.71-398.82] base, 299.33 x4: the jump from 80.9 ms seen earlier is on `max/main`
without X4 (a separate A/B of the case alone, -O0: 372 vs 339 ms medians), not this change.

`bb bench:programs jank` (median ms, 3 interleaved rounds; RESULT equal on both sides):

| program | base | x4 |
|---|---:|---:|
| sort (1e5) | 79.88 [78.30-84.53] | 46.21 [41.19-49.25] |
| pipeline (5e5) | 1110.47 [970.56-1364.40] | 952.18 [943.73-1100.79] |
| json (3e4) | 4851.99 [4357.01-5032.40] | 4244.64 [4207.83-4910.68] |

`bb boot:jank` on x4: 542/171/78/0, battery 143/145, libraries 61/61.

Commands: `/home/user/lanes/u0/x4-measure.sh` alternates, per round and side, `bash
dev/research/run-cohost-jank.sh dev/research/x4_parts.clj`, `bb bench:jank`, `bb bench:programs jank
sort,pipeline,json`; then `bb boot:jank` and the edge forms through `cohost.run --eval` on both trees.

## Removal

- (b) `cohost_reduce`, `reduce-init`, `reduce-noinit` and the two `host-reduce` arms go when jank-15
  (`dev/research/upstream/15-reduce-walk.jank`, U3) is in the series and `bb bench:jank`'s reduce-range,
  transduce and into-vector on the patched build without the arms are within their spread of these numbers.
- (a) The comparator shortcut removes cohost's own cost (a stock fn call per comparison, ~42 ns on apt), not
  an upstream defect; no upstream patch retires it. Re-measure it when U7 (direct calls) lands and drop it if
  `arr-sort-stock-compare-5e4` without it is within the spread of `arr-sort-host-compare-5e4`.
