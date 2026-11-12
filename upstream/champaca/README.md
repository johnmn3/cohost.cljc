# upstream/champaca

The patch the `:clj` leg's bundle is built from, and the record of what it
fixes upstream. Follows the `upstream/jank/patches` precedent: what cohost
needs from an upstream project lives in this repo, not in a side checkout.

## Pin

- champaca: github.com/b12n-oss/champaca @ `44ad7c4cd817612240afeb86f47119555cfbb27f`
- jolt: v0.8.26

Apply `fold-full.diff` to a checkout of that pin; the result is the
`CHAMPACA_SRC` that `dev/build-clj-bundle.sh` expects.

## What the fold is

One diff against the pin, four layers:

1. The hardened final champaca diff (loader class identity per loader, the
   conformance fixes) — the per-loader identity patch is in here; the fold
   supersedes it.
2. Whitelist tower inliner — inlines `clojure.lang.Numbers` and the five
   `Ops` implementations on loader-resolved identity.
3. Class-literal constants — per-unit constant cells keyed by class name +
   defining class, resolved lazily through the unchanged class loader path.
4. Ops devirtualization, with the direct-call fix: guard branches call the
   implementation methods by method-global, never through the inlining
   callee (without the fix the fib unit bloats ~8.2×; with it, 1.18×).

Toggles, all read by environment-variable PRESENCE — off means the variable
is UNSET, never set to `0`:

- `CHAMPACA_INLINE_TOWER` — whitelist tower inliner
- `CHAMPACA_CLASS_CONSTS` — class-literal constants
- `CHAMPACA_DEVIRT_OPS` — Ops devirtualization

The general (any-method) inliner was also built and proven correct, but it
measured ~15% slower on fib28 and ~8× slower on cache fill, so it is NOT in
this patch and stays off the ship line.

## Evidence (2026-11-12, quiet venues)

- Fold accepted: toggle-off translation identity, protocols CONFORM
  (23/196/0/0) with all toggles on, LoaderAssignabilityCheck and
  LoaderIdentityCheck pass, the 18-check tower battery identical across
  HotSpot / all-off / all-on.
- 27-kernel bench, `:clj` through jolt on the folded build: geomean
  **33.68× vs HotSpot JDK 25** (the unpatched build measures 112.30× on the
  same kernels). tak-`:clj`, which timed out at 900 s unpatched, completes.
- Class-literal constants alone: 12.26× warm / 11.24× cold on unhinted
  fib28, hinted controls flat.

## Upstream fixes

`UPSTREAM-FIXES.md` is the per-fix record for the champaca changes (what
each one is, and its proof), kept for the day these are posted to
b12n-oss/champaca. Per the operator's ruling the lane does not post them;
this file is the pointer.
