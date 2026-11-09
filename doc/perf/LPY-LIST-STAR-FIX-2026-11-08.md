# lpy FIX — basilisp adapter supplies list* and the sorted family (2026-11-08)

Lane: lane-m/cohost-lpy-list-star (base: lane-m/cohost-disentangle, the
installer-proven tree). Fix commits: 64717ea (list* via :core-extras),
c622e43 (list* also interned at load), 5903226 (sorted family interned).
All verification on the Mac runner: basilisp 0.5.1, bb 1.12.218 driving,
via the job agent (Mac mailbox DISPATCH #7, #8, #10).

## The defect and the mechanism

bench:lpy could not execute: cohost's boot died with CompilerException,
cannot resolve list* at src/cohost/thread.cljc:738, proven identically on
Grok and the Mac. The perf-refresh lane closed its lpy row NO NUMBER.

Two resolution contexts, which is why one door was not enough:

1. The BOOTED core (cohost.core, stock core.clj compiled by cohost's own
   boot): missing core names are supplied by the adapter's :core-extras,
   which boot interns into the booted core ns. The battery's "list ops"
   case calls (list* 1 [2 3]) in this context.
2. The HOST compiler's context: cohost.shim requires cohost.thread and
   cohost.http, and on basilisp those .cljc sources are compiled by
   basilisp's own compiler, resolving names against basilisp.core --
   before the booted core exists. :core-extras cannot reach this context
   (proven: DISPATCH #7, extras-only, still failed :phase :analyzing at
   thread.cljc:738).

Basilisp 0.5.1's core has no list* at all (bare-basilisp probe in the
DISPATCH #7 run), and no sorted collections of any kind.

## The fix (src/cohost/hosts/basilisp.lpy only)

- list-star defined in the adapter with exact stock list* semantics
  ((list* [1 2]) is the seq itself; (list* 1 [2 3]) => (1 2 3)), supplied
  BOTH ways: :core-extras {'list* list-star} for the booted core, and
  (intern (the-ns 'basilisp.core) 'list* list-star) at adapter load for
  the host compiler. Load order makes the intern land first: cohost.run
  requires cohost.hosts.auto (which loads this adapter) before
  boot/shim/thread/http compile.
- The sorted family, interned the same way when the battery's next stop
  proved to be sorted-map at http.cljc:176 (DISPATCH #8): host-sorted-map,
  host-sorted-map-by, host-sorted-set, host-sorted-set-by, interned as
  sorted-map, sorted-map-by, sorted-set, sorted-set-by. Basilisp has no
  sorted structure, so these are construction-sorted plain maps/sets:
  entries sorted by key at construction, then built into a plain map.
  Construction, assoc/update, lookup and content equality behave as
  stock; iteration order is the host map's own and a later assoc does
  not re-sort -- the caveat is documented at the definitions. The
  consumers reached on this host build once and read by key: http.cljc's
  header maps, and the shim's PersistentTreeMap/PersistentTreeSet-create,
  which bind the host core's sorted fns at shim load and previously
  answered (unsupported "sorted-map").

## Acceptance (Mac DISPATCH #10, fix tip 5903226, run 16m44s, EXIT=0)

Boot completes; thread.cljc loads (the future form macroexpands).

    battery: 209/221 stock behaviors pass
    libraries: 62/62 cases pass across 12 clojure.* libraries

The battery's "list ops" case passes. Baseline on the other seats is
219/221 + 62/62 with 2 standing \u-escape failures. Basilisp's 12
failures are a different family -- host value semantics, not missing
names, and not regressions (before this fix the battery could not run
at all on this tree): numeric tower; A7-4 redefinition/with-redefs;
hash = identical; assoc past a vector's end throws; nth/count/str of
strings and vectors; chunked map chunk-first counts; assoc on vectors
and maps (pairs, append, bounds, meta); checked i64 overflow throws;
quot of a double / int past 2^31; str of a double is Double.toString;
locals from count and native arithmetic; small vector literals are
vectors. They belong to the lpy line's behavior-suite (wall 4) work.

## The missing PERF row (bench:lpy, same run)

bench:lpy completed EXIT=0 and wrote target/bench-lpy.edn (full edn
quoted verbatim in Mac RESULT #10, lane-m/mac-mailbox). Conditions:
Mac runner, basilisp 0.5.1, bench opts {:min-runs 3, :warm-ms 500,
:settle-ms 250, :budget-ms 1000}, scale 1, 3-run medians.

    boot-ms 21048, libs-ms 19643
    geomean of per-case host:stock median ratios: x0.49 (29 of 30 cases)

The excluded case is map-lookup: the host side errored "maximum
recursion depth exceeded" (same? false, host-value nil); stock median
7434 ms. Medians are 3-run medians from a shared runner -- the bench
package's own output, with the fleet's standing caution about medians.
