# Perf pass: state and handoff

Written 2026-10-30, 2:05 PM ET, on branch `claude/eager-euler-ci8ev7`.
This file says where the perf work stands, what each change did when measured, and
what is left. A new session should read it first, then the files it names.

## The request

> "Let's do a perf upgrade pass. First, try to find all the places where we're being
> dumb and fix it. Improve all existing perf. Second, we'll create a higher level adapter
> layer that cohosts can opt into gradually. But providing higher level impls of higher
> level fns that are faster, that shortcut the cljc indirection, we can let those cohosts
> provide that. If the cohost provides no impl, it falls back to your cljc/clj impl."

- **Pass 1:** remove the needless costs. Mostly done; the remaining items are listed below.
- **Pass 2:** the opt-in "natives" layer. Not started; a design is below.
- **Then:** measure before against after on every host, write `doc/PERF.md`, and run an
  adversarial review.

## Resume

```bash
cd cohost.cljc && git checkout claude/eager-euler-ci8ev7
```

```bash
bb boot:jolt
```

Also run `bb boot:jvm` and `bb boot:cljs`. Each boots core.clj and runs the battery: 133
behaviors plus 61 library cases. basilisp's `bb boot:lpy` takes about 5 minutes; it has
not been run on this pass's changes.

```bash
jolt -Scp src -m cohost.run --bench --quick
```

Quick bench on one host, a few minutes. A full measurement uses `bb bench:jolt`,
`bench:jvm`, `bench:cljs`, `bench:browser`, and `bb bench:lpy --scale=0.05` with
`bb bench:jvm --scale=0.05`. Each writes `target/bench-<host>[-s<scale>].edn`.

```bash
bb bench:report target
```

`bb bench:report --compare doc/perf/before-4ce56eb target` prints the before/after table.

- **"Before":** commit `4ce56eb`. Its results are in `doc/perf/before-4ce56eb/`. They are
  scale 1 for clj, jolt, cljs and chromium. They were measured while other agents shared
  the 4-CPU box, so they are noisy. For clean before-numbers, re-run the benches at
  `4ce56eb` in a worktree, back to back with the after-run.
- **Batteries on this tree** (2:03 PM ET): jolt 133/133 and 61/61; JVM 133/133 and 61/61.
  ClojureScript is 131/133 and 61/61. Its two failures are the same as at `4ce56eb`:
  `(= 1 1.0)` is true because JS has one number type, and a macro's `&env` is the
  analyzer env. Both are known host limits, not regressions.
- **jolt corpus** (`bb corpus:jolt <jolt's test/chez/corpus.edn>`): not run on this pass.
  Run it before calling pass 1 done.

## Measured so far: jolt, quick runs, stock core median ms

The same box, `--quick`, so ±15%. The "own" column is jolt's own core on the same run.

| case | before (`4ce56eb`) | now | × jolt's own, before → now |
|---|---:|---:|---:|
| fn-call | 10.9 | 1.34 | ×6.4 → ×0.83 |
| loop-arith | 28.5 | 0.51 | ×55 → ×1.0 |
| reduce-range | 9.8 | 4.95 | ×2.1 → ×1.1 |
| lazy-pipeline | 1203 | 75 | ×196 → ×12 |
| transduce | 379 | 31 | ×50 → ×3.5 |
| into-vector | 613 | 33 | ×56 → ×3.2 |
| transient-vector | 611 | 24 | ×141 → ×1.2–6 |
| map-lookup | 41 | 21 | ×3.9 → ×1.3 |
| keyword-lookup | 377 | 9.6 | ×74 → ×1.7 |
| destructure | 620 | 30 | ×87 → ×4.2 |
| frequencies | 1366 | 111 | ×56 → ×3.5 |
| group-by | 918 | 82 | ×44 → ×3.8 |
| sort | 278 | 112 | ×9.7 → ×3.7 |
| sort-by | 4581 | 89 | ×168 → ×3.4 |
| set-build | 662 | 51 | ×35 → ×4.1 |
| distinct | 700 | 102 | ×14 → ×1.7 |
| partition | 228 | 72 | ×24 → ×7.4 |
| atom-swap | 197 | 13 | ×41 → ×2.8 |
| multimethod | 83 | 19 | ×8.1 → ×1.9 |
| str-build | 80 | 11 | ×21 → ×3.1 |
| string-join | 1154 | 96 | ×346 → ×25 |
| regex | 59 | 40 | ×30 → ×18 |
| format | 25 | 6.8 | ×4.3 → ×0.85 |

The JVM, ClojureScript and Chromium have not been re-benched since `4ce56eb`; their
batteries pass. basilisp has been neither re-benched nor re-run.

## What pass 1 changed (all in this commit)

- **The codemod** (`rewrite.cljc`):
  - `(.m x ...)` calls a prebuilt dispatcher, `cohost.shim/dot$m`, instead of building a
    closure on every call.
  - `(instance? Literal x)` goes straight to `shim/instance-of?`, without the `dot`
    round trip.
  - A checkcast of a hinted local calls a prebuilt `cast$<token>`.
  - Statics that are the host's own fn (`RT-first` is `first`) are emitted as the host
    symbol, which the host's compiler can inline. Where several host names hold the same
    fn, the shortest wins: on jolt, `+` and `+'` are one fn, but only `+` is inlined.
  - On jolt and ClojureScript, core.clj's `:inline` expansions are applied at rewrite
    time, as the JVM compiler does. ClojureScript keeps var meta unevaluated, so boot
    captures the `:inline` fns.
  - A member symbol's namespace is dropped, as the JVM does: a syntax-quoted inline
    spells `(. RT (cohost.core/aget ...))`.
  - Passthrough interop qualifies imported class names. This fixes
    `clojure.repl/source-fn` on the JVM ("Unable to resolve classname: LineNumberReader"),
    a bug that predates this pass.
- **The shim** (`shim.cljc`):
  - Hot predicates and string/regex capabilities are bound once (`h-*`), not looked up per
    call.
  - `canon` is memoized, and `instance-of?` tries `interface-preds` before `canon`.
  - `host-object?` gets a `:host-data?` fast path and a per-type cache.
  - The MultiFn has a method cache keyed on the method, prefer and hierarchy maps, like
    the JVM's `methodCache`.
  - `compare-with` replaces the old comparator wrapper.
  - Stock chunked branches run over the host's own chunks (adapter `:chunks`); the
    stand-ins remain for basilisp.
  - Fixed `Numbers-unchecked_minus`, which had no 1-arity on the JVM.
- **Adapters:**
  - `cap` does one lookup.
  - New capabilities: `capability`, `interned-var` (one var, not the namespace's whole
    map), `rewrite-inline?`, `host-data?`, `chunks`.
  - JVM: `resolve-core` and `interned-var` are direct; type hints remove reflection from
    `StringBuilder.append`, the Matcher calls and the exception constructors.
  - basilisp: `resolve-core` is one lookup.
  - ClojureScript: `var-of` and `interned-var` are O(1).
  - jolt: `host-data?` covers its own data, including vectors (chunks).
- **Boot** (`boot.cljc`, `policy.cljc`):
  - The macro scan runs only after a form that can make a macro (it ran after all 721
    forms), plus one scan at the end of each file. A library load works the same way.
  - `strip-string-tags` returns at once past the last `^"`.
  - Companion files are read once.
  - The list of the host's dynamic vars is built during boot, not on every `refer-core!`.
  - The policy lists are computed once per boot.
  - `ns-arg-wrap` has fixed arities.
  - When the host has chunks, cohost.core takes the eight chunk fns from it.

## Remaining pass-1 work, highest value first

Each finding has its full text, with file:line, the fix and its risk, in
`doc/perf/findings/*.json`. The projections below are reasoned from those traces. None is
measured; measure each one when it lands.

1. **regex: jolt ×18, JVM ×129 at the baseline.**
   - `:re-find-at` (`hosts/java.clj`) builds every group's text and span on every find,
     and the shim's Matcher `swap!`s an atom on every find.
   - The fix: return only `[start end]` with the host matcher, and compute groups and
     spans lazily when `.group` or `.start(g)` asks.
   - The JVM's reflection here is already fixed by the hints, but the JVM has not been
     re-benched.
   - Projection: most of the JVM's ×129. On jolt, re-seq asks for groups, so the gain
     there is smaller.
2. **string-join: jolt ×25, JVM ×580 at the baseline.**
   - The JVM's ×580 was the reflective `.append` in `java.clj` (warn-on-reflection showed
     it). It is now hinted but not re-measured; projection: most of the ×580.
   - On jolt, each `.append` pays `host-object?`: `(class x)` costs about 0.48 µs for a
     StringBuilder. A cheap jolt StringBuilder test in `:host-data?` (find one that costs
     about 0.01 µs, as `transient?` does) or a cheaper cache key would remove that.
3. **LazySeq construction on jolt** (`trace-lazy-seqs.json`, Fix D).
   - `:lazy-seq` goes through `host-new`.
   - The fix: use jolt's own `make-lazy-seq`/`coll->cells`, which jolt's own `lazy-seq`
     macro expands to.
   - Probe first: `(seq (lazy-seq nil))` timed against jolt's own.
4. **The rewrite walks every macro's arguments again** (`find-codemod-output.json`
   `rewalk-of-macro-arguments`).
   - A form under k stock macros is rewritten k+1 times. The fix is a stop set of the
     macro's argument forms (identical? check).
   - The finder judged this the largest boot and eval-time cost. Projection: a large boot
     win; unmeasured.
   - Do `try-rewalk-layers` first. It is also a **correctness bug on basilisp**: every
     re-walk adds a `catches? 'Exception` layer, which rethrows `AssertionError` before a
     user's `(catch Throwable ...)`.
5. **Smaller rewrite-time memos** (same file):
   - the inline lookup, per rewrite call;
   - `class-sym?`, per name;
   - `*kw-unrenames*`, cached by the renames map;
   - `simple-class-name`;
   - skip `*local-tags*` rebinding when no names are bound;
   - compute `instance?`'s class argument once.
6. **Correctness:** `(catch IOException e)` with `java.io` imported canonicalizes to
   `java.lang.IOException` (`catches-token-per-clause`). Emit the imported name.
7. **Prebuilt `is$<token>` predicates** for concrete classes (Keyword, Symbol, Long ...),
   as was done for `cast$`. Guard them to keep `class-of`'s order.
8. **On ClojureScript and basilisp, a missing method builds a dispatcher on every call**
   (`missing-method-closures-per-eval`). Build it once at rewrite time.
9. **basilisp inlining:**
   - Set `:rewrite-inline? true`. basilisp strips `:inline`, so boot's
     `capture-inline!` path applies there too.
   - basilisp's `:redef` meta exists so that `with-redefs` reaches compiled calls.
     Inlining is the JVM's semantics, where `with-redefs` of `+` does not reach inlined
     calls, so confirm that before turning it on.
10. **Measurement fairness on jolt** (`trace-arith-fncall.json`):
    - All of cohost's code (cohost.core, the shim, the adapter) is compiled at run time.
      In that code jolt wraps every non-tail call in a virtual-register save and restore,
      unless `JOLT_TRACE=0`. jolt's own core is compiled ahead of time and does not pay
      it.
    - Run the jolt benches with `JOLT_TRACE=0` on both sides, or record the setting.
    - Also have bench record the git HEAD (and a hash of a dirty tree) in its EDN.
      `--quick` takes one timed call per side, so never use it for the before/after
      tables.
11. **reduce over stock `+`** (`trace-reduce-lookup.json`):
    - `-reduce` and `protocols-coll-reduce` can pass the host's `+` or `*` when `f` is
      identical to the stock one. jolt's `vec-reduce` then open-codes the step. It is
      equivalent: reduce calls `f` only with 0 or 2 arguments.
    - reduce-range is ×1.1 now.
12. **Atoms** (`trace-arith-fncall.json`): `swap!` and friends are good pass-2
    candidates. A replacement can test `h-atom?` and call the host's `swap!`; on jolt
    atom-swap is ×2.8.
13. **Unfinished finders:** the jolt `arith-fncall` and `reduce-lookup` traces, and the
    `adapters`, `shim-core` and `shim-rest` sweeps. Their workflow scripts are in
    `dev/perf-workflows/`. Re-run what is still useful; each script's prompts name the
    already-landed fixes, so update them first.

## Pass 2: the natives layer (design, not started)

- **Capability:** an adapter capability `:natives`, a map of `{qualified-sym impl}`:
  - `'clojure.core/frequencies host-fn`: use this fn;
  - `'clojure.core/frequencies :host`: take the host core's own;
  - a host symbol: take that var of the host.
- **Boot:** `policy/decide` (or `boot/process-form!`) checks `(get natives name)` before
  evaluating the stock form. A hit interns the native the way `take-from-host!` does
  (`:cohost/native true` meta). A miss boots core.clj's definition, as now. Libraries
  (`load-source!`) do the same per library namespace, keyed `'clojure.string/join`.
- **Report:** the boot report counts `:native` forms, and the bench report lists them per
  host.
- **Opting in:** a host opts in one fn at a time. A native must pass the battery and the
  jolt corpus, and print JVM Clojure's answer in the bench (the report already checks
  stock answers against the JVM).
- **First candidates,** from the remaining ratios: the lazy seq fns (map, filter,
  partition, take), frequencies, group-by, into, `clojure.string/join`, and re-seq. Each
  host's own implementation is ×3–25 faster on jolt today.
- **Chunks first:** the `:chunks` capability (pass 1) is the first such opt-in. Taking
  `policy/chunk-names` from the host when the adapter has chunks is the pattern to
  generalize.

## Files

- `doc/perf/findings/`:
  - `trace-lazy-seqs.json`: per-element cost model; Fixes A–D.
  - `trace-transients-dispatch.json`
  - `find-boot-loader.json`: 13 findings, most landed.
  - `find-codemod-output.json`: 12 findings; items 4–8 above.
- `doc/perf/before-4ce56eb/`: the before-numbers.
- `dev/perf-workflows/`: the finder and trace workflow scripts.
